package com.turboio.addon;

/**
 * 目的地解析的**纯逻辑层**：城市锚、查询意图、候选取舍。
 *
 * ── 为什么要单独一层 ──────────────────────────────────────────────
 * 用户实测反馈：「还存在目的地错误的事件，这次把城市加上，在城市范围内搜索」。
 * 实测复现（2026-09-18，用内置 Key 真打高德接口）：
 *
 *   geocode「万象城」**不带 city** → 广西玉林市 / 广西贵港市   ← 起点太原，终点玉林
 *   geocode「万达广场」**不带 city** → 四川南充市南部县
 *   geocode「万象城」city=太原市  → 太原市晋源区万象城(公交站) / 太原万象城(东北门)
 *   place/text「万象城」city=太原市&citylimit=true → 太原万象城（万柏林区）
 *
 * 结论：**不加城市限定 = 全国取最优匹配 = 必然选到外省重名地点**。
 * 「万象城」「万达广场」「火车站」「人民医院」这种词全国几十个城市都有，
 * 命中外省那一个的后果就是"导航到广西玉林"。
 *
 * ── 另一个必须记住的坑：区级 adcode 不能当 city 用 ────────────────
 * `/v3/geocode/regeo` 返回的 `addressComponent.adcode` 是**区级**编码
 * （太原市杏花岭区 = 140107，不是 140100）。把它塞进 `city=`：
 *   place/text「万象城」city=140107&citylimit=true
 *     → 「想宅万象城店钟楼街店民宿(羊市街分店)」  ← 锁死在一个区里挑最近的同名店
 * 所以这里规定：**adcode 末两位必须是 00 才算城市级**，否则一律丢弃，
 * 改用 `city`（城市中文名）——实测 city=太原市 / city=0351 都正确。
 * 纯逻辑，无 android / org.json 依赖，可直接单测。
 */
public final class NavPlace {

    private NavPlace() {}

    /** 两个候选距离相差在这个范围内，视为"同一个地方"，此时不看远近只看查询意图。 */
    public static final double NEAR_SAME_METERS = 1000;

    /** 城市锚缓存有效期：城市不会几分钟就换一个。 */
    public static final long ANCHOR_TTL_MS = 10 * 60 * 1000L;
    /** 锚失效的位移阈值：跑出 15 公里才认为可能换城市了。 */
    public static final double ANCHOR_MOVE_METERS = 15000;

    // ══════════════════════════════════════════════════════════════
    //  城市锚
    // ══════════════════════════════════════════════════════════════

    /** 搜"在我这座城市里"用的锚。city / citycode 至少有一个可用才算有效。 */
    public static final class Anchor {
        /** 传 `city=` 参数用的值（城市中文名优先）。 */
        public final String city;
        /** 高德 citycode（如太原 0351），city 为空时的备选。 */
        public final String citycode;
        /** 给用户看的短名（"太原"），用于播报与兜底关键字。 */
        public final String name;

        Anchor(String city, String citycode, String name) {
            this.city = city == null ? "" : city;
            this.citycode = citycode == null ? "" : citycode;
            this.name = name == null ? "" : name;
        }

        public boolean usable() { return !city.isEmpty() || !citycode.isEmpty(); }
        /** 实际塞进 `city=` 的值。 */
        public String param() { return !city.isEmpty() ? city : citycode; }

        @Override public String toString() {
            if (!usable()) return "(无城市锚)";
            return name.isEmpty() ? param() : name;
        }
    }

    public static final Anchor EMPTY = new Anchor("", "", "");

    /**
     * adcode 是不是**城市级**。
     * 高德 regeo 给的是区级（140107）；区级当 city 用会把搜索锁死在一个区里
     * （实测「万象城」→ 杏花岭区某民宿）。只有末两位 00 才是市辖区/地级市级别。
     */
    public static boolean isCityAdcode(String adcode) {
        if (adcode == null) return false;
        String v = adcode.trim();
        return v.length() == 6 && v.endsWith("00") && v.matches("\\d{6}") && !"000000".equals(v);
    }

    /** "太原市" → "太原"；"内蒙古自治区" → "内蒙古"。 */
    public static String bareCity(String city) {
        if (city == null) return "";
        String v = city.trim();
        for (String suffix : new String[]{"特别行政区", "自治区", "自治州", "地区", "市", "省", "县", "区"}) {
            if (v.length() > suffix.length() && v.endsWith(suffix)) return v.substring(0, v.length() - suffix.length());
        }
        return v;
    }

    /**
     * regeo 的 addressComponent → 城市锚。
     *
     * @param city     高德给的 city 字段（可能为空 —— 直辖市/个别地区会空）
     * @param citycode 高德给的 citycode（0351）
     * @param adcode   ★ 区级，只在它是城市级时才用
     * @param province 高德给的 province，city 为空时兜底
     */
    public static Anchor anchor(String city, String citycode, String adcode, String province) {
        String c = city == null ? "" : city.trim();
        if (c.isEmpty()) c = province == null ? "" : province.trim();
        String code = citycode == null ? "" : citycode.trim();
        String ad = adcode == null ? "" : adcode.trim();
        // city 名与 citycode 都拿不到时，才退用（城市级）adcode
        if (c.isEmpty() && code.isEmpty() && isCityAdcode(ad)) c = ad;
        return new Anchor(c, code, bareCity(c));
    }

    /**
     * 上一次问到的城市锚还能不能用：10 分钟内、且没跑出 15 公里。
     *
     * 为什么要缓存：`/v3/geocode/regeo` 每开一次导航就打一次，
     * 而高德个人 Key 有 **并发/QPS 限制**（实测撞到过
     * `CUQPS_HAS_EXCEEDED_THE_LIMIT` 10021）。城市几分钟内不会变，缓存能省掉一次调用。
     */
    public static boolean reuseAnchor(long ageMs, double movedMeters) {
        if (ageMs < 0 || ageMs > ANCHOR_TTL_MS) return false;
        if (Double.isFinite(movedMeters) && movedMeters > ANCHOR_MOVE_METERS) return false;
        return true;
    }

    // ══════════════════════════════════════════════════════════════
    //  查询意图
    // ══════════════════════════════════════════════════════════════

    /**
     * 目标像"门牌/街道地址"还是"地标名"。
     *
     * 实测差别很大（city=太原市）：
     *   长风街12号 → geocode「山西省太原市小店区长风街12号」（准）
     *                place/text「新纪元大酒店(山西省政府店)」（也行，但绕了一层）
     *   万象城     → geocode「万象城(公交站)」  ← 公交站，不是商场
     *                place/text「太原万象城」    ← 就是那个商场
     * 所以地址类查询偏向 geocode，地标类查询偏向 POI。
     */
    public static boolean looksLikeAddress(String target) {
        if (target == null) return false;
        String t = target.trim();
        if (t.isEmpty()) return false;
        for (String marker : new String[]{"号", "弄", "巷", "栋", "幢", "单元", "室", "号楼", "组", "村", "口"}) {
            if (t.contains(marker)) return true;
        }
        boolean hasDigit = false;
        for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) { hasDigit = true; break; }
        if (hasDigit) {
            for (String marker : new String[]{"路", "街", "道", "巷", "大道", "大街"}) if (t.contains(marker)) return true;
        }
        return false;
    }

    /**
     * 直辖市 / 省会 / 特别行政区短名。
     * 用途：在太原说「导航去北京」时，**不该**把城市锚定在太原去找。
     * 城市锚 + citylimit 会把外城地名搜成"本市最像的那个"（比如某条北京路）。
     */
    private static final String[] BIG_CITIES = {
        "北京", "上海", "天津", "重庆", "石家庄", "太原", "呼和浩特", "沈阳", "长春", "哈尔滨",
        "南京", "杭州", "合肥", "福州", "南昌", "济南", "郑州", "武汉", "长沙", "广州",
        "深圳", "南宁", "海口", "成都", "贵阳", "昆明", "拉萨", "西安", "兰州", "西宁",
        "银川", "乌鲁木齐", "香港", "澳门", "台北",
    };

    /**
     * 用户要去的是**另一个城市**吗。
     *
     * 判定刻意收紧：只有"整个目标就是城市名"（北京 / 北京市）或"城市名 + 站/机场"
     * （北京站、上海虹桥机场）才算跨城。「北京路」不算 —— 那是路名。
     * 判宽了的后果是本市地名全被当成跨城搜，反而回到"全国乱匹配"。
     */
    public static boolean intercity(String target) {
        if (target == null) return false;
        String t = target.trim();
        if (t.isEmpty()) return false;
        for (String c : BIG_CITIES) {
            if (t.equals(c)) return true;
            if (t.startsWith(c)) {
                String rest = t.substring(c.length());
                // "北京市" / "北京站" / "北京南站" / "北京首都机场" 算；"北京路" 不算
                if (rest.startsWith("市") || rest.startsWith("省")) return true;
                if (rest.endsWith("站") || rest.endsWith("机场") || rest.endsWith("火车站")
                    || rest.endsWith("南站") || rest.endsWith("北站") || rest.endsWith("东站") || rest.endsWith("西站")) return true;
            }
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    //  候选取舍
    // ══════════════════════════════════════════════════════════════

    public enum Source { NONE, POI, GEOCODE }

    /**
     * POI 搜索与地址解析都拿到结果时选哪个。
     *
     * 规则（都能同城命中的前提下）：
     *   ① 只有一个 → 用它；
     *   ② 两个都在，且距离相差 < {@link #NEAR_SAME_METERS}（基本是同一个地方，
     *      比如"万象城(公交站)"与"太原万象城"只差 250 米）→ 按查询意图选：
     *      地标名信 POI（返回的是实体名），门牌地址信 geocode；
     *   ③ 偏好那一方**明显更远**（>3 倍且多出 2 公里以上）→ 换成另一方，
     *      避免"公交站 10 公里外 vs 真商场 500 米外"还挑公交站。
     */
    public static Source pick(NavCore.Point origin, String poiCoord, String geoCoord, boolean addressLike) {
        boolean hasP = poiCoord != null && !poiCoord.isEmpty();
        boolean hasG = geoCoord != null && !geoCoord.isEmpty();
        if (!hasP && !hasG) return Source.NONE;
        if (hasP && !hasG) return Source.POI;
        if (hasG && !hasP) return Source.GEOCODE;

        Source prefer = addressLike ? Source.GEOCODE : Source.POI;
        Source other = addressLike ? Source.POI : Source.GEOCODE;
        double dp = meters(origin, prefer == Source.POI ? poiCoord : geoCoord);
        double d1 = meters(origin, other == Source.POI ? poiCoord : geoCoord);
        if (dp >= 0 && d1 >= 0 && Math.abs(dp - d1) >= NEAR_SAME_METERS) {
            if (dp > d1 * 3 && dp - d1 > 2000) return other;
            return dp <= d1 ? prefer : other;
        }
        if (dp < 0 || d1 < 0) return prefer;
        return prefer;
    }

    /** "lon,lat" → 与起点的大圆距离（米）。解析不了返回 -1。 */
    public static double meters(NavCore.Point origin, String coord) {
        if (origin == null || coord == null || coord.isEmpty()) return -1;
        try {
            int comma = coord.indexOf(',');
            if (comma <= 0) return -1;
            double lon = Double.parseDouble(coord.substring(0, comma));
            double lat = Double.parseDouble(coord.substring(comma + 1));
            return NavCore.distance(origin, new NavCore.Point(lat, lon));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 同城也搜不到时的说法。
     * 重点是**别让用户以为是自己说错了** —— 同城搜不到最常见的原因是他要去外地，
     * 而跨城地名必须说出来（否则城市锚定会把"去北京"搜成"本市某条北京路"）。
     */
    public static String noResultReason(String target, Anchor anchor, boolean intercity) {
        String t = target == null ? "" : target.trim();
        if (intercity) return "没找到「" + t + "」这个地方，换个说法再试（跨城导航请说清城市，例如「导航到北京市」）";
        String where = anchor != null && anchor.usable() && !anchor.name.isEmpty() ? anchor.name : "附近";
        return "在" + where + "没找到「" + t + "」，换个说法再试；如果目的地在外地，请在名字前加上城市";
    }

    /** 兜底搜索的关键字：把城市名并进去（用户说的"把城市加上"）。 */
    public static String withCity(String target, Anchor anchor) {
        String t = target == null ? "" : target.trim();
        if (t.isEmpty()) return t;
        if (anchor == null || anchor.name.isEmpty()) return t;
        if (t.contains(anchor.name)) return t;
        return anchor.name + t;
    }
}

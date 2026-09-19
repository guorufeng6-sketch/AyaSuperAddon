import com.turboio.addon.NavCore;
import com.turboio.addon.NavPlace;

/**
 * NavPlace：目的地解析的规则层（"在城市范围内搜索"）。
 *
 * 存在理由（用户实测反馈）：
 *   用户：「还存在目的地错误的事件 这次把城市加上 在城市范围内搜索」。
 *   实测用内置 Key 真打高德（2026-09-18）：
 *     geocode「万象城」不带 city   → 广西玉林市 / 广西贵港市   ← 起点在太原
 *     geocode「万达广场」不带 city → 四川南充市南部县
 *     同城内搜则正确命中太原。
 *
 * 必须钉死的四件事：
 *   ① **区级 adcode 不是城市锚**。regeo 给的是杏花岭区 140107，
 *      拿它当 city 用，place/text 会返回「想宅万象城店钟楼街店民宿」——
 *      搜"万象城"搜出个民宿。这是"目的地错误"的第二条腿。
 *   ② 查询意图：门牌地址信地址解析，地标名信 POI 搜索。
 *   ③ 取舍规则：两个候选同一地方（<1 公里）按意图；差异大就取近的。
 *   ④ 跨城识别：在太原说「导航去北京」不能被城市锚定搜成本市某条北京路。
 */
public class NavPlaceTest {
    private static int checks = 0;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // ── ① adcode 级别：只有末两位 00 才算城市级 ──
        ok(NavPlace.isCityAdcode("140100"), "太原市 140100 是城市级");
        ok(!NavPlace.isCityAdcode("140107"), "杏花岭区 140107 是区级，不能当城市锚用");
        ok(!NavPlace.isCityAdcode("1401"), "长度不对不算");
        ok(!NavPlace.isCityAdcode(""), "空串不算");
        ok(!NavPlace.isCityAdcode(null), "null 不算");
        ok(!NavPlace.isCityAdcode("000000"), "全零不算");
        ok(!NavPlace.isCityAdcode("14AB00"), "非数字不算");

        // ── 城市锚组装：优先 city 名，区级 adcode 一律丢 ──
        NavPlace.Anchor a1 = NavPlace.anchor("太原市", "0351", "140107", "山西省");
        ok("太原市".equals(a1.city), "city 名应保留，实际：" + a1.city);
        ok("0351".equals(a1.citycode), "citycode 应保留");
        ok("太原".equals(a1.name), "短名应去掉「市」字，实际：" + a1.name);
        ok("太原市".equals(a1.param()), "city= 参数应当用城市名（不是区级 adcode）");

        NavPlace.Anchor a2 = NavPlace.anchor("", "0351", "140107", "");
        ok("0351".equals(a2.param()), "没有城市名时退用 citycode，实际：" + a2.param());
        ok(!a2.param().contains("140107"), "绝不能退用区级 adcode，实际：" + a2.param());

        NavPlace.Anchor a3 = NavPlace.anchor("", "", "140100", "");
        ok("140100".equals(a3.param()), "只有城市级 adcode 时才用它，实际：" + a3.param());

        NavPlace.Anchor a4 = NavPlace.anchor("", "", "140107", "");
        ok(!a4.usable(), "只有区级 adcode = 没有可用城市锚，实际：" + a4);

        NavPlace.Anchor a5 = NavPlace.anchor("", "", "", "北京市");
        ok("北京市".equals(a5.city), "直辖市 city 为空时用 province 兜底，实际：" + a5.city);
        ok("北京".equals(a5.name), "短名应去掉「市」，实际：" + a5.name);

        ok("内蒙古".equals(NavPlace.bareCity("内蒙古自治区")), "自治区后缀要去掉");
        ok("太原".equals(NavPlace.bareCity("太原市")), "市后缀要去掉");
        ok("太原".equals(NavPlace.bareCity("太原")), "没有后缀就别动它");
        ok("".equals(NavPlace.bareCity(null)), "null 不应崩");

        // ── 锚缓存：10 分钟 / 15 公里 ──
        ok(NavPlace.reuseAnchor(0, 0), "刚拿到的锚当然能复用");
        ok(NavPlace.reuseAnchor(9 * 60 * 1000L, 14000), "9 分钟 / 14 公里内可复用");
        ok(!NavPlace.reuseAnchor(11 * 60 * 1000L, 0), "超过 10 分钟要重新问");
        ok(!NavPlace.reuseAnchor(1000, 20000), "跑出 15 公里要重新问（可能换城市了）");
        ok(!NavPlace.reuseAnchor(-1, 0), "负数时间视为失效");

        // ── ② 查询意图 ──
        ok(NavPlace.looksLikeAddress("长风街12号"), "门牌号算地址");
        ok(NavPlace.looksLikeAddress("长风街12号楼3单元"), "楼/单元/室算地址");
        ok(NavPlace.looksLikeAddress("迎泽区某村"), "村算地址");
        ok(!NavPlace.looksLikeAddress("太原南站"), "车站是地标不是地址");
        ok(!NavPlace.looksLikeAddress("万象城"), "商场是地标");
        ok(!NavPlace.looksLikeAddress("茂业天地"), "商场是地标");
        ok(!NavPlace.looksLikeAddress(""), "空串不是地址");
        ok(!NavPlace.looksLikeAddress(null), "null 不应崩");
        ok(!NavPlace.looksLikeAddress("长风街"), "光有路名没门牌，当地标搜更准");

        // ── ④ 跨城识别（判宽了会把本市地名全弄成跨城搜）──
        ok(NavPlace.intercity("北京"), "整个词就是城市名 = 跨城");
        ok(NavPlace.intercity("北京市"), "「北京市」= 跨城");
        ok(NavPlace.intercity("北京站"), "「北京站」= 跨城");
        ok(NavPlace.intercity("上海虹桥机场"), "外城机场 = 跨城");
        ok(NavPlace.intercity("太原南站"), "本市的太原南站也算跨城模式 —— 但它带城市名，走全局搜同样对");
        ok(!NavPlace.intercity("万象城"), "商场不是城市");
        ok(!NavPlace.intercity("北京路"), "★「北京路」是路名，绝不能当跨城");
        ok(!NavPlace.intercity("人民医院"), "医院不是城市");
        ok(!NavPlace.intercity(""), "空串不是");
        ok(!NavPlace.intercity(null), "null 不应崩");

        // ── ③ 候选取舍 ──
        // 起点：太原长风街附近
        NavCore.Point origin = new NavCore.Point(37.8706, 112.5527);

        // 实测数据：万象城 —— geocode「万象城(公交站)」112.530721,37.807861
        //                poi「太原万象城」     112.528539,37.807606（同一片区域，差约 250 米）
        String geoWanxiang = "112.530721,37.807861";
        String poiWanxiang = "112.528539,37.807606";
        ok(NavPlace.pick(origin, poiWanxiang, geoWanxiang, false) == NavPlace.Source.POI,
            "地标名 + 两个候选基本同地 → 信 POI（返回的是实体名）");
        ok(NavPlace.pick(origin, poiWanxiang, geoWanxiang, true) == NavPlace.Source.GEOCODE,
            "地址式查询 + 基本同地 → 信地址解析");

        ok(NavPlace.pick(origin, null, geoWanxiang, false) == NavPlace.Source.GEOCODE,
            "POI 没结果就用地址解析");
        ok(NavPlace.pick(origin, poiWanxiang, "", false) == NavPlace.Source.POI,
            "地址解析没结果就用 POI");
        ok(NavPlace.pick(origin, null, null, false) == NavPlace.Source.NONE,
            "都没有 = 没命中");
        ok(NavPlace.pick(origin, "", "", true) == NavPlace.Source.NONE,
            "空串等同于没命中");

        // 偏好一方明显更远（>3 倍且多 2 公里以上）就换另一边 ——
        // 场景：地址解析给了 10 公里外的公交站，POI 给了 500 米外的真商场。
        String nearPoi = "112.5570,37.8710";      // 约 400 米
        String farGeo = "112.6500,37.9000";       // 约 9 公里
        ok(NavPlace.pick(origin, nearPoi, farGeo, true) == NavPlace.Source.POI,
            "★ 偏好地址解析，但地址解析给的候选远得离谱时应当换成 POI");
        ok(NavPlace.pick(origin, farGeo, nearPoi, false) == NavPlace.Source.GEOCODE,
            "★ 反过来也一样");

        // 起点为空（还没定位）时不能崩，也不该乱换
        ok(NavPlace.pick(null, poiWanxiang, geoWanxiang, false) == NavPlace.Source.POI,
            "拿不到起点时按意图选，不崩");
        ok(NavPlace.pick(null, null, geoWanxiang, false) == NavPlace.Source.GEOCODE,
            "拿不到起点 + 只有一个候选 → 用那一个");

        // ── 距离换算 ──
        ok(NavPlace.meters(origin, "112.5527,37.8706") < 1, "同一点距离应当约等于 0");
        ok(NavPlace.meters(origin, "112.610832,37.791632") > 8000,
            "太原南站应当有 8 公里以上，实际：" + NavPlace.meters(origin, "112.610832,37.791632"));
        ok(NavPlace.meters(origin, "") < 0, "空坐标返回 -1");
        ok(NavPlace.meters(origin, "乱码") < 0, "非坐标返回 -1");
        ok(NavPlace.meters(null, "112.5,37.8") < 0, "起点为空返回 -1");

        // ── 失败说法 ──
        String r1 = NavPlace.noResultReason("万象城", NavPlace.anchor("太原市", "0351", "140107", ""), false);
        ok(r1.contains("太原"), "同城搜不到要说清是在太原没找到，实际：" + r1);
        ok(r1.contains("城市"), "要提示外地目的地需加城市名，实际：" + r1);
        String r2 = NavPlace.noResultReason("北京市", NavPlace.anchor("太原市", "0351", "", ""), true);
        ok(r2.contains("导航到北京市"), "跨城搜不到要给出示例说法，实际：" + r2);
        String r3 = NavPlace.noResultReason("", NavPlace.EMPTY, false);
        ok(r3.startsWith("在附近没找到") || r3.contains("没找到"), "拿不到城市锚也不能崩，实际：" + r3);

        // ── 兜底关键字：把城市并进去（"把城市加上"）──
        NavPlace.Anchor t = NavPlace.anchor("太原市", "0351", "140107", "");
        ok("太原万象城".equals(NavPlace.withCity("万象城", t)), "应拼成「太原万象城」");
        ok("太原万象城".equals(NavPlace.withCity("太原万象城", t)), "已含城市名就别重复拼");
        ok("万象城".equals(NavPlace.withCity("万象城", NavPlace.EMPTY)), "没有城市锚就原样返回");
        ok("".equals(NavPlace.withCity("", t)), "空目标还是空");

        System.out.println("NavPlace: " + checks
            + " checks PASS (城市锚定 · 区级 adcode 必须丢弃 · 查询意图 · 候选取舍 · 跨城识别)");
    }
}

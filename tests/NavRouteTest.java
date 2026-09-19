import com.turboio.addon.NavCore;
import com.turboio.addon.NavGuide;
import com.turboio.addon.NavRoute;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NavRoute：高德 URL 拼装 + 折线几何。
 *
 * ── 这个测试类是为了钉死用户实测的「导航不刷新，卡在第一屏不动」 ──
 * 真因（2026-09-18 实测确认）：
 *   v3/direction/driving 的 steps **默认带** polyline；
 *   v5/direction/driving 的 steps **默认不带**，必须显式写 show_fields=polyline。
 *   漏了 → 每段几何为空 → 代码用"起点"占位 → 所有分段几何都等于起点 →
 *   NavCore.match 永远选中第 0 段、剩余里程恒等于全程、进度条不动、
 *   **每 3 秒推的还是同一屏**。
 *
 * 光靠"读代码"想不到这种 bug，只能靠两条断言把它钉住：
 *   ① URL 里必须有 show_fields=cost,tmcs,polyline（改坏了立刻红）；
 *   ② 拿**真实高德响应**（下面内联的 polyline 就是 2026-09-18 太原实测抓的）
 *      走一遍"沿路开过去"，段号必须能从 1 走到 2；而把几何抹成占位起点后，
 *      同样的走法必须**走不动** —— 这样正反两面都有证据。
 */
public class NavRouteTest {
    private static int checks = 0;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    // ── 真实高德 v5 驾车响应（show_fields=cost,tmcs,polyline，太原 起点→太原南站，
    //    全程 14100 米 / 2097 秒，共 15 段；这里取前 3 段做夹具）──
    private static final String FIX_STEP0 =
        "112.553091,37.869985;112.55367,37.86998;112.553761,37.869974";
    private static final String FIX_STEP1 =
        "112.553761,37.869974;112.553761,37.870328;112.553767,37.870457;112.553772,37.870661;"
        + "112.553783,37.871213;112.553794,37.871391;112.553821,37.871702;112.553842,37.871954;"
        + "112.553847,37.872056;112.553863,37.872227;112.553869,37.872303;112.55388,37.872437;"
        + "112.553901,37.872716;112.553912,37.87286;112.553923,37.872989;112.553949,37.873424";
    private static final String FIX_STEP2 =
        "112.553965,37.873574;112.555049,37.87351;112.556739,37.873408;112.557774,37.873349;"
        + "112.559223,37.873257;112.559979,37.873204;112.561283,37.873107;112.5625,37.873021;"
        + "112.563686,37.872957;112.565123,37.872866;112.565917,37.872818;112.56772,37.872721;"
        + "112.568117,37.8727;112.570402,37.872587;112.572816,37.872405;112.573975,37.872324;"
        + "112.574688,37.87227;112.575117,37.872238;112.577633,37.872083;112.578931,37.872045;"
        + "112.580155,37.872008;112.58046,37.872008;112.582788,37.871997;112.584666,37.872013";

    private static final String[] FIX_INSTR = {
        "向东行驶59米左转",
        "沿半坡西街向北行驶378米右转进入主路",
        "沿府西街途径府东街向东行驶2.7千米右转进入左侧道路",
    };
    private static final double[] FIX_DIST = {59, 378, 2690};
    private static final String[] FIX_LINE = {FIX_STEP0, FIX_STEP1, FIX_STEP2};

    private static final NavCore.Point ORIGIN = new NavCore.Point(37.870145, 112.552431);

    private static List<NavCore.Step> realRoute() {
        List<NavCore.Step> steps = new ArrayList<>();
        for (int i = 0; i < FIX_LINE.length; i++) {
            steps.add(new NavCore.Step(FIX_INSTR[i], NavRoute.line(FIX_LINE[i]), FIX_DIST[i]));
        }
        return steps;
    }

    public static void main(String[] args) {
        String key = "TESTKEY";

        // ══════════════════════════════════════════════════════════
        //  ① URL 拼装 —— 直接钉死"漏写 polyline"这个历史 bug
        // ══════════════════════════════════════════════════════════
        String drive = NavRoute.drivingUrl(key, "112.552431,37.870145", "112.610832,37.791632");
        ok(drive.startsWith("https://restapi.amap.com/v5/direction/driving?"), "必须走 v5 驾车接口，实际：" + drive);
        ok(drive.contains("show_fields=cost,tmcs,polyline"),
            "★ show_fields 必须带 polyline（v5 默认不给，漏了就是画面不动）实际：" + drive);
        ok(drive.contains("strategy=45"), "必须带 strategy=45（躲避拥堵 + 速度最快，导航要的是最快到达）");
        ok(drive.contains("origin=") && drive.contains("destination="), "起终点都要有");
        ok(!drive.contains(" "), "URL 里不能有裸空格");

        String regeo = NavRoute.regeoUrl(key, "112.5527,37.8706");
        ok(regeo.contains("/v3/geocode/regeo?"), "逆地理编码用 v3/geocode/regeo");
        ok(regeo.contains("location="), "要带 location");
        ok(regeo.contains("112.5527%2C37.8706"), "经纬度里的逗号要转义");

        String geoCity = NavRoute.geocodeUrl(key, "万象城", "太原市");
        ok(geoCity.contains("address=%E4%B8%87%E8%B1%A1%E5%9F%8E"), "关键词要 URL 编码");
        ok(geoCity.contains("city=%E5%A4%AA%E5%8E%9F%E5%B8%82"), "★ 必须带 city 参数（不然全国乱匹配）");
        String geoGlobal = NavRoute.geocodeUrl(key, "万象城", "");
        ok(!geoGlobal.contains("&city="), "跨城/无锚时才允许不带 city");
        String geoNull = NavRoute.geocodeUrl(key, "万象城", null);
        ok(!geoNull.contains("&city="), "null 城市等于不带");

        String poiCity = NavRoute.poiUrl(key, "万象城", "太原市");
        ok(poiCity.contains("/v3/place/text?"), "POI 搜索用 place/text");
        ok(poiCity.contains("city=%E5%A4%AA%E5%8E%9F%E5%B8%82"), "要带 city");
        ok(poiCity.contains("citylimit=true"),
            "★ 只给 city 不给 citylimit，高德是「优先但不限于」，边界上仍会跑到邻市");
        ok(poiCity.contains("offset=1") && poiCity.contains("page=1"), "要限定取第一条");
        String poiGlobal = NavRoute.poiUrl(key, "万象城", "");
        ok(!poiGlobal.contains("citylimit"), "没有城市就谈不上 citylimit");

        // ══════════════════════════════════════════════════════════
        //  ② 折线解析
        // ══════════════════════════════════════════════════════════
        List<NavCore.Point> l0 = NavRoute.line(FIX_STEP0);
        ok(l0.size() == 3, "第一段真实折线 3 个点，实际：" + l0.size());
        ok(NavRoute.usable(l0), "真实折线可用");
        ok(Math.abs(l0.get(0).lat - 37.869985) < 1e-6 && Math.abs(l0.get(0).lon - 112.553091) < 1e-6,
            "高德是 lon,lat 顺序，别搞反");
        ok(Math.abs(l0.get(2).lat - 37.869974) < 1e-6, "末点纬度要对");

        ok(NavRoute.line(null).isEmpty(), "null 折线返回空");
        ok(NavRoute.line("").isEmpty(), "空折线返回空");
        ok(NavRoute.line(";;;").isEmpty(), "只有分隔符返回空");
        ok(NavRoute.line("abc;1,2;3").size() == 1, "没有逗号的片段要丢掉，剩下的仍保留");
        ok(NavRoute.line("1,2;1,2;1,2").size() == 1, "相邻重复点要去掉");
        ok(NavRoute.line("112.5,37.8;999,999").size() == 1, "越界坐标要丢掉");
        ok(NavRoute.line("112.5,37.8").size() == 1, "一个点也能解析出来");
        ok(!NavRoute.usable(NavRoute.line("112.5,37.8")), "★ 单点不算可用几何");
        ok(!NavRoute.usable(NavRoute.line("112.5,37.8;112.5,37.8")), "★ 重合点不算可用几何（match 会退化）");
        ok(!NavRoute.usable(null), "null 不可用");
        ok(NavRoute.usable(NavRoute.line("112.5,37.8;112.5,37.9")), "两个不同点才可用");

        // ══════════════════════════════════════════════════════════
        //  ③ ★ 回归主线：沿真实路线开过去，段号必须往前走 ★
        // ══════════════════════════════════════════════════════════
        List<NavCore.Step> route = realRoute();
        ok(!NavRoute.allUnusable(route), "真实路线每一段都有几何");
        ok(!NavRoute.collapsedToOrigin(route), "真实路线不是「全塌成起点」");

        // 从起点开出去，最后走到第三段上
        int idx = 0;
        List<NavCore.Point> walk = NavRoute.line(FIX_STEP2);
        for (NavCore.Point p : walk) {
            NavCore.Match m = NavCore.match(route, p, idx);
            if (m.step >= 0 && m.offRoute <= 80) idx = m.step;
        }
        ok(idx == 2, "★ 沿路线走完第三段，段号应当推进到 2，实际：" + idx);

        // 起点处应当匹配到第 0/1 段，且剩余里程明显小于全程
        NavCore.Match atStart = NavCore.match(route, ORIGIN, 0);
        double total = 59 + 378 + 2690;
        double left = NavGuide.remainingDistance(route, Math.max(0, atStart.step), atStart.remainingStep);
        ok(left > 0, "剩余里程要能算出来，实际：" + left);
        ok(left < total, "★ 起点处的剩余里程必须小于全程（恒定等于全程就是「卡在第一屏」的指纹）实际：" + left);

        // 走到第三段时剩余里程必须变小 —— 进度条才会动
        int idx3 = 2;
        NavCore.Point nearEnd = walk.get(walk.size() / 2);
        NavCore.Match endMatch = NavCore.match(route, nearEnd, idx3);
        double leftEnd = NavGuide.remainingDistance(route, idx3, endMatch.remainingStep);
        ok(leftEnd < left, "★ 往前开剩余里程必须减少（" + left + " → " + leftEnd + "）");

        // ══════════════════════════════════════════════════════════
        //  ④ 反面证据：把几何抹成"起点占位"（= 漏写 polyline 的后果），走不动
        // ══════════════════════════════════════════════════════════
        List<NavCore.Point> placeholder = Arrays.asList(ORIGIN, ORIGIN);
        List<NavCore.Step> broken = new ArrayList<>();
        for (int i = 0; i < FIX_LINE.length; i++) broken.add(new NavCore.Step(FIX_INSTR[i], placeholder, FIX_DIST[i]));
        ok(NavRoute.allUnusable(broken), "全占位几何 = 不可用（触发按里程推进的兜底）");
        ok(NavRoute.collapsedToOrigin(broken), "★ 这就是「漏 polyline」的指纹，必须能被识别出来");
        int brokenIdx = 0;
        for (NavCore.Point p : walk) {
            NavCore.Match m = NavCore.match(broken, p, brokenIdx);
            if (m.step >= 0 && m.offRoute <= 80) brokenIdx = m.step;
        }
        ok(brokenIdx == 0, "★ 反证：几何塌成起点时段号永远走不动（这就是用户看到的现象），实际：" + brokenIdx);

        // ══════════════════════════════════════════════════════════
        //  ⑤ 补救：patch 用「上一段终点 → 下一段起点」补近似线段
        // ══════════════════════════════════════════════════════════
        List<List<NavCore.Point>> rawEmpty = new ArrayList<>();
        for (int i = 0; i < 3; i++) rawEmpty.add(new ArrayList<NavCore.Point>());
        List<List<NavCore.Point>> patched = NavRoute.patch(rawEmpty, ORIGIN);
        ok(patched.size() == 3, "补完还是 3 段");
        // 全段都缺几何时"邻居"也是空的 —— 补不出来是**预期行为**，
        // 此时由 allUnusable() 判定走里程兜底（画面照样往前走）。别硬编出一个假几何。
        List<NavCore.Step> patchedSteps = new ArrayList<>();
        for (int i = 0; i < 3; i++) patchedSteps.add(new NavCore.Step(FIX_INSTR[i], patched.get(i), FIX_DIST[i]));
        ok(NavRoute.allUnusable(patchedSteps), "★ 全缺几何 → 交给里程兜底（不是硬编几何）");

        // 只有一段又没有几何 → patch 也补不出来 → 必须由里程兜底接手
        List<List<NavCore.Point>> oneEmpty = new ArrayList<>();
        oneEmpty.add(new ArrayList<NavCore.Point>());
        List<List<NavCore.Point>> onePatched = NavRoute.patch(oneEmpty, ORIGIN);
        List<NavCore.Step> oneStep = new ArrayList<>();
        oneStep.add(new NavCore.Step("向东行驶", onePatched.get(0), 500));
        ok(NavRoute.allUnusable(oneStep), "单段无几何确实补不出来");
        ok(!NavRoute.usable(onePatched.get(0)), "补不出来时保持不可用，让调用方走兜底");

        // 已经有几何的段不许被 patch 动过
        List<List<NavCore.Point>> mixed = new ArrayList<>();
        mixed.add(new ArrayList<NavCore.Point>());
        mixed.add(NavRoute.line(FIX_STEP1));
        List<List<NavCore.Point>> mixedOut = NavRoute.patch(mixed, ORIGIN);
        ok(mixedOut.get(1).size() == NavRoute.line(FIX_STEP1).size(), "★ 有几何的段必须原样保留");
        ok(mixedOut.get(0).size() == 2, "缺几何的段用「起点 → 第 1 段首点」补");

        ok(NavRoute.patch(null, ORIGIN).isEmpty(), "null 输入不崩");
        ok(NavRoute.patch(new ArrayList<List<NavCore.Point>>(), ORIGIN).isEmpty(), "空输入不崩");
        ok(NavRoute.allUnusable(null), "null 视为不可用");
        ok(NavRoute.allUnusable(new ArrayList<NavCore.Step>()), "空路线视为不可用");

        // ══════════════════════════════════════════════════════════
        //  ⑥ 里程兜底：几何拿不到时，画面还得往前走
        // ══════════════════════════════════════════════════════════
        List<NavCore.Step> miles = new ArrayList<>();
        miles.add(new NavCore.Step("第一段", new ArrayList<NavCore.Point>(), 100));
        miles.add(new NavCore.Step("第二段", new ArrayList<NavCore.Point>(), 200));
        miles.add(new NavCore.Step("第三段", new ArrayList<NavCore.Point>(), 300));
        ok(NavGuide.estimateStepByTraveled(miles, 0) == 0, "没起步在第 0 段");
        ok(NavGuide.estimateStepByTraveled(miles, 50) == 0, "走了 50 米还在第 0 段");
        ok(NavGuide.estimateStepByTraveled(miles, 150) == 1, "走了 150 米进第 1 段");
        ok(NavGuide.estimateStepByTraveled(miles, 250) == 1, "走了 250 米还在第 1 段");
        ok(NavGuide.estimateStepByTraveled(miles, 350) == 2, "走了 350 米进第 2 段");
        ok(NavGuide.estimateStepByTraveled(miles, 99999) == 2, "走过头夹到最后一段，不能越界");
        ok(NavGuide.estimateStepByTraveled(miles, -5) == 0, "负里程视为没起步");
        ok(NavGuide.estimateStepByTraveled(null, 100) == 0, "null 不崩");
        ok(NavGuide.estimateStepByTraveled(new ArrayList<NavCore.Step>(), 100) == 0, "空路线不崩");
        // 段长未知的段不参与累加，但也不能让结果越界
        List<NavCore.Step> unknown = new ArrayList<>();
        unknown.add(new NavCore.Step("未知段", new ArrayList<NavCore.Point>(), -1));
        unknown.add(new NavCore.Step("已知段", new ArrayList<NavCore.Point>(), 100));
        ok(NavGuide.estimateStepByTraveled(unknown, 50) == 1, "跳过段长为 -1 的段，实际："
            + NavGuide.estimateStepByTraveled(unknown, 50));

        System.out.println("NavRoute: " + checks
            + " checks PASS (URL 必带 polyline+citylimit · 真实折线可解析 · 沿路走段号会推进 · 占位几何走不动(反证) · patch 补几何 · 里程兜底)");
    }
}

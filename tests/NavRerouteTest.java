import com.turboio.addon.NavReroute;

/**
 * NavReroute：偏航自动重算的判定与文案。
 *
 * 由来（用户原话）：
 *   「这版导航确实可以动了 但是有个问题 偏航后路线没有重算 而是提示我
 *     语音说出重新导航或者退出导航 但实际咱们在导航界面是不能发语音指令的
 *     加自动换路的逻辑 不提示语音指令」
 *
 * 重点钉四件事：
 *   ① 阈值边界（80 米偏航 / 60 米归位 —— 两个值必须**不同**，否则会在阈值附近翻转）；
 *   ② 抖动过滤：连续帧数不够就不重算，但拖到 300 米外立刻重算（那已经不是抖动了）；
 *   ③ 冷却：高德个人 Key 有频次限制，15 秒内不许重复打；
 *   ④ ★ 文案里**绝不能出现"说…"**★ —— 导航页里用户说不出话，
 *      提示语音指令等于把人卡住（这正是用户报的那个问题）。
 */
public class NavRerouteTest {
    private static int checks = 0;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // ── ① 阈值 ──
        ok(!NavReroute.offRoute(0), "在路线上不算偏航");
        ok(!NavReroute.offRoute(NavReroute.OFF_ROUTE_METERS), "正好 80 米不算偏航（用 > 不用 >=）");
        ok(NavReroute.offRoute(NavReroute.OFF_ROUTE_METERS + 0.1), "超过 80 米算偏航");
        ok(!NavReroute.offRoute(Double.NaN), "NaN 不能算偏航（拿不到定位时）");
        ok(!NavReroute.offRoute(Double.POSITIVE_INFINITY), "无穷不能算偏航");
        ok(NavReroute.backOnRoute(NavReroute.BACK_ON_ROUTE_METERS), "正好 60 米算回来了（用 <=）");
        ok(!NavReroute.backOnRoute(NavReroute.BACK_ON_ROUTE_METERS + 0.1), "超过 60 米不算回来");
        ok(!NavReroute.backOnRoute(Double.NaN), "NaN 不算回来");
        ok(NavReroute.BACK_ON_ROUTE_METERS < NavReroute.OFF_ROUTE_METERS,
            "归位阈值必须小于偏航阈值，否则会在 80 米附近反复翻转");
        ok(NavReroute.FAR_ENOUGH_METERS > NavReroute.OFF_ROUTE_METERS,
            "「跑远了」必须比偏航阈值大");

        // ── ② 该不该重算 ──
        boolean D = true;   // hasDestination
        boolean R = false;  // rerouting
        ok(NavReroute.shouldReroute(120, 2, -1, R, D), "首次偏航且连续两帧 → 立刻重算");
        ok(!NavReroute.shouldReroute(120, 2, -1, R, false), "没有目的地就没有重算这回事");
        ok(!NavReroute.shouldReroute(120, 2, -1, true, D), "已有重算在跑时不许再发（并发会打爆高德）");
        ok(!NavReroute.shouldReroute(50, 5, -1, R, D), "没偏航不重算");
        ok(!NavReroute.shouldReroute(120, 1, -1, R, D), "只连续一帧不重算（GPS 抖动 / 立交桥上下层）");
        ok(NavReroute.shouldReroute(120, NavReroute.CONSECUTIVE_FRAMES, -1, R, D), "连续帧数够了就重算");
        // 拖得太远就不再等连续帧 —— 真开出去 300 米不是抖动
        ok(NavReroute.shouldReroute(NavReroute.FAR_ENOUGH_METERS + 10, 1, -1, R, D),
            "拖到 300 米外，一帧就该重算");
        ok(!NavReroute.shouldReroute(NavReroute.FAR_ENOUGH_METERS, 1, -1, R, D),
            "正好 300 米仍要等连续帧（边界用 >）");

        // ── ③ 冷却 ──
        ok(!NavReroute.shouldReroute(120, 3, 1_000, R, D), "刚重算过 1 秒不许再来（高德会限流）");
        ok(!NavReroute.shouldReroute(120, 3, NavReroute.COOLDOWN_MS - 1, R, D), "冷却差 1 毫秒仍不许");
        ok(NavReroute.shouldReroute(120, 3, NavReroute.COOLDOWN_MS, R, D), "冷却到点就允许");
        ok(NavReroute.shouldReroute(120, 3, 60_000, R, D), "很久没试过当然允许");
        ok(NavReroute.COOLDOWN_MS >= 10_000, "冷却太短会把高德个人 Key 打限流，至少 10 秒");

        // ── ④ ★ 文案不许出现"说…" ★ ──
        String[] hud = {NavReroute.hudRerouting(), NavReroute.hudWaiting(), NavReroute.hudFailed("")};
        for (String text : hud) {
            ok(!text.contains("说"), "导航页里用户说不出话，文案不得提示语音指令，实际：" + text);
            ok(!text.contains("语音"), "同上，不得提语音，实际：" + text);
            ok(!text.isEmpty(), "文案不能为空");
        }
        ok(NavReroute.hudRerouting().contains("正在"), "重算中文案要说清在干活");
        ok(NavReroute.hudWaiting().contains("自动"), "冷却中文案要说明会自己重算");
        ok(NavReroute.hudWaiting().contains("继续"), "冷却中要告诉用户该干什么（继续沿当前道路行驶）");
        // 重算失败要把原因带上，用户才知道是"没信号"还是"额度用尽"
        String failed = NavReroute.hudFailed("高德：额度用尽");
        ok(failed.contains("额度用尽"), "失败原因要透传，实际：" + failed);
        ok(NavReroute.hudFailed("").contains("没有返回"), "空原因要有兜底说明");
        ok(NavReroute.hudFailed(null).contains("没有返回"), "null 原因也要兜底");

        // 成功播报（这是唯一可以"出声"的一句，因为它是给耳朵听的）
        String spoken = NavReroute.spokenRerouted(5200);
        ok(spoken.contains("重新规划"), "成功播报要说清已换路，实际：" + spoken);
        ok(spoken.contains("5.2"), "成功播报要带上全程里程，实际：" + spoken);

        System.out.println("NavReroute: " + checks
            + " checks PASS (阈值不重叠 · 抖动过滤与冷却 · 文案不提语音指令)");
    }
}

package com.turboio.addon;

/**
 * 偏航重算的判定与文案 —— **纯逻辑层，可单测**。
 *
 * ══ 为什么要有这个类（用户实测反馈）════════════════════════════
 * 用户原话：
 *   「这版导航确实可以动了 但是有个问题 偏航后路线没有重算 而是提示我
 *     语音说出重新导航或者退出导航 但实际咱们在导航界面是不能发语音指令的
 *     加自动换路的逻辑 不提示语音指令」
 *
 * 旧实现的偏航分支只有一行：
 *     pushScreen("◆ 已偏离路线\n请说「重新导航」或「退出导航」");
 * 三个问题叠在一起：
 *   ① **它把用户送进一个做不到的动作**。导航页是 App 内的 Dialog，语音指令
 *      走的是眼镜/官方 ASR 链路；用户盯着一个"请说…"的提示却没法说，
 *      只能停车手动操作。开车时这是最差的一种提示。
 *   ② **没有重算**。偏航后画面就停在那一句上，直到用户自己想办法。
 *   ③ **什么也没说清**。是 GPS 抖了一下？还是真开岔了？还是路被封了？
 *
 * 现在改成：偏航 → 连续两帧确认（GPS 抖动/立交桥上下层会瞬间"偏航"）
 * → 冷却期内自动重新规划 → 把新路线接上，**全程不需要用户说话**。
 *
 * 为什么判定要单独抽出来：这几个阈值（80 米 / 连续 2 帧 / 15 秒冷却）
 * 都是"错了会让人更迷糊"的参数（太灵敏 → 一路反复重算，高德个人 Key 还有
 * 并发限制；太迟钝 → 偏航两公里才反应过来），必须有单测钉住边界。
 */
public final class NavReroute {
    private NavReroute() {}

    /** 偏航阈值（米）：与旧实现保持一致，避免"以前不偏现在偏"的观感变化。 */
    public static final double OFF_ROUTE_METERS = 80;

    /** 回到这个距离内就算"回来了"。比触发阈值小 —— 否则会在 80 米附近反复翻转。 */
    public static final double BACK_ON_ROUTE_METERS = 60;

    /**
     * 拖到这个距离以外就不再等"连续帧数"，立刻重算。
     * 连续帧是为了过滤抖动；但真开出去 300 米已经不是抖动了。
     */
    public static final double FAR_ENOUGH_METERS = 300;

    /** 需要连续几帧判为偏航才触发。3 秒一帧 → 大约 6 秒确认。 */
    public static final int CONSECUTIVE_FRAMES = 2;

    /**
     * 两次重算之间的最小间隔。
     * 高德个人 Key 有明显并发/频次限制（实测撞到 CUQPS_HAS_EXCEEDED_THE_LIMIT），
     * 偏航后如果每 3 秒重算一次，很容易把自己限流掉，于是"越重算越没路线"。
     */
    public static final long COOLDOWN_MS = 15000;

    /** 这一帧算不算偏航。 */
    public static boolean offRoute(double offRouteMeters) {
        return Double.isFinite(offRouteMeters) && offRouteMeters > OFF_ROUTE_METERS;
    }

    /** 这一帧算不算"回到路线上了"（用于把连续帧计数清零）。 */
    public static boolean backOnRoute(double offRouteMeters) {
        return Double.isFinite(offRouteMeters) && offRouteMeters <= BACK_ON_ROUTE_METERS;
    }

    /**
     * 现在该不该发起重算。
     *
     * @param offRouteMeters 当前偏离距离（米）
     * @param consecutive    已连续偏航的帧数（**含本帧**）
     * @param sinceLastMs    距上次重算尝试过了多久（-1 = 本次导航从未尝试过）
     * @param rerouting      是否正有一次重算在跑（跑着就不许再发，否则会并发打高德）
     * @param hasDestination 有没有目的地（没有就没有"重算"这回事）
     */
    public static boolean shouldReroute(double offRouteMeters, int consecutive, long sinceLastMs,
                                        boolean rerouting, boolean hasDestination) {
        if (!hasDestination || rerouting) return false;
        if (!offRoute(offRouteMeters)) return false;
        // 抖动过滤：除非已经拖得很远，否则要连续几帧确认。
        if (consecutive < CONSECUTIVE_FRAMES && offRouteMeters <= FAR_ENOUGH_METERS) return false;
        if (sinceLastMs < 0) return true;                 // 第一次偏航，立刻重算
        return sinceLastMs >= COOLDOWN_MS;
    }

    /**
     * 偏航时那两行字（推给眼镜）。
     *
     * ★ 刻意**不提"说……"**：导航页里用户说不出话，提示语音指令等于把人卡住。
     *   该说的是"我在干什么"和"你需要做什么"（比如掉头/继续跟当前路走）。
     */
    public static String hudRerouting() {
        return "⟳ 已偏航\n正在自动重新规划路线…";
    }

    /** 冷却中（刚重算过、还在等下一次机会）：告诉用户"等一下会自己重算"，不要他去说话。 */
    public static String hudWaiting() {
        return "◆ 已偏航\n稍后自动重新规划\n（继续沿当前道路行驶）";
    }

    /** 重算失败：把原因带上，用户才知道是"没信号"还是"额度用尽"。 */
    public static String hudFailed(String reason) {
        String why = reason == null || reason.trim().isEmpty() ? "高德没有返回路线" : reason.trim();
        return "◆ 已偏航 · 重算失败\n" + why + "\n（继续沿当前道路行驶）";
    }

    /** 重算成功、路线已换新时的一句播报。 */
    public static String spokenRerouted(double totalMeters) {
        return "已重新规划路线，全程" + NavCore.meters(totalMeters);
    }
}

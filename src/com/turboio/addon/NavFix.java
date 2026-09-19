package com.turboio.addon;

import java.util.Locale;

/**
 * 定位新鲜度 / 自愈判定的**纯逻辑层**（不依赖 android，可直接单测）。
 *
 * ── 这个类为什么存在 ────────────────────────────────────────────────
 * 用户实测原话：
 *   「导航还是不刷新 貌似位置不更新？我没看到手机的位置图标 你再看看」
 *
 * 两句症状是同一个根因的两面：
 *   · **没有 App 持续请求定位更新** → 系统状态栏就不会显示定位图标
 *     （这就是"没看到位置图标"）；
 *   · 导航期间只读 `getLastKnownLocation` 的**缓存值** → 那个点从开机起
 *     就不变 → 段号永远不动 → 画面定死。
 *
 * 所以修法有两层，这个类负责第二层的**判定与文案**：
 *   ① `NavEngine` 导航期间常驻 `requestLocationUpdates`（让位置真的会走、图标会出现）；
 *   ② 用这里的判据决定「这个点还能不能用」「订阅是不是死了要重建」
 *      「界面上该写什么」，并把"定位没在走"这件事**显式说出来**。
 *
 * ★ 为什么必须显式说出来 ★
 *   "画面不动"有两个完全不同的原因：路真的没变，和定位根本没数据。
 *   旧实现两种情况在屏幕上长得一模一样，用户只能猜。现在定位一陈旧，
 *   导航页和眼镜上都会出现「⚠ 定位 23 秒未更新」，一眼可辨。
 */
public final class NavFix {

    /** 定位点在这个时间内算新鲜，可直接用于路线匹配。 */
    public static final long FRESH_MS = 15000;
    /**
     * 超过这个时间还没新点 → 眼镜上加一行提示。
     * 此时**仍然继续用旧点**匹配（否则画面会闪"等待定位"），
     * 只是把"这个数据有点旧"讲出来。
     */
    public static final long STALE_MS = 30000;
    /**
     * 超过这个时间还没新点 → 整屏显示"等待定位"。
     * 再拿旧点硬算下去，用户看到的就是"卡住不动"——那正是这次要修的病。
     */
    public static final long DEAD_MS = 45000;
    /**
     * 订阅标记为"在跑"、但这么久一个点都没来 → 视为死订阅，重建。
     * （HandlerThread 被系统回收、provider 被系统悄悄摘掉等情况下，
     *   我们的布尔标记不会自己变 false。）
     */
    public static final long NO_DATA_RESUBSCRIBE_MS = 45000;

    private NavFix() {}

    /**
     * 这个年龄的定位还能不能用来匹配路线。
     * ageMs < 0 表示"从来没有拿到过"，不能用。
     */
    public static boolean usableForMatch(long ageMs) {
        return ageMs >= 0 && ageMs <= DEAD_MS;
    }

    /** 需不需要在画面上提示"定位有点旧"。 */
    public static boolean shouldWarn(long ageMs) {
        return ageMs < 0 || ageMs > STALE_MS;
    }

    /** 位置太旧，旧点已经不该继续拿来算路程了。 */
    public static boolean tooOldToAdvance(long ageMs) {
        return ageMs < 0 || ageMs > DEAD_MS;
    }

    /**
     * 要不要重建定位订阅。
     *
     * ★ 为什么要有 `subscribedMs` 这个参数（防活锁）★
     *   「订阅在跑但从没拿到点」既是**死订阅**的特征，也是**刚订阅、回调还在路上**
     *   的正常瞬间。若只看这一条就重建，会出现活锁：重建 → removeUpdates 把正在
     *   路上的首点请求撤掉 → 又判「从没拿到」→ 又重建 …… 永远拿不到点。
     *   所以「从没拿到点」只在**订阅已运行超过 NO_DATA_RESUBSCRIBE_MS** 时
     *   才算死订阅。这是本次改动里最容易写错的一处。
     *
     * @param updatesRunning 订阅布尔标记
     * @param ageMs          最近一个定位点的年龄（-1 = 从没拿到）
     * @param subscribedMs   本次订阅已运行时长的毫秒数（-1 = 没有订阅）
     * @param navigating     是否正在导航（不导航就别订，省电）
     */
    public static boolean shouldResubscribe(boolean updatesRunning, long ageMs,
                                            long subscribedMs, boolean navigating) {
        if (!navigating) return false;
        if (!updatesRunning) return true;                  // 订阅掉了，无条件重建
        if (subscribedMs < 0) return false;                // 声称在跑却没有起始时间：别动，下个周期再看
        if (ageMs < 0) {
            // 从没拿到点：只有等够久才认定是死订阅（否则会活锁）
            return subscribedMs > NO_DATA_RESUBSCRIBE_MS;
        }
        return ageMs > NO_DATA_RESUBSCRIBE_MS;             // 疑似死订阅
    }

    /**
     * 眼镜上的一行警示。正常时返回**空串**（不占屏、不打扰）。
     * 优先级：没权限 > 开关没开 > 从没拿到 > 太旧。
     */
    public static String hudWarning(long ageMs, boolean hasPermission, boolean providerOn) {
        if (!hasPermission) return "⚠ 没有定位权限";
        if (!providerOn) return "⚠ 系统定位开关未开";
        if (ageMs < 0) return "⚠ 还没拿到定位";
        if (ageMs > STALE_MS) return "⚠ 定位 " + ageLabel(ageMs) + "未更新";
        return "";
    }

    /**
     * 导航页的「定位」一行。必须能区分四种状态，用户才知道该修什么：
     * 没权限 / 开关没开 / 订阅中但没数据（地库、大楼里）/ 正常在走。
     */
    public static String pageLine(boolean hasPermission, boolean providerOn, boolean updating,
                                  String provider, long ageMs, float accuracyM, String error) {
        if (!hasPermission) return "定位：未授权 —— 到系统设置里给「雷鸟」打开定位权限";
        if (!providerOn) return "定位：系统定位开关未打开（GPS 与网络定位都不可用）";
        if (updating && ageMs >= 0 && ageMs <= FRESH_MS) {
            String acc = accuracyLabel(accuracyM);
            return "定位：" + providerLabel(provider) + " · " + ageLabel(ageMs)
                + (acc.isEmpty() ? "" : " · " + acc) + " · 实时订阅中";
        }
        if (ageMs >= 0) {
            return "定位：" + providerLabel(provider) + " · " + ageLabel(ageMs)
                + "（超过 " + (STALE_MS / 1000) + " 秒没有新位置，画面会停在原地）";
        }
        if (error != null && !error.isEmpty()) return "定位：" + error;
        if (updating) return "定位：已订阅但还没有数据（在地库/大楼里可能要多等一会）";
        return "定位：没有在订阅（导航开始时才会启动）";
    }

    /** 定位来源的人话。 */
    public static String providerLabel(String provider) {
        if (provider == null || provider.isEmpty()) return "定位";
        String p = provider.toLowerCase(Locale.ROOT);
        if (p.contains("gps")) return "GPS";
        if (p.contains("network")) return "网络定位";
        if (p.contains("fused")) return "融合定位";
        if (p.contains("passive")) return "被动定位";
        return provider;
    }

    /** "刚刚" / "3 秒" / "2 分钟"。用于拼接「定位 3 秒未更新」。 */
    public static String ageLabel(long ageMs) {
        if (ageMs < 0) return "从未";
        if (ageMs < 2000) return "刚刚";
        if (ageMs < 60000) return Math.round(ageMs / 1000.0) + " 秒";
        return Math.round(ageMs / 60000.0) + " 分钟";
    }

    /** "±8 米"；拿不到精度（-1/NaN/Inf）返回空串。 */
    public static String accuracyLabel(float m) {
        if (Float.isNaN(m) || Float.isInfinite(m) || m <= 0) return "";
        if (m < 1f) return "±1 米内";
        return "±" + Math.round(m) + " 米";
    }
}

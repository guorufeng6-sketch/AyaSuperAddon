package com.turboio.addon;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 语音直达导航引擎。
 *
 * ── 设计原则（照用户的意见重写）─────────────────────────────────
 * 用户原话：
 *   "导航做的太复杂且不实用了……应该直接使用自有模型的时候，语音操控
 *    就可以直接开始导航……语音直接控制，获取当前位置，语音判断目标位置，
 *    利用高德给出的推荐行驶方案进行导航。而且，不应该是纯文字，
 *    虽然只有纯文字的显示，但我觉得可以带着箭头指示方向更合理。"
 *
 * 所以本类只做一条直线流程，没有任何手动选点/模拟/会话策略：
 *
 *   语音 → NavVoice 解析目的地
 *        → 取当前定位（GCJ-02）
 *        → 高德 Web 服务 v5/direction/driving（推荐方案，strategy=32 高德推荐）
 *        → 拆 steps，每段用 NavGuide 变成「箭头 + 距离」
 *        → 定时按定位重算当前段 → NavGlasses 推 5 行
 *
 * ── 与旧 NavigationUI 的关键差别 ────────────────────────────────
 *   · 不再嵌高德 Android SDK 地图（那块要 AMap Key 与重签校验，最易坏）
 *     改用纯 HTTP Web 服务 —— 只要网络就能用，与签名无关。
 *   · 不再有手动搜索框 / 长按选点 / 模拟导航 / 倍速 / 会话确认弹窗。
 *   · 显示从"一段长文字"变成"箭头 + 距离 + 动作"三行以内。
 *
 * 不碰 Android View（除了 Toast 级的提示由调用方负责），可被语音线程直接调用。
 */
public final class NavEngine {

    /** 高德 Android 平台 Key。用户可在「导航」页里覆盖。 */
    private static final String AMAP_KEY_DEFAULT = "3e2a782f29aa3b2a36e8741de53282f6";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ---- 当前导航会话状态（进程内单例，够用） ----
    private static volatile boolean active = false;
    private static volatile String destination = "";
    private static volatile String status = "未导航";
    private static volatile List<NavCore.Step> steps = new ArrayList<>();
    private static volatile double totalDistance = -1;
    private static volatile long totalDuration = -1;
    private static volatile int stepIndex = 0;
    private static volatile NavCore.Point lastFix;
    private static volatile long lastFixAt;
    private static volatile float lastAccuracy = -1;
    private static volatile String lastScreen = "";
    /** 高德实际解析到的目的地全名（用于把"你导到哪儿去了"讲清楚）。 */
    private static volatile String resolvedAddress = "";
    /**
     * 最近一次失败的**可读原因**。
     *
     * 由来：以前规划失败只有三种含糊措辞（"没有规划出路线"/"定位失败"），
     * 而真正的原因可能是 Key 过期、当日限流、地名解析不到、定位权限缺失……
     * 用户看到"没有规划出路线"完全无从下手。现在每条失败路径都必须写清楚。
     */
    private static volatile String lastReason = "";
    private static Runnable tick;
    /** 位置监听（高德 SDK 若可用就走它，否则退回系统 LocationManager）。 */
    private static Object aMapClient, aMapListener;

    // ══════════════════════════════════════════════════════════════
    //  常驻定位订阅（"位置不更新 / 看不到位置图标"的根因修法）
    //
    //  ── 用户实测 ──────────────────────────────────────────────────
    //  「导航还是不刷新 貌似位置不更新？我没看到手机的位置图标 你再看看」
    //
    //  ── 旧实现错在哪（两处，叠在一起） ─────────────────────────────
    //  ① **导航期间从未 requestLocationUpdates**。每秒调的都是
    //     `getLastKnownLocation` —— 那是**缓存值**，只反映"别的 App 上次
    //     定位的结果"。导航全程没有任何人请求过新定位，这个值从开机起就
    //     不变 → 段号永远不动 → 画面定死。
    //     顺带解释"看不到位置图标"：Android 只在**有 App 正在请求定位更新**
    //     时才点亮状态栏图标。没有订阅 = 没有图标。
    //  ② `if (here != null && fresh()) lastFix = here;` 这个条件与意图相反：
    //     `fresh()` 判的是"**上一次** lastFix 是否在 20 秒内"，一旦定位
    //     断了 20 秒，条件永远为假 —— **新拿到的点会被直接丢掉**，
    //     lastFix 冻死在旧点上，而且它非 null，所以连"等待定位"都不会显示。
    //
    //  ── 修法 ──────────────────────────────────────────────────────
    //  导航期间建立常驻订阅（GPS + 网络，1 秒一个点），点直接写 lastFix；
    //  订阅掉了由 {@link #ensureTicker()} 这个独立观察者重建；
    //  陈旧程度交给 {@link NavFix} 判定，并在界面/眼镜上**显式说出来**。
    // ══════════════════════════════════════════════════════════════
    private static final Object fixLock = new Object();
    private static android.os.HandlerThread fixThread;
    private static android.location.LocationListener fixListener;
    private static Context fixCtx;
    private static volatile NavCore.Point liveFix;
    private static volatile long liveAt = -1;
    private static volatile float liveAccuracy = -1;
    private static volatile String liveProvider = "";
    private static volatile boolean fixUpdates = false;
    private static volatile String fixError = "";
    /**
     * 本次订阅的起始时刻。用于区分「刚订阅、回调还在路上」和「订阅死了」——
     * 只看"有没有拿到过点"会把前者误判成后者，进而反复重建（见
     * {@link NavFix#shouldResubscribe} 的活锁说明）。-1 = 没有订阅。
     */
    private static volatile long fixStartedAt = -1;

    /**
     * 谁在用这次定位订阅（引用计数）。
     *
     * ── 为什么需要它（用户实测：「巡航模式同步更新位置获取 确保可用」）──
     * 定位订阅原先只有导航在用，`NavEngine.stop()` 里一句
     * `stopFixUpdates()` 直接拆掉。而巡航（`CruiseUI`）走的是**另一条**
     * 取点路径 —— 每秒读 `getLastKnownLocation` 的缓存值，和导航当初那个
     * "位置不更新"的坑是同一个：没有订阅就没有新点，缓存值从开机起不变，
     * 于是"前方路况"永远按同一个位置算。
     *
     * 修法：订阅改成"谁要谁登记"。导航要、巡航也要；只要还有人登记就保持订阅，
     * 只剩最后一个离开时才真正拆掉（免得 GPS 一直转着耗电、状态栏图标常挂）。
     */
    private static final Set<String> fixWanters = new HashSet<>();

    // ══════════════════════════════════════════════════════════════
    //  城市锚（"在城市范围内搜索"）
    //
    //  用户实测：「还存在目的地错误的事件，这次把城市加上，在城市范围内搜索」。
    //  实测复现：geocode「万象城」不带 city → 广西玉林市；「万达广场」→ 四川南充市。
    //  原因与坑见 {@link NavPlace}（尤其"区级 adcode 不能当 city 用"）。
    // ══════════════════════════════════════════════════════════════
    private static volatile NavPlace.Anchor cityAnchor = NavPlace.EMPTY;
    private static volatile long anchorAt = 0;
    private static volatile NavCore.Point anchorOrigin;

    /** 路线起点（几何不可用时按已行驶里程推进，需要它当 0 公里）。 */
    private static volatile NavCore.Point routeStart;
    /** 当前段还剩多少米（决定刷新节奏：快到路口就秒级刷）。 */
    private static volatile double currentStepRemaining = -1;
    /** 刷新用的 Application context（供 {@link #ensureTicker()} 自愈重连心跳）。 */
    private static volatile Context tickCtx;
    /** 几何缺失时的诊断说明（已显示在导航页/眼镜上，避免用户以为"坏了"）。 */
    private static volatile String geometryNote = "";
    /**
     * 这次到底在哪个范围里搜的目的地（"太原 市内" / "全国（跨城搜索）"）。
     * 必须显示出来 —— 用户看到的"目的地错误"里有一半是"范围不对"，
     * 只有把范围摆到界面上，用户才能一眼判断该加城市名还是改说法。
     */
    private static volatile String searchScope = "";

    // ══════════════════════════════════════════════════════════════
    //  偏航自动重算
    //
    //  ── 用户实测（这一版的反馈）────────────────────────────────────
    //  「偏航后路线没有重算 而是提示我语音说出重新导航或者退出导航
    //    但实际咱们在导航界面是不能发语音指令的 加自动换路的逻辑
    //    不提示语音指令」
    //
    //  旧实现的偏航分支只有一句 Toast/画面文案：「请说『重新导航』或『退出导航』」。
    //  问题不在文案，在于**它让用户去做一件在导航页里做不到的事**：
    //  导航页是 App 内的全屏 Dialog，语音走的是眼镜/官方 ASR 那一路；
    //  用户盯着"请说…"，却没有任何可说的入口，只能停车手动点。
    //  所以现在：偏航 → 连续两帧确认 → 自动重新规划 → 新路线直接接上，
    //  用户一句话都不用说。阈值/冷却/文案都在 {@link NavReroute}（有单测）。
    // ══════════════════════════════════════════════════════════════

    /** 是否正有一次重算在跑（防止并发的重算把高德打限流）。 */
    private static volatile boolean rerouting = false;
    /** 上次发起重算的时刻（elapsedRealtime）；0 = 本次导航还没重算过。 */
    private static volatile long rerouteAt = 0;
    /** 连续偏航帧数（GPS 抖动/立交桥上下层会瞬间"偏航"，要连续确认）。 */
    private static volatile int offRouteStreak = 0;
    /** 本次导航重算了几次（诊断用，也让用户知道"它自己在处理"）。 */
    private static volatile int rerouteCount = 0;
    /**
     * 首次解析成功的目的地坐标（"lon,lat"）。
     * 重算必须**沿用同一个坐标**：重新解析一次可能因为重名 POI 落到别处
     * （见 NavPlace 城市锚那一节），偏航重算却把终点悄悄换掉是最不可接受的。
     */
    private static volatile String destCoordCache = "";

    /**
     * 高德定位回调的结果槽。
     *
     * ⚠️ 必须是**静态字段**：`waitAmapFix` 里的代理监听器只在第一次调用时创建，
     * 之后 `aMapListener != null` 直接复用 —— 如果结果槽是每次调用新建的局部变量，
     * 监听器就会一直往第一次那个数组里写，新数组永远是 null，
     * 于是 while 循环空转到超时、`waitAmapFix` 从第二次起必然失败。
     */
    private static final Object[] amapFix = new Object[1];
    private static final Object amapFixLock = new Object();

    private static final String OWNER = "nav";

    static {
        // 通道仲裁：别的模块抢显示时，仲裁层会调 NavEngine::stop 把我停掉。
        NavGlasses.onRelease(OWNER, "导航", NavEngine::stop);
    }

    private NavEngine() {}

    public static boolean active() { return active; }
    public static String status() { return status; }
    public static String destination() { return destination; }
    /** 最近一次推到眼镜的完整画面（测试/诊断用）。 */
    public static String screen() { return lastScreen; }
    public static int stepIndex() { return stepIndex; }
    public static int stepCount() { return steps.size(); }
    /** 高德解析到的目的地全名（"山西省太原市小店区太原南站"）。解析失败为空串。 */
    public static String resolvedAddress() { return resolvedAddress; }
    /** 当前用的城市锚（"太原"）；拿不到时空串。导航页把它显示出来供核对。 */
    public static String anchorName() { return cityAnchor == null ? "" : cityAnchor.name; }
    /** 几何缺失之类的降级说明；正常时为空串。 */
    public static String geometryNote() { return geometryNote; }
    /** 本次目的地的搜索范围（"太原 市内" / "全国（跨城搜索）"）。 */
    public static String searchScope() { return searchScope; }

    // ══════════════════════════════════════════════════════════════
    //  对外入口：语音
    // ══════════════════════════════════════════════════════════════

    /**
     * 处理一句语音。由 AyaSuperAddon 在拦截到 navigate / local 意图后调用，
     * 或在 ASR 命中导航句式时调用。
     *
     * 全部工作都在后台线程，绝不阻塞调用方（native 回调线程）。
     *
     * @param app  Application context（拿定位与 prefs）
     * @param raw  用户原话
     * @param explicitNavigate 官方 domain 是否为 navigate
     * @return 一句给用户/眼镜的即时反馈；null 表示"这句不是导航指令，别管"
     */
    public static String handleVoice(Context app, String raw, boolean explicitNavigate) {
        if (app == null) return null;
        NavVoice.Command command = NavVoice.parse(raw, explicitNavigate);
        switch (command.action) {
            case STOP:
                stop();
                speak(app, "已退出导航");
                // 眼镜会话也一并关掉（导航专用）。
                NavGlasses.stop();
                return "已退出导航";
            case CRUISE:
                speak(app, "正在获取前方路况…");
                new Thread(() -> CruiseUI.briefToGlasses(app), "TurboIO-cruise-voice").start();
                return "正在获取前方路况…";
            case START:
                if (command.destination.isEmpty()) {
                    String ask = NavVoice.clarify(command);
                    speak(app, ask);
                    return ask;
                }
                startAsync(app, command.destination);
                return "正在规划去" + command.destination + "的路线…";
            case NONE:
            default:
                return null;
        }
    }

    /** 开始导航（后台线程跑网络）。 */
    public static void startAsync(Context app, String target) {
        if (target == null || target.trim().isEmpty()) return;
        final String dest = target.trim();
        destination = dest;
        status = "正在定位…";
        lastReason = "";
        resolvedAddress = "";
        geometryNote = "";
        searchScope = "";
        currentStepRemaining = -1;
        // 偏航重算状态每趟归零（上一趟的"刚重算过"会影响这一趟该不该重算）。
        rerouting = false; rerouteAt = 0; offRouteStreak = 0; rerouteCount = 0;
        destCoordCache = "";
        active = true;
        tickCtx = app.getApplicationContext();
        // ★ 先开常驻订阅（"位置不更新 / 看不到位置图标"的修法）。
        //   必须在 locate() 之前 —— 它既是首点来源，也是状态栏定位图标
        //   出现的条件（没有 App 请求定位更新，系统就不点亮那个图标）。
        //   用 wantFix 而不是 startFixUpdates：巡航可能已经在用同一份订阅，
        //   登记制能保证"退出导航"不会顺手把巡航的定位也拆掉。
        wantFix(app, OWNER);
        speak(app, "正在规划去" + dest + "的路线…");
        new Thread(() -> {
            try {
                NavCore.Point here = locate(app, 12000);
                if (here == null) {
                    lastReason = hasLocationPermission(app)
                        ? (fixError.isEmpty()
                            ? "拿不到当前位置（车机/手机刚开机时定位要等一会，可稍后再试）" : fixError)
                        : "缺少定位权限（去「导航」页授权后再试）";
                    status = "定位失败 · " + lastReason;
                    active = false;
                    speak(app, "定位失败：" + lastReason);
                    pushScreen("◎ 定位失败\n" + clipDest() + "\n" + lastReason);
                    return;
                }
                lastFix = here; lastFixAt = SystemClock.elapsedRealtime(); lastAccuracy = 30;
                // 订阅一个点都没来时（没权限 / provider 全关），把首点种进 live 槽 ——
                // 否则 liveAt 恒为 -1，refresh() 会永远判"定位太旧"而卡在等待定位。
                if (liveAt < 0) { liveFix = here; liveAt = SystemClock.elapsedRealtime(); }
                routeStart = here;
                status = "正在规划路线…";
                Route route = plan(app, here, dest);
                if (route == null || route.steps.isEmpty()) {
                    if (lastReason.isEmpty()) lastReason = "没找到「" + dest + "」这个地方，换个说法再试";
                    status = "规划失败 · " + lastReason;
                    active = false;
                    speak(app, "规划失败：" + lastReason);
                    pushScreen("◎ 没找到路线\n" + dest + "\n" + lastReason);
                    return;
                }
                steps = route.steps; totalDistance = route.distance; totalDuration = route.duration;
                stepIndex = 0;
                status = "导航中 · " + dest;
                // 会话已开着就复用，没开就开一个。
                // 刚起步时"剩余里程 = 全程"，所以进度条从 0% 起，一眼能看出还没开始走。
                double remaining = NavGuide.remainingDistance(route.steps, 0, route.firstDistance);
                if (!(remaining > 0)) remaining = totalDistance;
                currentStepRemaining = route.firstDistance > 0 ? route.firstDistance : remaining;
                String first = NavGuide.compose(NavGuide.of(route.steps.get(0).instruction, route.firstDistance),
                    dest, totalDistance, remaining, totalDuration, 0, steps.size());
                lastScreen = first;
                NavGlasses.acquire(OWNER);
                if (NavGlasses.ready()) NavGlasses.push(first); else NavGlasses.show(first, true);
                // 远程目的地要讲清楚"它到底听成了哪里"：市内导航却出现几千公里，
                // 十有八九是重名 POI 被解析到外省（见 resolveDestination 的说明）。
                String where = resolvedAddress;
                String far = (route.distance > FAR_AWAY_METERS && !where.isEmpty())
                    ? "（目的地：" + where + "）" : "";
                speak(app, "开始导航，全程" + NavCore.meters(totalDistance) + far);
                startTicker(app);
            } catch (Exception e) {
                String why = e.getMessage() == null || e.getMessage().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
                // getJson 抛异常前可能已经写了更具体的原因（HTTP 500 / 高德错误码），别覆盖掉。
                if (lastReason.isEmpty()) lastReason = "规划出错：" + why;
                status = "规划失败 · " + lastReason;
                active = false;
                speak(app, "路线规划失败：" + lastReason);
                pushScreen("◎ 规划失败\n" + clipDest() + "\n" + lastReason);
            }
        }, "TurboIO-nav-plan").start();
    }

    /** 结束导航。 */
    public static void stop() {
        active = false;
        steps = new ArrayList<>();
        stepIndex = 0;
        totalDistance = -1; totalDuration = -1;
        status = "未导航";
        lastScreen = "";
        resolvedAddress = "";
        lastReason = "";
        geometryNote = "";
        currentStepRemaining = -1;
        routeStart = null;
        rerouting = false; rerouteAt = 0; offRouteStreak = 0;
        destCoordCache = "";
        stopTicker();
        stopLocation();
        // 注销定位订阅：最后一个使用者离开时才真正拆（巡航还在用就留着）。
        dropFix(OWNER);
        liveFix = null; liveAt = -1; liveAccuracy = -1; liveProvider = ""; fixError = "";
        NavGlasses.release(OWNER);
    }

    /** 给模型/UI 用的一句话导航现状。 */
    public static String statusText() {
        if (!active) return lastReason.isEmpty() ? "当前没有在导航" : ("上一次导航没成功：" + lastReason);
        if (steps.isEmpty()) return status;
        int idx = Math.min(stepIndex, steps.size() - 1);
        double left = NavGuide.remainingDistance(steps, idx, -1);
        long age = fixAgeMs();
        return "正在导航去" + destination
            + (resolvedAddress.isEmpty() ? "" : "（高德解析为 " + resolvedAddress + "）")
            + "，第 " + (idx + 1) + "/" + steps.size()
            + " 段，剩余约 " + (left > 0 ? NavCore.meters(left) : "—")
            + (rerouting ? "（已偏航，正在自动重新规划…）"
                : (rerouteCount > 0 ? "（已自动重算 " + rerouteCount + " 次）" : ""))
            + (geometryNote.isEmpty() ? "" : geometryNote)
            + (age >= 0 && age <= NavFix.FRESH_MS ? ""
                : "。⚠ 定位已经 " + NavFix.ageLabel(age) + "没有更新（画面会因此停住）")
            + (lastScreen.isEmpty() ? "" : "。眼镜上现在显示：" + lastScreen.replace('\n', ' '));
    }

    /** 定位现状一句话（给导航页显示用；把"为什么不动"讲清楚）。 */
    public static String fixText(Context app) {
        if (app == null) app = tickCtx;
        if (app == null) return NavFix.pageLine(true, true, fixUpdates, liveProvider, fixAgeMs(), liveAccuracy, fixError);
        return NavFix.pageLine(hasLocationPermission(app), providerEnabled(app), fixUpdates,
            liveProvider, fixAgeMs(), liveAccuracy, fixError);
    }

    /**
     * 等这次规划出结果（成功或失败），最多 maxMs 毫秒。
     *
     * 语音链路不需要它 —— 用户听 Toast、看眼镜就够了。但**模型调用需要**：
     * 模型必须知道"到底成没成、失败原因是什么"，才能如实回话。
     * 返回一句话（可能仍是"正在规划…"，说明超时）。
     */
    public static String awaitPlan(long maxMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0, maxMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!steps.isEmpty()) {
                return "已开始导航：去" + destination + "，全程 " + NavCore.meters(totalDistance)
                    + (totalDuration > 0 ? "，约 " + Math.max(1, totalDuration / 60) + " 分钟" : "")
                    + "。指引已经显示在眼镜上了。";
            }
            if (!active) {
                return lastReason.isEmpty() ? "上一次导航没成功" : ("导航没起来：" + lastReason);
            }
            try { Thread.sleep(250); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return status;
    }

    // ══════════════════════════════════════════════════════════════
    //  定时：按最新定位决定当前段，推送到眼镜
    // ══════════════════════════════════════════════════════════════

    /**
     * 启动刷新心跳。
     *
     * ── 与「和倒计时一样的错误：不刷新」的关系 ──────────────────────
     * 倒计时那次的心跳是"建在某个页面入口里、条件不满足就不排下一次"，一秒即自杀。
     * 导航这边同类的坑有两处，一并钉死：
     *   ① 旧实现首次刷新固定等 3 秒，且 tick==null 判断缺失 → 有并存两条链的可能；
     *   ② 一旦链断（异常被吞 / 被别的模块停掉），没有任何人负责接回去
     *      → 画面就停住了，且从界面看不出是"心跳死了"还是"路没变"。
     * 现在：幂等启动 + 末尾一定续排 + {@link #ensureTicker()} 供界面轮询自愈。
     */
    private static void startTicker(Context app) {
        tickCtx = app.getApplicationContext();
        final Context ctx = tickCtx;
        // 幂等：先撤掉旧链，绝不并存两条（否则推进速度翻倍、还会互相覆盖 sent）
        if (tick != null) { try { MAIN.removeCallbacks(tick); } catch (Throwable ignored) { } tick = null; }
        tick = new Runnable() {
            @Override public void run() {
                if (!active) { tick = null; return; }
                try { refresh(ctx); } catch (Exception ignored) { }
                if (!active) { tick = null; return; }
                MAIN.postDelayed(this, nextDelayMs());
            }
        };
        MAIN.postDelayed(tick, 800);   // 首帧别等 3 秒
    }

    /**
     * 心跳保活：导航还在跑但心跳没了就重新接上。
     *
     * 由导航页的 2 秒轮询调用 —— 那是一个**独立于推送链路**的观察者，
     * 所以即便推送链自己出了任何意外，最坏 2 秒后也会被接回来。
     * 幂等，可以随便调。
     */
    public static void ensureTicker() {
        if (!active) return;
        Context ctx = tickCtx;
        if (ctx == null) return;
        // ★ 定位订阅也在这里自愈。
        //   订阅是"位置会跟着走"的前提：HandlerThread 被系统回收、provider
        //   被悄悄摘掉时，我们自己的布尔标记不会自己变 false，于是位置静默
        //   停更 —— 那正是用户看到的"不刷新"。这里由独立观察者定期补订。
        //   5 秒节流：重建会重开线程，别 2 秒一次白折腾。
        long now = SystemClock.elapsedRealtime();
        long subscribedMs = fixStartedAt < 0 ? -1 : now - fixStartedAt;
        if (NavFix.shouldResubscribe(fixUpdates, fixAgeMs(), subscribedMs, true) && now - lastFixTry > 5000) {
            lastFixTry = now;
            // 死订阅必须先拆，否则 startFixUpdates 的幂等判断会直接 return。
            if (fixUpdates) stopFixUpdates();
            startFixUpdates(ctx);
        }
        if (tick != null) return;
        startTicker(ctx);
    }

    /** 上次尝试（重）建定位订阅的时间，用于节流。 */
    private static volatile long lastFixTry = 0;

    /**
     * 自适应刷新间隔（毫秒）。
     *
     * 为什么要变：固定 3 秒时，快到一个 100 米后的路口还得等最多 3 秒才更新，
     * 而"还剩 80 米右转"和"已过路口"是完全不同的两个动作。
     * 快到路口就 1 秒一刷；通道被 SDK 拒过（{@link NavGlasses#lastSendFailed()}）
     * 就退到 5 秒，宁可读秒变粗，也不要整条显示通道被打死。
     */
    private static long nextDelayMs() {
        if (NavGlasses.lastSendFailed()) return 5000;
        double left = currentStepRemaining;
        if (left >= 0 && left < 400) return 1000;
        return 3000;
    }

    private static void stopTicker() {
        if (tick != null) MAIN.removeCallbacks(tick);
        tick = null;
    }

    /** 取一次定位 → 匹配当前段 → 组屏 → 推眼镜。 */
    private static void refresh(Context app) {
        // ★ 定位源：订阅来的**实时点**优先。
        //   旧代码这里只有 currentPoint()（= getLastKnownLocation 的缓存值），
        //   而导航期间从未订阅 → 那个值从开机起就不变 → 段号不动 → 画面定死。
        NavCore.Point here = liveFixSnapshot();
        if (here == null) here = currentPoint(app);
        // ★ 拿到就更新。旧代码是 `if (here != null && fresh())`，而 fresh()
        //   判的是"**上一次** lastFix 是否在 20 秒内" —— 定位一断超 20 秒，
        //   条件永远为假，**新拿到的点被直接丢掉**，lastFix 冻死在旧点上。
        //   条件与意图相反，这是"位置不更新"的第二层真凶。
        if (here != null) { lastFix = here; lastFixAt = SystemClock.elapsedRealtime(); }

        List<NavCore.Step> snapshot = steps;
        if (snapshot.isEmpty()) return;
        long age = fixAgeMs();
        if (lastFix == null || NavFix.tooOldToAdvance(age)) {
            // 再拿旧点硬算下去就是"卡住不动"。这里必须**说清楚是定位没数据**，
            // 而不是让用户对着一个静止画面猜是程序坏了还是路没变。
            String why = NavFix.hudWarning(age, hasLocationPermission(app), providerEnabled(app));
            pushScreen("↑ 等待定位…\n" + (why.isEmpty() ? "正在重新获取位置…" : why) + "\n" + clipDest());
            return;
        }
        int idx;
        double remainingStep;
        if (NavRoute.allUnusable(snapshot)) {
            // ── 几何缺失兜底（"画面不动"的第二道保险）──────────────────
            // 高德没给 polyline（漏 show_fields）、响应被截断、或这段路真没几何时，
            // 定位匹配对每一段都会算出同一个结果 → 永远停在第 0 段 → 画面定死。
            // 这里改用"已行驶里程 ÷ 各段 step_distance"推进段号：
            // 精度不如几何匹配，但画面会跟着车往前走。
            double traveled = routeStart == null ? 0 : NavCore.distance(routeStart, lastFix);
            int est = NavGuide.estimateStepByTraveled(snapshot, traveled);
            if (est > stepIndex) stepIndex = est;
            idx = Math.max(0, Math.min(stepIndex, snapshot.size() - 1));
            remainingStep = -1;
            geometryNote = "（高德未返回路线几何，已按里程推进）";
        } else {
            NavCore.Match match = NavCore.match(snapshot, lastFix, stepIndex);
            if (match.step >= 0 && !NavReroute.offRoute(match.offRoute)) {
                stepIndex = match.step;
                if (NavReroute.backOnRoute(match.offRoute)) offRouteStreak = 0;
            } else if (NavReroute.offRoute(match.offRoute)) {
                // ── 偏航：**自动重算**，不再让用户去"说话" ────────────────
                // 旧实现只推一句「请说『重新导航』或『退出导航』」——
                // 而导航页是 App 内 Dialog，用户在这里根本说不出语音指令，
                // 等于把人卡在一个做不到的动作上（见 NavReroute 的类注释）。
                offRouteStreak++;
                long since = rerouteAt <= 0 ? -1 : (SystemClock.elapsedRealtime() - rerouteAt);
                if (NavReroute.shouldReroute(match.offRoute, offRouteStreak, since, rerouting, hasDestination())) {
                    pushScreen(NavReroute.hudRerouting());
                    startReroute(app);
                } else if (rerouting) {
                    pushScreen(NavReroute.hudRerouting());
                } else {
                    // 还在冷却里（刚重算过）——告诉用户"它还会自己再试"，
                    // 而不是让他以为坏了要去点什么。
                    pushScreen(NavReroute.hudWaiting());
                }
                return;
            }
            idx = Math.max(0, Math.min(stepIndex, snapshot.size() - 1));
            if (geometryNote.startsWith("（高德未返回")) geometryNote = "";
            remainingStep = match.remainingStep;
        }
        NavCore.Step step = snapshot.get(idx);
        NavGuide.Guidance guide = NavGuide.of(step.instruction, remainingStep > 0 ? remainingStep : -1);
        // 剩余里程：当前段还剩的（定位匹配给出）+ 之后所有段的全长。
        // 它是进度条和"剩余时间"的唯一输入 —— 没有它就只能显示全程。
        double remaining = NavGuide.remainingDistance(snapshot, idx, remainingStep);
        currentStepRemaining = remainingStep > 0 ? remainingStep : NavGuide.stepLength(step);
        // 到达判定：最后一段且离终点足够近（几何不可用时按剩余里程判）。
        if (idx >= snapshot.size() - 1 && totalDistance > 0) {
            boolean arrived = remainingStep >= 0 ? remainingStep < 30 : remaining < 30;
            if (arrived) {
                pushScreen(NavGuide.compose(NavGuide.of("到达目的地", -1), destination,
                    totalDistance, 0, totalDuration, idx, snapshot.size()));
                stop();
                MAIN.postDelayed(NavGlasses::stop, 8000);
                return;
            }
        }
        String screen = NavGuide.compose(guide, destination, totalDistance, remaining, totalDuration, idx, snapshot.size());
        // 定位有点旧（但还没到"不能用"）：继续用旧点算，同时把这件事说出来。
        // 用户第一眼看到"画面不动"时，这行字直接告诉他问题在定位、不在程序。
        if (NavFix.shouldWarn(age)) {
            String warn = NavFix.hudWarning(age, hasLocationPermission(app), providerEnabled(app));
            if (!warn.isEmpty()) screen = screen + "\n" + warn;
        }
        pushScreen(screen);
    }

    private static String clipDest() {
        return destination.length() > 12 ? destination.substring(0, 12) : destination;
    }

    /** 有没有目的地 —— 没有就谈不上"重算路线"。 */
    private static boolean hasDestination() {
        return destination != null && !destination.isEmpty();
    }

    // ══════════════════════════════════════════════════════════════
    //  偏航重算（在后台线程重新规划，成功后整条替换）
    // ══════════════════════════════════════════════════════════════

    /**
     * 从当前位置重新规划到**同一个目的地**，成功后无缝接上新路线。
     *
     * 几个刻意的取舍：
     *   · **沿用首次解析出的目的地坐标**（destCoordCache）。重新解析一次地名
     *     有可能落到同名的别处 —— 偏航本来就已经让人紧张了，再把终点换掉
     *     是不可接受的。只有在没有缓存（首次就没解析成功）时才重解析。
     *   · **起点用当前定位**，不是"离路线最近的那个点"，因为重算的意义就是
     *     "从我现在所在的地方怎么走"。
     *   · 失败**不清空旧路线**：旧路线仍能给出方向性提示，比一片空白强；
     *     冷却期过后会自动再试（见 NavReroute.COOLDOWN_MS）。
     *   · 每一次尝试都写 `rerouteAt`（不是成功才写）：否则失败时会每次刷新
     *     都打一次高德，很容易把自己打到并发限流。
     */
    private static void startReroute(Context app) {
        final Context ctx = app == null ? tickCtx : app.getApplicationContext();
        if (ctx == null) return;
        final NavCore.Point from = lastFix;
        rerouting = true;
        rerouteAt = SystemClock.elapsedRealtime();
        offRouteStreak = 0;
        speak(ctx, "已偏航，正在重新规划路线");
        new Thread(() -> {
            try {
                NavCore.Point origin = from != null ? from : locate(ctx, 6000);
                if (origin == null) {
                    rerouting = false;
                    pushScreen(NavReroute.hudFailed("拿不到当前位置"));
                    return;
                }
                Route route = planReroute(ctx, origin);
                if (route == null || route.steps.isEmpty()) {
                    rerouting = false;
                    pushScreen(NavReroute.hudFailed(lastReason));
                    return;
                }
                // ★ 替换顺序很重要：先把几何/里程换掉，再动段号与起点，
                //   否则 refresh() 可能用新 steps 配旧 stepIndex 算出一屏错的东西。
                steps = route.steps;
                totalDistance = route.distance;
                totalDuration = route.duration;
                stepIndex = 0;
                routeStart = origin;
                geometryNote = NavRoute.allUnusable(route.steps) ? "（高德未返回路线几何，已按里程推进）" : "";
                currentStepRemaining = route.firstDistance > 0 ? route.firstDistance : totalDistance;
                status = "导航中 · " + destination;
                rerouteCount++;
                rerouteAt = SystemClock.elapsedRealtime();   // 新路线重新计时
                rerouting = false;
                speak(ctx, NavReroute.spokenRerouted(totalDistance));
                // 立刻出第一屏，不等下一个 tick（偏航时用户最需要马上看到新指引）。
                double remaining = NavGuide.remainingDistance(route.steps, 0, route.firstDistance);
                if (!(remaining > 0)) remaining = totalDistance;
                pushScreen(NavGuide.compose(
                    NavGuide.of(route.steps.get(0).instruction, route.firstDistance),
                    destination, totalDistance, remaining, totalDuration, 0, steps.size()));
            } catch (Exception e) {
                String why = e.getMessage() == null || e.getMessage().isEmpty()
                    ? e.getClass().getSimpleName() : e.getMessage();
                rerouting = false;
                pushScreen(NavReroute.hudFailed(lastReason.isEmpty() ? why : lastReason));
            }
        }, "TurboIO-nav-reroute").start();
    }

    /** 本次导航重算了几次（界面/诊断用）。 */
    public static int rerouteCount() { return rerouteCount; }
    /** 是否正在重算（界面/模型读状态用）。 */
    public static boolean rerouting() { return rerouting; }

    private static void pushScreen(String screen) {
        if (screen == null || screen.isEmpty()) return;
        lastScreen = screen;
        try {
            // 抢显示通道（幂等）：如果此刻是别的模块占着（电子书/倒计时…），
            // 会先让它停掉自己的推送循环，否则导航的话面会被对方立刻覆盖。
            NavGlasses.acquire(OWNER);
            // show()：会话就绪立即发；还在开启中就排队，ready 后由 tick 补发。
            NavGlasses.show(screen, true);
        } catch (Exception ignored) { }
    }

    /**
     * 定位是否还在有效期内。
     *
     * @deprecated 旧判据（"上一次 lastFix 是否在 20 秒内"）**条件与意图相反**，
     * 被误用在 `refresh()` 的赋值条件上，导致定位一断超 20 秒、新点就被白白
     * 丢掉、lastFix 冻死。现在鲜度判断统一走 {@link NavFix}（按"点的年龄"判，
     * 而不是按"上次更新距今"判）。保留此方法只为兼容可能的旧调用。
     */
    @Deprecated
    private static boolean fresh() {
        return lastFixAt > 0 && SystemClock.elapsedRealtime() - lastFixAt < 20000;
    }

    // ══════════════════════════════════════════════════════════════
    //  定位
    // ══════════════════════════════════════════════════════════════

    /**
     * 开始**持续**订阅定位。导航全程必须一直开着。
     *
     * 这是"位置会跟着车走"的唯一来源，也是状态栏出现定位图标的条件
     * （Android 只在有 App 主动请求定位更新时才显示那个图标 —— 用户
     * "没看到位置图标"就是这里从来没被调用过）。
     *
     * GPS 与网络定位**都订**：车机天线差、进地库时网络点能兜住，
     * GPS 恢复后自动接回。1 秒一个点（1000ms），导航需要这个粒度。
     *
     * 幂等，可随便调。
     */
    public static void startFixUpdates(Context app) {
        if (app == null) return;
        synchronized (fixLock) {
            if (fixUpdates) return;
            fixCtx = app.getApplicationContext();
            fixError = "";
            try {
                LocationManager lm = (LocationManager) fixCtx.getSystemService(Context.LOCATION_SERVICE);
                if (lm == null) { fixError = "系统没有定位服务"; return; }
                if (!hasLocationPermission(fixCtx)) { fixError = "缺少定位权限"; return; }

                if (fixThread == null) {
                    fixThread = new android.os.HandlerThread("TurboIO-nav-fix");
                    fixThread.start();
                }
                if (fixListener == null) {
                    fixListener = location -> {
                        if (location == null) return;
                        try {
                            // 系统 provider 给的是 WGS-84，高德吃 GCJ-02 → 必须转。
                            NavCore.Point p = CruiseUI.wgs84ToGcj02(
                                location.getLatitude(), location.getLongitude());
                            long now = SystemClock.elapsedRealtime();
                            float acc = location.hasAccuracy() ? location.getAccuracy() : -1;
                            liveFix = p; liveAt = now; liveAccuracy = acc;
                            liveProvider = location.getProvider() == null ? "" : location.getProvider();
                            // ★ 直接落进 lastFix：refresh() 只认"最新点"，
                            //   绝不再犯"有值却因为条件相反被丢掉"的错。
                            lastFix = p; lastFixAt = now; lastAccuracy = acc;
                            fixError = "";
                            // 定位点每秒来一个 —— 顺手当成**第二个独立观察者**：
                            // 刷新心跳若断了（异常被吞 / 被别的模块停掉），导航页
                            // 没开着时没人调 ensureTicker，这里补上。post 到主线程
                            // 执行（自愈里可能重建订阅，不能在订阅自己的回调线程上做）。
                            if (active && tick == null) MAIN.post(NavEngine::ensureTicker);
                        } catch (Throwable ignored) { }
                    };
                }
                boolean any = false;
                for (String provider : new String[]{
                        LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    try {
                        if (!lm.isProviderEnabled(provider)) continue;
                        lm.requestLocationUpdates(provider, 1000L, 0f, fixListener, fixThread.getLooper());
                        any = true;
                    } catch (Throwable ignored) { }
                }
                fixUpdates = any;
                fixStartedAt = any ? SystemClock.elapsedRealtime() : -1;
                if (!any) fixError = "定位开关没打开（GPS 与网络定位都不可用）";
            } catch (Throwable t) {
                fixError = "定位订阅失败：" + t.getClass().getSimpleName();
                fixUpdates = false;
            }
        }
    }

    /** 停止订阅（退出导航时调，别让 GPS 一直转着耗电）。 */
    public static void stopFixUpdates() {
        synchronized (fixLock) {
            try {
                if (fixListener != null && fixCtx != null) {
                    LocationManager lm = (LocationManager) fixCtx.getSystemService(Context.LOCATION_SERVICE);
                    if (lm != null) lm.removeUpdates(fixListener);
                }
            } catch (Throwable ignored) { }
            fixListener = null;
            fixUpdates = false;
            fixStartedAt = -1;
            if (fixThread != null) {
                try { fixThread.quitSafely(); } catch (Throwable ignored) { }
                fixThread = null;
            }
        }
    }

    /**
     * 「我要用定位」：登记一个使用者并（必要时）建立订阅。幂等。
     *
     * 导航和巡航各自登记自己的名字；只要还有人登记，订阅就不会被拆。
     */
    public static void wantFix(Context app, String owner) {
        if (app == null || owner == null || owner.isEmpty()) return;
        boolean first;
        synchronized (fixWanters) {
            first = fixWanters.add(owner) && fixWanters.size() == 1;
        }
        // 已经有人登记、且订阅还活着 → 什么都不用做（别人的订阅我也能用）。
        if (!first && fixUpdates) return;
        startFixUpdates(app);
    }

    /** 「我用完了」：注销；最后一个离开时才真正拆掉订阅。 */
    public static void dropFix(String owner) {
        if (owner == null || owner.isEmpty()) return;
        boolean last;
        synchronized (fixWanters) {
            fixWanters.remove(owner);
            last = fixWanters.isEmpty();
        }
        if (last) stopFixUpdates();
    }

    /** 最近一个实时订阅点（GCJ-02）；没有订阅或无数据时 null。 */
    public static NavCore.Point latestFix() {
        return liveFixSnapshot();
    }

    /**
     * 等一个"新鲜"的实时点，最多等 maxMs。
     *
     * 巡航用：它没有导航那样的每秒心跳，是"按需取一次"，
     * 所以必须**先 {@link #wantFix} 再等点**，而不是读缓存
     * （读缓存正是旧实现"位置永远不更新"的那个坑）。
     * 本方法自己**不**登记/注销订阅 —— 由调用方决定订阅的生命期
     * （一次播报用完就 drop，连续巡航则整段持有）。
     * 等不到返回 null，由调用方决定怎么提示。
     */
    public static NavCore.Point awaitFix(Context app, long maxMs) {
        NavCore.Point quick = liveFixSnapshot();
        if (quick != null) return quick;
        long deadline = SystemClock.elapsedRealtime() + Math.max(0, maxMs);
        while (SystemClock.elapsedRealtime() < deadline) {
            NavCore.Point p = liveFixSnapshot();
            if (p != null) return p;
            if (!fixUpdates) break;      // 订阅起不来（没权限/开关没开），别干等
            try { Thread.sleep(200); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return liveFixSnapshot();
    }

    /** 系统定位开关是否至少有一个 provider 开着。 */
    public static boolean providerEnabled(Context app) {
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最近一个定位点距今多少毫秒；-1 表示从没拿到过。 */
    public static long fixAgeMs() {
        long at = liveAt;
        if (at < 0) return -1;
        return SystemClock.elapsedRealtime() - at;
    }

    /** 订阅是否在跑（诊断用）。 */
    public static boolean fixSubscribed() { return fixUpdates; }

    /** 最近定位来源（"gps"/"network"）。 */
    public static String fixProvider() { return liveProvider; }

    /** 订阅失败的原因；正常为空串。 */
    public static String fixError() { return fixError; }

    private static NavCore.Point liveFixSnapshot() {
        return liveUpdates() ? liveFix : null;
    }

    private static boolean liveUpdates() { return fixUpdates; }

    /**
     * 拿当前位置（已转 GCJ-02）。
     *
     * 顺序（★ 与旧实现的关键差别：**第 ① 步是新增的**）：
     *   ① 等订阅的首点 —— 这是导航全程唯一可靠的定位源；
     *   ② 系统缓存 lastKnownLocation（旧实现只有这一步，于是永远不动）；
     *   ③ 主动向系统要一次；
     *   ④ 最后才退回反射调高德 SDK。
     */
    static NavCore.Point locate(Context app, long waitMs) {
        // ① 等订阅的首点。订阅没起来就别干等，立刻走后面的兜底。
        long budget = Math.max(0, Math.min(waitMs, 8000));
        long deadline = SystemClock.elapsedRealtime() + budget;
        NavCore.Point live = liveFixSnapshot();
        while (live == null && SystemClock.elapsedRealtime() < deadline) {
            if (!fixUpdates) break;
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            live = liveFixSnapshot();
        }
        if (live != null) return live;
        // ② 系统缓存
        NavCore.Point quick = currentPoint(app);
        if (quick != null) return quick;
        // ③ / ④ 主动要一次（把剩余预算给它们）
        long rest = Math.max(1000, waitMs - budget);
        NavCore.Point fresh = requestFreshFix(app, rest);
        if (fresh != null) return fresh;
        return waitAmapFix(app, rest);
    }

    /**
     * 主动向系统要一次定位。
     *
     * ── 为什么需要 ────────────────────────────────────────────────
     * `getLastKnownLocation` 在没有别的 App 最近定位过时就是 null（新车机刚开机
     * 很常见），而反射调高德 SDK 又依赖 SDK 在位。两者都失败 = "拿不到当前位置"
     * = 用户体感"语音说了导航但没法规划路线"。
     *
     * 回调必须跑在**有 Looper 的线程**上：这里用的是调用线程自己 prepare 的
     * Looper，而调用方 `locate()` 通常在后台线程里 —— 直接 prepare + wait 会
     * 让 Looper 永远轮不到消息。所以单开一个 HandlerThread 来收回调。
     */
    private static NavCore.Point requestFreshFix(Context app, long waitMs) {
        android.os.HandlerThread thread = null;
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                ? LocationManager.GPS_PROVIDER
                : (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null);
            if (provider == null) return null;

            thread = new android.os.HandlerThread("TurboIO-fix");
            thread.start();
            final Location[] got = new Location[1];
            final Object lock = new Object();
            final android.location.LocationListener listener = location -> {
                synchronized (lock) { if (got[0] == null) got[0] = location; lock.notifyAll(); }
            };
            lm.requestLocationUpdates(provider, 0, 0, listener, thread.getLooper());
            long deadline = SystemClock.elapsedRealtime() + Math.max(1000, Math.min(waitMs, 10000));
            synchronized (lock) {
                while (got[0] == null && SystemClock.elapsedRealtime() < deadline) {
                    try { lock.wait(200); } catch (InterruptedException e) { break; }
                }
            }
            try { lm.removeUpdates(listener); } catch (Exception ignored) { }
            if (got[0] == null) return null;
            return CruiseUI.wgs84ToGcj02(got[0].getLatitude(), got[0].getLongitude());
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (thread != null) { try { thread.quitSafely(); } catch (Exception ignored) { } }
        }
    }

    static NavCore.Point currentPoint(Context app) {
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (!lm.isProviderEnabled(provider)) continue;
                    Location loc = lm.getLastKnownLocation(provider);
                    if (loc == null) continue;
                    if (best == null || loc.getTime() > best.getTime()) best = loc;
                } catch (SecurityException ignored) { }
            }
            if (best == null) return null;
            // 超过 5 分钟的老定位不作为起点（会算出荒唐的路线）。
            if (System.currentTimeMillis() - best.getTime() > 300000) return null;
            return CruiseUI.wgs84ToGcj02(best.getLatitude(), best.getLongitude());
        } catch (Exception e) {
            return null;
        }
    }

    /** 用高德定位 SDK 等一次回调（若 SDK 不可用则返回 null）。 */
    private static NavCore.Point waitAmapFix(Context app, long waitMs) {
        try {
            if (aMapClient == null) {
                aMapClient = NavReflect.make("com.amap.api.location.AMapLocationClient", app);
            }
            synchronized (amapFixLock) { amapFix[0] = null; }
            if (aMapListener == null) {
                // 监听器只建一次，所以它写的必须是**静态**结果槽（见 amapFix 的注释）
                aMapListener = NavReflect.proxy("com.amap.api.location.AMapLocationListener", (name, args) -> {
                    if (args.length > 0 && args[0] != null) synchronized (amapFixLock) { amapFix[0] = args[0]; }
                });
                NavReflect.call(aMapClient, "setLocationListener", aMapListener);
                Object option = NavReflect.make("com.amap.api.location.AMapLocationClientOption");
                NavReflect.call(option, "setOnceLocation", true);
                NavReflect.call(aMapClient, "setLocationOption", option);
            }
            NavReflect.call(aMapClient, "startLocation");
            long deadline = SystemClock.elapsedRealtime() + waitMs;
            Object fix = null;
            while (SystemClock.elapsedRealtime() < deadline) {
                synchronized (amapFixLock) { fix = amapFix[0]; }
                if (fix != null) break;
                Thread.sleep(200);
            }
            try { NavReflect.call(aMapClient, "stopLocation"); } catch (Exception ignored) { }
            if (fix == null) return null;
            Object loc = fix;
            int code = ((Number) NavReflect.call(loc, "getErrorCode")).intValue();
            if (code != 0) return null;
            double lat = ((Number) NavReflect.call(loc, "getLatitude")).doubleValue();
            double lon = ((Number) NavReflect.call(loc, "getLongitude")).doubleValue();
            if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6) return null;
            return new NavCore.Point(lat, lon);   // 高德已返回 GCJ-02
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void stopLocation() {
        try { if (aMapClient != null) NavReflect.call(aMapClient, "stopLocation"); } catch (Exception ignored) { }
    }

    public static boolean hasLocationPermission(Context app) {
        try {
            return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  高德路线（Web 服务，不依赖 SDK 与签名）
    // ══════════════════════════════════════════════════════════════

    static final class Route {
        final List<NavCore.Step> steps;
        final double distance;
        final long duration;
        final double firstDistance;
        Route(List<NavCore.Step> steps, double distance, long duration, double firstDistance) {
            this.steps = steps; this.distance = distance; this.duration = duration; this.firstDistance = firstDistance;
        }
    }

    /**
     * 目的地文本 → 推荐驾车方案。
     *
     * 两步：
     *   ① v3/geocode/geo 把「太原南站」变成坐标
     *   ② v5/direction/driving 用 strategy=32（高德推荐）拿方案
     *
     * v5 的 steps 里每条带 instruction 与 step_distance，
     * 这正是 NavGuide 需要的输入。
     */
    static Route plan(Context app, NavCore.Point origin, String target) throws Exception {
        String key = amapKey(app);
        if (key.isEmpty()) { lastReason = "没有可用的高德 Key，请在「导航」页填一个 Android 平台 Key"; return null; }
        // ① 目的地解析（★ 必须先锚定"当前城市"，见 resolveDestination 的说明）
        String destCoord = resolveDestination(key, origin, target);
        if (destCoord == null || destCoord.isEmpty()) {
            if (lastReason.isEmpty()) lastReason = "没能把「" + target + "」解析成坐标";
            return null;
        }
        // ★ 记下解析结果：偏航重算要**沿用同一个终点**（见 planReroute）。
        destCoordCache = destCoord;
        return planTo(key, origin, destCoord);
    }

    /**
     * 偏航重算用的规划：**沿用首次解析出的目的地坐标**。
     *
     * 为什么不重新解析一遍地名：`resolveDestination` 是"城市内双问 + 按意图取舍"，
     * 同一句话在不同时刻可能落到相隔一公里的两个候选（"万象城"既能是商场、
     * 也能是公交站）。偏航本身已经让用户紧张，这时候把**终点**悄悄换掉，
     * 是最难被发现也最危险的一类错误。所以只要缓存里有坐标就直接用。
     */
    static Route planReroute(Context app, NavCore.Point origin) throws Exception {
        String key = amapKey(app);
        if (key.isEmpty()) { lastReason = "没有可用的高德 Key，请在「导航」页填一个 Android 平台 Key"; return null; }
        String destCoord = destCoordCache;
        if (destCoord == null || destCoord.isEmpty()) {
            destCoord = resolveDestination(key, origin, destination);
            if (destCoord == null || destCoord.isEmpty()) {
                if (lastReason.isEmpty()) lastReason = "没能把「" + destination + "」解析成坐标";
                return null;
            }
            destCoordCache = destCoord;
        }
        return planTo(key, origin, destCoord);
    }

    /**
     * 真正打高德拿方案（把"解析目的地"与"请求路线"分开，重算才能复用坐标）。
     * 调用方必须先确保 `destCoord` 非空。
     */
    private static Route planTo(String key, NavCore.Point origin, String destCoord) throws Exception {
        // ② 驾车路线（strategy=32 = 高德推荐，含躲避拥堵倾向）
        //    ★ show_fields 必须带 polyline 已收进 NavRoute.drivingUrl ★
        //    v5 与 v3 不一样：v3 的 steps 里默认带 polyline，v5 **不带**，
        //    要显式在 show_fields 里点出来。漏了它，每一段的几何都是空的，
        //    所有分段几何就全等于"起点" → NavCore.match 永远匹配到第 0 段、
        //    剩余里程恒等于全程、进度条不动、画面每 3 秒推的还是同一屏
        //    —— 用户实测的「不刷新，卡在第一屏不动」。
        //    现在这条 URL 有单测钉着（NavRouteTest），改坏了立刻红。
        String originText = String.format(Locale.ROOT, "%.6f,%.6f", origin.lon, origin.lat);
        JSONObject route = getJson(NavRoute.drivingUrl(key, originText, destCoord));
        JSONObject routeObj = route.optJSONObject("route");
        if (routeObj == null) {
            if (lastReason.isEmpty()) lastReason = "高德没有返回路线（起点或终点没被接受）";
            return null;
        }
        JSONArray paths = routeObj.optJSONArray("paths");
        if (paths == null || paths.length() == 0) {
            if (lastReason.isEmpty()) lastReason = "高德没有返回可走的驾车路线";
            return null;
        }
        JSONObject path = paths.getJSONObject(0);   // 第一条 = 高德推荐方案

        double distance = path.optDouble("distance", -1);
        long duration = -1;
        JSONObject cost = path.optJSONObject("cost");
        if (cost != null) duration = cost.optLong("duration", -1);
        if (duration <= 0) duration = (long) path.optDouble("duration", -1);

        JSONArray rawSteps = path.optJSONArray("steps");
        if (rawSteps == null || rawSteps.length() == 0) return null;
        List<String> instructions = new ArrayList<>();
        List<List<NavCore.Point>> rawLines = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (int i = 0; i < rawSteps.length(); i++) {
            JSONObject raw = rawSteps.optJSONObject(i);
            if (raw == null) continue;
            String instruction = raw.optString("instruction", "");
            if (instruction.isEmpty()) instruction = raw.optString("road_name", "");
            if (instruction.isEmpty()) continue;
            instructions.add(instruction);
            rawLines.add(NavRoute.line(raw.optString("polyline", "")));
            distances.add(raw.optDouble("step_distance", -1));
        }
        if (instructions.isEmpty()) return null;
        // 缺几何的段用「上一段终点 → 下一段起点」补出近似线段。
        // 旧实现塞的是 [origin, origin]，所有段一起缺几何时就全部塌成同一个点，
        // 这正是"永远停在第 0 段"的机制；现在塌不了。
        List<List<NavCore.Point>> lines = NavRoute.patch(rawLines, origin);
        List<NavCore.Step> out = new ArrayList<>();
        double firstDistance = -1;
        for (int i = 0; i < instructions.size(); i++) {
            double stepDistance = i < distances.size() ? distances.get(i) : -1;
            if (i == 0) firstDistance = stepDistance;
            List<NavCore.Point> points = i < lines.size() ? lines.get(i) : new ArrayList<>(); 
            out.add(new NavCore.Step(instructions.get(i), points, stepDistance));
        }
        // 首段距离塞进 instruction 文案会更稳（高德有时不给 step_distance）。
        if (firstDistance <= 0) firstDistance = NavGuide.extractDistance(out.get(0).instruction);
        // 诊断：几何还是一片空白时，让用户知道是"高德没给几何"，不是 App 坏了。
        if (NavRoute.allUnusable(out)) {
            geometryNote = "（高德未返回路线几何，已按里程推进）";
        }
        return new Route(out, distance, duration, firstDistance);
    }

    // ══════════════════════════════════════════════════════════════
    //  目的地解析（修「目的地错误：导到外省同名地点」）
    //
    //  ── 用户实测 ────────────────────────────────────────────────
    //  「还存在目的地错误的事件，这次把城市加上，在城市范围内搜索」。
    //
    //  ── 实测复现（2026-09-18，内置 Key 直接打高德）────────────────
    //    geocode「万象城」不带 city        → 广西玉林市玉州区万象城      ← 起点太原！
    //    geocode「万达广场」不带 city      → 四川南充市南部县万达广场
    //    place/text「万象城」city=140100   → 太原万象城（万柏林区）      ✅
    //    place/text「万象城」city=140107   → 「想宅万象城店钟楼街店民宿」 ❌
    //
    //  两条结论，缺一不可：
    //   ① **必须带 city**。不带就是"全国取最优匹配"，而「万象城」「万达广场」
    //      「火车站」「人民医院」全国几十个城市都有，命中哪个纯看运气。
    //   ② **city 只能是城市名 / citycode / 城市级 adcode**。regeo 给的 adcode
    //      是**区级**（杏花岭区 140107），当 city 用会把搜索锁死在一个区里，
    //      于是挑出"本区里最像的那个"—— 一个叫「万象城店」的民宿。
    //      旧实现正是直接拿 regeo 的 adcode 当 city 用的。
    //
    //  ── 现在的取法 ──────────────────────────────────────────────
    //   ① regeo 拿城市锚（城市中文名优先，citycode 备选；区级 adcode 一律丢），
    //      锚缓存 10 分钟 / 15 公里 —— 高德个人 Key 有并发限制（实测撞到 10021）；
    //   ② 同城内**同时**问 POI 搜索与地址解析，再按查询意图取舍
    //      （{@link NavPlace#pick}：地标信 POI、门牌信 geocode，但明显更远的一方会被换掉）；
    //   ③ 同城都没命中 → 关键字里并进城市名再全国搜一次（"把城市加上"）；
    //   ④ 仍没命中，而用户说的是**另一个城市**（"导航去北京"）→ 去掉锚全局搜；
    //   ⑤ 最后把解析到的全名播报/显示出来（"目的地：山西省太原市小店区太原南站"），
    //      一旦听错用户当场就能发现，而不是开出十公里才觉得不对。
    // ══════════════════════════════════════════════════════════════

    private static final double FAR_AWAY_METERS = 300000;   // 30 万米 = 300 公里

    /** 解析目的地坐标，返回高德格式的 "lon,lat"；失败返回 null。 */
    static String resolveDestination(String key, NavCore.Point origin, String target) throws Exception {
        // 这一步之前的失败原因（比如 regeo 限流）不该污染后面的判断：谁能成谁说了算。
        lastReason = "";

        boolean cross = NavPlace.intercity(target) || mentionsCity(target, currentCityName());
        // 城市锚永远先取（有缓存，10 分钟内/15 公里内不额外发请求）——
        // 即便这次是跨城搜索，界面也要能显示"你在哪座城市"，否则用户无从判断。
        NavPlace.Anchor here = currentAnchor(key, origin);
        NavPlace.Anchor anchor = cross ? NavPlace.EMPTY : here;
        searchScope = cross ? "全国（跨城搜索）"
            : (anchor.usable() ? (anchor.name.isEmpty() ? anchor.param() : anchor.name) + " 市内" : "全国（未拿到所在城市）");
        boolean addressLike = NavPlace.looksLikeAddress(target);

        String poi = null, geo = null;
        if (anchor.usable()) {
            // 先按意图顺序问，两个都问（各一次请求），再决定用哪个。
            if (addressLike) { geo = geocode(key, target, anchor.param()); poi = poiSearch(key, target, anchor.param()); }
            else { poi = poiSearch(key, target, anchor.param()); geo = geocode(key, target, anchor.param()); }
        }
        NavPlace.Source picked = NavPlace.pick(origin, poi, geo, addressLike);
        if (picked != NavPlace.Source.NONE) {
            lastReason = "";
            return picked == NavPlace.Source.POI ? poi : geo;
        }

        // ③ 同城没命中：把城市名并进关键字，全国搜一次（用户说的"把城市加上"）。
        if (anchor.usable() && !anchor.name.isEmpty()) {
            String withCity = NavPlace.withCity(target, anchor);
            String p2 = poiSearch(key, withCity, "");
            if (p2 == null) p2 = geocode(key, withCity, "");
            if (p2 != null) { lastReason = ""; return p2; }
        }

        // ④ 跨城（或拿不到城市锚）：按用户原话全局搜。
        //    这里**只能**在跨城时走 —— 市内地名全局搜正是"导到广西玉林"的来源。
        if (cross || !anchor.usable()) {
            String g = geocode(key, target, "");
            if (g == null) g = poiSearch(key, target, "");
            if (g != null) { lastReason = ""; return g; }
        }

        if (lastReason.isEmpty()) lastReason = NavPlace.noResultReason(target, anchor, cross);
        return null;
    }

    /**
     * 拿（带缓存的）城市锚。
     *
     * 缓存的两个理由：
     *  ① 城市几分钟内不会变，而 regeo 每次导航都要打；
     *  ② 高德个人 Key 有**并发/QPS 限制** —— 实测连续几个请求就吃到
     *     `CUQPS_HAS_EXCEEDED_THE_LIMIT`（10021）。省一次是一次。
     */
    static NavPlace.Anchor currentAnchor(String key, NavCore.Point origin) {
        NavPlace.Anchor cached = cityAnchor;
        long age = SystemClock.elapsedRealtime() - anchorAt;
        // 起点的位移：拿缓存的锚位与当前定位比（拿不到就当 0 米，即仍可复用）。
        double move = (anchorOrigin == null || origin == null) ? 0 : NavCore.distance(anchorOrigin, origin);
        if (cached != null && cached.usable() && anchorAt > 0 && NavPlace.reuseAnchor(age, move)) return cached;
        NavPlace.Anchor fresh = fetchAnchor(key, origin);
        if (fresh.usable()) { cityAnchor = fresh; anchorAt = SystemClock.elapsedRealtime(); anchorOrigin = origin; }
        return fresh;
    }

    private static String coord(NavCore.Point p) {
        return p == null ? "" : String.format(Locale.ROOT, "%.6f,%.6f", p.lon, p.lat);
    }

    /**
     * regeo 反查城市锚。
     *
     * ⚠️ 旧实现这里有个必崩的写法：`regeo.optJSONObject("regeocode").optJSONObject(...)`。
     *    高德出错时（限流/未开通逆地理编码）body 里根本没有 `regeocode`，
     *    于是对 null 调方法 → **NullPointerException** → 整条规划链以
     *    「规划出错：NullPointerException」结束。现在逐层判空。
     */
    static NavPlace.Anchor fetchAnchor(String key, NavCore.Point origin) {
        if (origin == null) return NavPlace.EMPTY;
        try {
            JSONObject regeo = getJson(NavRoute.regeoUrl(key, coord(origin)));
            JSONObject regeocode = regeo.optJSONObject("regeocode");
            if (regeocode == null) return NavPlace.EMPTY;
            JSONObject comp = regeocode.optJSONObject("addressComponent");
            if (comp == null) return NavPlace.EMPTY;
            return NavPlace.anchor(comp.optString("city", ""), comp.optString("citycode", ""),
                comp.optString("adcode", ""), comp.optString("province", ""));
        } catch (Exception e) {
            return NavPlace.EMPTY;
        }
    }

    static String currentCityName() { return cityAnchor == null ? "" : cityAnchor.name; }

    /** 地址解析。city 为空就是全国范围（**只在跨城或拿不到城市锚时才允许**）。 */
    static String geocode(String key, String target, String city) throws Exception {
        JSONArray geocodes = getJson(NavRoute.geocodeUrl(key, target, city)).optJSONArray("geocodes");
        if (geocodes == null || geocodes.length() == 0) return null;
        JSONObject first = geocodes.optJSONObject(0);
        if (first == null) return null;
        String c = first.optString("location", "");
        if (c.isEmpty()) return null;
        String addr = first.optString("formatted_address", "");
        if (addr.isEmpty()) addr = first.optString("province", "") + first.optString("city", "") + target;
        resolvedAddress = addr;
        return c;
    }

    /** POI 关键字搜索。city 非空时必定带 citylimit=true（详见 {@link NavRoute#poiUrl}）。 */
    static String poiSearch(String key, String target, String city) throws Exception {
        JSONArray pois = getJson(NavRoute.poiUrl(key, target, city)).optJSONArray("pois");
        if (pois == null || pois.length() == 0) return null;
        JSONObject poi = pois.optJSONObject(0);
        if (poi == null) return null;
        String c = poi.optString("location", "");
        if (c.isEmpty()) return null;
        String name = poi.optString("name", target);
        String area = poi.optString("cityname", "") + poi.optString("adname", "");
        resolvedAddress = area.isEmpty() ? name : (area + " · " + name);
        return c;
    }

    /** "lon,lat" → 与起点的大圆距离（米）。解析不了返回 -1。 */
    static double straightMeters(NavCore.Point origin, String c) {
        return NavPlace.meters(origin, c);
    }

    /**
     * 用户这句话里有没有提到城市/省份名。
     * 提到就说明他可能就是要去外地（"导航去北京"），不该被同城锚定纠正。
     */
    static boolean mentionsCity(String target, String cityName) {
        if (target == null) return false;
        for (String marker : new String[]{"市", "省", "区", "县", "州", "旗"}) {
            if (target.contains(marker)) return true;
        }
        if (cityName != null && !cityName.isEmpty()) {
            String bare = cityName.endsWith("市") ? cityName.substring(0, cityName.length() - 1) : cityName;
            if (!bare.isEmpty() && target.contains(bare)) return true;
        }
        return false;
    }

    /** 取高德 Key：设置里填过就用用户的，否则返回空（v3r26 起不再内置）。 */
    static String amapKey(Context app) {
        try {
            String saved = app.getSharedPreferences("turboio_settings", 0).getString("amap_key", "");
            if (saved != null && !saved.trim().isEmpty()) return saved.trim();
        } catch (Exception ignored) { }
        return "";
    }

    /**
     * 高德 Web 服务的 GET。
     *
     * ★ 关键：高德出错时**HTTP 依然是 200**，错误藏在 body 的 status/info/infocode 里
     *   （例如 `{"status":"0","info":"DAILY_QUERY_OVER_LIMIT","infocode":"10003"}`）。
     *   旧实现只看 HTTP 码，于是限流被当成"没有匹配的地点"，用户只看到
     *   一句含糊的"没有规划出路线"。这里顺手把原因记进 lastReason，谁先失败谁写。
     */
    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) {
                lastReason = "高德接口不可达（HTTP " + code + "，检查网络或代理）";
                throw new java.io.IOException("HTTP " + code);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            JSONObject json = new JSONObject(out.toString("UTF-8"));
            String ok = json.optString("status", "1");
            if (!"1".equals(ok)) {
                lastReason = NavCore.amapReason(json.optString("info", ""), json.optString("infocode", ""));
            }
            return json;
        } finally {
            conn.disconnect();
        }
    }

    // ══════════════════════════════════════════════════════════════

    private static void speak(Context app, String text) {
        status = text == null ? status : text;
        if (text == null) return;
        MAIN.post(() -> {
            try { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show(); }
            catch (Exception ignored) { }
        });
    }
}

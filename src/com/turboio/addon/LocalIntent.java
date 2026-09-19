package com.turboio.addon;

import java.util.Locale;

/**
 * 「这句话是不是本机能力」的统一判定 —— **纯逻辑层，可单测**。
 *
 * ══ 为什么要有这个类（用户实测反馈）════════════════════════════
 * 用户原话：「官方的意图接管还是有问题 自有模型没问题」
 * 追问后的答案：**部分能用部分不行** —— 导航、倒计时能用，家况 / 天气 / 电子书不行。
 *
 * ── 根因：接管判定**过度依赖官方给的 domain** ──────────────────
 * 旧 `NlpRouter.consume` 的分支条件大致是：
 *   · 导航  ← domain=="navigate" 或本地口令兜底
 *   · 家况  ← **必须** domain ∈ {local, iot, home} 且含家况关键词
 *   · 天气  ← 本地口令（填过 Key）
 * 于是"能用不能用"完全取决于**官方 NLU 这一句给了什么 domain**：
 *   · 说「导航到太原站」官方给 navigate（或给 chat，被本地口令兜住）→ 能用；
 *   · 说「家里温度多少」，官方很可能给 chat/weather —— **不再匹配 local/iot/home**，
 *     于是我们连"要不要接管"都不问，直接放行给官方闲聊，用户体感"家况叫不动"。
 *   · 天气、电子书更彻底：电子书**根本没写进任何分支**。
 *
 * ── 现在的原则 ──────────────────────────────────────────────────
 * **以"这句话本身"为准，domain 只作加分项。** 本机能力（导航/倒计时/家况/天气/
 * 电子书/随口记）的共同特征是"口令特征明显"：它们要么有固定唤醒词，
 * 要么有固定句式。判定这些不需要知道官方把它归到哪个 domain。
 *
 * domain 仍然有用，所以保留为**放宽条件**：
 *   · domain 明说是本地/物联网能力 → 家况类只要沾边就认（官方都当成本地指令了）；
 *   · domain 是 chat/未知 → 有「家里」这种地点词就认（那是"在问家里"的最强信号），
 *     只有设备词（如单独一句「温度」）才额外要求"疑问或状态"信号；
 *     否则「我家的猫叫什么」这种闲聊会被当成家况吞掉。
 *
 * 判定顺序 = 优先级，后面的动作绝不该抢前面的（都在注释里逐条写了原因）。
 */
public final class LocalIntent {
    private LocalIntent() {}

    public enum Kind {
        NONE,
        /** 导航（开始/退出/巡航/重算） */
        NAV,
        /** 倒计时 */
        TIMER,
        /** 实时天气（和风） */
        WEATHER,
        /** 家况播报（米家/HA 网关只读聚合） */
        HOME,
        /** 电子书（打开/续读/翻页/进度） */
        EBOOK,
        /** 随口记（写入/打开/朗读/清空） */
        MEMO
    }

    /**
     * 统一判定。
     *
     * @param query        用户原话
     * @param domain       官方给的 domain（**原始值**，早于 navigate→chat 改写）
     * @param hasBook      电子书里有没有导入过书（没有书时"翻页"这类口令不接管）
     * @param weatherReady 和风 Key 是否配好（没配就别抢官方能答的天气问句）
     */
    public static Kind classify(String query, String domain, boolean hasBook, boolean weatherReady) {
        if (query == null) return Kind.NONE;
        String q = query.trim();
        if (q.isEmpty()) return Kind.NONE;

        // ① 随口记：口令极其独特（「随口记」「记一下」），零歧义，排最前。
        //    它绝不能排在导航后面 —— 「记一下 去太原站接人」不该被当成导航。
        if (Memo.parse(q) != Memo.Action.NONE) return Kind.MEMO;

        // ② 电子书：同理，"下一页""继续读"根本不会与别的口令混淆。
        //    没有书时只认"打开/查进度"（那两种情况回一句"还没导入书"也有意义）；
        //    "翻页"类没有书就没意义，放行给官方，免得白吃一句。
        ReaderCmd.Action book = ReaderCmd.parse(q);
        if (book != ReaderCmd.Action.NONE && (hasBook || ReaderCmd.worksWithoutBook(book))) return Kind.EBOOK;

        // ③ 导航：NavVoice 的口令表已经很严（开始/停止/巡航），直接复用，
        //    避免"导航的判据"和"导航的执行"两套。
        NavVoice.Command nav = NavVoice.parse(q, false);
        if (nav.action != NavVoice.Action.NONE) return Kind.NAV;

        // ④ 倒计时：Countdown.parseDuration 是唯一判据（它有 50+ 项单测）。
        if (Countdown.isCountdownCommand(q)) return Kind.TIMER;

        // ⑤ 天气：★ 必须排除家况问句 ★
        //    「家里温度多少」同时命中天气（多少度/温度）与家况（温度）。
        //    家况是本机网关的真实数据，比天气更贴近用户意图，所以排除后留给 ⑥。
        if (weatherReady && !looksLikeHome(q) && Weather.isWeatherQuery(q)) return Kind.WEATHER;

        // ⑥ 家况
        if (homeQuery(q, domain)) return Kind.HOME;

        return Kind.NONE;
    }

    // ══════════════════════════════════════════════════════════════
    //  家况判定（放宽 domain 依赖的那一条）
    // ══════════════════════════════════════════════════════════════

    /** 「家里」这类**地点词**：出现它就基本可以确定是在问家况，而不是别的。 */
    static final String[] HOME_PLACE = {"家里", "家中", "屋内", "室内", "家里边", "家里的"};

    /**
     * 疑问/状态信号：**只有设备词、没有地点词**时，必须同时有它才认。
     *
     * （有地点词的情况见 {@link #homeQuery} ③ —— 那种不必再要信号。）
     */
    static final String[] HOME_SIGNAL = {
        "怎么样", "怎么样啊", "什么情况", "情况", "如何", "状态", "多少", "几度", "几度了",
        "看一下", "看看", "查一下", "查查", "播报", "念一下", "还在", "开着吗", "关着吗",
        "开没开", "关没关", "在不在", "有人在吗", "有人吗", "安全吗", "正常吗"
    };

    /**
     * 明确的**动作词**：出现就说明用户要"操作设备"，不是"问情况"。
     * 这种必须留给官方 —— 官方能真的把灯打开，我们只能念一句状态，
     * 抢过来就是净损失（用户会觉得"以前能开灯，装了插件就只能听播报"）。
     *
     * ★ v3r19 补了「开灯 / 关灯 / 关掉 / 拉上 / 拉开」：
     *   之前这几句会被家况吃掉（只要官方给的 domain 是 local/iot 就无条件接管），
     *   用户说「开灯」听到的是一句"客厅灯当前是关的" —— 灯没开，还多花了时间。
     *
     *   代价（已知并接受）：像「客厅开灯了吗」这种带动作词的疑问句也会一起放行给官方。
     *   官方自己知道设备状态，答得出来；而反过来"把真的开灯指令吃成一句播报"是不可接受的。
     *   两害相权，"少数问句少答一句"远比"该开的灯开不了"轻。
     */
    static final String[] HOME_ACTION = {
        "打开", "关闭", "开灯", "关灯", "开一下", "关一下", "开个", "关个", "关掉", "拉上", "拉开",
        "调到", "调成", "设置", "设成",
        "改成", "调高", "调低", "播放", "暂停", "停止播放", "下一首", "上一首"
    };

    /**
     * 这句话是不是在问家况。
     *
     * @param domain 官方 domain；明说是本地/物联网能力时判定放宽
     */
    public static boolean homeQuery(String query, String domain) {
        String q = normalize(query);
        if (q.isEmpty()) return false;

        // ① 要操作设备 → 不抢（见 HOME_ACTION 的说明）。
        if (containsAny(q, HOME_ACTION)) return false;

        // ② 必须先沾"家"或家居设备词，否则「今天怎么样」也会被吞。
        boolean explicitPlace = containsAny(q, HOME_PLACE);
        boolean deviceWord = containsAny(q, NlpRouter.HOME_KEYS);
        if (!explicitPlace && !deviceWord) return false;

        // ③ ★ 明确说了「家里 / 屋内」这种地点词 → 直接认，不再要求疑问信号。
        //    地点词本身就是"我在问家里的情况"的最强信号：「家里湿度」这种光秃秃的
        //    名词句是最顺口的形式之一，而旧规则（必须有"多少/怎么样"）会把它漏给官方。
        //    已知代价：「家里 WiFi 密码是多少」这类也会进家况 —— 但它最终表现为
        //    "播报一屏家居状态"，不会误导用户，比"叫不动"好得多。
        if (explicitPlace) return true;

        // ④ domain 明说是本地/物联网能力 → 认（官方自己都把它归成本地指令了）。
        if (isLocalDomain(domain)) return true;

        // ⑤ 只剩设备词（如单独一句「温度」）→ 还要有疑问或状态信号。
        //    没有信号时这句话太模糊（可能是"把温度调高"的残句），留给官方更稳。
        return containsAny(q, HOME_SIGNAL);
    }

    /** 官方把自己能力之外的东西归到哪几个 domain（来自 D7/V0 与 H7 的观察）。 */
    static boolean isLocalDomain(String domain) {
        String d = domain == null ? "" : domain.trim().toLowerCase(Locale.ROOT);
        return d.equals("local") || d.equals("iot") || d.equals("home") || d.equals("device");
    }

    /** 与 HomeStatusUI.looksLikeHomeQuestion / NlpRouter.looksLikeHome 同一把尺子（避免三处判据漂移）。 */
    static boolean looksLikeHome(String query) {
        return NlpRouter.looksLikeHome(query);
    }

    // ── 小工具 ──

    static String normalize(String query) {
        return query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
    }

    static boolean containsAny(String haystack, String[] needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    /** 给状态行/日志用的一句话。 */
    public static String label(Kind kind) {
        switch (kind) {
            case NAV: return "导航";
            case TIMER: return "倒计时";
            case WEATHER: return "天气";
            case HOME: return "家况";
            case EBOOK: return "电子书";
            case MEMO: return Memo.NAME;
            default: return "无";
        }
    }
}

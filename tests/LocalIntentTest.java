import com.turboio.addon.LocalIntent;

/**
 * LocalIntent：本机本事的统一判定（官方意图接管的"要不要抢"决策）。
 *
 * 由来（用户原话）：
 *   「官方的意图接管还是有问题 自有模型没问题 但是电子书貌似不支持」
 *   追问后：「**部分能用部分不行**」—— 导航、倒计时能用，家况 / 天气 / 电子书不行。
 *
 * 根因是旧实现把"要不要接管"押在**官方给的 domain** 上：家况要求
 * domain ∈ {local,iot,home}，而官方对「家里温度多少」经常给 chat/weather，
 * 于是我们连问都不问就放行给官方闲聊 —— 用户体感就是"叫不动"。
 *
 * 现在的原则：**以"这句话本身"为准，domain 只作加分项。**
 * 这个测试就是钉住这条原则，并守住"别抢官方能做的事"的另一半。
 */
public class LocalIntentTest {
    private static int checks = 0;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(Object expected, Object actual, String message) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    /** 简洁调用：有书 + 天气可用。 */
    private static LocalIntent.Kind k(String q) { return LocalIntent.classify(q, "chat", true, true); }
    private static LocalIntent.Kind k(String q, String domain, boolean hasBook, boolean weather) {
        return LocalIntent.classify(q, domain, hasBook, weather);
    }

    public static void main(String[] args) {
        // ── ① 导航（旧版就能用，别被改坏）──
        eq(LocalIntent.Kind.NAV, k("导航到太原南站"), "「导航到 X」");
        eq(LocalIntent.Kind.NAV, k("带我去万象城"), "「带我去 X」");
        eq(LocalIntent.Kind.NAV, k("退出导航"), "「退出导航」");
        eq(LocalIntent.Kind.NAV, k("打开巡航"), "「打开巡航」");
        eq(LocalIntent.Kind.NAV, k("路况怎么样"), "「路况怎么样」= 巡航");
        eq(LocalIntent.Kind.NONE, k("导航怎么用"), "问功能句不该开始导航");

        // ── ② 倒计时 ──
        eq(LocalIntent.Kind.TIMER, k("倒计时 5 分钟"), "「倒计时 5 分钟」");
        eq(LocalIntent.Kind.TIMER, k("计时 30 秒"), "「计时 30 秒」");
        eq(LocalIntent.Kind.TIMER, k("番茄钟"), "「番茄钟」");

        // ── ③ 电子书（★ 旧版完全没写，这就是"电子书叫不动"的原因）──
        eq(LocalIntent.Kind.EBOOK, k("打开电子书"), "「打开电子书」应被接管");
        eq(LocalIntent.Kind.EBOOK, k("继续读"), "「继续读」应被接管（续读核心口令）");
        eq(LocalIntent.Kind.EBOOK, k("下一页"), "有书时「下一页」应被接管");
        eq(LocalIntent.Kind.EBOOK, k("上一页"), "有书时「上一页」应被接管");
        eq(LocalIntent.Kind.EBOOK, k("读到哪了"), "「读到哪了」应被接管");
        eq(LocalIntent.Kind.EBOOK, k("退出阅读"), "有书时「退出阅读」应被接管");
        // 没有书时：打开/查进度仍接管（回一句"还没导入书"也有意义），翻页类放行
        eq(LocalIntent.Kind.EBOOK, k("打开电子书", "chat", false, true), "没书时「打开电子书」也要接管");
        eq(LocalIntent.Kind.EBOOK, k("阅读进度", "chat", false, true), "没书时「阅读进度」也要接管");
        eq(LocalIntent.Kind.NONE, k("下一页", "chat", false, true), "没书时「下一页」应放行（吃了也做不了事）");
        eq(LocalIntent.Kind.NONE, k("继续读", "chat", false, true), "没书时「继续读」应放行");

        // ── ④ 备忘录（口令最独特，必须排在最前）──
        eq(LocalIntent.Kind.MEMO, k("记一下 买牛奶"), "「记一下 X」应先判成备忘录");
        eq(LocalIntent.Kind.MEMO, k("随口记 买牛奶"), "别名当唤醒词");
        eq(LocalIntent.Kind.MEMO, k("打开随口记"), "「打开随口记」");
        eq(LocalIntent.Kind.MEMO, k("念念随口记"), "「念念随口记」");
        eq(LocalIntent.Kind.MEMO, k("清空备忘录"), "「清空备忘录」");
        // ★ v3r20：官方也认的词不能抢 —— 否则就是用户说的"和官方待办冲突"。
        eq(LocalIntent.Kind.NONE, k("备忘录 买牛奶"), "「备忘录 X」让给官方待办");
        eq(LocalIntent.Kind.NONE, k("添加待办 交电费"), "「待办」一律让给官方");
        // ★ 判序：含"去…"的备忘不该被导航吃掉
        eq(LocalIntent.Kind.MEMO, k("记一下 去太原站接人"), "含地名的备忘仍是备忘，不能被导航抢走");
        eq(LocalIntent.Kind.MEMO, k("记一下 倒计时 5 分钟的事"), "含「倒计时」的备忘仍是备忘");

        // ── ⑤ 家况：★ domain 不再是一票否决 ★ ──
        // 这三条是用户报的"家况叫不动"：官方把 domain 给了 chat，旧实现直接放行。
        eq(LocalIntent.Kind.HOME, k("家里温度多少"), "官方给 chat 也要接管（旧版就是这里失效）");
        eq(LocalIntent.Kind.HOME, k("家里怎么样"), "「家里怎么样」");
        eq(LocalIntent.Kind.HOME, k("家里灯开着吗"), "「家里灯开着吗」");
        eq(LocalIntent.Kind.HOME, k("家里湿度"), "「家里湿度」");
        eq(LocalIntent.Kind.HOME, k("客厅空调还在吗"), "家居设备词 + 状态信号");
        // domain 明说是本地能力 → 放宽（官方自己都当成本地指令了）
        eq(LocalIntent.Kind.HOME, k("家里情况", "local", true, true), "domain=local 时放宽");
        eq(LocalIntent.Kind.HOME, k("家里情况", "iot", true, true), "domain=iot 时放宽");
        eq(LocalIntent.Kind.HOME, k("家里情况", "home", true, true), "domain=home 时放宽");
        eq(LocalIntent.Kind.HOME, k("家里怎么样", "device", true, true), "domain=device 时放宽");

        // ── ⑥ 家况：不能抢官方的"动作类"指令 ──
        // 官方能真的把灯打开，我们只能念一句状态 —— 抢过来是净损失。
        eq(LocalIntent.Kind.NONE, k("把客厅灯打开"), "动作类留给官方");
        eq(LocalIntent.Kind.NONE, k("打开家里的灯"), "动作类留给官方（即使含「家里」）");
        eq(LocalIntent.Kind.NONE, k("把空调调到 26 度"), "动作类留给官方");
        eq(LocalIntent.Kind.NONE, k("播放音乐"), "播放类完全无关");
        // ── ⑦ 家况：不能把闲聊吞掉 ──
        eq(LocalIntent.Kind.NONE, k("我家的猫叫什么"), "没有状态信号的家常闲聊不该吞");
        eq(LocalIntent.Kind.NONE, k("今天怎么样"), "没有「家」这个地点词不该吞");
        eq(LocalIntent.Kind.NONE, k("温度"), "只有设备词、没有地点也不该吞");

        // ── ⑧ 天气：没配 Key 就留给官方；家况问句优先给家况 ──
        eq(LocalIntent.Kind.WEATHER, k("太原天气"), "配好 Key 后天气应接管");
        eq(LocalIntent.Kind.WEATHER, k("今天天气怎么样"), "「今天天气怎么样」");
        eq(LocalIntent.Kind.NONE, k("太原天气", "chat", true, false), "没配 Key 时天气要放行给官方");
        eq(LocalIntent.Kind.HOME, k("家里温度多少", "chat", true, true),
            "★「家里温度多少」必须判家况而不是天气（本机网关的真实数据更贴近意图）");
        eq(LocalIntent.Kind.HOME, k("家里几度", "chat", true, true), "同上");
        eq(LocalIntent.Kind.WEATHER, k("外面多少度", "chat", true, true), "没有「家」的才是天气");

        // ── ⑨ 边界与标签 ──
        eq(LocalIntent.Kind.NONE, LocalIntent.classify(null, "chat", true, true), "null 不接管");
        eq(LocalIntent.Kind.NONE, LocalIntent.classify("", "chat", true, true), "空串不接管");
        eq(LocalIntent.Kind.NONE, LocalIntent.classify("   ", "chat", true, true), "纯空白不接管");
        eq(LocalIntent.Kind.NONE, LocalIntent.classify("帮我写一首诗", "chat", true, true), "纯闲聊不接管");
        eq(LocalIntent.Kind.NONE, LocalIntent.classify("帮我写一首诗", null, true, true), "domain 为 null 也不炸");
        ok(LocalIntent.label(LocalIntent.Kind.HOME).contains("家"), "标签可读");
        ok(LocalIntent.label(LocalIntent.Kind.EBOOK).contains("电子书"), "电子书标签");
        ok(LocalIntent.label(LocalIntent.Kind.MEMO).contains("备忘录"), "备忘录标签用模块名");
        ok(LocalIntent.label(LocalIntent.Kind.NONE).equals("无"), "NONE 的标签是「无」");

        System.out.println("LocalIntent: " + checks
            + " checks PASS (以句子为准而非 domain · 电子书/备忘录入列 · 家况不再被 domain 卡死 · 不抢官方的动作指令)");
    }
}

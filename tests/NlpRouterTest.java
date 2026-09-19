import com.turboio.addon.Memo;
import com.turboio.addon.NlpRouter;

/**
 * NlpRouter 是"要不要接管这句话"的唯一决策点，它决定了扩展会不会抢走官方流程。
 *
 * ── v3r19 重写说明 ──────────────────────────────────────────────
 * 用户实测：「官方的意图接管还是有问题」「部分能用部分不行 —— 导航、倒计时能用，
 * 家况 / 天气 / 电子书不行」。根因是旧实现把"要不要接管"押在**官方给的 domain** 上。
 * 现在判定权交给 LocalIntent：**以"这句话本身"为准，domain 只作加分项**。
 * 所以这个测试里凡是"用 domain=chat 也要接管"的断言，都是在钉这次的修复。
 *
 * 边界一条不松：
 *   · 开关关着时永远放行；
 *   · 离线、空语句一律放行；
 *   · 官方给了**结构化指令**（command）的一律让给它执行 —— 只有随口记例外
 *     （官方没有记事本能力，抢过来零损失）。
 */
public class NlpRouterTest {
    private static int checks = 0;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    /** consume 的常用写法：默认 online、无结构化指令、有书。 */
    private static boolean take(String domain, String query) {
        return NlpRouter.consume(domain, "chat", null, query, false, false, true);
    }
    private static boolean takeNoBook(String domain, String query) {
        return NlpRouter.consume(domain, "chat", null, query, false, false, false);
    }

    public static void main(String[] args) {
        // ── ① 开关 ──
        NlpRouter.setActive(false);
        ok(!NlpRouter.consume("navigate", "nav", null, "回家", false, false, true), "关闭状态下不得接管");
        ok("未启用".equals(NlpRouter.lastDecision()), "关闭状态说明应可读");
        NlpRouter.setActive(true);

        // ── ② 官方 domain=navigate ──
        ok(NlpRouter.consume("navigate", "nav", null, "去公司", false, false, true), "navigate 应接管");
        ok(NlpRouter.lastDecision().startsWith("接管导航"), "navigate 说明应可读");

        // ── ③ 纯闲聊放行 ──
        ok(!NlpRouter.consume("chat", "chat", "workflow", "讲个笑话", false, false, true), "普通问答不得接管");
        ok(NlpRouter.lastDecision().startsWith("放行官方"), "放行说明应可读");

        // ── ④ 离线 / 空语句 ──
        ok(!NlpRouter.consume("navigate", "nav", null, "回家", true, false, true), "离线链路不得接管");
        ok("离线链路，放行".equals(NlpRouter.lastDecision()), "离线说明应可读");
        ok(!NlpRouter.consume("navigate", "nav", null, "   ", false, false, true), "空语句不得接管");
        ok(!NlpRouter.consume("navigate", "nav", null, null, false, false, true), "null 语句不得接管");

        // ── ⑤ 家况：★ 不再要求 domain 必须是 local/iot/home ★ ──
        // 这三条正是用户报的"家况叫不动"：官方对这类问句经常给 chat。
        ok(take("chat", "家里温度多少"), "domain=chat 也要接管家况问句（旧版就是这里失效）");
        ok(NlpRouter.lastDecision().startsWith("接管家况"), "说明应写明是家况");
        ok(take("chat", "家里怎么样"), "「家里怎么样」");
        ok(take("local", "家里几度"), "domain=local 自然也认");
        ok(NlpRouter.consume("home", "status", null, "家里什么情况", false, false, true), "domain=home");
        ok(NlpRouter.consume("iot", "query", null, "屋内湿度多少", false, false, true), "domain=iot");
        // ⚠️ 关键回归：「家里多少度」同时命中家况关键词与天气关键词（"多少度"）。
        //    必须归家况 —— 家里有温度传感器，问的是室内温度，不是天气。
        NlpRouter.setWeatherEnabled(true);
        ok(NlpRouter.consume("local", "query", null, "家里多少度", false, false, true), "家况问句不得被天气抢走");
        ok(NlpRouter.lastDecision().startsWith("接管家况"), "「家里多少度」应归家况，实际：" + NlpRouter.lastDecision());

        // ── ⑥ 家况：不能抢官方的"动作类"指令（官方能真的开灯）──
        ok(!take("iot", "开灯"), "「开灯」是动作，必须放行给官方执行");
        ok(!take("local", "打开家里的灯"), "「打开…」是动作，放行");
        ok(!take("chat", "把空调调到 26 度"), "「调到」是动作，放行");
        ok(!take("local", "播放音乐"), "非家况指令必须放行");
        ok(!take("iot", "打开设置"), "非家况指令必须放行");
        ok(!take("chat", "我家的猫叫什么"), "家常闲聊不得被吞");

        // ── ⑦ 带结构化指令的一律让给官方（随口记例外）──
        ok(!NlpRouter.consume("local", "control", null, "开灯", false, true, true),
            "带命令对象的不得接管，交给官方执行");
        ok(NlpRouter.lastDecision().startsWith("放行官方"), "说明应写明放行原因");
        ok(NlpRouter.consume("chat", "chat", null, "记一下 买牛奶", false, true, true),
            "★ 随口记即使官方给了指令也要接管（官方没有记事本能力）");
        ok(NlpRouter.lastDecision().contains(Memo.NAME), "说明要带上模块名");

        // ── ⑧ 官方系统控制 / 未知 domain ──
        ok(!NlpRouter.consume("system_ctrl", "sound_tube_on", null, "开", false, false, true),
            "官方系统控制不得接管");
        ok(!NlpRouter.consume(null, null, null, "随便说点什么", false, false, true), "未知 domain 不得接管");

        // ── ⑨ 天气（默认关：没填 Key 就别抢官方能答的）──
        NlpRouter.setWeatherEnabled(false);
        ok(!NlpRouter.weatherEnabled(), "天气接管开关默认应当是关的");
        ok(!take("chat", "今天天气怎么样"), "天气接管关闭时不得接管天气问句");
        NlpRouter.setWeatherEnabled(true);
        ok(take("chat", "太原天气"), "天气接管开启后应当接管天气问句");
        ok(NlpRouter.lastDecision().startsWith("接管天气"), "天气说明应可读，实际：" + NlpRouter.lastDecision());

        // ── ⑩ 电子书（★ 旧版完全没写，这是"电子书叫不动"的原因）──
        ok(take("chat", "打开电子书"), "「打开电子书」应接管");
        ok(NlpRouter.lastDecision().startsWith("接管电子书"), "电子书说明应可读");
        ok(take("chat", "继续读"), "「继续读」应接管（续读核心口令）");
        ok(take("chat", "下一页"), "有书时「下一页」应接管");
        ok(!takeNoBook("chat", "下一页"), "没书时「下一页」应放行（吃了也做不了事）");
        ok(takeNoBook("chat", "打开电子书"), "没书时「打开电子书」仍要接管（回一句'还没导入书'）");

        // ── ⑪ 随口记 ──
        ok(take("chat", "记一下 买牛奶"), "「记一下 X」应接管");
        ok(NlpRouter.lastDecision().contains(Memo.NAME), "说明用模块名");
        ok(take("chat", "打开随口记"), "「打开随口记」应接管");
        ok(!take("chat", "我记不起来了"), "「我记不起来了」是闲聊，不得接管");

        // ── ⑫ 倒计时 ──
        ok(take("chat", "倒计时 5 分钟"), "「倒计时 5 分钟」应接管");
        ok(NlpRouter.lastDecision().startsWith("接管倒计时"), "倒计时说明应可读");

        // ── ⑬ 导航口令优先级 ──
        ok(take("chat", "导航到气象局"), "导航口令应接管（即使官方给 chat）");
        ok(take("chat", "退出导航"), "「退出导航」应接管");

        System.out.println("NlpRouter: " + checks
            + " checks PASS (以句子为准而非 domain · 电子书/备忘录入列 · 动作类指令与结构化指令一律让给官方)");
    }
}

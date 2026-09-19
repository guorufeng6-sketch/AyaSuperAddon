import com.turboio.addon.ReaderCmd;

/**
 * ReaderCmd：电子书口令的宽松识别（与 VoicePager 的严格等值匹配互补）。
 *
 * 由来（用户原话）：「自有模型里叫不动」「电子书貌似不支持」。
 * 根因是功能只在 ASR 回调里认「下一页」这种 ≤8 字的短指令，
 * 「打开电子书」「继续读」这类整句没人处理，模型也不知道有这回事。
 *
 * 重点钉三件事：
 *   ① 六个动作各自能认出来，且**判序正确**（「停止翻页」不能被 NEXT 抢走）；
 *   ② 没人管的口令（「继续」）必须保持 NONE —— 它归 VoicePager 的翻页，
 *      两处对同一个词给出不同动作，用户就会看到"有时翻页、有时只是重推当前屏"；
 *   ③ 超长句子一律不认（那一定是在聊天，不是在念指令）。
 */
public class ReaderCmdTest {
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

    public static void main(String[] args) {
        // ── ① 打开 ──
        eq(ReaderCmd.Action.OPEN, ReaderCmd.parse("打开电子书"), "「打开电子书」");
        eq(ReaderCmd.Action.OPEN, ReaderCmd.parse("我要看书"), "「我要看书」");
        eq(ReaderCmd.Action.OPEN, ReaderCmd.parse("电子书"), "光念「电子书」= 打开");
        eq(ReaderCmd.Action.OPEN, ReaderCmd.parse("开始阅读"), "「开始阅读」");
        eq(ReaderCmd.Action.OPEN, ReaderCmd.parse("帮我打开电子书"), "宽松一档：含「打开」+「电子书」");
        eq(ReaderCmd.Action.OPEN, ReaderCmd.parse("看一下阅读"), "宽松一档：含「看一下」+「阅读」");

        // ── ② 续读 ──
        eq(ReaderCmd.Action.READ, ReaderCmd.parse("继续读"), "「继续读」");
        eq(ReaderCmd.Action.READ, ReaderCmd.parse("接着上次读"), "「接着上次读」");
        eq(ReaderCmd.Action.READ, ReaderCmd.parse("读给我听"), "「读给我听」");
        eq(ReaderCmd.Action.READ, ReaderCmd.parse("念书"), "「念书」");
        eq(ReaderCmd.Action.READ, ReaderCmd.parse("续读"), "「续读」");

        // ── ③ 翻页 ──
        eq(ReaderCmd.Action.NEXT, ReaderCmd.parse("下一页"), "「下一页」");
        eq(ReaderCmd.Action.NEXT, ReaderCmd.parse("下一屏"), "「下一屏」");
        eq(ReaderCmd.Action.NEXT, ReaderCmd.parse("翻页"), "「翻页」");
        eq(ReaderCmd.Action.PREV, ReaderCmd.parse("上一页"), "「上一页」");
        eq(ReaderCmd.Action.PREV, ReaderCmd.parse("往回翻"), "「往回翻」");

        // ── ④ 判序：停止/退出 与 查进度 必须优先 ──
        // 「停止翻页」里含「翻页」——先判 NEXT 就会被翻过去，用户体感"说了停还在翻"。
        eq(ReaderCmd.Action.CLOSE, ReaderCmd.parse("停止翻页"), "「停止翻页」必须是 CLOSE，不能被 NEXT 抢走");
        eq(ReaderCmd.Action.CLOSE, ReaderCmd.parse("退出电子书"), "「退出电子书」");
        eq(ReaderCmd.Action.CLOSE, ReaderCmd.parse("别读了"), "「别读了」");
        eq(ReaderCmd.Action.STATUS, ReaderCmd.parse("读到哪了"), "「读到哪了」= 查进度");
        eq(ReaderCmd.Action.STATUS, ReaderCmd.parse("阅读进度"), "「阅读进度」");
        eq(ReaderCmd.Action.STATUS, ReaderCmd.parse("现在第几屏"), "「现在第几屏」");

        // ── ⑤ 刻意不认的 ──
        // 「继续」同时是 VoicePager.NEXT 的词。ASR 链路先走 VoicePager（严格），
        // 本类若也认它，两处动作可能不一致 —— 所以统一交给 VoicePager 翻页。
        eq(ReaderCmd.Action.NONE, ReaderCmd.parse("继续"), "「继续」留给 VoicePager 翻页，本类不认");
        eq(ReaderCmd.Action.NONE, ReaderCmd.parse("今天天气怎么样"), "无关句不认");
        eq(ReaderCmd.Action.NONE, ReaderCmd.parse("导航到太原站"), "导航句不认");
        eq(ReaderCmd.Action.NONE, ReaderCmd.parse(""), "空串不认");
        eq(ReaderCmd.Action.NONE, ReaderCmd.parse(null), "null 不认");
        eq(ReaderCmd.Action.NONE, ReaderCmd.parse(new String(new char[60]).replace('\0', '书')), "超长句不认");

        // ── ⑥ 没有书时哪些还说得通 ──
        ok(ReaderCmd.worksWithoutBook(ReaderCmd.Action.OPEN), "没书时「打开」也有意义（回一句'还没导入书'）");
        ok(ReaderCmd.worksWithoutBook(ReaderCmd.Action.STATUS), "没书时「查进度」也有意义");
        ok(!ReaderCmd.worksWithoutBook(ReaderCmd.Action.NEXT), "没书时翻页没意义，应放行给官方");
        ok(!ReaderCmd.worksWithoutBook(ReaderCmd.Action.PREV), "没书时回翻没意义");
        ok(!ReaderCmd.worksWithoutBook(ReaderCmd.Action.READ), "没书时续读没意义");
        ok(!ReaderCmd.worksWithoutBook(ReaderCmd.Action.NONE), "NONE 不算说得通");
        ok(ReaderCmd.describe(ReaderCmd.Action.READ).contains("读"), "describe 要给可读说明");
        ok(ReaderCmd.describe(ReaderCmd.Action.NONE).equals("无"), "NONE 的说明是「无」");

        System.out.println("ReaderCmd: " + checks
            + " checks PASS (六动作识别 · 判序正确 · 「继续」留给翻页链路 · 没书时的可用性)");
    }
}

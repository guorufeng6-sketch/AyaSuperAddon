package com.turboio.addon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 电子书的口令解析 —— **纯逻辑层，可单测**。
 *
 * ══ 为什么要有这个类（用户实测反馈）════════════════════════════
 * 用户原话：「自有模型没问题 但是电子书貌似不支持」「另外加电子书续读功能」
 *
 * 之前电子书只有一条入口：`VoicePager` 在 **ASR 回调**里精确匹配「下一页」
 * 这类**極短指令**（≤8 字、必须完全相等）。那条路能用，但有两个洞：
 *   ① **「打开电子书」没有任何人处理**。想开始读，只能手动点 App 里的入口。
 *   ② **模型完全不知道有电子书这回事**（没进 `tools` 数组）——
 *      打字说「继续读我的书」它只会说不会。
 *
 * 本类负责"这句话是不是在说电子书、想干什么"，供三处共用：
 *   · `LocalIntent`（官方意图接管）；
 *   · `LocalCapabilities` / `LocalTools`（自有模型的工具）；
 *   · `ReaderUI`（真正执行）。
 * 一份规则三处共用，才不会出现"语音能翻、模型不能"这种半残状态。
 *
 * ── 与 `VoicePager` 的关系 ──────────────────────────────────────
 * `VoicePager` 是**极短指令的等值匹配**（故意严格，避免把用户的提问吃掉），
 * 本类是**宽松的口令识别**（允许「帮我翻到下一页」「打开电子书」这种整句）。
 * 两者不冲突：ASR 链路先走 VoicePager（严格），没命中再由意图层走本类（宽松）。
 */
public final class ReaderCmd {
    private ReaderCmd() {}

    public enum Action {
        NONE,
        /** 打开电子书界面 */
        OPEN,
        /** 从当前进度继续读（续读）→ 推到眼镜 */
        READ,
        NEXT, PREV,
        /** 读到哪了 */
        STATUS,
        /** 停止自动翻页 / 结束阅读 */
        CLOSE
    }

    private static final List<String> OPEN = Arrays.asList(
        "打开电子书", "打开电子书吧", "打开阅读", "打开书籍", "打开书", "我要看书", "我想看书",
        "看一下电子书", "看看电子书", "电子书", "开始阅读", "打开阅读器", "打开阅读模式");

    private static final List<String> READ = Arrays.asList(
        "继续读", "继续读书", "接着读", "接着读书", "继续阅读", "接着阅读", "开始读", "开始朗读",
        "念书", "读书", "朗读", "读给我听", "念给我听", "读一下书", "念一下书",
        "从上次那里继续", "接着上次读", "续读", "接着念");
    // ⚠️ 「继续」刻意**不放在这**：它与 `VoicePager.NEXT` 里的「继续」是同一个词，
    //    而 ASR 链路会先把它当翻页。两处对同一个词给出不同动作，用户会看到
    //    "有时翻页、有时只是重推当前屏"。统一按翻页处理（NEXT）。

    private static final List<String> NEXT = Arrays.asList(
        "下一页", "下一頁", "翻页", "翻到下一页", "下一屏", "下一张", "往下翻", "继续翻", "next");

    private static final List<String> PREV = Arrays.asList(
        "上一页", "上一頁", "往回翻", "翻到上一页", "上一屏", "上一张", "previous", "back");

    private static final List<String> STATUS = Arrays.asList(
        "读到哪了", "读到哪儿了", "阅读进度", "读到第几页", "现在第几屏", "我读到哪了", "书的进度");

    private static final List<String> CLOSE = Arrays.asList(
        "退出阅读", "退出電子書", "退出电子书", "别读了", "不读了", "停止翻页", "停止阅读",
        "结束阅读", "关掉电子书");

    /** 最长认到多长 —— 超长的句子一定是在聊天，不是在念指令。 */
    static final int MAX_LEN = 24;

    /** 解析一句话想干什么。永远不返回 null。 */
    public static Action parse(String raw) {
        String q = VoicePager.normalize(raw);
        if (q.isEmpty() || q.length() > MAX_LEN) return Action.NONE;
        // 顺序有讲究：先判"停止/退出"和"读到哪里"，再判"下一页"。
        // 因为「停止翻页」本身就包含"翻页"，顺序反了会被 NEXT 抢走。
        if (CLOSE.contains(q)) return Action.CLOSE;
        if (STATUS.contains(q)) return Action.STATUS;
        if (NEXT.contains(q)) return Action.NEXT;
        if (PREV.contains(q)) return Action.PREV;
        if (READ.contains(q)) return Action.READ;
        if (OPEN.contains(q)) return Action.OPEN;
        // 宽松一档：整句里含"电子书/阅读"且带"打开/看看"。
        if (containsAny(q, "打开", "看一下", "看看") && containsAny(q, "电子书", "阅读", "书")) return Action.OPEN;
        return Action.NONE;
    }

    /** 这个动作是不是"没有书也说得通"（打开/查进度 → 说得通：可以回他"还没导入书"）。 */
    public static boolean worksWithoutBook(Action action) {
        return action == Action.OPEN || action == Action.STATUS;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    /** 给模型/状态行用的一句话说明。 */
    public static String describe(Action action) {
        switch (action) {
            case OPEN: return "打开电子书";
            case READ: return "继续读";
            case NEXT: return "下一屏";
            case PREV: return "上一屏";
            case STATUS: return "查进度";
            case CLOSE: return "停止阅读";
            default: return "无";
        }
    }

    /** 空列表便于调用方直接迭代（避免到处判 null）。 */
    static List<String> none() { return Collections.unmodifiableList(new ArrayList<String>()); }
}

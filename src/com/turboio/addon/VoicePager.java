package com.turboio.addon;

import java.util.Arrays;
import java.util.List;

/**
 * 语音翻页：把 ASR 文本匹配成电子书翻页指令。
 *
 * 设计要点（为什么必须"精确短指令"）：
 *   ASR 结果同时也可能是用户在问模型问题。如果把"下一页"当作子串匹配，
 *   "帮我看看下一页写的什么" 会被误吃。所以：
 *     - 先去掉空白与标点；
 *     - 长度超过阈值一律不认（正常提问远长于指令）；
 *     - 必须与指令表【完全相等】才命中。
 */
final class VoicePager {

    private static final int MAX_LEN = 8;

    private static final List<String> NEXT = Arrays.asList(
        "下一页", "下一頁", "翻页", "翻頁", "继续", "繼續", "往下", "下一页吧", "下一张", "next");
    private static final List<String> PREV = Arrays.asList(
        "上一页", "上一頁", "往回翻", "上一张", "previous", "back");
    private static final List<String> STOP = Arrays.asList(
        "停止翻页", "停止自動", "停止自动", "停", "别翻了", "結束閱讀", "退出阅读", "退出閱讀");
    private static final List<String> TOP = Arrays.asList(
        "回到开头", "回到開頭", "从头开始", "從頭開始", "第一页", "第一頁");

    /** 命中返回 true，表示这句话已被消费、不要再交给模型。 */
    static boolean handle(String raw) {
        String text = normalize(raw);
        if (text.isEmpty() || text.length() > MAX_LEN) return false;

        if (STOP.contains(text)) { ReaderUI.stopAuto(); return true; }

        if (NEXT.contains(text)) {
            ReaderUI.stopAuto();
            return ReaderUI.turnByVoice(+1);
        }
        if (PREV.contains(text)) {
            ReaderUI.stopAuto();
            return ReaderUI.turnByVoice(-1);
        }
        if (TOP.contains(text)) {
            ReaderUI.stopAuto();
            // 没提供"跳到"接口，重开一次最简单
            return false;
        }
        return false;
    }

    /** 去掉所有空白与中英文标点，方便做等值比较。 */
    static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if ("，。！？、；：,.!?;:\"'`~（）()【】[]{}《》<>—-_/\\".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /** 是否有书可翻。供 UI 提示用。 */
    static boolean available() { return ReaderUI.hasBook(); }
}

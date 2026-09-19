package com.turboio.addon;

import java.util.*;
import java.util.regex.*;

/**
 * 语音导航的纯逻辑核心：把「一句话」翻译成一次导航指令。
 *
 * ── 为什么要独立出来 ────────────────────────────────────────────
 * 语音说一句「导航去太原南站」，中间要经过好几步判断：
 *   · 这到底是不是导航？（"我今天导航坏了" 不该触发）
 *   · 目的地是哪几个字？（"带我去太原南站" → "太原南站"）
 *   · 是要开始还是结束？（"取消导航" / "退出导航"）
 *   · 是不是问路而不是要导航？（"导航怎么开" 也许是问功能）
 * 这些判断如果不抽出来，就会散落在 UI 线程与反射调用之间，
 * 既测不了，也容易在改一处时崩另一处。
 *
 * 所以这里只做字符串推断，不碰 Android API、不碰网络、不碰反射 ——
 * 可以 100% 用单测覆盖。
 */
public final class NavVoice {
    private NavVoice() {}

    /** 一次语音导航的意图。 */
    public enum Action {
        START,      // 开始导航到某个目的地
        STOP,       // 结束导航
        CRUISE,     // 打开路况巡航
        NONE        // 不是导航指令
    }

    public static final class Command {
        public final Action action;
        /** START 时的目的地（已去掉"导航去/带我/到"等前缀）；其余为空串。 */
        public final String destination;
        /** 人类可读的判定说明，给状态行 / 调试用。 */
        public final String reason;
        Command(Action action, String destination, String reason) {
            this.action = action; this.destination = destination; this.reason = reason;
        }
        @Override public String toString() { return action + "(" + destination + ") · " + reason; }
    }

    // ── 句首的"导航"意图词 ──
    // 顺序有讲究：长词在前，避免 "导航去" 被 "导航" 抢先匹配后残留 "去"。
    private static final String[] NAV_HEADS = {
        "导航到", "导航去", "导航至", "导航前往",
        "带我去", "带我到", "带我去往",
        "我要去", "我想去", "我要到",
        "去往", "前往",
        "开车去", "开车到", "骑车去", "骑车到", "步行去", "步行到",
        "导个航去", "导个航到",
        "导航"
    };

    // ── 结束 / 取消 ──
    private static final String[] STOP_WORDS = {
        "退出导航", "结束导航", "停止导航", "取消导航", "关掉导航", "关闭导航",
        "不导航了", "不用导航了", "停止指引", "结束指引", "停止巡逻", "停止巡航"
    };

    // ── 巡航 ──
    private static final String[] CRUISE_WORDS = {
        "打开巡航", "开始巡航", "开启巡航", "进入巡航",
        "巡航模式", "路况播报", "播报路况", "前方路况",
        "打开路况", "看看路况", "路况怎么样"
    };

    /**
     * 把一句话判定成一次导航指令。
     *
     * @param raw 用户原话（任意长度，内部会 trim / 去标点）
     * @param explicitNavigate 官方 NLP 给的 domain 是否为 navigate。
     *        这个参数很重要：domain==navigate 时官方已经告诉我们"这句是导航"，
     *        即使用户没说"导航"两个字（比如只说"太原南站"），也应该按导航处理。
     * @return 永不返回 null
     */
    public static Command parse(String raw, boolean explicitNavigate) {
        String q = normalize(raw);
        if (q.isEmpty()) return new Command(Action.NONE, "", "空语句");

        // ① 结束优先 —— "取消导航" 里也含 "导航"，必须先判。
        for (String w : STOP_WORDS) {
            if (q.contains(w)) return new Command(Action.STOP, "", "命中结束词：" + w);
        }

        // ② 巡航
        for (String w : CRUISE_WORDS) {
            if (q.contains(w)) return new Command(Action.CRUISE, "", "命中巡航词：" + w);
        }

        // ③ "导航" 本身被当成名词在问（"导航怎么用"），不触发
        if (!explicitNavigate && looksLikeQuestion(q)) {
            return new Command(Action.NONE, "", "像在问功能，不是要导航");
        }

        // ④ 剥前缀取目的地
        for (String head : NAV_HEADS) {
            int at = q.indexOf(head);
            // 只接受出现在句首附近的前缀，避免 "我朋友说导航去那家店" 这种误伤。
            if (at >= 0 && at <= 4) {
                String tail = q.substring(at + head.length()).trim();
                // "导航" 后面紧跟 "吧/吧？/一下" 之类，说明没说目的地。
                tail = stripTrailingParticles(tail);
                if (isUsableDestination(tail)) {
                    return new Command(Action.START, tail, "前缀「" + head + "」→ " + tail);
                }
                // 有导航词但没目的地：让上层去问"去哪"。
                return new Command(Action.START, "", "有导航词但没说出目的地");
            }
        }

        // ⑤ 官方明确说是 navigate，但没匹配到任何前缀词：
        //    整句就当目的地（"太原南站" / "万象城"）。
        if (explicitNavigate) {
            String dest = stripTrailingParticles(q);
            if (isUsableDestination(dest)) return new Command(Action.START, dest, "官方 navigate，整句作目的地");
        }

        return new Command(Action.NONE, "", "不是导航指令");
    }

    /** 去空白 / 统一标点 / 去掉句末语气词，保留中文英文数字。 */
    static String normalize(String raw) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\u3000' || c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            b.append(c);
        }
        return b.toString();
    }

    /**
     * 去掉句末的语气/礼貌后缀。
     * "太原南站吧" → "太原南站"；"公司谢谢" → "公司"；"一下" → ""。
     *
     * ⚠️ 注意 "一下" 这种长度==后缀本身的输入：它整个就是语气词，
     *    必须允许剥成空串。早期版本加了 length>tail.length() 的保护，
     *    结果 "导航一下" 被留在 "一下"，被当成目的地（真实踩过）。
     */
    static String stripTrailingParticles(String value) {
        String v = value == null ? "" : value.trim();
        String[] tails = {"吧", "呢", "啊", "呀", "哦", "噢", "嘛", "一下", "谢谢", "多谢", "好吗", "可以吗", "！", "。", "？", "，", "?"};
        boolean changed = true;
        while (changed && !v.isEmpty()) {
            changed = false;
            for (String tail : tails) {
                // 整句就是语气词时允许剥成空；否则至少保留 1 个字符。
                if (v.equals(tail)) { v = ""; changed = true; break; }
                if (v.endsWith(tail)) { v = v.substring(0, v.length() - tail.length()).trim(); changed = true; }
            }
        }
        return v.trim();
    }

    /**
     * 目的地是否可用。
     * 太短（<2 字）多半是"那""这里"这类指代，语音链路拿不到上下文，交给上层追问更稳。
     * 太长（>60 字）多半整段话被吞进来了，宁可判失败也不要拿一句废话去搜地点。
     */
    public static boolean isUsableDestination(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.length() < 2 || v.length() > 60) return false;
        // 纯语气词 / 纯标点
        return v.matches(".*[\\u4e00-\\u9fa5A-Za-z0-9].*");
    }

    /** "导航怎么用" / "导航是什么意思" —— 在问功能，不是在要导航。 */
    static boolean looksLikeQuestion(String q) {
        String[] asks = {"怎么用", "怎么开", "怎么设置", "是什么", "什么意思", "能用吗", "能用么", "好用吗", "在哪"};
        for (String a : asks) if (q.contains(a)) return true;
        return false;
    }

    /**
     * 目的地缺失时的追问文案。眼镜端就一句，要短。
     * 返回 null 表示不需要追问（例如 action 不是 START）。
     */
    public static String clarify(Command command) {
        if (command == null || command.action != Action.START) return null;
        if (!command.destination.isEmpty()) return null;
        return "去哪？说「导航到 太原南站」";
    }
}

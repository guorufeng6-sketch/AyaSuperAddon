package com.turboio.addon;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「备忘录」—— 语音记事本的**纯逻辑层**（口令解析 + 文本清洗 + 展示文案）。
 *
 * ══ 用户需求 ═══════════════════════════════════════════════════
 * 「另加备忘录功能 语音打开备忘录 写入备忘录 手机可查看 可以取名随口记」
 * 后来（2026-09-18）追加：「工具里的随口记改成备忘录，同步修改 tool 调用，
 * 随口记老是和官方待办冲突」—— 于是模块名与界面文案统一叫**备忘录**，
 * 而「随口记 / 随手记」降级成**别名**（老用户嘴顺了还能用）。
 *
 * ── 为什么需要这个东西（而不是"让它进模型就行"）────────────────
 * 备忘录是**开车时最常用的一个动作**：脑子里闪过一件事，要一句话记下来。
 * 全程经过模型（联网、等 token、可能限流）意味着：
 *   ① 慢 —— 说完要等一两秒才有反馈；
 *   ② 不确定 —— 模型可能"理解"成别的意思，把内容改写或只回答不记录；
 *   ③ 不通就废 —— 没网/Key 失效时，一个纯本地的记事本也跟着不可用。
 * 所以写入走**纯本地口令**（`记一下 买牛奶` → 直接落盘），
 * 但同时也注册成工具，让自有模型能说、能查。
 *
 * ── ★ 与官方「待办 / 日程」的边界（冲突的来源）★ ───────────────
 * 官方 App 自带待办与日程，且它认的词就是「备忘录 / 记事本 / 待办 / 日程」。
 * 我们早期把「备忘录」当**写入触发词**，于是同一句话两边都可能记一条 ——
 * 用户实测反馈就是「随口记老是和官方待办冲突」。
 *
 * 现在的分工（一句话：**抢独特口令，让官方能力**）：
 *   · 写入只认**显式触发词**：「记一下 / 帮我记 / 记一笔 / 记录一下」，
 *     以及**官方不会用**的两个别名「随口记 / 随手记」；
 *     光秃秃的「备忘录 买牛奶」不再是我们的写入口令（见 {@link #ADD_TRIGGERS}）；
 *   · 句首是「待办」「日程」的，一律让给官方（{@link #OFFICIAL_ONLY}）；
 *   · 「打开备忘录 / 念念备忘录 / 清空备忘录」仍然认 —— 那是我们自己的界面，
 *     有"打开/念念/清空"这种明确前缀，不会与官方抢。
 *
 * 纯逻辑：不碰文件、不碰 android.*（TTS/存储分别在 `MemoStore` / `Speak`）。
 */
public final class Memo {
    private Memo() {}

    /** 模块名 = 唤醒词。改这里，所有口令与界面文案一起变。 */
    public static final String NAME = "备忘录";
    /** 别名：用户很可能顺口说成这些，一并认（也用于「打开/念念/清空 X」）。 */
    static final String[] ALIASES = {"备忘录", "随口记", "随手记", "记事本", "笔记"};

    /** 单条内容上限（防止整段话被写进来）。 */
    public static final int MAX_TEXT = 200;
    /** 最多存多少条（老的自动丢，避免文件无限长）。 */
    public static final int MAX_ITEMS = 500;
    /**
     * 一条内容短于这个长度就算"没听清"。
     * 1 个字的"记一下 好"其实是有效内容，所以下限放到 1；
     * 真正的空内容（"记一下"）由调用方追问"要记什么"。
     */
    public static final int MIN_TEXT = 1;

    public enum Action { NONE, ADD, OPEN, LIST, CLEAR, DELETE_LAST }

    // ── 触发词（长的在前，避免「记一下」把「帮我记一下」切出个"帮"字）──
    //  ★ v3r20：「备忘录 / 记事本 / 笔记」三个**裸别名已从写入触发词里移除**。
    //    官方 App 自己也有备忘录与待办，同一个词当写入口令会两边同时记一条
    //    （用户原话：「随口记老是和官方待办冲突」）。
    //    但「随口记 / 随手记」保留 —— 这是官方绝不会用的词，零歧义。
    //  ⚠️ 刻意**没有**光秃秃的「记」和「记录」：否则「我记不起来了」
    //     会切出内容"不起来了"、被当成一条笔记。
    private static final String[] ADD_TRIGGERS = {
        "随口记一下", "随手记一下", "帮我记录一下", "添加一条备忘录", "加一条备忘录", "记一条备忘录",
        "添加备忘录", "帮我记一下", "帮我记一笔", "帮我记", "记录一下",
        "记一下", "记一笔", "记下",
        "随口记", "随手记"
    };
    /**
     * **官方自己也有**的能力词：出现在句首就把这句话让给官方。
     *
     * 为什么必须让：官方对「待办 / 日程」是有真实落库与提醒能力的，
     * 我们抢过来只会变成一个只能看的本地文本。用户实测「老是和官方待办冲突」，
     * 根因就是同一句话两边都认。
     */
    private static final String[] OFFICIAL_ONLY = {"待办", "日程"};
    private static final String[] OPEN_PREFIX = {
        "打开随口记", "打开随手记", "打开备忘录", "打开记事本", "打开笔记", "打开我的备忘录",
        "看一下随口记", "看看随口记"
    };
    private static final String[] LIST_WORDS = {
        "念念随口记", "念一下随口记", "念念备忘录", "念一下备忘录", "读一下随口记", "读一下备忘录",
        "看看备忘录", "看一下备忘录", "查看备忘录", "看备忘录", "随口记里有什么", "随口记有什么",
        "我记了什么", "我记了啥", "备忘录里有什么", "我的备忘录", "念给我听", "念念"
    };
    private static final String[] CLEAR_WORDS = {
        "清空随口记", "清空备忘录", "清空随手记", "清空记事本", "清空我的备忘录",
        "删掉所有备忘录", "删除全部备忘录", "删除所有备忘录"
    };
    private static final String[] DELETE_LAST_WORDS = {
        "删掉最后一条", "删除最后一条", "删掉最后一条备忘录", "撤销刚才记的", "撤销上一条",
        "删掉刚才那条", "删掉刚才记的"
    };

    /**
     * 疑问词：出现它说明用户在**问这个功能**，而不是在写内容。
     *
     * 「备忘录怎么写」「随口记在哪」这两种最容易被误处理成一条内容叫"怎么写"／"在哪"，
     * 那是这个模块能犯的最糟糕的错误（用户想学怎么用，结果多了一条垃圾笔记）。
     */
    private static final String[] QUESTION_WORDS = {
        "怎么", "怎么样", "为什么", "为啥", "如何", "是什么", "什么意思", "在哪", "在哪儿",
        "哪里", "哪个", "能不能", "会不会", "可不可以"
    };

    // ══════════════════════════════════════════════════════════════
    //  解析
    // ══════════════════════════════════════════════════════════════

    /**
     * 这句话想干什么。永远不返回 null。
     *
     * 判序很关键（每一条都是踩过或想过的坑）：
     *   0. **官方自己的词**（待办 / 日程）先让路 —— 见 {@link #OFFICIAL_ONLY}；
     *   1. **清空 / 删除** 先判 —— 「清空备忘录」里含「备忘录」，先判 OPEN 会当成"打开"；
     *   2. **朗读/查看** 再判 —— 「念念备忘录」同理；
     *   3. **别名 + 疑问词** 判为"在问功能"，直接放行 —— 否则「备忘录怎么写」
     *      会变成一条内容叫"怎么写"；
     *   4. **打开**靠"前缀 + 完整别名"判，不用 contains（否则"帮我打开备忘录记一下"会歧义）；
     *   5. **写入**最后判，它是唯一允许"带尾巴"的动作。
     */
    public static Action parse(String raw) {
        String q = clean(raw);
        if (q.isEmpty() || q.length() > 60) return Action.NONE;

        // ⓪ 官方自己的地盘：让路。
        //    句首就是「待办 / 日程」→ 那是官方待办/日程的句型；
        //    句子里出现但用户又没说显式写入触发词 → 同样让给官方。
        //    反过来「记一下 待办事项」是用户明说要记，照记（见下面的显式触发词判断）。
        if (startsWithAny(q, OFFICIAL_ONLY)) return Action.NONE;
        if (containsAny(q, OFFICIAL_ONLY) && !startsWithExplicitTrigger(raw)) return Action.NONE;

        if (equalsAny(q, CLEAR_WORDS)) return Action.CLEAR;
        if (equalsAny(q, DELETE_LAST_WORDS)) return Action.DELETE_LAST;
        // ★ 朗读/查看必须在"打开"之前判：「念念随口记」里也含「随口记」。
        if (equalsAny(q, LIST_WORDS)) return Action.LIST;

        // ★ 「别名 + 疑问词」= 在**问这个功能**，不是在记东西（也不该硬弹一个界面）。
        //   放行给官方/模型它们能真的回答"怎么用"。
        //   注意前提是**没有显式触发词**：「记一下 导航怎么用」里的"怎么"是内容的一部分，
        //   用户明说了要记，就该记下来。
        if (containsAny(q, ALIASES) && containsAny(q, QUESTION_WORDS) && !startsWithExplicitTrigger(raw)) {
            return Action.NONE;
        }

        for (String p : OPEN_PREFIX) if (q.startsWith(p)) return Action.OPEN;
        if (equalsAny(q, ALIASES)) return Action.OPEN;         // 光念一句「随口记」= 打开

        // 写入：唯一允许"带尾巴"的动作，所以放最后判。
        String content = text(raw);
        if (!content.isEmpty()) return Action.ADD;
        // 只说了「记一下」没说内容 —— 仍然是 ADD，由调用方追问"要记什么"。
        if (startsWithTrigger(raw)) return Action.ADD;
        return Action.NONE;
    }

    /** 句首就是写入触发词（内容可以为空）。 */
    static boolean startsWithTrigger(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        for (String t : ADD_TRIGGERS) if (s.startsWith(t)) return true;
        return false;
    }

    /**
     * 句首是不是**明确的**写入触发词（不含「随手记 / 随口记」这两个纯别名）。
     *
     * 用来区分「记一下 导航怎么用」（要记，内容是"导航怎么用"）与
     * 「备忘录怎么用」（在问功能）；也用来判断「记一下 待办事项」这种
     * **用户明说要记**的句子要不要绕过 {@link #OFFICIAL_ONLY} 让路规则。
     */
    static boolean startsWithExplicitTrigger(String raw) {
        if (raw == null) return false;
        String s = raw.trim();
        for (String t : ADD_TRIGGERS) {
            if (isAliasOnly(t)) continue;
            if (s.startsWith(t)) return true;
        }
        return false;
    }

    private static boolean isAliasOnly(String trigger) {
        for (String a : ALIASES) if (a.equals(trigger)) return true;
        return false;
    }

    /**
     * 从原句里切出"要记的内容"。
     *
     * ⚠️ 用的是**原句**（不是 normalize 后的），因为 normalize 会删掉标点 ——
     * 「记一下 明天 9:30 开会」里的冒号是内容的一部分，不能丢。
     *
     * ★ 刻意**不剥句末语气词**：笔记要保真。"记一下 提醒他一下" 里的第二个
     *   「一下」是内容，剥掉就变味了。只去首部标点/空白。
     */
    public static String text(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("[\\p{Cntrl}]", "").trim();
        for (String t : ADD_TRIGGERS) {
            int at = s.indexOf(t);
            if (at < 0) continue;
            String rest = trimLead(s.substring(at + t.length())).trim();
            if (rest.length() > MAX_TEXT) rest = rest.substring(0, MAX_TEXT);
            return hasContent(rest) ? rest : "";
        }
        return "";
    }

    /** 内容是不是够格记下来（有实义字符、且不超过上限）。 */
    public static boolean hasContent(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.length() < MIN_TEXT || v.length() > MAX_TEXT) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isLetterOrDigit(c)) return true;
            // 中文按 isLetterOrDigit 已经是 true；这里兜住 emoji / 其他符号类内容
            if (!Character.isWhitespace(c) && !isPunct(c)) return true;
        }
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    //  存储格式（一行一条：`时间\t内容`）
    // ══════════════════════════════════════════════════════════════

    public static final class Item {
        public final long at;
        public final String text;
        public Item(long at, String text) { this.at = at; this.text = text == null ? "" : text; }
    }

    /** 序列化。**时间在前、内容在后**，内容里的换行/制表符换成空格（保一行一条）。 */
    public static String serialize(List<Item> items) {
        StringBuilder b = new StringBuilder();
        if (items == null) return "";
        for (Item it : items) {
            if (it == null || it.text.isEmpty()) continue;
            b.append(it.at).append('\t')
             .append(it.text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '))
             .append('\n');
        }
        return b.toString();
    }

    /** 反序列化。被截断/写坏的行直接跳过 —— 宁可少一条，也不能整本读不出来。 */
    public static List<Item> parseAll(String stored) {
        List<Item> out = new ArrayList<>();
        if (stored == null || stored.isEmpty()) return out;
        for (String line : stored.split("\n")) {
            if (line.trim().isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                long at = Long.parseLong(line.substring(0, tab).trim());
                String text = line.substring(tab + 1).trim();
                if (text.isEmpty()) continue;
                if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
                out.add(new Item(at, text));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /**
     * 追加一条，并按上限（单条 {@link #MAX_TEXT} 字、共 {@link #MAX_ITEMS} 条）收拾。
     * 返回新列表（调用方负责落盘）。
     *
     * ★ 截断放在这里、而不是散在各个界面上：`append` 是条目进入列表的**唯一**通道，
     *   在这挡一次，就不可能出现"界面里显示 300 字、文件里存 200 字、重启后对不上"。
     *   空内容直接不追加 —— `serialize` 本来就会跳过空条目，
     *   若这里放进去，就会出现"内存里有、盘上没有"的假象。
     */
    public static List<Item> append(List<Item> items, long at, String text) {
        List<Item> out = new ArrayList<>(items == null ? Collections.<Item>emptyList() : items);
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) return out;
        if (value.length() > MAX_TEXT) value = value.substring(0, MAX_TEXT);
        out.add(new Item(at, value));
        while (out.size() > MAX_ITEMS) out.remove(0);
        return out;
    }

    /** 删掉最后一条（语音"撤销刚才记的"）。空列表返回空列表。 */
    public static List<Item> removeLast(List<Item> items) {
        List<Item> out = new ArrayList<>(items == null ? Collections.<Item>emptyList() : items);
        if (!out.isEmpty()) out.remove(out.size() - 1);
        return out;
    }

    /** 最新的在前（展示与朗读都用这个顺序）。 */
    public static List<Item> newestFirst(List<Item> items) {
        List<Item> out = new ArrayList<>(items == null ? Collections.<Item>emptyList() : items);
        Collections.reverse(out);
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    //  文案
    // ══════════════════════════════════════════════════════════════

    /** 时间戳 → "09-18 20:31"。 */
    public static String stamp(long at) {
        try {
            return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(at));
        } catch (Exception e) {
            return "";
        }
    }

    /** 推眼镜的画面：标题 + 最多 maxItems 条（眼镜最多 5 行，所以 maxItems 通常给 4）。 */
    public static String glasses(List<Item> items, int maxItems) {
        if (items == null || items.isEmpty()) {
            return NAME + "还是空的\n说「记一下 买牛奶」就能记一条";
        }
        List<Item> recent = newestFirst(items);
        int n = Math.max(1, Math.min(maxItems, recent.size()));
        StringBuilder b = new StringBuilder(NAME).append(" · ").append(items.size()).append(" 条");
        for (int i = 0; i < n; i++) {
            Item it = recent.get(i);
            b.append('\n').append(stamp(it.at)).append(' ').append(clip(it.text, 16));
        }
        if (recent.size() > n) b.append("\n…还有 ").append(recent.size() - n).append(" 条");
        return b.toString();
    }

    /** 朗读/回复文案：最近 max 条。 */
    public static String spoken(List<Item> items, int max) {
        if (items == null || items.isEmpty()) {
            return NAME + "还是空的，说「记一下 买牛奶」就能记一条";
        }
        List<Item> recent = newestFirst(items);
        int n = Math.max(1, Math.min(max, recent.size()));
        StringBuilder b = new StringBuilder(NAME).append("共 ").append(items.size()).append(" 条。最近 ");
        b.append(n).append(" 条：");
        String[] ordinals = {"一", "二", "三", "四", "五", "六"};
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append('；');
            b.append(ordinals[Math.min(i, ordinals.length - 1)]).append('、')
             .append(spokenText(recent.get(i).text));
        }
        if (recent.size() > n) b.append("。更早的 ").append(recent.size() - n).append(" 条在手机里看");
        return b.toString();
    }

    public static String addedReply(String text) {
        return "已记到" + NAME + "：" + clip(text, 24);
    }

    public static String clearedReply(int removed) {
        return removed <= 0 ? NAME + "本来就是空的" : "已清空" + NAME + "（" + removed + " 条）";
    }

    public static String deletedReply(boolean ok) {
        return ok ? "已删掉" + NAME + "最后一条" : NAME + "里没有可删的内容";
    }

    public static String needContentReply() {
        return "要记什么？说「记一下 买牛奶」这样就行";
    }

    /** 朗读时把换行、多余空白压平（TTS 念换行会停顿得很怪）。 */
    static String spokenText(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    /** 截断到 max 字（超出加省略号）。界面/眼镜/回执文案都用它，规则只能有一处。 */
    public static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    // ── 小工具 ──

    /** 去空白/标点、转小写 —— 只用于"判动作"，不用于"取内容"。 */
    static String clean(String raw) {
        return VoicePager.normalize(raw);
    }

    private static boolean equalsAny(String value, String[] candidates) {
        if (value == null) return false;
        for (String c : candidates) {
            if (c.equals(value)) return true;
            // ASR 常带尾巴：「念念随口记吧」「打开备忘录啊」——
            // 用"以候选词开头且尾巴很短"来兜，避免为此再列一遍变体。
            if (value.startsWith(c) && value.length() <= c.length() + 2) return true;
        }
        return false;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String n : needles) if (value.contains(n)) return true;
        return false;
    }

    /** 句首就是候选词之一（用于"这是官方待办的句型"这类判断）。 */
    private static boolean startsWithAny(String value, String[] candidates) {
        if (value == null) return false;
        for (String c : candidates) if (value.startsWith(c)) return true;
        return false;
    }

    /** 去掉首部的空白与标点（「：」「，」「"」等），让内容从第一个实义字开始。 */
    static String trimLead(String value) {
        if (value == null) return "";
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || isPunct(c)) { i++; continue; }
            break;
        }
        return value.substring(i).trim();
    }

    private static boolean isPunct(char c) {
        if (c < 0x21) return true;
        return "，。！？、；：,.!?;:\"'`~（）()【】[]{}《》<>—-_/\\|·…“”‘’".indexOf(c) >= 0;
    }

    /** 只读别名列表（给测试与文案用）。 */
    public static List<String> aliases() {
        return Collections.unmodifiableList(Arrays.asList(ALIASES));
    }
}

package com.turboio.addon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「本机自建能力」的工具清单与参数规则 —— **纯逻辑层，不碰 Android / JSON**。
 *
 * ══ 为什么要把"能力"注册成工具（用户实测反馈）════════════════════
 * 用户原话：
 *   「导航不在我能操作的范围内」「倒计时和计时器不在我可控制的范围」
 *   「米家没问题…是因为米家接的 MCP 吗？AI 可以直接对接 MCP，
 *     但是 AI 对接不了本机的自建功能？」
 *
 * 根因跟 MCP 无关，也跟"本机 vs 远程"无关：
 *   · `ToolClient.specs()` 注册了 `mijia_devices / mijia_control / home_status /
 *     web_search / knowledge_query / fastgpt_knowledge` —— 米家是**注册过的工具**，
 *     模型能调、能成；
 *   · 导航（高德 HTTP）、倒计时、天气（和风 HTTP）虽然早就跑通了，
 *     却只挂在**语音拦截器**（`NlpRouter` → `dispatchIntercept`）上，
 *     **从来没有出现在 `tools` 数组里**。
 *   · 模型看到的工具清单里根本没有导航，它只能如实回答"不在我能操作的范围内"。
 *     它没撒谎 —— 是没人告诉它有。
 *
 * 本类负责"告诉它有哪些、参数怎么算"；真正执行在 {@link LocalTools}。
 * 拆开的原因只有一个：让参数合成与门控逻辑能进单测（时间/边界最容易出错的地方）。
 */
public final class LocalCapabilities {
    private LocalCapabilities() {}

    // ── 工具名 ──
    public static final String NAV_START    = "nav_start";
    public static final String NAV_STOP     = "nav_stop";
    public static final String NAV_STATUS   = "nav_status";
    public static final String TIMER_START  = "timer_start";
    public static final String TIMER_CANCEL = "timer_cancel";
    public static final String TIMER_STATUS = "timer_status";
    public static final String WEATHER_NOW  = "weather_now";
    // ★ v3r19 新增：电子书与备忘录（界面文案 v3r20 起统一叫「备忘录」）。
    //   用户实测「自有模型里叫不动」「电子书貌似不支持」
    //   —— 根因与导航当初完全一样：功能早就有了，只是**没出现在 tools 数组里**，
    //   模型只能如实回答"不在我能操作的范围内"。
    public static final String READER_OPEN   = "reader_open";
    public static final String READER_RESUME = "reader_resume";
    public static final String READER_NEXT   = "reader_next";
    public static final String READER_PREV   = "reader_prev";
    public static final String READER_STATUS = "reader_status";
    public static final String MEMO_ADD      = "memo_add";
    public static final String MEMO_LIST     = "memo_list";
    public static final String MEMO_OPEN     = "memo_open";
    // ★ v3r22 新增：运动追踪（跑步 / 骑行）。与导航/倒计时同一套"注册给模型"的思路 ——
    //   功能早就有了，之前没出现在 tools 数组里，模型只能答"不在我能操作的范围内"。
    public static final String SPORT_START  = "sport_start";
    public static final String SPORT_STATUS = "sport_status";
    public static final String SPORT_STOP   = "sport_stop";
    // ★ v3r23 新增：巡航模式（前方路况播报）。与导航无关，注册给模型以便语音 / 文字调用。
    public static final String CRUISE_START = "cruise_start";
    public static final String CRUISE_STOP  = "cruise_stop";
    // ★ v3r24 新增：热搜 / 热榜新闻。数据源免 Key，永远可用，所以无条件注册。
    public static final String NEWS_HOT  = "news_hot";
    public static final String NEWS_NEXT = "news_next";
    // ★ v3r24 新增：Tavily 联网搜索。**有条件**注册 —— 没填 Key 就搜不了，
    //   注册了只会让模型把"我查一下"换成一句"请先填 Key"，那是净损失。
    public static final String WEB_SEARCH = "web_search";

    /** 本机工具全集（固定顺序，便于单测与阅读）。 */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
        NAV_START, NAV_STOP, NAV_STATUS, TIMER_START, TIMER_CANCEL, TIMER_STATUS, WEATHER_NOW,
        READER_OPEN, READER_RESUME, READER_NEXT, READER_PREV, READER_STATUS,
        MEMO_ADD, MEMO_LIST, MEMO_OPEN,
        SPORT_START, SPORT_STATUS, SPORT_STOP,
        CRUISE_START, CRUISE_STOP,
        NEWS_HOT, NEWS_NEXT,
        WEB_SEARCH));

    /** 没有电子书就用不上的那几个（打开/查进度除外：那两个回一句"还没导入书"也有意义）。 */
    private static final List<String> NEEDS_BOOK = Collections.unmodifiableList(Arrays.asList(
        READER_RESUME, READER_NEXT, READER_PREV));

    // ══════════════════════════════════════════════════════════════
    //  门控
    // ══════════════════════════════════════════════════════════════

    /**
     * 本轮实际注册哪些工具（默认认为电子书里有书，供不关心这件事的调用方使用）。
     *
     * 只有天气是**有条件**的：没填和风 Key 时查不出任何东西，注册了只会让模型
     * 把本来能答的"今天天气"换成一句"请先填 Key"，那是净损失
     * （与 `NlpRouter.setWeatherEnabled` 的判据保持一致）。
     *
     * 导航/倒计时是纯本机能力，永远注册 —— 就算定位权限缺失，也应该让模型拿到
     * **真实失败原因**（"拿不到当前位置"），而不是"我不会"。
     */
    public static List<String> names(boolean weatherReady) {
        return names(weatherReady, true, false);
    }

    public static List<String> names(boolean weatherReady, boolean hasBook) {
        return names(weatherReady, hasBook, false);
    }

    /**
     * @param webReady Tavily Key 填了没。没填就不注册 web_search —— 与天气同理：
     *                 注册了只会让模型把"我查一下"换成一句"请先填 Key"，那是净损失。
     */
    public static List<String> names(boolean weatherReady, boolean hasBook, boolean webReady) {
        List<String> out = new ArrayList<>();
        for (String name : ALL) {
            if (WEATHER_NOW.equals(name) && !weatherReady) continue;
            if (!hasBook && NEEDS_BOOK.contains(name)) continue;
            if (WEB_SEARCH.equals(name) && !webReady) continue;
            out.add(name);
        }
        return out;
    }

    /** 名字是否落在"本机工具"里（不看门控；执行前会再查一次）。 */
    public static boolean handles(String name) {
        return name != null && ALL.contains(name.trim());
    }

    // ══════════════════════════════════════════════════════════════
    //  参数规则
    // ══════════════════════════════════════════════════════════════

    /**
     * 目的地清洗。
     *
     * 模型给的 `destination` 不能直接信 —— 实测它会做三件"顺手"的事：
     *   ① 把「导航到太原南站」整句塞进来（schema 里写了不要带「导航到」，但它会带）；
     *   ② 带语气词「太原南站吧」；
     *   ③ 在用户只是**问功能**（"导航怎么用"）时也调这个工具，目的地填一整句问句。
     * 三种都由这里统一收拾：先剥前缀 → 再剥语气词 → 问句直接判不合法。
     *
     * 非法（太短/太长/无实义字符/像问句）一律返回空串，上层会拒绝这次调用并让模型
     * 如实告诉用户"目的地没听清"，而不是拿一句废话去搜地点（那会导到莫名其妙的地方）。
     */
    public static String destination(String raw) {
        if (raw == null) return "";
        String v = NavVoice.normalize(raw);
        if (v.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;
            b.append(c);
        }
        String out = stripNavHead(b.toString());
        out = NavVoice.stripTrailingParticles(out);
        // 「导航怎么用」「太原站在哪」——用户是在问功能，不是在要导航。
        if (NavVoice.looksLikeQuestion(out)) return "";
        // 复用语音链路的同一把尺子：太短像"那儿"（拿不到指代），太长是整句被吞进来了。
        return NavVoice.isUsableDestination(out) ? out : "";
    }

    /**
     * 剥掉句首的导航前缀（长词在前，"导航到"不该被"导航"抢先匹配后残留一个"到"）。
     *
     * 允许剥成空串 —— 「导航」/「带我去」单独出现时本来就**不是目的地**，
     * 剥空后再由 {@link #destination} 判非法，比在这里特判一串黑名单干净。
     */
    static String stripNavHead(String value) {
        String v = value == null ? "" : value;
        String[] heads = {"导航前往", "导航到", "导航去", "导航至", "带我去往", "带我去", "带我到",
            "我要去", "我想去", "我要到", "去往", "前往", "导航"};
        for (String head : heads) {
            if (v.startsWith(head)) return v.substring(head.length()).trim();
        }
        return v;
    }

    /**
     * 分钟 + 秒 + 名字 → 一句"倒计时"口令，交回 `CountdownUI.handleVoice` 解析。
     *
     * 为什么绕一道口令、而不是直接调 `startTimer`：
     *   `Countdown.parseDuration` 里已经写死了上限一天、番茄钟、单位识别、取消词等规则，
     *   并且有 50+ 项单测。直接调 startTimer 等于把这些规则再实现一遍，两边迟早不一致。
     *   合成的口令在单测里**再用 `Countdown.parseDuration(结果)` 反证一次**，就闭环了。
     *
     * @return 可直接喂给语音入口的口令；参数非法返回 null
     */
    public static String timerCommand(String minutes, String seconds, String label) {
        long total = 0;
        boolean found = false;
        Long m = wholeNumber(minutes, 0, 24 * 60);
        if (m != null) { total += m * 60; found = true; }
        Long s = wholeNumber(seconds, 0, 24 * 3600);
        if (s != null) { total += s; found = true; }
        if (!found || total <= 0) return null;
        if (total > 24 * 3600) return null;
        StringBuilder b = new StringBuilder("倒计时 ");
        if (m != null && m > 0) b.append(m).append(" 分钟 ");
        if (s != null && s > 0) b.append(s).append(" 秒 ");
        String name = cleanLabel(label);
        if (!name.isEmpty()) b.append(name);
        return b.toString().trim();
    }

    /** 城市 + "天气" 组成天气问句；城市为空则整句就是"天气"（用设置里存的城市）。 */
    public static String weatherCommand(String city) {
        String c = city == null ? "" : NavVoice.normalize(city);
        c = c.replaceAll("[\\p{Cntrl}]", "");
        if (c.length() > 12) c = "";
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            // 中文城市名以外的字符一律不认，宁可让上层用默认城市，也别拿英文串去查
            if (!(ch >= 0x4E00 && ch <= 0x9FFF)) { c = ""; break; }
        }
        if (c.endsWith("市") && c.length() > 1) c = c.substring(0, c.length() - 1);
        if (c.isEmpty()) return "天气";
        return c + "天气";
    }

    /** 倒计时名字：去掉控制字符；超长直接丢弃（宁可叫"倒计时"也别塞半句话进去）。 */
    public static String cleanLabel(String raw) {
        if (raw == null) return "";
        String v = NavVoice.normalize(raw).replaceAll("[\\p{Cntrl}]", "");
        if (v.isEmpty() || v.length() > 16) return "";
        return v;
    }

    /**
     * 备忘录条目的内容清洗。
     *
     * 模型给的 `text` 同样不能直接信：它会把整句「帮我记一下 买牛奶」塞进来，
     * 也会带换行/制表符。这里统一：去控制字符 → 压平空白 → 按上限截断 → 空则返回空串
     * （上层据此拒绝这次调用，让模型追问"要记什么"，而不是记下一条空内容）。
     */
    public static String memoText(String raw) {
        if (raw == null) return "";
        String v = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (v.length() > Memo.MAX_TEXT) v = v.substring(0, Memo.MAX_TEXT);
        return Memo.hasContent(v) ? v : "";
    }

    /** 允许的 key 之外一律拒绝（与 ToolClient 的严格校验风格一致）。 */
    public static boolean onlyKeys(Set<String> actual, String... allowed) {
        Set<String> ok = new HashSet<>(Arrays.asList(allowed));
        for (String k : actual) if (!ok.contains(k)) return false;
        return true;
    }

    /** 兜底：小写化后比较工具名（模型偶尔会改大小写）。 */
    public static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static Long wholeNumber(String raw, long min, long max) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        // 只吃纯整数：模型偶尔会传 "3.5"/"3分钟"，那种一律当没给，别猜。
        if (!v.matches("[0-9]{1,6}")) return null;
        try {
            long n = Long.parseLong(v);
            if (n < min || n > max) return null;
            return n;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

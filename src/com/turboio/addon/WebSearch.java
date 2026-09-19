package com.turboio.addon;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索（Tavily）的纯逻辑层 —— 不碰 Android / 网络，可 100% 单测。
 *
 * ══ 为什么要有这一层 ══════════════════════════════════════════
 * 用户要的是「让自有模型具有联网搜索能力」。链路是：
 *   用户提问 → 模型看到 web_search 工具 → 调用 → 本类拼请求 / 解析 / 排版
 *   → 结果文本回到模型 → 模型据此回答（而不是凭训练数据编）
 * 拼请求与解析是最容易写错又最难在真机上排查的部分（认证头、字段名、转义），
 * 所以放纯逻辑层用单测钉死。
 *
 * ══ Tavily 接口（2026-09-19 核实）══════════════════════════════
 *   POST https://api.tavily.com/search
 *   头：Authorization: Bearer tvly-…（Key **不进 body** —— 早期示例才有 api_key 字段）
 *       Content-Type: application/json
 *   体：{"query":"…","max_results":5,"search_depth":"basic","topic":"general","include_answer":true}
 *   回：{"query":…,"answer":"AI 摘要","results":[{"title","url","content","score"}]}
 *
 * JSON 解析复用 {@link News} 里那套轻量工具（同包、纯字符串、无 org.json 依赖）。
 */
public final class WebSearch {
    private WebSearch() {}

    public static final String ENDPOINT = "https://api.tavily.com/search";
    /** 眼镜固定 5 行。 */
    public static final int LINES = 5;
    public static final int LINE_MAX = 20;

    /** 一条搜索结果。 */
    public static final class Result {
        public final String title;
        public final String url;
        public final String content;

        public Result(String title, String url, String content) {
            this.title = nz(title);
            this.url = nz(url);
            this.content = nz(content);
        }
    }

    static String nz(String v) { return v == null ? "" : v; }

    // ══════════════════════════════════════════════════════════════
    //  请求
    // ══════════════════════════════════════════════════════════════

    /**
     * 组装请求体。**Key 不放这里** —— 走 Authorization: Bearer 头。
     *
     * @param query      检索词（会被 JSON 转义，带引号 / 换行也不会把请求体搞坏）
     * @param maxResults 1–20，越界夹到边界
     */
    public static String bodyJson(String query, int maxResults) {
        int n = maxResults < 1 ? 1 : (maxResults > 20 ? 20 : maxResults);
        return "{\"query\":" + jstr(query)
            + ",\"max_results\":" + n
            + ",\"search_depth\":\"basic\""
            + ",\"topic\":\"general\""
            + ",\"include_answer\":true}";
    }

    /** 默认 5 条（Tavily basic 深度下够用，也省额度）。 */
    public static String bodyJson(String query) { return bodyJson(query, 5); }

    /** JSON 字符串转义（引号 / 反斜杠 / 控制字符）。 */
    public static String jstr(String raw) {
        if (raw == null) return "\"\"";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    // ══════════════════════════════════════════════════════════════
    //  解析
    // ══════════════════════════════════════════════════════════════

    /** Tavily 给的 AI 摘要（include_answer=true 时才有）；没有返回 ""。 */
    public static String answer(String json) {
        if (json == null || json.isEmpty()) return "";
        String v = News.field(json, "answer");
        return v == null ? "" : v.trim();
    }

    /** 解析 results 数组。解析不出就返回空列表（上层据此报"没搜到"）。 */
    public static List<Result> parse(String json) {
        List<Result> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        String arr = News.arrayField(json, "results");
        if (arr == null) return out;
        for (String o : News.objects(arr)) {
            String url = News.field(o, "url");
            String title = News.field(o, "title");
            if (title.isEmpty() && url.isEmpty()) continue;
            if (title.isEmpty()) title = url;
            out.add(new Result(title, url, News.field(o, "content")));
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    //  给模型看的文本
    // ══════════════════════════════════════════════════════════════

    /**
     * 整理成模型能直接引用的一段文字 —— 这是"让模型有联网能力"的落点：
     * 工具返回什么，模型就只能基于什么回答，所以这里必须带上**来源 URL**，
     * 否则模型会把它当成自己的知识、编造成"我记得…"。
     */
    public static String toModelText(List<Result> rs, String ans) {
        StringBuilder b = new StringBuilder();
        if (ans != null && !ans.trim().isEmpty()) {
            b.append("【检索摘要】").append(ans.trim()).append("\n\n");
        }
        if (rs == null || rs.isEmpty()) {
            b.append("（没有检索到结果）");
            return b.toString();
        }
        b.append("【检索结果 · 共 ").append(rs.size()).append(" 条】\n");
        int i = 1;
        for (Result r : rs) {
            b.append(i++).append(". ").append(r.title).append('\n');
            if (!r.url.isEmpty()) b.append("   来源：").append(r.url).append('\n');
            if (!r.content.isEmpty()) b.append("   ").append(brief(r.content, 220)).append('\n');
        }
        b.append("\n以上来自联网检索，请据此回答并说明信息来源；没有的就直说检索不到。");
        return b.toString();
    }

    /** 按字符数截断（给模型看的文本按字符算就行，不用折算全角）。 */
    public static String brief(String text, int max) {
        if (text == null) return "";
        String t = text.trim().replaceAll("\\s+", " ");
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    // ══════════════════════════════════════════════════════════════
    //  眼镜排版
    // ══════════════════════════════════════════════════════════════

    /** 眼镜一页：表头 + 最多 4 条标题（≤5 行）。 */
    public static String compose(List<Result> rs, int page) {
        if (rs == null || rs.isEmpty()) return "◎ 联网搜索：没结果";
        int per = LINES - 1;
        int pages = (rs.size() + per - 1) / per;
        int p = page < 0 ? 0 : (page >= pages ? pages - 1 : page);
        StringBuilder out = new StringBuilder(News.clip("◎ 联网搜索 " + (p + 1) + "/" + pages, LINE_MAX));
        int used = 1;
        for (int i = p * per; i < rs.size() && used < LINES; i++) {
            String line = News.clip((i + 1) + ". " + rs.get(i).title, LINE_MAX);
            if (line.isEmpty()) continue;
            out.append('\n').append(line);
            used++;
        }
        return out.toString();
    }

    // ══════════════════════════════════════════════════════════════
    //  语音 / 问句
    // ══════════════════════════════════════════════════════════════

    /** 是不是一句"要联网搜一下"的话。 */
    public static boolean isSearchQuery(String raw) {
        if (raw == null) return false;
        String q = raw.trim();
        if (q.isEmpty()) return false;
        return q.contains("搜一下") || q.contains("搜索") || q.contains("查一下")
            || q.contains("帮我查") || q.contains("上网查") || q.contains("联网")
            || q.contains("查查");
    }

    /**
     * 从一句话里抠出检索词：剥掉"搜一下 / 联网搜索 / 帮我查"这类前缀和"谢谢"后缀。
     * 抠不出来返回 ""（上层据此拒绝这次调用，让模型追问要查什么）。
     */
    public static String query(String raw) {
        if (raw == null) return "";
        String q = raw.trim();
        String[] heads = {"帮我联网搜索", "帮我上网查一下", "联网搜索一下", "联网搜索", "上网查一下",
            "帮我搜索一下", "帮我搜一下", "帮我查一下", "搜索一下", "搜一下", "查一下",
            "帮我查", "帮我搜", "搜索", "查查", "查"};
        for (String h : heads) {
            if (q.startsWith(h)) { q = q.substring(h.length()).trim(); break; }
        }
        String[] tails = {"谢谢", "好吗", "可以吗", "一下"};
        for (String t : tails) {
            if (q.endsWith(t) && q.length() > t.length()) { q = q.substring(0, q.length() - t.length()).trim(); break; }
        }
        q = q.replaceAll("\\s+", " ").trim();
        // 太短（"天气"这种被剥坏的残句）或太长（整段话被塞进来）都不当检索词
        if (q.length() < 2 || q.length() > 200) return "";
        return q;
    }
}

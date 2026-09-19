package com.turboio.addon;

import java.util.ArrayList;
import java.util.List;

/**
 * 热搜 / 热榜新闻的纯逻辑核心（不碰 Android / 网络 / JSON 库，可 100% 单测）。
 *
 * ══ 数据源选型（2026-09-19 实测）══════════════════════════════
 * 比过几家，选 **60s.viki.moe**（开源项目 vikiboss/60s）：
 *   · vvhan / pearktrue：本机与国内网络下要么空响应要么被挡，不稳定；
 *   · 微博官方 hotSearch：直接 Forbidden（要登录态，做不了）；
 *   · 60s.viki.moe：四个榜全通，返回标准 JSON。
 *
 * 选它最关键的一条是**免 Key** —— 用户装完就能看，不用先去注册申请。
 * 榜单（实测结构）：
 *   微博热搜 /v2/weibo   → data:[{title, hot_value, link}]            50 条
 *   头条热搜 /v2/toutiao → data:[{title, hot_value, cover, link}]     50 条
 *   知乎热榜 /v2/zhihu   → data:[{title, detail, hot_value_desc, …}]  30 条
 *   今日新闻 /v2/60s     → data:{date, news:[...], tip, …}
 *
 * ══ 为什么不引 org.json ══════════════════════════════════════
 * android.jar 里的 org.json 只是桩（方法体 throw "Stub!"），纯 JVM 单测里
 * 一调就炸。为了让「解析 + 眼镜排版」都能进单测，这里内置一个够用的小解析：
 * 只取字符串字段、按顶层对象切分、正确处理转义与反斜杠 u 形式的 Unicode 转义。
 *（注意：注释里不能写单个反斜杠+u —— Java 在词法分析前就处理 Unicode 转义，会报错。）
 *
 * ══ 眼镜排版（硬约束）════════════════════════════════════════
 * 字幕通道只有 5 行、替换式、宽度有限，所以固定 ≤5 行、每行 clip 到
 * {@link #LINE_MAX} 个全角字符以内。
 */
public final class News {
    private News() {}

    /** 每行最多多少"全角字符"（与 Weather / TextPage 同一套折算口径）。 */
    public static final int LINE_MAX = 20;
    /** 眼镜固定 5 行。 */
    public static final int LINES = 5;

    public static final String WEIBO   = "weibo";
    public static final String TOUTIAO = "toutiao";
    public static final String ZHIHU   = "zhihu";
    public static final String DAILY   = "60s";

    /** 榜单 id → API 路径片段。 */
    public static String path(String board) {
        if (TOUTIAO.equals(board)) return TOUTIAO;
        if (ZHIHU.equals(board)) return ZHIHU;
        if (DAILY.equals(board)) return "60s";
        return WEIBO;
    }

    /** 榜单 id → 中文名（眼镜表头、手机页标题、语音回复都用它）。 */
    public static String name(String board) {
        if (TOUTIAO.equals(board)) return "头条热搜";
        if (ZHIHU.equals(board)) return "知乎热榜";
        if (DAILY.equals(board)) return "今日新闻";
        return "微博热搜";
    }

    /** 榜单顺序（手机页与语音默认都按这个顺序）。 */
    public static List<String> boards() {
        List<String> out = new ArrayList<>();
        out.add(WEIBO);
        out.add(TOUTIAO);
        out.add(ZHIHU);
        out.add(DAILY);
        return out;
    }

    /** 一条热榜条目。 */
    public static final class Item {
        public final String title;
        public final String hot;   // 已压缩的热度，如 "142万"
        public final String desc;  // 摘要（知乎有），可能为空
        public final String link;

        public Item(String title, String hot, String desc, String link) {
            this.title = nz(title);
            this.hot = nz(hot);
            this.desc = nz(desc);
            this.link = nz(link);
        }
    }

    static String nz(String v) { return v == null ? "" : v; }

    // ══════════════════════════════════════════════════════════════
    //  解析
    // ══════════════════════════════════════════════════════════════

    /** 按榜单分派解析。认不出榜单按微博处理。 */
    public static List<Item> parse(String board, String json) {
        if (DAILY.equals(board)) return parseDaily(json);
        if (ZHIHU.equals(board)) return parseZhihu(json);
        return parseList(json);
    }

    /** 微博 / 头条：data 是对象数组，热度在 hot_value。 */
    public static List<Item> parseList(String json) {
        List<Item> out = new ArrayList<>();
        String arr = dataSection(json);
        if (arr == null) return out;
        for (String o : objects(arr)) {
            String t = field(o, "title");
            if (t.isEmpty()) continue;
            out.add(new Item(t, hotText(field(o, "hot_value")), "", field(o, "link")));
        }
        return out;
    }

    /** 知乎：热度在 hot_value_desc（如 "589 万热度"），摘要在 detail。 */
    public static List<Item> parseZhihu(String json) {
        List<Item> out = new ArrayList<>();
        String arr = dataSection(json);
        if (arr == null) return out;
        for (String o : objects(arr)) {
            String t = field(o, "title");
            if (t.isEmpty()) continue;
            out.add(new Item(t, hotText(field(o, "hot_value_desc")), field(o, "detail"), field(o, "link")));
        }
        return out;
    }

    /** 今日新闻：data.news 是**字符串数组**（没有热度）。 */
    public static List<Item> parseDaily(String json) {
        List<Item> out = new ArrayList<>();
        String data = dataSection(json);
        if (data == null) return out;
        String arr = arrayField(data, "news");
        if (arr == null) return out;
        for (String v : stringArray(arr)) {
            String t = v.trim();
            if (!t.isEmpty()) out.add(new Item(t, "", "", ""));
        }
        return out;
    }

    /**
     * 热度压缩成人话：1425355 → "142万"；"589 万热度" → "589万"。
     * 已经带「万 / 亿」的原样保留数字部分，别再除一次（除出来会变成 589）。
     */
    public static String hotText(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (v.isEmpty()) return "";
        if (v.endsWith("热度")) v = v.substring(0, v.length() - 2).trim();
        if (v.contains("万") || v.contains("亿")) return v.replaceAll("\\s+", "");
        String digits = v.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        long n;
        try { n = Long.parseLong(digits); } catch (NumberFormatException e) { return ""; }
        if (n <= 0) return "";
        if (n >= 100000000L) return (n / 100000000L) + "亿";
        if (n >= 10000L) return (n / 10000L) + "万";
        return String.valueOf(n);
    }

    // ── 轻量 JSON（只够用，不追求完备）────────────────────────

    /** 取顶层 "data" 对应的值片段（数组或对象，含两侧括号）；没有返回 null。 */
    static String dataSection(String json) {
        if (json == null) return null;
        String k = "\"data\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int c = json.indexOf(':', i + k.length());
        if (c < 0) return null;
        int s = c + 1;
        while (s < json.length() && isWs(json.charAt(s))) s++;
        if (s >= json.length()) return null;
        char ch = json.charAt(s);
        if (ch == '[') return match(json, s, '[', ']');
        if (ch == '{') return match(json, s, '{', '}');
        return null;
    }

    /** 在一个对象片段里取某个数组字段（如 news），返回含 [] 的整段。 */
    static String arrayField(String obj, String key) {
        if (obj == null) return null;
        String k = "\"" + key + "\"";
        int i = obj.indexOf(k);
        if (i < 0) return null;
        int c = obj.indexOf(':', i + k.length());
        if (c < 0) return null;
        int s = c + 1;
        while (s < obj.length() && isWs(obj.charAt(s))) s++;
        if (s >= obj.length() || obj.charAt(s) != '[') return null;
        return match(obj, s, '[', ']');
    }

    /** 在一个对象片段里取字符串字段；缺失或不是字符串返回 ""。 */
    static String field(String obj, String key) {
        if (obj == null) return "";
        String k = "\"" + key + "\"";
        int i = obj.indexOf(k);
        if (i < 0) return "";
        int c = obj.indexOf(':', i + k.length());
        if (c < 0) return "";
        int s = c + 1;
        while (s < obj.length() && isWs(obj.charAt(s))) s++;
        return readString(obj, s);
    }

    /** 按顶层对象切分一个数组片段。 */
    static List<String> objects(String arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        int i = 0;
        while (i < arr.length()) {
            if (arr.charAt(i) == '{') {
                String o = match(arr, i, '{', '}');
                if (o == null) break;
                out.add(o);
                i += o.length();
            } else {
                i++;
            }
        }
        return out;
    }

    /** 按顶层字符串切分一个数组片段（news:[...] 用）。 */
    static List<String> stringArray(String arr) {
        List<String> out = new ArrayList<>();
        if (arr == null) return out;
        int i = 0;
        while (i < arr.length()) {
            if (arr.charAt(i) == '"') {
                int end = stringEnd(arr, i);
                if (end < 0) break;
                out.add(unescape(arr.substring(i + 1, end - 1)));
                i = end;
            } else {
                i++;
            }
        }
        return out;
    }

    static boolean isWs(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }

    /** 从 start（必须是 open）扫到配对闭括号，返回含括号的整段。字符串感知。 */
    static String match(String s, int start, char open, char close) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return s.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 从 start 起的 JSON 字符串结束位置（闭引号的下一个下标）；不是字符串返回 -1。 */
    static int stringEnd(String s, int start) {
        if (start >= s.length() || s.charAt(start) != '"') return -1;
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i + 1;
        }
        return -1;
    }

    static String readString(String s, int start) {
        int end = stringEnd(s, start);
        if (end < 0) return "";
        return unescape(s.substring(start + 1, end - 1));
    }

    /** 反转义：\n \t \r \b \f \\ \" \/ 与反斜杠 u 形式的 Unicode 转义。 */
    static String unescape(String raw) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c != '\\') { out.append(c); i++; continue; }
            i++;
            if (i >= raw.length()) break;
            char e = raw.charAt(i);
            if (e == 'u' && i + 4 < raw.length()) {
                try {
                    out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                } catch (NumberFormatException ignored) { }
                i += 4;
            } else {
                switch (e) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'r': out.append('\r'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    default:  out.append(e);
                }
            }
            i++;
        }
        return out.toString();
    }

    // ══════════════════════════════════════════════════════════════
    //  眼镜排版
    // ══════════════════════════════════════════════════════════════

    /** 按"全角=1、半角=0.5"折算宽度（与 TextPage.clip 同一口径）。 */
    public static double width(String s) {
        if (s == null) return 0;
        double n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += (c < 0x2E80) ? 0.5 : 1.0;
        }
        return n;
    }

    /** 一行条目："1. 标题 142万"。标题会被压缩以给热度腾位置。 */
    public static String itemLine(int no, Item it) {
        if (it == null || it.title.isEmpty()) return "";
        String prefix = no + ". ";
        // 减 1 是给 clip 追加的省略号留位：TextPage.clip 截断后会再补一个全角「…」，
        // 不留位的话整行会超出 LINE_MAX，末尾的热度会被第二次 clip 整个吃掉。
        double room = LINE_MAX - width(prefix) - 1.0;
        if (!it.hot.isEmpty()) room -= width(it.hot) + 0.5;
        String t = clip(it.title, (int) Math.floor(room < 4 ? 4 : room));
        StringBuilder b = new StringBuilder(prefix).append(t);
        if (!it.hot.isEmpty()) b.append(' ').append(it.hot);
        return clip(b.toString(), LINE_MAX);
    }

    /**
     * 眼镜上的一页：表头 + 最多 4 条（≤5 行）。page 从 0 开始，越界自动夹到有效范围。
     * 表头带 "1/13" 这种页码 —— 5 行装不下 50 条，必须让用户知道还能翻。
     */
    public static String compose(String board, List<Item> items, int page) {
        if (items == null || items.isEmpty()) return "◎ 没拿到" + name(board);
        int per = LINES - 1;                       // 表头占 1 行，剩 4 行给条目
        int pages = (items.size() + per - 1) / per;
        int p = page < 0 ? 0 : (page >= pages ? pages - 1 : page);
        StringBuilder out = new StringBuilder(clip("◎ " + name(board) + " " + (p + 1) + "/" + pages, LINE_MAX));
        int used = 1;
        for (int i = p * per; i < items.size() && used < LINES; i++) {
            String line = itemLine(i + 1, items.get(i));
            if (line.isEmpty()) continue;
            out.append('\n').append(line);
            used++;
        }
        return out.toString();
    }

    /** 一共几页（供翻页用）。 */
    public static int pages(List<Item> items) {
        if (items == null || items.isEmpty()) return 0;
        int per = LINES - 1;
        return (items.size() + per - 1) / per;
    }

    // ══════════════════════════════════════════════════════════════
    //  语音
    // ══════════════════════════════════════════════════════════════

    /**
     * 是不是一句"看热搜 / 新闻"的话。
     *
     * 刻意**不**收「微博」「知乎」这种平台名单独出现的情况 —— 用户说「知乎」多半
     * 是想聊别的，只有配上热榜类词才是要看榜。
     */
    public static boolean isNewsQuery(String raw) {
        if (raw == null) return false;
        String q = raw.trim();
        if (q.isEmpty()) return false;
        return q.contains("热搜") || q.contains("热榜") || q.contains("热点")
            || q.contains("新闻") || q.contains("头条") || q.contains("有什么事")
            || q.contains("热搜榜") || q.contains("今日要闻");
    }

    /**
     * 从一句话里挑榜单。认不出返回 {@link #WEIBO}（微博热搜，最常用）。
     *
     * 「新闻」优先判给今日新闻，因为那才是"新闻"的字面意思；
     * 「热搜 / 热榜」默认给微博。
     */
    public static String board(String raw) {
        if (raw == null) return WEIBO;
        String q = raw.trim();
        if (q.contains("知乎")) return ZHIHU;
        if (q.contains("头条")) return TOUTIAO;
        if (q.contains("今日新闻") || q.contains("今天的新闻") || q.contains("新闻") || q.contains("要闻")) return DAILY;
        return WEIBO;
    }

    /** 按"全角字符"折算截断（半角算半个），超长加省略号。 */
    public static String clip(String text, int max) {
        return TextPage.clip(text, max);
    }
}

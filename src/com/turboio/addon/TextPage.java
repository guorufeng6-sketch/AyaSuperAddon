package com.turboio.addon;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分页 / 行宽折算 / 截断（纯逻辑，不碰 Android，可 100% 单测）。
 *
 * ── 为什么单独一个类 ────────────────────────────────────────────
 * 这套规则原本写在 {@link ReaderUI} 里。但 ReaderUI 的静态初始化会去
 * `NavGlasses.onRelease(...)`，而 NavGlasses 的静态字段是
 * `new Handler(Looper.getMainLooper())` —— 在纯 JVM 单测里一碰就崩。
 * 于是"分页对不对"这件最该被测试钉死的事反而测不了。
 * 抽到这里以后：ReaderUI / Weather / 提词器共用同一套口径，
 * 单测只需要 `java -cp build/test` 就能跑。
 *
 * ── 核心口径（务必与眼镜端一致）────────────────────────────────
 * · 一行宽度用"全角字符"为单位：CJK 及全角符号 = 1，ASCII / 拉丁 = 0.5。
 * · 眼镜字幕只有 5 行，所以**必须按行切**而不是按字数切 ——
 *   同样的字数、行宽不同就是不同的行数，按字数切一定会出现"要滚屏"。
 * · 行首不放收尾标点（。，、）等）。
 */
public final class TextPage {
    private TextPage() {}

    /** 眼镜端字幕的硬上限：5 行。 */
    public static final int MAX_LINES = 5;
    /** 默认每行宽度（全角字符数）。宁可少放两个字，也不要出现"要滚屏"。 */
    public static final int DEFAULT_LINE_WIDTH = 20;

    public static List<String> paginate(String text, int lineWidth) {
        return paginate(text, lineWidth, MAX_LINES);
    }

    /** 按行宽折行后，每 maxLines 行切一页（页内写死 '\n'）。 */
    public static List<String> paginate(String text, int lineWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int limit = Math.max(8, lineWidth);
        int linesPerPage = Math.max(1, maxLines);
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) return out;

        List<String> lines = new ArrayList<>();
        for (String para : normalized.split("\n")) {
            String p = para.trim();
            if (p.isEmpty()) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
                continue;
            }
            wrapInto(p, limit, lines);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);

        for (int i = 0; i < lines.size(); i += linesPerPage) {
            StringBuilder page = new StringBuilder();
            for (int j = i; j < Math.min(i + linesPerPage, lines.size()); j++) {
                if (page.length() > 0) page.append('\n');
                page.append(lines.get(j));
            }
            out.add(page.toString());
        }
        return out;
    }

    /** 把一段话按行宽折成若干行，追加到 lines。 */
    public static void wrapInto(String para, int limit, List<String> lines) {
        StringBuilder line = new StringBuilder();
        double used = 0;
        for (int i = 0; i < para.length(); i++) {
            char c = para.charAt(i);
            double w = units(c);
            // 行首不放收尾标点：宁可让这行多挤一个字符，也不要出现"，"起头。
            boolean wouldBreak = used + w > limit && line.length() > 0;
            if (wouldBreak && !breaksAtStart(c)) {
                lines.add(line.toString());
                line.setLength(0);
                used = 0;
            }
            line.append(c);
            used += w;
        }
        if (line.length() > 0) lines.add(line.toString());
    }

    /** 行首禁用字符（收尾型标点）。 */
    public static boolean breaksAtStart(char c) {
        return "。，、；：！？）】》」』…—·.,;:!?)]}>".indexOf(c) >= 0;
    }

    /** 显示宽度：ASCII / 拉丁算半个全角，CJK 及全角符号算一个。 */
    public static double units(char c) {
        return c < 0x1100 ? 0.5 : 1.0;
    }

    /** 一段文本的显示宽度（全角单位）。 */
    public static double width(String text) {
        if (text == null) return 0;
        double w = 0;
        for (int i = 0; i < text.length(); i++) w += units(text.charAt(i));
        return w;
    }

    /** 按宽度截断，超长加省略号。 */
    public static String clip(String text, double max) {
        if (text == null) return "";
        double used = 0;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            double w = units(c);
            if (used + w > max) return b.append('…').toString();
            b.append(c);
            used += w;
        }
        return b.toString();
    }

    /** 一页里有几行。给"必须 ≤5 行"的断言用。 */
    public static int lineCount(String page) {
        if (page == null || page.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < page.length(); i++) if (page.charAt(i) == '\n') n++;
        return n;
    }

    /** 一页里最宽的那一行有多宽。给"不超行宽"的断言用。 */
    public static double widestLine(String page) {
        if (page == null) return 0;
        double max = 0;
        for (String line : page.split("\n")) max = Math.max(max, width(line));
        return max;
    }
}

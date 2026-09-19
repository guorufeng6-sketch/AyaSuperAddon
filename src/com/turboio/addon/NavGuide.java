package com.turboio.addon;

import java.util.*;

/**
 * 转向指引的纯逻辑核心：把高德的 step 文本变成「箭头 + 一行短句 + 进度条」。
 *
 * ── 为什么需要箭头 ──────────────────────────────────────────────
 * 眼镜端只有纯文字通道（5 行、无图形）。但转向指引的本质是"方向"，
 * 纯文字"右转"要读三个字才反应过来，而一个箭头字符（→）
 * 扫一眼就知道 —— 在开车场景里这点时间很值钱。
 *
 * 所以策略是：把高德的 instruction 压成最上面一行「箭头 + 距离 + 动作」，
 * 下面是原始指令 + 进度条 + 剩余里程。屏幕只有 5 行，顶部那行必须信息密度最高。
 *
 * ── ★★★ 字形可用性（2026-09-18 实测踩坑，务必先读）★★★ ─────────
 * 眼镜是**中文固件**，字体只保证覆盖 **GB2312/GBK** 字符集。
 * 原先用的 `↰ ↱ ⤴ ⤵ ⟲ ↻ ⇢ •` 这些箭头 **连 GBK 都不在码表里**
 * （用 Python `ch.encode('gbk')` 逐个验证过），固件画不出来时
 * 那一格就是空白 —— 用户看到的效果等于"纯文字，没有箭头"。
 *
 * 实测码表归属：
 *   ✅ GB2312 内（最稳）： ← ↑ → ↓ ● ○ ◎ ◇ ◆ ■ □ ▲ △ ★ ☆ ※
 *   🟡 仅 GBK 内：        ↖ ↗ ↘ ↙ █ ▓
 *   ❌ 都不在（别用）：   ↰ ↱ ⤴ ⤵ ⟲ ↻ ⇢ • ↯ ↺ ░ ▶ ◀ ➜
 *
 * 所以默认用 {@link Style#SAFE}（GB2312 安全字符）。若某台设备字体
 * 覆盖更全，可在「导航」页切成 FANCY 拿回精细箭头；若连 GB2312 都画不出，
 * 切 ASCII 用纯西文符号兜底。样式开关存在设置里，重装后保留。
 *
 * ── 高德 instruction 的真实长相（实测） ──────────────────────────
 *   "沿长风西街向西行驶500米，右转"
 *   "进入建设南路"
 *   "沿滨河西路行驶1.2公里，左前方转弯进入迎泽大街"
 *   "到达目的地"
 *   "在道路尽头左转"
 * 方向词散落在句子里，且和"转弯进入/左前方转弯"这类复合词混在一起，
 * 所以匹配顺序必须从长到短（"左前方转弯" 要先于 "左转" 命中断）。
 *
 * 本类不碰 Android / 网络 / 反射，可 100% 单测覆盖。
 */
public final class NavGuide {
    private NavGuide() {}

    // ══════════════════════════════════════════════════════════════
    //  箭头样式（字形可用性不同，做成可切换）
    // ══════════════════════════════════════════════════════════════

    /**
     * 箭头样式。默认 {@link #SAFE} —— 眼镜固件是中文环境，
     * 只有 GB2312 内码表里的符号才敢保证画得出来。
     */
    public enum Style {
        /** 标准：只用 GB2312 内的符号（← ↑ → ↓ ● ○ ◎ ◇ ◆ ■ □ ▲ △）。推荐。 */
        SAFE("标准（兼容性最好，推荐）"),
        /** 精细：本项目的原始设计（↰ ↱ ↖ ↗ ⤴ ⤵ ⟲ ↻）。部分固件可能显示不出来。 */
        FANCY("精细箭头（部分设备画不出）"),
        /** 纯 ASCII：`<` `>` `^` 等，连 GB2312 都没有时的最后兜底。 */
        ASCII("纯西文符号（最保守）");

        public final String label;
        Style(String label) { this.label = label; }
    }

    private static volatile Style style = Style.SAFE;

    public static Style style() { return style; }
    public static void setStyle(Style value) { if (value != null) style = value; }
    public static int styleIndex() { return style.ordinal(); }

    /** 按序号设置（0=SAFE / 1=FANCY / 2=ASCII），越界自动夹紧。 */
    public static void setStyleIndex(int index) {
        Style[] all = Style.values();
        style = all[Math.max(0, Math.min(index, all.length - 1))];
    }
    public static int styleCount() { return Style.values().length; }
    public static Style styleAt(int index) {
        Style[] all = Style.values();
        return all[Math.max(0, Math.min(index, all.length - 1))];
    }

    /**
     * 切到下一个样式并返回新的序号。
     *
     * ★ 必须取模回绕 ★
     *   旧实现是 `setStyleIndex(ordinal+1)`，而 setStyleIndex 越界是"夹紧"不是"回绕"：
     *   到最后一项（ASCII）再 +1 → 3 → 夹回 2，永远停在 ASCII。
     *   用户实测「切到西文后就不能再继续切换了」就是这个。
     *   （界面已改成列表直选，这里保留回绕语义给语音/快捷入口用。）
     */
    public static int cycleStyle() {
        setStyleIndex((style.ordinal() + 1) % Style.values().length);
        return style.ordinal();
    }

    /**
     * 一个转向类型。
     * fancy/safe/ascii 是三套字形，{@link #glyph()} 按当前样式取其一；
     * label 是手机端读的短标签，也用来在眼镜上补足语义
     * （SAFE 模式下左右只能各用一个箭头，靠 label 区分"左转/稍向左/急左转"）。
     */
    public enum Turn {
        //          fancy  safe  ascii  label
        LEFT        ("↰", "←", "<",  "左转"),
        RIGHT       ("↱", "→", ">",  "右转"),
        SLIGHT_LEFT ("↖", "←", "<",  "稍向左"),
        SLIGHT_RIGHT("↗", "→", ">",  "稍向右"),
        SHARP_LEFT  ("⤴", "←", "<<", "急左转"),
        SHARP_RIGHT ("⤵", "→", ">>", "急右转"),
        UTURN       ("⟲", "◆", "U",  "掉头"),
        ROUNDABOUT  ("↻", "○", "O",  "环岛"),
        MERGE       ("⇢", "→", ">",  "汇入主路"),
        RAMP        ("↗", "△", "^",  "上匝道"),
        STRAIGHT    ("↑", "↑", "^",  "直行"),
        ARRIVE      ("◎", "◎", "*",  "到达"),
        WAYPOINT    ("◇", "◇", "o",  "途经点"),
        UNKNOWN     ("•", "●", "*",  "继续行驶");

        /** 精细箭头（原始设计，GBK 外字符多，慎用）。 */
        public final String fancy;
        /** GB2312 安全字形 —— 默认使用。 */
        public final String safe;
        /** 纯 ASCII 字形。 */
        public final String ascii;
        /** 手机端短标签。 */
        public final String label;

        Turn(String fancy, String safe, String ascii, String label) {
            this.fancy = fancy; this.safe = safe; this.ascii = ascii; this.label = label;
        }

        /** 按当前样式取箭头字符。 */
        public String glyph() {
            switch (style) {
                case FANCY: return fancy;
                case ASCII: return ascii;
                default:    return safe;
            }
        }
    }

    /**
     * 解析结果。
     *  topLine  —— 给眼镜第一行（箭头 + 距离 + 动作），信息密度最高
     *  detail   —— 高德原话（让用户能核对）
     *  turn     —— 判定出的方向
     *  distance —— 从原话里抠出来的距离（米），抠不到为 -1
     */
    public static final class Guidance {
        public final Turn turn;
        public final String topLine;
        public final String detail;
        public final double distance;
        Guidance(Turn turn, String topLine, String detail, double distance) {
            this.turn = turn; this.topLine = topLine; this.detail = detail; this.distance = distance;
        }
        @Override public String toString() { return topLine; }
        /** 是否已经到达终点。 */
        public boolean arrived() { return turn == Turn.ARRIVE; }
    }

    // ── 方向词表：从长到短，避免 "左前方转弯" 被 "左转" 抢 ──
    private static final String[][] TURN_WORDS = {
        {"左前方转弯", "左前方"},   // 高德常用复合词
        {"右前方转弯", "右前方"},
        {"左后方转弯", "左后方"},
        {"右后方转弯", "右后方"},
        {"向左前方", "左前方"},
        {"向右前方", "右前方"},
        {"向左后方", "左后方"},
        {"向右后方", "右后方"},
        {"靠左前方", "左前方"},
        {"靠右前方", "右前方"},
        {"调头", "掉头"}, {"掉头", "掉头"},
        {"环岛", "环岛"}, {"绕环岛", "环岛"},
        {"左转", "左转"}, {"向左转", "左转"}, {"向左转弯", "左转"}, {"左转弯", "左转"},
        {"右转", "右转"}, {"向右转", "右转"}, {"向右转弯", "右转"}, {"右转弯", "右转"},
        {"急转弯", "转弯"}, {"转弯", "转弯"},
        {"进入主路", "汇入"}, {"汇入", "汇入"}, {"并线", "汇入"},
        {"匝道", "匝道"}, {"上桥", "匝道"}, {"下桥", "匝道"},
        // 「靠左/靠右」要排在匝道之类具体动作之后 —— "靠右上匝道" 的真实语义是上匝道，
        // 只判到"靠右"会丢掉最关键的动作信息。
        {"靠左", "左转"}, {"靠右", "右转"},
        {"直行", "直行"}, {"沿", "直行"}, {"继续", "直行"},
        {"到达目的地", "到达"}, {"到达", "到达"}, {"抵达", "到达"},
        {"途经点", "途经"}
    };

    /**
     * 把一条高德 step 转成眼镜上要显示的两行。
     *
     * @param instruction 高德 getInstruction() 的原话，可能为 null
     * @param stepDistance 高德给的本段距离（米），未知传 -1
     */
    public static Guidance of(String instruction, double stepDistance) {
        String text = instruction == null ? "" : instruction.trim();
        Turn turn = classify(text);
        double distance = stepDistance > 0 ? stepDistance : extractDistance(text);
        String top = buildTop(turn, distance, text);
        return new Guidance(turn, top, text.isEmpty() ? "（无指令）" : text, distance);
    }

    /** 判定方向。 */
    public static Turn classify(String text) {
        if (text == null || text.isEmpty()) return Turn.UNKNOWN;
        // 到达要最先判 —— "到达目的地" 里没有方向词，但可能有"目的地"。
        for (String[] pair : TURN_WORDS) {
            if (text.contains(pair[0])) {
                return map(pair[1]);
            }
        }
        return Turn.STRAIGHT;   // 高德的 step 绝大多数是"沿X路行驶"，按直行处理
    }

    private static Turn map(String key) {
        switch (key) {
            case "左转": return Turn.LEFT;
            case "右转": return Turn.RIGHT;
            case "稍向左": return Turn.SLIGHT_LEFT;
            case "稍向右": return Turn.SLIGHT_RIGHT;
            case "急左": return Turn.SHARP_LEFT;
            case "急右": return Turn.SHARP_RIGHT;
            case "左前方": return Turn.SLIGHT_LEFT;
            case "右前方": return Turn.SLIGHT_RIGHT;
            case "左后方": return Turn.SHARP_LEFT;
            case "右后方": return Turn.SHARP_RIGHT;
            case "掉头": return Turn.UTURN;
            case "环岛": return Turn.ROUNDABOUT;
            case "汇入": return Turn.MERGE;
            case "匝道": return Turn.RAMP;
            case "直行": return Turn.STRAIGHT;
            case "到达": return Turn.ARRIVE;
            case "途经": return Turn.WAYPOINT;
            case "转弯": return Turn.STRAIGHT;
            default: return Turn.UNKNOWN;
        }
    }

    /**
     * 第一行：`→ 前方 300 米 右转` / `◎ 已到达` / `↑ 沿长风西街向西行驶`.
     * 规则：有距离 → 箭头 + 距离 + 动作；没距离 → 箭头 + 原话截断。
     */
    static String buildTop(Turn turn, double distance, String text) {
        String g = turn.glyph();
        if (turn == Turn.ARRIVE) return g + " 已到达目的地";
        if (turn == Turn.WAYPOINT) return g + " 经过途经点";
        String dist = distance > 0 ? ("前方 " + NavCore.meters(distance) + " ") : "";
        if (dist.isEmpty()) {
            // 抠不到距离时退回原话，但要短 —— 眼镜一行约 14 个汉字。
            String shortText = clipHan(text, 14);
            if (shortText.isEmpty()) return g + " " + turn.label;
            return g + " " + shortText;
        }
        return g + " " + dist + turn.label;
    }

    /**
     * 从高德原话里抠距离。
     * 例："沿长风西街向西行驶500米，右转" → 500
     *     "行驶1.2公里后右转" → 1200
     * 抠不到返回 -1。
     */
    public static double extractDistance(String text) {
        if (text == null || text.isEmpty()) return -1;
        // 公里优先（"1.2公里" 里也含 "2公里"，必须先匹配带小数的完整形式）
        java.util.regex.Matcher km = java.util.regex.Pattern
            .compile("([0-9]+(?:\\.[0-9]+)?)\\s*公里").matcher(text);
        if (km.find()) {
            try { return Double.parseDouble(km.group(1)) * 1000; } catch (NumberFormatException ignored) { }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("([0-9]+(?:\\.[0-9]+)?)\\s*米").matcher(text);
        if (m.find()) {
            try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) { }
        }
        return -1;
    }

    /** 按汉字/字符数截断，用于空间不够时的兜底。 */
    public static String clipHan(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        if (t.length() <= max) return t;
        return t.substring(0, max) + "…";
    }

    // ══════════════════════════════════════════════════════════════
    //  进度条 / 剩余里程
    // ══════════════════════════════════════════════════════════════

    /**
     * 进度条。填充用实心、未走用空心 —— 全部取自 GB2312 内码表，
     * 保证中文固件画得出来（`░ ▒ ▶ ◀` 这类画不出，别换回去）。
     *
     * SAFE  `■■■■■■□□ 75%`      8 格
     * FANCY `●●●●●●●○○○ 75%`    10 格
     * ASCII `#########--- 75%`   12 格
     */
    public static String bar(double ratio) {
        return barCells(ratio, barCellCount()) + " " + Math.round(clamp01(ratio) * 100) + "%";
    }

    /** 当前样式的格子数。 */
    public static int barCellCount() {
        switch (style) {
            case FANCY: return 10;
            case ASCII: return 12;
            default:    return 8;
        }
    }

    /** 填充字符（实心）。 */
    public static String barOn() {
        switch (style) {
            case FANCY: return "●";
            case ASCII: return "#";
            default:    return "■";
        }
    }

    /** 空白字符（空心）。 */
    public static String barOff() {
        switch (style) {
            case FANCY: return "○";
            case ASCII: return "-";
            default:    return "□";
        }
    }

    /** 只画格子，不带百分比（倒计时那种自己排版的地方用）。 */
    public static String barCells(double ratio, int cells) {
        if (cells <= 0) cells = 8;
        double r = clamp01(ratio);
        int filled = (int) Math.round(r * cells);
        String on = barOn(), off = barOff();
        StringBuilder out = new StringBuilder(cells);
        for (int i = 0; i < cells; i++) out.append(i < filled ? on : off);
        return out.toString();
    }

    private static double clamp01(double v) {
        if (!Double.isFinite(v) || v < 0) return 0;
        return v > 1 ? 1 : v;
    }

    /** 已行驶比例。算不出来（没里程）返回 -1，调用方据此决定是否画进度条。 */
    public static double progressRatio(double totalDistance, double remainingDistance) {
        if (!(totalDistance > 0) || !(remainingDistance >= 0)) return -1;
        double r = (totalDistance - remainingDistance) / totalDistance;
        if (r < 0) r = 0;
        if (r > 1) r = 1;
        return r;
    }

    /**
     * 从第 idx 段起算的剩余里程。
     * 优先用高德给的 `step_distance`（最准），缺了用折线几何长度兜底。
     * 当前段的"已走部分"由定位匹配算出的 remainingStep 提供。
     */
    public static double remainingDistance(List<NavCore.Step> steps, int idx, double remainingStep) {
        if (steps == null || steps.isEmpty()) return -1;
        int from = Math.max(0, Math.min(idx, steps.size() - 1));
        double sum;
        if (remainingStep > 0) {
            sum = remainingStep;
        } else {
            // 匹配没给出"本段还剩多少"时，退化成"本段全长"（好过算成 0）。
            sum = stepLength(steps.get(from));
        }
        for (int i = from + 1; i < steps.size(); i++) sum += stepLength(steps.get(i));
        return sum;
    }

    /** 单段长度：高德 step_distance 优先，否则按折线几何算。 */
    static double stepLength(NavCore.Step step) {
        if (step == null) return 0;
        if (step.distance > 0) return step.distance;
        double d = 0;
        List<NavCore.Point> pts = step.points;
        for (int i = 1; i < pts.size(); i++) d += NavCore.distance(pts.get(i - 1), pts.get(i));
        return d;
    }

    /**
     * 几何不可用时，按**已行驶里程**估算当前在第几段。
     *
     * ── 为什么需要这条兜底 ────────────────────────────────────────
     * 用户实测「导航不刷新，卡在第一屏不动」。真因是 v5 的 steps 默认不带
     * polyline（见 {@link NavRoute}），几何全空 → 定位匹配永远选中第 0 段。
     * 现在 polyline 已经补上，但"几何拿不到"这件事本身（高德改字段、
     * 代理截断响应、某条路真没几何）**还会再发生** —— 那时画面不该再定死。
     *
     * 高德的 `step_distance` 与几何无关，永远是有的，用它按累计里程推进：
     * 车往前开一公里，段号就往外走一公里对应的距离，画面跟着走。
     * 精度不如几何匹配，但**远好过一屏不动的废画面**。
     *
     * @param traveled 从路线起点算起的已行驶里程（米）；≤0 返回第 0 段
     * @return 段下标（必然落在 [0, steps.size()-1]）
     */
    public static int estimateStepByTraveled(List<NavCore.Step> steps, double traveled) {
        if (steps == null || steps.isEmpty()) return 0;
        if (!(traveled > 0)) return 0;
        double acc = 0;
        for (int i = 0; i < steps.size(); i++) {
            double len = stepLength(steps.get(i));
            if (len <= 0) continue;              // 段长未知就不累加，别把它当成 0 米路口
            acc += len;
            if (traveled < acc) return i;
        }
        return steps.size() - 1;
    }

    /**
     * 剩余时间（秒）。高德给的是全程时间，按剩余里程比例折算 ——
     * 比"一直显示全程时间"有用得多（开出去一半还显示"约 40 分钟"是误导）。
     */
    public static long etaSeconds(double totalDistance, double remainingDistance, long totalDurationSeconds) {
        if (totalDurationSeconds <= 0) return -1;
        if (totalDistance > 0 && remainingDistance >= 0) {
            double r = remainingDistance / totalDistance;
            if (r < 0) r = 0;
            if (r > 1) r = 1;
            return Math.round(totalDurationSeconds * r);
        }
        return totalDurationSeconds;
    }

    // ══════════════════════════════════════════════════════════════
    //  组屏
    // ══════════════════════════════════════════════════════════════

    /**
     * 组装完整的眼镜画面（最多 5 行）。兼容旧签名（剩余里程未知）。
     */
    public static String compose(Guidance current, String destination,
                                 double totalDistance, long totalDurationSeconds,
                                 int stepIndex, int stepCount) {
        return compose(current, destination, totalDistance, -1, totalDurationSeconds, stepIndex, stepCount);
    }

    /**
     * 组装完整的眼镜画面（最多 5 行）。
     *
     * 行序（信息密度从上到下递减）：
     *   ① 箭头 + 距离 + 动作   ← 开车时只看这一行
     *   ② 高德原话（核对用，已到达时省略）
     *   ③ 进度条 + 百分比      ← 一眼知道还有多远
     *   ④ 剩余里程 · 预计时间
     *   ⑤ 目的地
     *
     * @param remainingDistance 从当前位置到终点的剩余里程（米），未知传 -1
     */
    public static String compose(Guidance current, String destination,
                                 double totalDistance, double remainingDistance,
                                 long totalDurationSeconds, int stepIndex, int stepCount) {
        StringBuilder b = new StringBuilder();
        boolean arrived = current != null && current.arrived();
        if (current != null) {
            b.append(current.topLine);
            // 到达时 topLine 就是"已到达目的地"，再补一行原话纯属占地方。
            if (!arrived && current.detail != null && !current.detail.isEmpty()
                    && !current.detail.equals(current.topLine)) {
                b.append('\n').append(clipHan(current.detail, 20));
            }
        } else {
            b.append("↑ 等待定位…");
        }
        // ③ 进度条
        double ratio = progressRatio(totalDistance, remainingDistance);
        if (ratio >= 0) b.append('\n').append(bar(ratio));
        // ④ 剩余里程 + 预计时间
        String meta = metaLine(totalDistance, remainingDistance, totalDurationSeconds);
        if (!meta.isEmpty()) b.append('\n').append(meta);
        // ⑤ 目的地
        if (destination != null && !destination.isEmpty()) {
            b.append('\n').append("→ ").append(clipHan(destination, 12));
        }
        return b.toString();
    }

    /** 第 ④ 行：`剩 3.2 公里 · 约 8 分钟`（没有剩余里程时退回全程）。 */
    static String metaLine(double totalDistance, double remainingDistance, long totalDurationSeconds) {
        StringBuilder m = new StringBuilder();
        if (remainingDistance == 0) m.append("已到达终点");
        else if (remainingDistance > 0) m.append("剩 ").append(NavCore.meters(remainingDistance));
        else if (totalDistance > 0) m.append("全程 ").append(NavCore.meters(totalDistance));
        long eta = etaSeconds(totalDistance, remainingDistance, totalDurationSeconds);
        if (eta > 0) {
            if (m.length() > 0) m.append(" · ");
            m.append("约 ").append(Math.max(1, (eta + 59) / 60)).append(" 分钟");
        }
        return m.toString();
    }

    /**
     * 只取眼镜上最顶上那一行（停车/等待时用，屏幕更干净）。
     */
    public static String headline(Guidance current) {
        return current == null ? "↑ 等待定位…" : current.topLine;
    }

    /**
     * 样例画面。给「导航」页做"当前样式长这样"的预览用，
     * 也方便单测固定住 5 行排版。
     */
    public static String sample() {
        return compose(of("沿长风西街向西行驶500米，右转", 500),
            "太原南站", 8200, 3100, 900, 2, 9);
    }

    /**
     * 按指定样式渲染样例（临时切换，渲染完还原）。
     * 「眼镜显示样式」列表给每一项做实时预览用 —— 用户不用真切过去才知道长什么样。
     */
    public static String sampleFor(int styleIndex) {
        Style keep = style;
        try {
            setStyleIndex(styleIndex);
            return sample();
        } finally {
            style = keep;
        }
    }
}

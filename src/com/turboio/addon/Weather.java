package com.turboio.addon;

/**
 * 实时天气的纯逻辑核心（不碰 Android / 网络 / 反射，可 100% 单测）。
 *
 * ── 为什么单独一层 ──────────────────────────────────────────────
 * 天气数据来自和风天气的两三个 HTTP 接口，返回的是 JSON；
 * 但"怎么把 JSON 变成眼镜上那 5 行"是纯字符串问题。分出来以后：
 *   · 眼镜排版能被单测钉死（行数、行宽、GB2312 字符）；
 *   · 换接口/加字段只动 {@link WeatherUI}，不碰排版。
 *
 * ── 眼镜排版（硬约束）────────────────────────────────────────
 * 字幕通道只有 5 行、替换式、宽度有限，所以：
 *   · 固定 5 行，超了没有第 6 行可显示；
 *   · 每行都要 clip 到 {@link #LINE_MAX} 个全角字符以内；
 *   · 只用 GB2312 内的符号（中文固件画不出的字符 = 空白格）。
 */
public final class Weather {
    private Weather() {}

    /** 每行最多多少"全角字符"（与 ReaderUI 的行宽是同一套折算口径）。 */
    public static final int LINE_MAX = 20;
    /** 眼镜固定 5 行。 */
    public static final int LINES = 5;

    /** 一份"当前天气" + 今天的区间。字段缺失用空串，compose 会自动省略。 */
    public static final class Now {
        public final String city;        // 太原
        public final String text;        // 晴
        public final double temp;        // 24
        public final double feels;       // 22
        public final String humidity;    // 38
        public final String windDir;     // 西北
        public final String windScale;   // 3
        public final String precip;      // 0.0
        public final String pressure;    // 1002
        public final String vis;         // 25
        public final String obsTime;     // 15:20
        public final String todayMin;    // 12（可空）
        public final String todayMax;    // 26（可空）

        public Now(String city, String text, double temp, double feels, String humidity,
                   String windDir, String windScale, String precip, String pressure,
                   String vis, String obsTime, String todayMin, String todayMax) {
            this.city = nz(city); this.text = nz(text); this.temp = temp; this.feels = feels;
            this.humidity = nz(humidity); this.windDir = nz(windDir); this.windScale = nz(windScale);
            this.precip = nz(precip); this.pressure = nz(pressure); this.vis = nz(vis);
            this.obsTime = nz(obsTime); this.todayMin = nz(todayMin); this.todayMax = nz(todayMax);
        }

        private static String nz(String v) { return v == null ? "" : v; }
    }

    static String nz(String v) { return v == null ? "" : v; }

    /** ① 城市 + 天气现象。 */
    public static String headline(Now now) {
        if (now == null) return "◎ 天气";
        String tail = now.text.isEmpty() ? "" : " · " + now.text;
        return clip("◎ " + (now.city.isEmpty() ? "当前" : now.city) + tail, LINE_MAX);
    }

    /** ② 气温 + 体感。 */
    public static String temperature(Now now) {
        if (now == null) return "";
        StringBuilder b = new StringBuilder("气温 ").append(round(now.temp)).append("℃");
        if (now.feels > -100 && Math.abs(now.feels - now.temp) >= 1) {
            b.append(" · 体感 ").append(round(now.feels)).append("℃");
        }
        return clip(b.toString(), LINE_MAX);
    }

    /** ③ 湿度 + 风。 */
    public static String humidityWind(Now now) {
        if (now == null) return "";
        StringBuilder b = new StringBuilder();
        if (!now.humidity.isEmpty()) b.append("湿度 ").append(now.humidity).append("%");
        String wind = wind(now);
        if (!wind.isEmpty()) {
            if (b.length() > 0) b.append(" · ");
            b.append("风 ").append(wind);
        }
        return clip(b.toString(), LINE_MAX);
    }

    static String wind(Now now) {
        if (now == null) return "";
        StringBuilder w = new StringBuilder();
        if (!now.windDir.isEmpty()) w.append(now.windDir);
        if (!now.windScale.isEmpty() && !"0".equals(now.windScale)) w.append(' ').append(now.windScale).append("级");
        return w.toString().trim();
    }

    /** ④ 降水 + 气压。 */
    public static String precipPressure(Now now) {
        if (now == null) return "";
        StringBuilder b = new StringBuilder();
        if (!now.precip.isEmpty()) b.append("降水 ").append(now.precip).append("mm");
        if (!now.pressure.isEmpty()) {
            if (b.length() > 0) b.append(" · ");
            b.append("气压 ").append(now.pressure).append("hPa");
        }
        return clip(b.toString(), LINE_MAX);
    }

    /** ⑤ 能见度 + 今天区间（没有区间就退回观测时间）。 */
    public static String visibilityToday(Now now) {
        if (now == null) return "";
        StringBuilder b = new StringBuilder();
        if (!now.vis.isEmpty()) b.append("能见度 ").append(now.vis).append("km");
        String today = "";
        if (!now.todayMin.isEmpty() && !now.todayMax.isEmpty()) {
            today = "今日 " + now.todayMin + "~" + now.todayMax + "℃";
        } else if (!now.obsTime.isEmpty()) {
            today = "更新 " + now.obsTime;
        }
        if (!today.isEmpty()) {
            if (b.length() > 0) b.append(" · ");
            b.append(today);
        }
        return clip(b.toString(), LINE_MAX);
    }

    /**
     * 组装眼镜画面。**固定最多 5 行**，空行自动丢弃（不会因为某字段缺失就多出第 6 行）。
     */
    public static String compose(Now now) {
        String[] lines = {headline(now), temperature(now), humidityWind(now), precipPressure(now), visibilityToday(now)};
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            if (used >= LINES) break;                 // 兜底：绝不超过 5 行
            if (out.length() > 0) out.append('\n');
            out.append(line);
            used++;
        }
        if (out.length() == 0) return "◎ 没有拿到天气数据";
        return out.toString();
    }

    // ══════════════════════════════════════════════════════════════
    //  第 2 页：空气质量
    //
    //  和风 2025 改版后 v7/air/now 已废弃（实测 403 Deprecated），
    //  新版是 /airquality/v1/current/{lat}/{lon} —— **按经纬度查**，
    //  坐标从 /geo/v2/city/lookup 的返回里拿，不用再单独做地理编码。
    //  返回结构也换了：indexes[].aqi + pollutants[].concentration.value。
    // ══════════════════════════════════════════════════════════════

    /** 一份空气质量。 */
    public static final class Air {
        public final String aqi;       // 68
        public final String category;  // 良
        public final String primary;   // O3（首要污染物，可能为空）
        public final String pm25;      // 34
        public final String pm10;
        public final String o3, no2, so2, co;
        public final String advice;    // 健康建议（取"一般人群"那句，可能为空）

        public Air(String aqi, String category, String primary, String pm25, String pm10,
                   String o3, String no2, String so2, String co, String advice) {
            this.aqi = nz(aqi); this.category = nz(category); this.primary = nz(primary);
            this.pm25 = nz(pm25); this.pm10 = nz(pm10); this.o3 = nz(o3);
            this.no2 = nz(no2); this.so2 = nz(so2); this.co = nz(co); this.advice = nz(advice);
        }
    }

    /** ① AQI + 等级。 */
    public static String airHeadline(Air air) {
        if (air == null) return "◎ 空气质量";
        String tail = air.category.isEmpty() ? "" : " " + air.category;
        return clip("◎ 空气质量" + tail, LINE_MAX);
    }

    /** ② AQI + 首要污染物。 */
    public static String airIndex(Air air) {
        if (air == null) return "";
        StringBuilder b = new StringBuilder();
        if (!air.aqi.isEmpty()) b.append("AQI ").append(air.aqi);
        if (!air.primary.isEmpty()) {
            if (b.length() > 0) b.append(" · ");
            b.append("主要 ").append(air.primary);
        }
        return clip(b.toString(), LINE_MAX);
    }

    /** ③ 颗粒物。 */
    public static String airParticles(Air air) {
        if (air == null) return "";
        StringBuilder b = new StringBuilder();
        if (!air.pm25.isEmpty()) b.append("PM2.5 ").append(air.pm25);
        if (!air.pm10.isEmpty()) {
            if (b.length() > 0) b.append(" · ");
            b.append("PM10 ").append(air.pm10);
        }
        return clip(b.toString(), LINE_MAX);
    }

    /** ④ 气态污染物。 */
    public static String airGases(Air air) {
        if (air == null) return "";
        StringBuilder b = new StringBuilder();
        String[][] pairs = {{"O3", air.o3}, {"NO2", air.no2}, {"SO2", air.so2}, {"CO", air.co}};
        for (String[] p : pairs) {
            if (p[1] == null || p[1].isEmpty()) continue;
            if (b.length() > 0) b.append(" · ");
            b.append(p[0]).append(' ').append(p[1]);
        }
        return clip(b.toString(), LINE_MAX);
    }

    /** ⑤ 健康建议：太长就退回等级本身，绝不顶出第 6 行。 */
    public static String airAdvice(Air air) {
        if (air == null) return "";
        String a = air.advice.trim();
        if (a.isEmpty()) {
            return air.category.isEmpty() ? "" : "空气" + air.category;
        }
        // 和风的建议原文较长（"空气质量可接受，但某些污染物可能…"），
        // 只取第一个分句，读起来利索。
        int cut = a.indexOf('，');
        if (cut <= 0) cut = a.indexOf('。');
        String head = cut > 0 ? a.substring(0, cut) : a;
        String line = clip(head, LINE_MAX);
        // clip 会加省略号；如果第一个分句本身就超宽，说明这句不适合上眼镜，
        // 退回"空气XX"这种两三个字的短语。
        if (line.endsWith("…") && !air.category.isEmpty()) return "空气" + air.category;
        return line;
    }

    /** 眼镜上的空气质量页（≤5 行）。 */
    public static String composeAir(Air air) {
        if (air == null) return "◎ 没有拿到空气质量";
        return join(new String[]{airHeadline(air), airIndex(air), airParticles(air), airGases(air), airAdvice(air)});
    }

    // ══════════════════════════════════════════════════════════════
    //  第 3 页：未来 3 天
    // ══════════════════════════════════════════════════════════════

    /** 一天预报（足够眼镜用）。 */
    public static final class Day {
        public final String date;     // 09/18
        public final String textDay;  // 多云
        public final String min, max; // 12 / 26
        public final String sunrise, sunset;

        public Day(String date, String textDay, String min, String max, String sunrise, String sunset) {
            this.date = nz(date); this.textDay = nz(textDay); this.min = nz(min); this.max = nz(max);
            this.sunrise = nz(sunrise); this.sunset = nz(sunset);
        }
    }

    public static String dayLine(Day d) {
        if (d == null) return "";
        StringBuilder b = new StringBuilder();
        if (!d.date.isEmpty()) b.append(d.date).append(' ');
        if (!d.textDay.isEmpty()) b.append(d.textDay).append(' ');
        if (!d.min.isEmpty() && !d.max.isEmpty()) b.append(d.min).append('~').append(d.max).append("℃");
        return clip(b.toString().trim(), LINE_MAX);
    }

    /** 眼镜上的预报页：表头 + 最多 3 天 + 日出日落（≤5 行）。 */
    public static String composeDays(java.util.List<Day> days) {
        if (days == null || days.isEmpty()) return "◎ 没有拿到预报";
        StringBuilder out = new StringBuilder("◎ 未来 " + Math.min(3, days.size()) + " 天");
        int used = 1;
        for (Day d : days) {
            if (used >= 4) break;                  // 留一行给日出日落
            String line = dayLine(d);
            if (line.isEmpty()) continue;
            out.append('\n').append(line);
            used++;
        }
        // 第 5 行：今天（第一天）的日出日落，比再挤一天更有信息量。
        Day first = days.get(0);
        if (used < LINES && !first.sunrise.isEmpty() && !first.sunset.isEmpty()) {
            out.append('\n').append(clip("日出 " + first.sunrise + " · 日落 " + first.sunset, LINE_MAX));
        }
        return out.toString();
    }

    // ══════════════════════════════════════════════════════════════
    //  第 4 页：今日生活指数
    // ══════════════════════════════════════════════════════════════

    /** 一条生活指数（穿衣 / 紫外线 / 运动 …）。 */
    public static final class Index {
        public final String name;   // 穿衣
        public final String level;  // 舒适

        public Index(String name, String level) {
            this.name = nz(name); this.level = nz(level);
        }
    }

    /** 眼镜上的生活指数页：表头 + 最多 4 条（≤5 行）。 */
    public static String composeIndices(java.util.List<Index> list) {
        if (list == null || list.isEmpty()) return "◎ 没有拿到生活指数";
        StringBuilder out = new StringBuilder("◎ 今日生活指数");
        int used = 1;
        for (Index it : list) {
            if (used >= LINES) break;
            if (it.name.isEmpty() || it.level.isEmpty()) continue;
            out.append('\n').append(clip(it.name + " " + it.level, LINE_MAX));
            used++;
        }
        return out.toString();
    }

    /** 把若干行拼成画面（丢空行、绝不超过 5 行）。 */
    static String join(String[] lines) {
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            if (used >= LINES) break;
            if (out.length() > 0) out.append('\n');
            out.append(line);
            used++;
        }
        return out.length() == 0 ? "◎ 没有拿到数据" : out.toString();
    }

    /**
     * 域名归一化：容忍用户直接粘贴整条 URL（带 https:// 或尾部路径），只留主机名。
     *
     * 这是配置里最容易错的一栏 —— 从控制台复制 API Host 时很容易带上
     * `https://` 或结尾斜杠，多一个字符整条请求就 404/403。
     * 做成纯逻辑放在这里，是为了让单测把各种粘贴形态都钉死。
     */
    public static String normalizeHost(String raw) {
        if (raw == null) return "";
        String h = raw.trim();
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int query = h.indexOf('?');
        if (query >= 0) h = h.substring(0, query);
        h = h.replace(" ", "").replace("\t", "");
        return h.trim();
    }

    /** 数字取整成"24"这种短串（23.6 → 24，避免行宽被小数吃掉）。 */
    static String round(double v) {        if (!Double.isFinite(v)) return "—";
        return String.valueOf(Math.round(v));
    }

    // ── 和风返回值的压缩（放在这里而不是 WeatherUI，才能被单测钉死）──

    /** 和风返回 "西北风" → 眼镜上省两个字变成 "西北"。 */
    public static String stripWind(String dir) {
        if (dir == null) return "";
        String d = dir.trim();
        return d.endsWith("风") ? d.substring(0, d.length() - 1) : d;
    }

    /** 去掉小数部分（和风给的是字符串数字，"12.0" → "12"）。 */
    public static String trimNum(String v) {
        if (v == null) return "";
        String t = v.trim();
        int dot = t.indexOf('.');
        return dot > 0 ? t.substring(0, dot) : t;
    }

    /** ISO 时间 → "15:20"。 */
    public static String shortTime(String iso) {
        if (iso == null) return "";
        String s = iso.trim();
        int t = s.indexOf('T');
        if (t < 0) return s;
        String time = s.substring(t + 1);
        return time.length() >= 5 ? time.substring(0, 5) : time;
    }

    /** "2026-09-18" → "09/18"。 */
    public static String shortDate(String iso) {
        if (iso == null) return "";
        String s = iso.trim();
        if (s.length() >= 10) return s.substring(5, 7) + "/" + s.substring(8, 10);
        return s;
    }

    /** 按"全角字符"折算截断（半角算半个），超长加省略号。 */
    public static String clip(String text, int max) {
        return TextPage.clip(text, max);
    }

    // ══════════════════════════════════════════════════════════════
    //  语音
    // ══════════════════════════════════════════════════════════════

    /** 是不是一句"问天气"的话。 */
    public static boolean isWeatherQuery(String raw) {
        if (raw == null) return false;
        String q = raw.trim();
        if (q.isEmpty()) return false;
        return q.contains("天气") || q.contains("气温") || q.contains("多少度")
            || q.contains("下雨") || q.contains("要带伞") || q.contains("空气质量");
    }

    /**
     * 从一句话里抠出城市名。抠不到返回空串（= 用当前位置）。
     *
     * 覆盖：「太原天气」「查一下太原的天气」「北京今天天气怎么样」
     * 刻意**不**做行政区划词典（那需要一张表），只用前后缀裁剪 ——
     * 和风天气的 city/lookup 本身就能吃"太原"这种简称，模糊一点没关系。
     */
    public static String voiceCity(String raw) {
        if (raw == null) return "";
        String q = raw.trim();
        if (q.isEmpty()) return "";
        String[] heads = {"查一下", "查查", "查询", "帮我查", "帮我看看", "看看", "今天", "明天", "现在", "请问"};
        for (String h : heads) {
            if (q.startsWith(h)) { q = q.substring(h.length()); break; }
        }
        String[] tails = {"今天天气怎么样", "明天天气怎么样", "天气怎么样", "的天气怎么样", "天气如何",
            "今天天气", "明天天气", "的天气", "天气", "气温", "多少度", "温度",
            "会不会下雨", "下雨吗", "要带伞吗", "空气质量怎么样", "空气质量"};
        for (String t : tails) {
            int i = q.indexOf(t);
            if (i >= 0) q = q.substring(0, i);
        }
        q = q.replace("的", "").replace("市", "").trim();
        // 剩下的如果还带标点或太长，就不当成城市（宁可问用户，也别查错城市）。
        if (q.isEmpty() || q.length() > 12) return "";
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (!(c >= 0x4E00 && c <= 0x9FFF)) return "";
        }
        return q;
    }
}

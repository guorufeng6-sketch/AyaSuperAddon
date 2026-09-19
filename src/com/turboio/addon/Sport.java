package com.turboio.addon;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 健身运动（跑步 / 骑行）的**纯逻辑层**：状态机 + 距离累加（haversine）+ 进度与格式化。
 *
 * 不碰任何 Android API，可进单测（见 runtests.sh 的 PURE 列表）。
 * 定位点的采集在 {@link SportUI} 里（需要 LocationManager），这里只负责"拿到经纬度后怎么算"。
 *
 * ── 为什么独立成纯逻辑 ──────────────────────────────────────────────
 * 距离/进度是最容易算错的地方（单位换算、单点 GPS 抖动、会话时长），
 * 必须能脱离手机跑单测。语音口令解析（"跑步 1 公里" → 类型 + 目标米数）也一样。
 */
public final class Sport {

    public enum Type {
        RUN("跑步", "\uD83C\uDFC3"),   // 🏃
        RIDE("骑行", "\uD83D\uDEB4");  // 🚴
        public final String label, icon;
        Type(String l, String i) { label = l; icon = i; }
    }

    public enum Action { NONE, START, STATUS, STOP; }

    /** 解析口令的结果。 */
    public static final class SportCmd {
        public final Action action;
        public final Type type;
        public final int goalMeters; // 0 = 无目标
        SportCmd(Action a, Type t, int g) { action = a; type = t; goalMeters = g; }
    }

    /** 一次运动的历史记录（纯数据，持久化由 SportUI 负责）。 */
    public static final class Record {
        public final Type type;
        public final long startedAt;       // 开始时刻（epoch ms）
        public final long elapsedMs;
        public final double distanceMeters;
        public final double avgSpeedKmh;    // 整段平均速度（km/h）
        public final int goalMeters;        // 0 = 无目标
        public Record(Type type, long startedAt, long elapsedMs, double distanceMeters, double avgSpeedKmh, int goalMeters) {
            this.type = type; this.startedAt = startedAt; this.elapsedMs = elapsedMs;
            this.distanceMeters = distanceMeters; this.avgSpeedKmh = avgSpeedKmh; this.goalMeters = goalMeters;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  状态（单一实例，由 SportUI 持有）
    // ══════════════════════════════════════════════════════════════
    private Type type;
    private long startMs, stopMs;
    private boolean running, stopped;
    private int goalMeters;
    private double totalMeters;
    private double lastLat, lastLon;
    private boolean hasLast;
    /** 上一次有效定位的时间戳（ms），用于算瞬时速度。 */
    private long lastFixMs;
    /** 平滑后的瞬时速度（m/s）；单点噪声与离谱尖峰都被过滤。 */
    private double speedMps;

    public void start(Type t, int goalMeters) {
        this.type = t;
        this.startMs = System.currentTimeMillis();
        this.stopMs = 0;
        this.running = true;
        this.stopped = false;
        this.goalMeters = goalMeters;
        this.totalMeters = 0;
        this.hasLast = false;
    }

    /** 喂一个定位点。running 之外（已结束）的点不计入。 */
    public void addPoint(double lat, double lon) {
        long now = System.currentTimeMillis();
        if (!running) { hasLast = false; lastFixMs = 0; speedMps = 0; return; }
        if (hasLast) {
            double d = haversine(lastLat, lastLon, lat, lon);
            // 过滤单点剧烈跳变（室内漂移 / 隧道里 GPS 抽风）：>200m 视为无效。
            if (d >= 0 && d <= 200) {
                totalMeters += d;
                if (lastFixMs > 0) {
                    double dt = (now - lastFixMs) / 1000.0;   // 秒
                    // 合理采样间隔：0.2s ~ 60s（太快=同点抖动，太慢=两次定位隔太久没意义）
                    if (dt > 0.2 && dt < 60) {
                        double inst = d / dt;                  // m/s
                        if (inst < 50) {                       // 忽略 > 180km/h 的离谱尖峰
                            speedMps = speedMps <= 0 ? inst : (speedMps * 0.7 + inst * 0.3);
                        }
                    }
                }
            }
        }
        lastLat = lat; lastLon = lon; hasLast = true; lastFixMs = now;
    }

    public void stop() {
        if (running) stopMs = System.currentTimeMillis();
        running = false;
        stopped = true;
    }

    public long elapsedMs() {
        return running ? System.currentTimeMillis() - startMs : (stopMs - startMs);
    }
    public double distanceMeters() { return totalMeters; }
    public boolean hasGoal() { return goalMeters > 0; }
    public int goalMeters() { return goalMeters; }
    public int goalPercent() {
        return goalMeters <= 0 ? 0 : (int) Math.min(999, totalMeters / goalMeters * 100);
    }
    public Type type() { return type; }
    public boolean running() { return running; }
    public boolean stopped() { return stopped; }
    /** 已结束但还有数据（可查历史），区别于"从没开始过"。 */
    public boolean hasRecord() { return stopped && totalMeters > 0; }

    /** 平滑后的瞬时速度（km/h）。 */
    public double currentSpeedKmh() { return speedMps * 3.6; }
    /** 平均速度（km/h）：总距离 / 总时长；没数据返回 0。 */
    public double avgSpeedKmh() {
        long sec = elapsedMs() / 1000;
        return (sec > 0 && totalMeters > 0) ? (totalMeters / sec * 3.6) : 0;
    }
    /** 当前配速（秒/公里），由平滑瞬时速度换算；静止返回 0。 */
    public int paceSecPerKm() {
        double kmh = currentSpeedKmh();
        if (kmh < 0.2) return 0;
        return (int) Math.round(1000.0 / (kmh / 3.6));
    }
    /** 本次开始的时刻（epoch ms），给历史记录用。 */
    public long startedAt() { return startMs; }

    // ══════════════════════════════════════════════════════════════
    //  格式化
    // ══════════════════════════════════════════════════════════════

    /** 眼镜 / 状态卡用的进度画面：进度条 + 速度 + 配速。字符只用镜片画得出的（■/□）。 */
    public String hud() {
        if (type == null) return "运动未开始";
        String t = fmtTime(elapsedMs());
        String d = String.format(Locale.ROOT, "%.2f", totalMeters / 1000.0);
        String bar = NavGuide.barCells(goalMeters > 0 ? Math.min(1.0, totalMeters / goalMeters) : 0, 8);
        String speed = "速度 " + String.format(Locale.ROOT, "%.1f", currentSpeedKmh()) + "km/h";
        String pace = "配速 " + fmtPace(paceSecPerKm());
        if (goalMeters > 0) {
            String g = String.format(Locale.ROOT, "%.2f", goalMeters / 1000.0);
            return type.icon + " " + type.label + " " + t + "\n"
                    + bar + " " + goalPercent() + "%\n"
                    + d + "/" + g + "km · " + speed + " · " + pace;
        }
        return type.icon + " " + type.label + " " + t + "\n"
                + d + " km · " + speed + " · " + pace;
    }

    /** 配速格式化：277s → 4'37"；0/非法 → "--'--"。 */
    public static String fmtPace(int secPerKm) {
        if (secPerKm <= 0) return "--'--\"";
        int m = secPerKm / 60, s = secPerKm % 60;
        return m + "'" + (s < 10 ? "0" : "") + s + "\"";
    }

    public static String fmtTime(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, ss);
        return String.format(Locale.ROOT, "%02d:%02d", m, ss);
    }

    /** 地球两点球面距离（米）。 */
    public static double haversine(double la1, double lo1, double la2, double lo2) {
        final int R = 6371000;
        double dLat = Math.toRadians(la2 - la1);
        double dLon = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ══════════════════════════════════════════════════════════════
    //  口令解析（与语音链路同一把尺子：先压平空白）
    // ══════════════════════════════════════════════════════════════

    private static final Pattern GOAL = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|米|m)");

    public static SportCmd parse(String q) {
        if (q == null) return new SportCmd(Action.NONE, null, 0);
        String s = q.replaceAll("[\\s　]+", "");

        // 停止 / 结束（放在最前，避免被下面的类型判定抢走）
        if (s.contains("停止运动") || s.contains("结束运动") || s.contains("结束跑步")
                || s.contains("结束骑行") || s.contains("停运动") || s.contains("退出运动")
                || s.contains("停止跑步") || s.contains("停止骑行"))
            return new SportCmd(Action.STOP, null, 0);

        // 查询进度
        if (s.contains("运动进度") || s.contains("跑到哪") || s.contains("骑到哪")
                || s.contains("运动多久") || s.contains("运动多少") || s.contains("运动情况")
                || s.contains("运动到哪") || s.contains("还差多少"))
            return new SportCmd(Action.STATUS, null, 0);

        // 开始：先判类型
        Type type = null;
        if (s.contains("跑步") || s.contains("跑")) type = Type.RUN;
        else if (s.contains("骑行") || s.contains("骑车") || s.contains("自行车")) type = Type.RIDE;
        if (type == null) return new SportCmd(Action.NONE, null, 0);

        // 目标：数字 + 单位
        int goal = 0;
        Matcher m = GOAL.matcher(s);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1));
            String u = m.group(2);
            if (u.equals("米") || u.equals("m")) goal = (int) v;
            else goal = (int) (v * 1000);
        }
        return new SportCmd(Action.START, type, goal);
    }
}

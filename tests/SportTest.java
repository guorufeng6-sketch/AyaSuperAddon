import com.turboio.addon.Sport;

/**
 * 运动追踪（跑步 / 骑行）纯逻辑层。
 *
 * 重点钉五件事：
 *   ① **haversine**：球面距离不能算错（1° 纬度 ≈ 111km，这是最直观的基准）；
 *   ② **口令解析**：「跑步 / 骑行 / 停止运动 / 运动进度」判对，无关句不接管；
 *   ③ **单位换算**：「1 公里」→ 1000m、「2.5 公里」→ 2500m、「500 米」→ 500m；
 *   ④ **状态机 + 进度**：开始→累计距离→进度封顶 999%→停止有记录；
 *   ⑤ **GPS 跳变过滤**：单点 >200m 的漂移点（没有中间采样）不得计入距离。
 *
 * ⚠️ 距离累计用的是"真实 GPS 采样"口径：每步 ≈ 100m（1Hz 采样、跑步/骑行速度下
 *    单步就是几米到百来米），而不是"两帧之间瞬移 111km"——后者正是 GPS 抽风，
 *    会被 200m 的防漂移阈值挡掉，这是预期行为，不是 bug。
 */
public class SportTest {
    private static int checks = 0;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(Object expected, Object actual, String message) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    public static void main(String[] args) {
        // ── ① haversine ──
        double d1 = Sport.haversine(0.0, 0.0, 1.0, 0.0);
        ok(Math.abs(d1 - 111195.0) < 200, "1° 纬度 ≈ 111195m，实际 " + d1);
        eq(0.0, Sport.haversine(0, 0, 0, 0), "同点距离 0");
        double d2 = Sport.haversine(0.0, 0.0, 0.0, 1.0);
        ok(d2 > 110000 && d2 < 112000, "1° 经度（赤道附近）≈ 111km，实际 " + d2);

        // ── ② 口令解析：类型 ──
        eq(Sport.Action.START, Sport.parse("开始跑步").action, "开始跑步 = START");
        eq(Sport.Type.RUN, Sport.parse("开始跑步").type, "跑步 = RUN");
        eq(Sport.Type.RIDE, Sport.parse("开始骑行").type, "骑行 = RIDE");
        eq(Sport.Action.START, Sport.parse("跑步").action, "裸「跑步」= START");
        eq(Sport.Action.STOP, Sport.parse("停止运动").action, "停止运动 = STOP");
        eq(Sport.Action.STOP, Sport.parse("结束跑步").action, "结束跑步 = STOP");
        eq(Sport.Action.STATUS, Sport.parse("运动进度").action, "运动进度 = STATUS");
        eq(Sport.Action.STATUS, Sport.parse("跑到哪了").action, "跑到哪了 = STATUS");
        eq(Sport.Action.NONE, Sport.parse("导航到太原").action, "无关句不接管");
        eq(Sport.Action.NONE, Sport.parse("").action, "空串不接管");
        eq(Sport.Action.NONE, Sport.parse(null).action, "null 不接管");

        // ── ③ 单位换算 ──
        eq(1000, Sport.parse("跑步 1公里").goalMeters, "1 公里 = 1000m");
        eq(2500, Sport.parse("骑行 2.5公里").goalMeters, "2.5 公里 = 2500m");
        eq(500, Sport.parse("跑步 500米").goalMeters, "500 米 = 500m");
        eq(0, Sport.parse("开始跑步").goalMeters, "无目标 = 0");

        // ── ④ 状态机 + 真实距离累计（每步 ≈100m，模拟 1Hz GPS 采样）──
        Sport s = new Sport();
        eq(false, s.running(), "未开始 running=false");
        s.start(Sport.Type.RUN, 1000);
        eq(true, s.running(), "开始后 running=true");
        eq(true, s.hasGoal(), "有目标");
        eq(1000, s.goalMeters(), "目标米数");
        s.addPoint(0.0, 0.0);
        s.addPoint(0.0009, 0.0);  // ≈ 100m
        s.addPoint(0.0018, 0.0);  // 再 ≈ 100m，累计 ≈ 200m
        ok(Math.abs(s.distanceMeters() - 200.0) < 30, "距离累计 ≈ 200m，实际 " + s.distanceMeters());
        ok(s.goalPercent() >= 15 && s.goalPercent() <= 30, "进度约 20%，实际 " + s.goalPercent());
        s.stop();
        eq(false, s.running(), "停止后 running=false");
        eq(true, s.stopped(), "stopped=true");
        eq(true, s.hasRecord(), "有记录");

        // ── ④b 目标达成度封顶 999%（小步累计到远超目标）──
        Sport sc = new Sport();
        sc.start(Sport.Type.RUN, 100);
        sc.addPoint(0.0, 0.0);
        for (int i = 1; i <= 20; i++) sc.addPoint(0.001 * i, 0.0); // 总计 ≈ 2.2km
        eq(999, sc.goalPercent(), "目标达成度应封顶 999%，实际 " + sc.goalPercent());

        // ── ⑤ GPS 跳变过滤（单点瞬移 >200m，没有中间采样）──
        Sport s2 = new Sport();
        s2.start(Sport.Type.RUN, 0);
        s2.addPoint(0.0, 0.0);
        s2.addPoint(10.0, 10.0); // ≈ 1500km 单点跳变，应被忽略
        eq(0.0, s2.distanceMeters(), "GPS 跳变点应被过滤");

        // ── ⑥ fmtTime ──
        eq("00:00", Sport.fmtTime(0), "0s");
        eq("01:05", Sport.fmtTime(65000), "65s");
        eq("1:01:01", Sport.fmtTime(3661000), "1h1m1s");

        // ── ⑦ hud 格式（小步累计到 ≈0.9km）──
        Sport h = new Sport();
        h.start(Sport.Type.RUN, 1000);
        h.addPoint(0.0, 0.0);
        for (int i = 1; i <= 9; i++) h.addPoint(0.0009 * i, 0.0); // ≈ 0.9km
        String hud = h.hud();
        ok(hud.contains("跑步"), "hud 含类型，实际 " + hud);
        ok(hud.contains("km"), "hud 含 km，实际 " + hud);
        ok(hud.contains("%"), "hud 含百分比，实际 " + hud);

        // ── ⑧ fmtPace ──
        eq("--'--\"", Sport.fmtPace(0), "静止配速 = --'--\"");
        eq("4'37\"", Sport.fmtPace(277), "277s → 4'37\"");
        eq("1'00\"", Sport.fmtPace(60), "60s → 1'00\"");
        eq("5'00\"", Sport.fmtPace(300), "300s → 5'00\"");

        // ── ⑨ Record 数据类（纯数据，持久化由 SportUI 负责）──
        Sport.Record r = new Sport.Record(Sport.Type.RUN, 1700000000000L, 600000, 1250.5, 12.5, 1000);
        eq(Sport.Type.RUN, r.type, "record type");
        ok(Math.abs(r.distanceMeters - 1250.5) < 1e-6, "record distance");
        ok(Math.abs(r.avgSpeedKmh - 12.5) < 1e-6, "record avg speed");
        eq(1000, r.goalMeters, "record goal");

        // ── ⑩ hud 含速度/配速（无目标分支也要有「速度」「配速」）──
        Sport h2 = new Sport();
        h2.start(Sport.Type.RIDE, 0);
        String hud2 = h2.hud();
        ok(hud2.contains("速度"), "hud 含速度，实际 " + hud2);
        ok(hud2.contains("配速"), "hud 含配速，实际 " + hud2);
        // 有目标分支含进度条（■/□，镜片画得出）与百分比
        Sport h3 = new Sport();
        h3.start(Sport.Type.RUN, 1000);
        h3.addPoint(0.0, 0.0);
        for (int i = 1; i <= 5; i++) h3.addPoint(0.0009 * i, 0.0); // ≈ 0.5km / 1000m 目标 → ~50%
        String hud3 = h3.hud();
        ok(hud3.contains("■") || hud3.contains("□"), "hud 含进度条，实际 " + hud3);
        ok(hud3.contains("%"), "hud 含百分比，实际 " + hud3);

        System.out.println("Sport: " + checks
            + " checks PASS（haversine · parse · 单位换算 · 状态机 · 跳变过滤 · fmtTime · hud · fmtPace · record · 速度配速）");
    }
}

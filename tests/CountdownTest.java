// Countdown 纯逻辑单测（不碰 Android）
import com.turboio.addon.Countdown;
public class CountdownTest {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL: " + name); }
    }
    static void eq(String name, Object got, Object exp) {
        boolean ok = java.util.Objects.equals(got, exp);
        if (!ok) System.out.println("  FAIL: " + name + " got=" + got + " exp=" + exp);
        if (ok) pass++; else fail++;
    }

    public static void main(String[] a) {
        // clock 格式化
        eq("clock 0", Countdown.clock(0), "00:00");
        eq("clock 5", Countdown.clock(5), "00:05");
        eq("clock 65", Countdown.clock(65), "01:05");
        eq("clock 3661", Countdown.clock(3661), "1:01:01");
        eq("clock neg", Countdown.clock(-3), "00:00");

        // wallClock 格式（只验证形状，不验证具体时刻）
        check("wallClock 格式", Countdown.wallClock(1000L).matches("\\d{2}:\\d{2}:\\d{2}"));
        check("wallClockShort 格式", Countdown.wallClockShort(1000L).matches("\\d{2}:\\d{2}"));

        // bar —— 字符来自 NavGuide 当前样式；默认 SAFE 只用 GB2312 内的 ■ / □。
        // 以前是 ▓ / ░，其中 ░(U+2591) 连 GBK 都不在码表里，眼镜上那一格是空白。
        eq("bar 0", Countdown.bar(0, 10), "□□□□□□□□□□");
        eq("bar 1", Countdown.bar(1, 10), "■■■■■■■■■■");
        eq("bar half", Countdown.bar(0.5, 10), "■■■■■□□□□□");

        // Timer
        long now = 1_000_000L;
        Countdown.Timer t = new Countdown.Timer("煮面", 5 * 60_000L, now);
        eq("remaining 初始=5min", t.remaining(now), 5 * 60_000L);
        eq("elapsed 初始=0", t.elapsed(now), 0L);
        check("not finished", !t.finished(now));
        check("not paused", !t.paused());
        long mid = now + 2 * 60_000L; // +2min
        eq("remaining 2min后=3min", t.remaining(mid), 3 * 60_000L);
        eq("elapsed 2min后=2min", t.elapsed(mid), 2 * 60_000L);
        check("progress ~0.4", Math.abs(t.progress(mid) - 0.4) < 1e-9);
        long end = now + 10 * 60_000L; // 超过总时长
        check("finished 之后", t.finished(end));
        eq("remaining 之后=0", t.remaining(end), 0L);

        // 暂停/恢复
        long p = now + 60_000L;
        t.pause(p);
        check("paused", t.paused());
        eq("elapsed 暂停时停在 1min", t.elapsed(p + 999_000L), 60_000L);
        t.resume(p + 999_000L);
        check("resumed", !t.paused());
        eq("elapsed 恢复后累计", t.elapsed(p + 999_000L + 30_000L), 90_000L);

        // parseDuration
        eq("parse 5分钟", Countdown.parseDuration("倒计时5分钟"), 300L);
        eq("parse 30秒", Countdown.parseDuration("定时30秒"), 30L);
        eq("parse 1小时", Countdown.parseDuration("倒计时1小时"), 3600L);
        eq("parse 1小时30分", Countdown.parseDuration("倒计时1小时30分钟"), 5400L);
        eq("parse 90分钟", Countdown.parseDuration("计时90分钟"), 5400L);
        eq("parse 番茄钟", Countdown.parseDuration("来个番茄钟"), 1500L);
        eq("parse 取消", Countdown.parseDuration("取消倒计时"), -1L);
        eq("parse 没时长→-2", Countdown.parseDuration("倒计时"), -2L);
        eq("parse 无关→0", Countdown.parseDuration("今天天气不错"), 0L);
        eq("parse 上限一天", Countdown.parseDuration("倒计时99小时"), 24L * 3600L);

        // isCountdownCommand
        check("cmd 5分钟", Countdown.isCountdownCommand("倒计时5分钟"));
        check("cmd 非", !Countdown.isCountdownCommand("今天天气不错"));

        // parseName
        eq("name 煮面", Countdown.parseName("倒计时5分钟煮面"), "煮面");
        eq("name 默认", Countdown.parseName("倒计时5分钟"), "倒计时");
        eq("name 停车", Countdown.parseName("定时30分钟停车"), "停车");

        // compose
        Countdown.Timer t2 = new Countdown.Timer("番茄钟", 25 * 60_000L, now);
        String c = Countdown.compose(t2, now + 60_000L, now + 60_000L);
        check("compose 含剩", c.contains("● 剩"));
        check("compose 含名字", c.contains("番茄钟"));
        check("compose 含现在", c.contains("现在"));
        check("compose 含进度条", c.contains("□") && c.contains("%"));
        check("compose 不超过 5 行", c.split("\n", -1).length <= 5);
        String cDone = Countdown.compose(t2, now + 100 * 60_000L, now + 100 * 60_000L);
        check("compose 时间到", cDone.contains("时间到"));
        String cClock = Countdown.compose(null, now, now);
        check("compose 纯时钟", cClock.contains("◎") && cClock.matches("◎ \\d{2}:\\d{2}:\\d{2}"));
        // ★ 眼镜只画得出 GB2312 内的符号 —— 混进 emoji / 块元素就是空白一格。
        String gb2312 = "←↑→↓●○◎◇◆■□▲△★☆※";
        for (String frame : new String[]{c, cDone, cClock}) {
            for (char ch : frame.toCharArray()) {
                if (ch < 128 || ch == ' ') continue;
                if (Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) continue;
                if (gb2312.indexOf(ch) >= 0) continue;
                if ("·：".indexOf(ch) >= 0) continue;          // 中文标点，GB2312 有
                throw new AssertionError("倒计时眼镜画面含非 GB2312 符号 <" + ch + ">，会变空白："
                    + frame.replace('\n', '|'));
            }
        }

        // ── keepBeating：心跳存活判定 ────────────────────────────────
        // ★ 这一步是血换来的：旧实现"当前没有倒计时"时**不排下一次心跳**，
        //   而页面一打开正是这个状态 → 循环活 1 秒就死；
        //   用户接着点「5 分钟」时 timer 有了，却已经没人刷新手机端、也没人推眼镜，
        //   现象就是「App 上数字不动 + 眼镜上画面不动」。下面把规则钉死。
        check("beat 页面开着就必须继续（哪怕还没有倒计时）",
            Countdown.keepBeating(false, false, true, true, -1));
        check("beat 空闲 + 页面关 → 收工",
            !Countdown.keepBeating(false, false, true, false, -1));
        check("beat 倒计时在跑 + 页面关 → 继续（眼镜上要读秒）",
            Countdown.keepBeating(true, false, true, false, -1));
        check("beat 不推眼镜 + 页面关 → 收工",
            !Countdown.keepBeating(true, false, false, false, -1));
        check("beat 结束后 10 秒内 → 继续挂\"时间到\"",
            Countdown.keepBeating(true, true, true, false, 9_000L));
        check("beat 结束后满 10 秒 → 停",
            !Countdown.keepBeating(true, true, true, false, Countdown.KEEP_AFTER_FINISH_MS));
        check("beat 结束但页面开着 → 继续（时钟还要走）",
            Countdown.keepBeating(true, true, false, true, 999_999L));

        // ── endedAt：结束时刻，"时间到"那屏靠它算挂了多久 ──
        Countdown.Timer t3 = new Countdown.Timer("煮面", 5 * 60_000L, now);
        eq("endedAt 无暂停", t3.endedAt(), now + 5 * 60_000L);
        t3.pause(now + 60_000L);
        t3.resume(now + 120_000L);          // 暂停 1 分钟
        eq("endedAt 含暂停", t3.endedAt(), now + 6 * 60_000L);

        System.out.println("Countdown: " + pass + " checks PASS" + (fail == 0 ? "" : (" / " + fail + " FAIL")));
        if (fail > 0) System.exit(1);    }
}

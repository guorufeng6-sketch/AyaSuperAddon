package com.turboio.addon;

import java.util.*;

/**
 * 倒计时 + 眼镜端时间显示的纯逻辑核心。
 *
 * ── 用户需求（第 7 点）─────────────────────────────────────────
 *   "倒计时的接入。最好带眼镜时间显示。"
 *
 * 拆成两件事：
 *   ① 一个倒计时（番茄钟 / 煮面 / 停车 / 小睡…）跑在 App 侧；
 *   ② 眼镜端每 N 秒看到「剩余 MM:SS」+ 当前时间，让你不用掏手机。
 *
 * ── 眼镜上怎么排（最多 5 行）───────────────────────────────────
 *   ⏳ 剩 04:32          ← 第一行：剩余时间（大字位置）
 *   番茄钟 · 专注        ← 第二行：名字
 *   ▓▓▓▓▓▓░░░░ 45%      ← 第三行：进度条（纯字符，一眼看进度）
 *   现在 21:47:03        ← 第四行：当前时间（用户明确要的"眼镜时间显示"）
 *   预计 21:51:35 结束   ← 第五行：结束时刻
 *
 * 本类不碰 Android，可 100% 单测覆盖（时间靠注入的 now 参数）。
 */
public final class Countdown {
    private Countdown() {}

    /** 一次倒计时任务。 */
    public static final class Timer {
        public final String name;
        public final long totalMillis;
        private final long startedAt;
        private long pausedAt;      // >0 表示已暂停，值是暂停时刻
        private long pausedTotal;   // 累计已暂停时长

        public Timer(String name, long totalMillis, long now) {
            if (totalMillis <= 0) throw new IllegalArgumentException("totalMillis");
            this.name = name == null || name.trim().isEmpty() ? "倒计时" : name.trim();
            this.totalMillis = totalMillis;
            this.startedAt = now;
        }

        /** 已过时间（不含暂停）。 */
        public long elapsed(long now) {
            long reference = pausedAt > 0 ? pausedAt : now;
            long raw = reference - startedAt - pausedTotal;
            return Math.max(0, Math.min(totalMillis, raw));
        }
        public long remaining(long now) {
            return Math.max(0, totalMillis - elapsed(now));
        }
        public boolean finished(long now) { return remaining(now) <= 0; }
        /**
         * 结束时刻（epoch millis）= 起始 + 总时长 + **已完成**的暂停累计。
         * 心跳靠它算"已经结束多久了"，从而决定"时间到"那屏还要不要继续显示。
         * 注意：正在暂停中时这里不会推进（要推进得把当前这段暂停也加上），
         * 但暂停中的 timer 不会 finished，所以不影响判断。
         */
        public long endedAt() { return startedAt + totalMillis + pausedTotal; }
        public boolean paused() { return pausedAt > 0; }
        public double progress(long now) {
            return totalMillis <= 0 ? 1 : (double) elapsed(now) / totalMillis;
        }
        public void pause(long now) {
            if (pausedAt > 0 || finished(now)) return;
            pausedAt = now;
        }
        public void resume(long now) {
            if (pausedAt <= 0) return;
            pausedTotal += Math.max(0, now - pausedAt);
            pausedAt = 0;
        }
    }

    // ── 常用时长（秒）──
    public static final int[] PRESETS = {60, 180, 300, 600, 1500};   // 1分 3分 5分 10分 25分（番茄钟）
    public static final String[] PRESET_NAMES = {"1 分钟", "3 分钟", "5 分钟", "10 分钟", "25 分钟 番茄钟"};

    // ══════════════════════════════════════════════════════════════
    //  心跳循环的存活判定 —— ★ 本轮最重要的修复就在这里 ★
    // ══════════════════════════════════════════════════════════════

    /** 倒计时结束后，"时间到"那屏还继续挂在眼镜上的时长。 */
    public static final long KEEP_AFTER_FINISH_MS = 10000L;

    /**
     * 1 秒心跳**要不要排下一次**。
     *
     * ── 为什么单独立成纯函数 ──────────────────────────────────────
     * 这里曾经有个把整个功能搞死的逻辑漏洞：旧实现在"当前没有倒计时在跑"
     * 时**不排下一次**，而页面一打开时正是这个状态 —— 循环活 1 秒就死。
     * 用户接着点「5 分钟」时 `timer` 有了，但已经**没人刷新手机端、也没人推眼镜**，
     * 现象恰好是「App 上数字不动 / 眼镜上画面不动」。
     *
     * 所以规则写死成一句：**页面开着就必须继续**（要显示实时时钟，
     * 而且用户随时会点时长）；页面关了则只要还有倒计时在推就继续，
     * 倒计时结束后再多显示 {@link #KEEP_AFTER_FINISH_MS} 的"时间到"。
     *
     * @param hasTimer        当前有没有倒计时
     * @param finished        倒计时是否已结束
     * @param pushing         是否正在推眼镜
     * @param pageOpen        倒计时页面是否开着
     * @param sinceFinishedMs 距结束已过多久（未结束传任意负数）
     */
    public static boolean keepBeating(boolean hasTimer, boolean finished, boolean pushing,
                                      boolean pageOpen, long sinceFinishedMs) {
        if (pageOpen) return true;                       // ★ 页面开着 = 无条件继续
        if (!hasTimer || !pushing) return false;         // 没倒计时 / 不推眼镜 → 收工
        if (!finished) return true;
        return sinceFinishedMs >= 0 && sinceFinishedMs < KEEP_AFTER_FINISH_MS;
    }

    /** 秒数 → `MM:SS`，超过 1 小时用 `H:MM:SS`。 */
    public static String clock(long seconds) {
        if (seconds < 0) seconds = 0;
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    /** 一天的秒数 → `HH:mm:ss`（眼镜上"现在几点"）。 */
    public static String wallClock(long epochMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMillis);
        return String.format(Locale.ROOT, "%02d:%02d:%02d",
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND));
    }
    /** `HH:mm`（结束时刻用，秒没必要）。 */
    public static String wallClockShort(long epochMillis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMillis);
        return String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /**
     * 字符进度条。宽度固定，字符取自 {@link NavGuide} 的当前样式
     * （默认 ■ / □ —— GB2312 内码表里，中文固件才画得出来）。
     *
     * ⚠️ 以前用的是 `▓ / ░`。`░`(U+2591) 连 GBK 都不在码表里，
     * 眼镜上那一格是空白，所以进度条看着是残缺的。别再换回去。
     */
    public static String bar(double progress, int width) {
        return NavGuide.barCells(progress, width <= 0 ? 10 : width);
    }

    /**
     * 组装眼镜画面（最多 5 行）。
     *
     * ⚠️ 只放 GB2312 能画的字符。以前这里用了 `🕐 ⏳ ⏰` 三个 emoji，
     * 中文固件一个都画不出来 —— 眼镜上第一行会变成空白 + "剩 04:32"。
     *
     * @param timer    当前倒计时，可为 null（null 表示只显示时间，即"眼镜时钟"模式）
     * @param now      当前时刻（epoch millis）
     * @param elapsed  已运行时长（millis，用于算"现在"），一般传 now
     */
    public static String compose(Timer timer, long now, long elapsed) {
        StringBuilder b = new StringBuilder();
        if (timer == null) {
            // 纯时钟模式：只要时间，用户说"最好带眼镜时间显示"。
            b.append("◎ ").append(wallClock(now));
            return b.toString();
        }
        long remainMs = timer.remaining(elapsed);
        long remainSec = (remainMs + 999) / 1000;   // 向上取整，剩 0.3 秒显示 00:01 而不是 00:00
        boolean done = timer.finished(elapsed);
        b.append(done ? "◎ 时间到" : ("● 剩 " + clock(remainSec)));
        b.append('\n').append(timer.name).append(timer.paused() ? " · 已暂停" : "");
        b.append('\n').append(NavGuide.bar(timer.progress(elapsed)));
        b.append('\n').append("现在 ").append(wallClock(now));
        long endAt = now + remainMs;
        b.append('\n').append(done ? "已结束" : ("预计 " + wallClockShort(endAt) + " 结束"));
        return b.toString();
    }

    /**
     * 解析一句"倒计时"语音。
     * 例：「倒计时 5 分钟」「计时 30 秒」「番茄钟」「倒计时 1 小时」「取消倒计时」
     *
     * @return 秒数（>0 表示开始）；-1 表示"取消"；0 表示"不是倒计时指令"
     */
    public static long parseDuration(String raw) {
        if (raw == null) return 0;
        String q = NavVoice.normalize(raw);
        if (q.isEmpty()) return 0;
        if (q.contains("取消倒计时") || q.contains("停止倒计时") || q.contains("结束倒计时")
            || q.contains("取消计时") || q.contains("停止计时")) return -1;
        // 番茄钟默认 25 分钟
        if (q.contains("番茄钟") || q.contains("番茄")) return 1500;
        boolean hasKey = q.contains("倒计时") || q.contains("计时") || q.contains("定时") || q.contains("提醒我");
        // 抠数字：支持 "5分钟" "30秒" "1小时" "1小时30分" "90分钟"
        long total = 0; boolean found = false;
        java.util.regex.Matcher h = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(?:个)?小时").matcher(q);
        if (h.find()) { try { total += Math.round(Double.parseDouble(h.group(1)) * 3600); found = true; } catch (NumberFormatException ignored) { } }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(?:分钟|分)").matcher(q);
        if (m.find()) { try { total += Math.round(Double.parseDouble(m.group(1)) * 60); found = true; } catch (NumberFormatException ignored) { } }
        java.util.regex.Matcher s = java.util.regex.Pattern.compile("([0-9]+)\\s*秒").matcher(q);
        if (s.find()) { try { total += Long.parseLong(s.group(1)); found = true; } catch (NumberFormatException ignored) { } }
        if (hasKey && !found) return -2;   // 说了倒计时但没给时长 → 让上层追问
        if (!hasKey || !found) return 0;
        if (total <= 0) return 0;
        if (total > 24 * 3600) total = 24 * 3600;   // 上限一天，防手滑
        return total;
    }

    /** 从话里取个名字："倒计时 5 分钟 煮面" → "煮面"。取不到返回 "倒计时"。 */
    public static String parseName(String raw) {
        if (raw == null) return "倒计时";
        String q = NavVoice.normalize(raw);
        // 去掉所有 "倒计时/计时/定时/提醒我 + 数字单位" 的部分，剩下的当名字。
        String name = q.replaceAll("(倒计时|计时|定时|提醒我)", "")
            .replaceAll("[0-9]+(?:\\.[0-9]+)?\\s*(?:个)?(小时|分钟|分|秒)", "")
            .trim();
        name = NavVoice.stripTrailingParticles(name);
        if (name.isEmpty() || name.length() > 16) return "倒计时";
        return name;
    }

    /**
     * 语音是否是倒计时相关指令。
     */
    public static boolean isCountdownCommand(String raw) {
        long r = parseDuration(raw);
        return r != 0;
    }
}

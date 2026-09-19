package com.turboio.addon;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import java.lang.ref.WeakReference;

/**
 * 倒计时页 + 眼镜端时间常显。
 *
 * 逻辑核心全在 Countdown（纯逻辑、可单测），本类只负责：
 *   · 页面上选时长 / 起停 / 暂停
 *   · 每秒把画面推到眼镜（眼镜端就是"抬头看剩余时间 + 现在几点"）
 *
 * ══════════════════════════════════════════════════════════════════
 *  ★ 2026-09-18 深夜：心跳整条重写（用户实测"App 与眼镜都不动"）
 * ══════════════════════════════════════════════════════════════════
 * ── 旧实现错在哪 ──────────────────────────────────────────────
 *  1 秒循环建在 show() 里，而且**"当前没有倒计时在跑"时不排下一次**：
 *
 *     if (running() || timer.paused()) { postDelayed(this, 1000); }
 *
 *  用户打开页面时正是"还没有倒计时"这个状态 —— 循环跑 1 秒就自己死了。
 *  接着点「5 分钟」时 timer 有了，但**已经没有任何人在刷新手机端、也没人推眼镜**，
 *  现象因此是「App 上数字不动 + 眼镜上画面不动」，两条症状同一个根。
 *  更隐蔽的是：语音说「倒计时 5 分钟」时页面根本没打开，
 *  连那个会死的循环都没被创建过 —— 只有一帧被推上去，然后永远停在那儿。
 *
 * ── 现在的规则 ────────────────────────────────────────────────
 *  · 心跳是**常驻**的，与"此刻有没有倒计时"完全解耦：
 *    页面开着 → 一直跳（要显示走动的时钟）；
 *    页面关了但还在推倒计时 → 继续跳（眼镜上照常读秒）；
 *    页面关了、没倒计时、也不推眼镜 → 才停。
 *    判定抽成纯函数 {@link Countdown#keepBeating}，单测钉死。
 *  · 页面只挂"弱引用"到手机端 TextView：页面关了不影响心跳，
 *    心跳也不拦着页面被回收（旧实现用静态强引用 TextView，页面重开会指向旧 View）。
 *  · 响铃只响一次（比 timer 对象身份），否则心跳会每秒 Toast 一次。
 *  · 推眼镜节奏自适应：固体/通道拒绝过（type-5 回执带错）就自动降到 3 秒一推，
 *    宁可读秒变粗，也不要整条通道被拒到看不见。
 *
 * ── 为什么眼镜端每秒推一次 ──────────────────────────────────────
 * 字幕通道本身有 3 秒节流 + 去重（NavGlasses 的 tick 那条路），
 * 但倒计时要"看得见在读秒"，必须走 {@link NavGlasses#push} 绕过节流。
 * 一秒一次对通道压力不大（单条 < 100 字节），且用户就是要这个体感。
 */
final class CountdownUI {

    private static final String OWNER = "countdown";

    /** 正常读秒节奏。 */
    private static final long PUSH_MS = 1000L;
    /** SDK 拒绝过推文之后自动降级到的节奏 —— 少给字幕通道添堵。 */
    private static final long PUSH_SLOW_MS = 3000L;

    private static volatile Countdown.Timer timer;
    /** 是否往眼镜推（页面开关 / 开始倒计时 / 「退出眼镜显示」都会改它）。 */
    private static volatile boolean pushing = true;
    /** 已经为哪一个倒计时响过铃（比对象身份）—— 少了它心跳会每秒响一次。 */
    private static Object rangFor;
    /** 心跳里每次"真的推了"记一笔，用来算节奏；claim 单独限流，避免刷爆 start()。 */
    private static long lastPushAt, lastClaimAt;

    private static final Handler HEART = new Handler(Looper.getMainLooper());
    private static boolean beating;
    private static volatile boolean pageOpen;

    private static WeakReference<TextView> headlineRef = new WeakReference<>(null);
    private static WeakReference<TextView> detailRef = new WeakReference<>(null);
    private static WeakReference<Switch> switchRef = new WeakReference<>(null);
    /** 语音路径没有 Activity，Toast 靠它。 */
    private static volatile Context appCtx;

    private static android.app.Dialog dialog;

    static {
        // 别的模块抢显示通道时仲裁层会调这个。只停"推眼镜"，**不杀倒计时**
        // —— 手机上还在跑，到点照样响铃（见 releaseChannel 的注释）。
        NavGlasses.onRelease(OWNER, "倒计时", CountdownUI::releaseChannel);
    }

    static Countdown.Timer current() { return timer; }
    static boolean running() { return timer != null && !timer.finished(System.currentTimeMillis()); }

    /**
     * 一句话描述当前倒计时（给模型工具 `timer_status` 与首页状态行共用）。
     *
     * 注意和 {@link #running()} 的区别：这里**包含了刚结束、还在显示"时间到"**的那 10 秒，
     * 用户问"还剩多久"时如果说"没有倒计时"，反而和眼镜上还挂着的"时间到"矛盾。
     */
    static String describe() {
        Countdown.Timer t = timer;
        if (t == null) return "现在没有在跑的倒计时";
        long now = System.currentTimeMillis();
        if (t.finished(now)) return "「" + t.name + "」已经时间到了";
        long sec = (t.remaining(now) + 999) / 1000;
        return "「" + t.name + "」还剩 " + Countdown.clock(sec) + (t.paused() ? "（已暂停）" : "");
    }

    // ══════════════════════════════════════════════════════════════
    //  页面
    // ══════════════════════════════════════════════════════════════

    static void show(Activity host) {
        if (host == null) return;
        appCtx = host.getApplicationContext();
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog screen = TurboStyle.screen(host, "倒计时 · 眼镜时钟", box, () -> AyaSuperAddon.showHome());
        dialog = screen;
        pageOpen = true;

        // ── 状态卡 ──
        LinearLayout status = TurboStyle.card(host, box);
        status.addView(TurboStyle.label(host, "现在", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, status, 8);
        TextView headline = TurboStyle.text(host, "—", 26, TurboStyle.LIME);
        headline.setLineSpacing(TurboStyle.dp(host, 5), 1f);
        status.addView(headline);
        TurboStyle.gap(host, status, 8);
        TextView detail = TurboStyle.text(host, "", 13, TurboStyle.MUTED);
        detail.setLineSpacing(TurboStyle.dp(host, 4), 1f);
        status.addView(detail);

        // ── 时长选择 ──
        LinearLayout presets = TurboStyle.card(host, box);
        presets.addView(TurboStyle.label(host, "选一个时长", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, presets, 8);
        for (int i = 0; i < Countdown.PRESETS.length; i++) {
            final int secs = Countdown.PRESETS[i];
            final String presetName = Countdown.PRESET_NAMES[i];
            TurboStyle.button(host, presets, presetName, i == Countdown.PRESETS.length - 1,
                () -> begin(host, secs, presetName));
        }

        // ── 自定义 ──
        LinearLayout custom = TurboStyle.card(host, box);
        custom.addView(TurboStyle.label(host, "自定义（分钟）", 11, TurboStyle.MUTED));
        EditText minutes = new EditText(host);
        minutes.setHint("例如 8");
        minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minutes.setSingleLine();
        TurboStyle.field(host, minutes);
        custom.addView(minutes);
        TurboStyle.button(host, custom, "开始自定义倒计时", true, () -> {
            try {
                int m = Integer.parseInt(minutes.getText().toString().trim());
                if (m < 1 || m > 1440) throw new NumberFormatException();
                begin(host, m * 60, m + " 分钟");
            } catch (NumberFormatException e) {
                Toast.makeText(host, "请输入 1 - 1440 之间的整数分钟", Toast.LENGTH_SHORT).show();
            }
        });

        // ── 控制 ──
        LinearLayout controls = TurboStyle.card(host, box);
        controls.addView(TurboStyle.label(host, "控制", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, controls, 8);
        TurboStyle.button(host, controls, "暂停 / 继续", false, () -> {
            Countdown.Timer t = timer;
            if (t == null) { toast("还没有倒计时"); return; }
            long now = System.currentTimeMillis();
            if (t.paused()) t.resume(now); else t.pause(now);
            ensureBeat();          // 暂停后画面不再变化，但循环要活着等"继续"
            paint();
            if (t.paused()) pushNow(true);
        });
        TurboStyle.button(host, controls, "取消倒计时", false, () -> {
            if (timer == null) { toast("还没有倒计时"); return; }
            cancel();
            toast("已取消");
        });

        // ── 眼镜显示开关 ──
        Switch toGlasses = new Switch(host);
        toGlasses.setText("同步到眼镜（剩余时间 + 现在几点）");
        toGlasses.setTextColor(TurboStyle.INK);
        toGlasses.setChecked(pushing);
        toGlasses.setOnCheckedChangeListener((v, on) -> {
            pushing = on;
            if (on) { ensureBeat(); claimChannel(true); }
            else { NavGlasses.release(OWNER); }
        });
        controls.addView(toGlasses);

        // ── 眼镜显示控制（退出 / 重置） ──
        TurboStyle.displayControl(host, box, "倒计时会持续占用字幕通道；测别的功能前点「退出眼镜显示」。");

        TurboStyle.gap(host, box, 14);
        box.addView(TurboStyle.text(host,
            "眼镜上会显示 5 行：\n"
            + "  剩 04:32 / 番茄钟 / " + NavGuide.bar(0.45) + " / 现在 21:47:03 / 预计 21:51 结束\n\n"
            + "倒计时期间每秒刷新一次，绕开字幕通道的 3 秒节流，体感是连续读秒。\n"
            + "若通道拒收（固件吃不消），会自动降到每 3 秒一推，不会整个断掉。\n"
            + "眼镜会话满 4 分钟会自动续接，长倒计时不会中断。\n"
            + "关掉这个页面不会停掉倒计时：眼镜上照样读秒，到点照样响铃。\n"
            + "语音也能用：「倒计时 5 分钟」「番茄钟」「取消倒计时」。", 11, TurboStyle.FAINT));

        // 页面只挂弱引用：页面关了心跳照跑，心跳也不拦着页面被回收。
        headlineRef = new WeakReference<>(headline);
        detailRef = new WeakReference<>(detail);
        switchRef = new WeakReference<>(toGlasses);

        TurboStyle.onDismissExtra(screen, () -> {
            pageOpen = false;
            headlineRef = new WeakReference<>(null);
            detailRef = new WeakReference<>(null);
            switchRef = new WeakReference<>(null);
        });

        // ★ 打开页面立刻起心跳 ★
        //   页面要显示走动的时钟，而且用户随时会点时长 ——
        //   旧实现把循环藏在"有没有倒计时"后面，页面一打开循环就死了。
        ensureBeat();
        refresh(headline, detail);
    }

    // ══════════════════════════════════════════════════════════════
    //  心跳（常驻，不随页面开关而亡）
    // ══════════════════════════════════════════════════════════════

    private static final Runnable BEAT = new Runnable() {
        @Override public void run() {
            beating = false;
            long now = System.currentTimeMillis();
            Countdown.Timer t = timer;
            boolean finished = t != null && t.finished(now);

            paint();                                        // ① 手机端文字（页面关了就跳过）

            if (t != null && pushing) {                     // ② 眼镜
                if (finished) ringOnce(t); else pushNow(false);
            }

            // ③ 要不要继续跳 —— 纯函数判定，见 Countdown.keepBeating
            if (Countdown.keepBeating(t != null, finished, pushing, pageOpen,
                    t == null ? -1 : now - t.endedAt())) {
                schedule();
            } else if (t != null) {
                // 结束画面挂够了 / 不再推眼镜：把通道让出去，别一直攥着。
                NavGlasses.release(OWNER);
            }
        }
    };

    /** 幂等起跳（UI 线程直接调；语音路径在后台线程调，自动转主线程）。 */
    private static void ensureBeat() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            HEART.removeCallbacks(BEAT);
            beating = true;
            HEART.postDelayed(BEAT, 1000);
        } else {
            HEART.post(CountdownUI::ensureBeat);
        }
    }

    private static void schedule() {
        if (beating) return;
        beating = true;
        HEART.postDelayed(BEAT, 1000);
    }

    /** 刷新手机端那一对 TextView（页面可能已经关了）。 */
    private static void paint() {
        TextView headline = headlineRef.get();
        if (headline == null) return;
        refresh(headline, detailRef.get());
    }

    // ══════════════════════════════════════════════════════════════
    //  眼镜
    // ══════════════════════════════════════════════════════════════

    /**
     * 抢通道并推第一帧。
     *
     * @param force true = 无视限流立刻推（用户刚点了开关，要有即时反馈）
     */
    private static void claimChannel(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastClaimAt < 1500) return;   // 未就绪时别每秒刷一次 start()
        lastClaimAt = now;
        try {
            NavGlasses.acquire(OWNER);
            NavGlasses.show(frame(false), true);
            lastPushAt = now;
        } catch (Throwable ignored) { }
    }

    /**
     * 按当前节奏推一帧。
     * 节奏 = 正常 1 秒；一旦 SDK 拒收过就降到 3 秒（成功一次自动恢复）。
     */
    private static void pushNow(boolean force) {
        Countdown.Timer t = timer;
        if (t == null || !pushing) return;
        long now = System.currentTimeMillis();
        long interval = NavGlasses.lastSendFailed() ? PUSH_SLOW_MS : PUSH_MS;
        if (!force && now - lastPushAt < interval) return;
        lastPushAt = now;
        try {
            if (!NavGlasses.ready()) { claimChannel(false); return; }
            NavGlasses.push(frame(false));
        } catch (Throwable ignored) { }
    }

    /** 到点响铃：只响一次，否则心跳会每秒 Toast 一遍。 */
    private static void ringOnce(Countdown.Timer t) {
        if (rangFor == t) return;
        rangFor = t;
        toast("倒计时结束：" + t.name);
        try { NavGlasses.show(frame(true), true); } catch (Throwable ignored) { }
        lastPushAt = System.currentTimeMillis();
    }

    /** 组装一帧（含"现在几点"）。字符只用 GB2312 能画的，眼镜才显示得出来。 */
    private static String frame(boolean finished) {
        long now = System.currentTimeMillis();
        Countdown.Timer t = timer;
        if (t == null) return "◎ " + Countdown.wallClock(now);
        if (finished) return "◎ 时间到 · " + t.name + "\n现在 " + Countdown.wallClock(now);
        return Countdown.compose(t, now, now);
    }

    /** 手机端文字。detail 可能为 null（页面已关）。 */
    private static void refresh(TextView headline, TextView detail) {
        long now = System.currentTimeMillis();
        Countdown.Timer t = timer;
        if (t == null) {
            headline.setText(Countdown.wallClock(now));
            if (detail != null) detail.setText("没有倒计时在跑 · 眼镜上可以只显示时间");
            return;
        }
        long remain = t.remaining(now);
        long sec = (remain + 999) / 1000;
        if (t.finished(now)) {
            headline.setText("时间到");
            if (detail != null) detail.setText(t.name + " 已结束 · 眼镜上还留 10 秒");
        } else {
            headline.setText(Countdown.clock(sec));
            if (detail != null) {
                detail.setText(t.name + (t.paused() ? " · 已暂停" : "")
                    + "\n" + Countdown.bar(t.progress(now), 12) + " " + Math.round(t.progress(now) * 100) + "%"
                    + "\n预计 " + Countdown.wallClockShort(now + remain) + " 结束");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  起停
    // ══════════════════════════════════════════════════════════════

    private static void begin(Activity host, int seconds, String name) {
        startTimer(name, seconds * 1000L);
        if (host != null) Toast.makeText(host, "开始：" + name, Toast.LENGTH_SHORT).show();
    }

    /**
     * 开始 / 重开一个倒计时。UI 与语音两条路**共用**这一处，
     * 免得再出现"某条路忘了起心跳"这种只坏一半的 bug。
     *
     * 可以从任意线程调：心跳与眼镜操作都在主线程上做。
     */
    private static void startTimer(final String name, final long millis) {
        onMain(() -> {
            timer = new Countdown.Timer(name, millis, System.currentTimeMillis());
            rangFor = null;
            pushing = true;
            lastPushAt = 0; lastClaimAt = 0;
            Switch sw = switchRef.get();
            if (sw != null) sw.setChecked(true);
            ensureBeat();
            claimChannel(true);   // 立刻上第一帧，不用等 1 秒
            paint();
        });
    }

    private static void onMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else HEART.post(r);
    }

    private static void toast(String msg) {
        final Context ctx = appCtx;
        if (ctx == null) return;
        try { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) { }
    }

    // ── 供 NlpRouter / AyaSuperAddon 使用 ──

    /** 语音路径：解析一句话并开始倒计时。返回给用户的反馈；null = 不是倒计时指令。 */
    static String handleVoice(Context app, String raw) {
        if (app != null) appCtx = app.getApplicationContext();
        long secs = Countdown.parseDuration(raw);
        if (secs == 0) return null;
        if (secs == -1) { cancel(); return "已取消倒计时"; }
        if (secs == -2) return "要倒计时多久？说「倒计时 5 分钟」";
        String name = Countdown.parseName(raw);
        // ★ 语音走的是后台线程；心跳/抢通道都得回主线程（startTimer 内部会转）。
        startTimer(name, secs * 1000L);
        return "倒计时 " + Countdown.clock(secs) + " 已开始" + (name.equals("倒计时") ? "" : "（" + name + "）");
    }

    /** 取消：停掉计时，眼镜上留一行"已取消"。不关页面（页面时钟继续走）。 */
    static void cancel() {
        onMain(() -> {
            Countdown.Timer t = timer;
            timer = null;
            rangFor = null;
            if (t != null) NavGlasses.release(OWNER);
            try {
                NavGlasses.show("倒计时已取消\n现在 " + Countdown.wallClock(System.currentTimeMillis()), true);
            } catch (Throwable ignored) { }
            paint();
            // 页面开着就继续跳（显示时钟）；关着则这一拍自然收工。
            if (pageOpen) ensureBeat();
        });
    }

    /**
     * 让出显示通道（登记在 NavGlasses 里，别的模块 acquire 时会调）。
     *
     * ★ 这里**只停推眼镜，不杀倒计时** —— 用户从别的功能抢走眼镜显示时，
     *   手机上那个倒计时没有理由跟着消失；到点照样响铃。
     */
    static void releaseChannel() {
        onMain(() -> {
            pushing = false;
            Switch sw = switchRef.get();
            if (sw != null) sw.setChecked(false);
            if (pageOpen) ensureBeat();
        });
    }

    /** 彻底停止（扩展卸载时用）。 */
    static void stop() {
        onMain(() -> {
            HEART.removeCallbacks(BEAT);
            beating = false;
            timer = null; rangFor = null;
            pageOpen = false;
            headlineRef = new WeakReference<>(null);
            detailRef = new WeakReference<>(null);
            switchRef = new WeakReference<>(null);
            NavGlasses.release(OWNER);
        });
    }
}

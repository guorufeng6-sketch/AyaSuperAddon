package com.turboio.addon;

import android.content.Context;
import android.speech.tts.TextToSpeech;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本机 TTS：把**我们自己的回复**念出来。
 *
 * ══ 为什么现在必须有它 ═════════════════════════════════════════
 * 用户实测反馈：「让他去导航 语音回复不支持 但后台却在规划路线」。
 *
 * 两件事同时发生，是因为**同一个回合被两条链路各自回答了一次**：
 *   · 扩展这边：接管成功 → 起高德、推眼镜、Toast（但**不出声**）；
 *   · 官方那边：这一轮还带着"要问模型"的语义 → 官方模型照常回答，
 *     而它不知道本机扩展能干什么 → 于是**用语音念了一句"不支持"**。
 *
 * 修法有两半，缺一不可：
 *   ① 接管成功时把这一轮的"还有下一轮"关掉（见 `TurboNlpInterceptor.smali`），
 *      让官方不要再去问模型 —— 否则永远会有一句自相矛盾的官方语音；
 *   ② **我们自己出声** —— 否则用户什么都听不到，"没反应"和"不支持"一样糟。
 * 这就是本类的职责（②）。设置里「语音读回复」可以关掉。
 *
 * ── 实现上的注意点 ──────────────────────────────────────────────
 *   · 初始化是**异步**的（onInit 才可用），所以首批回复可能赶不上；
 *     这里排队等 init，最多等一会儿，赶不上就静默跳过（不能为了出声阻塞语音链路）。
 *   · `speak` 必须在**有 Looper 的线程**（TTS 内部用 Handler）；语音链路是后台线程，
 *     所以统一 post 到主线程。
 *   · 中文固件不一定装了中文语音包 —— 拿不到就退英文引擎，最坏静默，
 *     **绝不**因为 TTS 失败影响主功能。
 */
final class Speak {
    private Speak() {}

    /** 设置里的开关（默认开）。 */
    static final String PREF_KEY = "voice_reply_tts";

    private static volatile TextToSpeech tts;
    private static final AtomicBoolean initStarted = new AtomicBoolean(false);
    private static volatile boolean ready = false;
    private static volatile boolean enabled = true;

    static void setEnabled(boolean value) { enabled = value; }

    /** 在 install() 里预热，避免第一次说话时还在初始化。 */
    static void warmUp(final Context app) {
        if (app == null || !initStarted.compareAndSet(false, true)) return;
        final Context ctx = app.getApplicationContext();
        AyaSuperAddon.runOnMain(() -> {
            try {
                tts = new TextToSpeech(ctx, status -> {
                    ready = status == TextToSpeech.SUCCESS;
                    if (!ready || tts == null) return;
                    try {
                        // 先试中文，失败退系统默认（拿不到中文包也不至于完全不出声）。
                        int r = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.getDefault());
                        }
                        tts.setSpeechRate(1.05f);
                    } catch (Throwable ignored) { }
                });
            } catch (Throwable ignored) { }
        });
    }

    /**
     * 念一句话（非阻塞）。给的是**用户要听的话**，不是镜头文案 ——
     * 换行会被 TTS 念成奇怪的停顿，所以这里先压平。
     */
    static void say(Context app, String text) {
        if (!enabled || text == null) return;
        final String line = text.replaceAll("\\s+", " ").trim();
        if (line.isEmpty()) return;
        if (app != null) warmUp(app);
        AyaSuperAddon.runOnMain(() -> {
            try {
                TextToSpeech engine = tts;
                if (engine == null) { warmUp(app); return; }   // 还没建起来：下次再说，绝不阻塞
                if (!ready) return;                            // 初始化失败/未完成：静默
                engine.speak(line, TextToSpeech.QUEUE_ADD, null, "turboio-" + System.nanoTime());
            } catch (Throwable ignored) { }
        });
    }

    /** 诊断用：TTS 是否可用。 */
    static boolean available() { return ready; }
}

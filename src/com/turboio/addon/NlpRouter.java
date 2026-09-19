package com.turboio.addon;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 意图路由策略（纯逻辑，可单测，不碰反射与 Android API）。
 *
 * ── 为什么要这个东西 ──────────────────────────────────────────────
 * 官方在 H7/c.i() 里初始化 AI 控制器时，会把 com.rayneo.airuntime 的
 * INlpInterceptor 实现（D7/V0）注册进 AssistantController：
 *
 *     assistantController.setNlpInterceptor(D7.V0.a);
 *
 * 之后眼镜每识别出一句话，native 层会先把 NlpResult 交给拦截器；
 * 拦截器返回 true 就表示"这一轮我吃下了，官方别往下走"。
 *
 * 官方 D7/V0 只做三件事：
 *   ① intent ∈ {make_payment, sound_tube_on, sound_tube_off}
 *        -> domain = "system_ctrl", hasNextRound = false
 *   ② domain == "navigate"
 *        -> domain = "chat", intent = "chat", hasNextRound = true   ← 导航被并进普通问答
 *   ③ (calendar, create_schedule_event) / (task, create_task)
 *        -> hasNextRound = true
 *
 * ── 我们要做的事 ────────────────────────────────────────────────
 * 把一部分意图从"官方 chat 流程"里摘出来，交给自己处理：
 * 导航、家里情况、通勤这类跟本地能力强相关的，走 addon 自己的链路；
 * 其余一律原样放行，行为跟没装扩展时完全一致。
 *
 * 关键点：拦截器看到的 domain 是【原始值】，早于官方的 navigate->chat 改写，
 * 所以这里能拿到官方自己都丢掉的"导航"语义。
 */
public final class NlpRouter {
    private NlpRouter() {}

    /** 接管开关。默认关，跟随设置页里的 mode。 */
    private static final AtomicBoolean active = new AtomicBoolean(false);
    public static void setActive(boolean value) { active.set(value); }
    public static boolean active() { return active.get(); }

    /**
     * 天气接管开关。**只有用户填过和风天气 Key 才开**。
     *
     * 为什么要有这个独立开关：天气问句（"今天天气怎么样"）官方自己也能答。
     * 如果用户没配我们的和风 Key 就把它抢过来，等于把能用的官方能力换成了
     * 一句"请先去填 Key"—— 那是净损失。所以配置了才接管。
     */
    private static final AtomicBoolean weatherEnabled = new AtomicBoolean(false);
    public static void setWeatherEnabled(boolean value) { weatherEnabled.set(value); }
    public static boolean weatherEnabled() { return weatherEnabled.get(); }

    /** 最近一次判定的可读说明，给状态行用。 */
    private static volatile String lastDecision = "未启用";

    public static String lastDecision() { return lastDecision; }

    /**
     * 判定一条 NLP 结果要不要被扩展接管。
     *
     * ── v3r19 重写的原因（用户实测：「官方的意图接管还是有问题」）────────
     * 追问后的答案是「**部分能用部分不行**」：导航、倒计时能用，家况/天气/电子书不行。
     * 根因是旧实现把"要不要接管"押在**官方给的 domain** 上：
     *   · 家况要求 `domain ∈ {local, iot, home}` —— 而官方对「家里温度多少」
     *     经常给 chat/weather，于是我们连问都不问，直接放行给官方闲聊；
     *   · 天气只认口令（这条还行），电子书**根本没写**。
     * 现在统一交给 {@link LocalIntent}：**以"这句话本身"为准，domain 只作加分项**
     * （domain 明说是本地能力时，家况类判定放宽）。这样"能不能用"就不再取决于
     * 官方 NLU 那一刻的心情。
     *
     * @param hasBook 电子书里有没有导入过书（没有书时"翻页"类口令不该被吃）
     */
    public static boolean consume(String domain, String intent, String sub, String query,
                                  boolean offline, boolean command, boolean hasBook) {
        if (!active.get()) { lastDecision = "未启用"; return false; }
        if (offline) { lastDecision = "离线链路，放行"; return false; }
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) { lastDecision = "空语句，放行"; return false; }

        String d = safe(domain);
        String i = safe(intent);

        // ① 官方 domain 明说是导航。
        //    ⚠️ 能走到这一步的 navigate 是"官方没抢"的那些 —— 官方的 D7/V0 对
        //    domain==navigate 会自己改写成 chat 并 return true（见 TurboNlpInterceptor.smali
        //    里的注释），所以真正兜住导航的是下面 ② 的本地口令判定，不是这一行。
        if ("navigate".equals(d)) {
            lastDecision = "接管导航：" + clip(q);
            return true;
        }

        // ② 本机口令统一判定。
        //    `command != null` 表示官方产出了**结构化指令**（它自己能执行），
        //    这种一律让给官方（历史上曾因为抢这些把"打开音乐""打开设置"吞掉，
        //    用户体感是"叫不动了"）。只有随口记例外：官方没有对应的能力，
        //    而它又是个纯本地记事本，抢过来零损失。
        LocalIntent.Kind kind = LocalIntent.classify(q, d, hasBook, weatherEnabled.get());
        if (command) {
            if (kind == LocalIntent.Kind.MEMO) {
                lastDecision = "接管" + Memo.NAME + "：" + clip(q);
                return true;
            }
            lastDecision = "放行官方（有结构化指令）：" + d + "/" + i;
            return false;
        }
        switch (kind) {
            case MEMO:    lastDecision = "接管" + Memo.NAME + "：" + clip(q); return true;
            case EBOOK:   lastDecision = "接管电子书：" + clip(q); return true;
            case NAV:     lastDecision = "接管导航口令：" + clip(q); return true;
            case TIMER:   lastDecision = "接管倒计时：" + clip(q); return true;
            case WEATHER: lastDecision = "接管天气：" + clip(q); return true;
            case HOME:    lastDecision = "接管家况：" + d + "/" + i; return true;
            default: break;
        }

        // ③ 其余全部放行，保持官方行为不变。
        lastDecision = "放行官方：" + d + "/" + i + "/" + safe(sub);
        return false;
    }

    /**
     * 家况类问句判定（纯逻辑，便于单测；关键词与 HomeStatusUI 保持一致）。
     * 宁可漏判也别误判 —— 误判会把官方的设备控制/播放类指令吞掉。
     */
    static final String[] HOME_KEYS = {"家里", "屋内", "室内", "家的情况", "家里怎么",
        "温度", "湿度", "空调", "灯", "窗帘", "门窗", "净化器", "插座", "pm2.5", "甲醛"};

    static boolean looksLikeHome(String query) {
        String q = query == null ? "" : query.toLowerCase(java.util.Locale.ROOT);
        for (String k : HOME_KEYS) if (q.contains(k)) return true;
        return false;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String clip(String value) {
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }
}

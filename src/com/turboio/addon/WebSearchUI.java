package com.turboio.addon;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Tavily 联网搜索的执行层（Android）：发请求、翻错误码、结果交给眼镜 / 模型。
 *
 * 分工：
 *   · 请求体拼装与响应解析 → 纯逻辑层 {@link WebSearch}（有单测）
 *   · Key 配置页            → {@code AyaSuperAddon.showWebSearch()}（与米家 / RAG 同一套）
 *   · 真正发 HTTP           → 本类
 *
 * ══ 为什么"让自有模型具有联网搜索能力"要这么接 ══════════════════
 * 模型自己不会上网。链路是：用户提问 → 模型决定调 web_search → 本类真的去搜
 * → 检索结果作为**工具返回**回到模型 → 模型基于这段真实文本回答。
 * 所以 {@link WebSearch#toModelText} 里必须带上来源 URL，否则模型会把检索到的
 * 内容当成自己的记忆，转头说成"我记得…"（那就是编造了）。
 */
final class WebSearchUI {
    private WebSearchUI() {}

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String OWNER = "websearch";
    static final String PREF_KEY = "tavily_key";

    /** 最近一次检索结果（眼镜翻页 / 状态显示用）。 */
    private static final List<WebSearch.Result> last = new ArrayList<>();

    /** Key 是否已配置 —— 决定 web_search 工具注不注册（没配就不注册，模型不会假装搜过）。 */
    static boolean ready(Context app) { return !key(app).isEmpty(); }

    static String key(Context app) {
        // 与 RAG / 米家一致：走 Android Keystore 加密保存，不落明文、不写日志。
        try {
            String k = SecretStore.get(app, PREF_KEY);
            return k == null ? "" : k.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 执行一次检索。**阻塞**（最多约 20 秒），只在后台线程调 —— 工具调用与网络线程
     * 都满足，但绝不能在主线程调（会把整个 App 卡住）。
     *
     * 抛出的异常文案会原样回到模型、再由模型转述给用户，所以每条都写成
     * "人能看懂、并且知道下一步该干嘛"的话，而不是 "HTTP 401"。
     */
    static String search(Context app, String query) throws Exception {
        String key = key(app);
        if (key.isEmpty())
            throw new IllegalStateException("还没填 Tavily API Key：去首页「联网搜索（Tavily）」里粘贴一次");
        HttpURLConnection c = (HttpURLConnection) new URL(WebSearch.ENDPOINT).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);   // Key 走头，不进 body
            c.setDoOutput(true);
            byte[] body = WebSearch.bodyJson(query).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(body);
                os.flush();
            } finally {
                try { os.close(); } catch (Throwable ignored) { }
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403)
                throw new IllegalStateException("Tavily 拒绝了请求（" + code + "）：API Key 不对或已失效，请重新粘贴一次");
            if (code == 429)
                throw new IllegalStateException("Tavily 额度用尽或触发限流（429）：等一会儿再试，或去官网看额度");
            if (code < 200 || code >= 300)
                throw new IllegalStateException("Tavily 返回 " + code + "：服务暂时不可用，稍后再试");
            InputStream in = c.getInputStream();
            String json;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                json = new String(out.toByteArray(), "UTF-8");
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
            List<WebSearch.Result> rs = WebSearch.parse(json);
            last.clear();
            last.addAll(rs);
            return WebSearch.toModelText(rs, WebSearch.answer(json));
        } finally {
            c.disconnect();
        }
    }

    /** 眼镜上显示最近一次检索（≤5 行标题 + 页码）。 */
    private static void pushGlasses() {
        try {
            if (last.isEmpty()) return;
            NavGlasses.acquire(OWNER);
            NavGlasses.show(WebSearch.compose(last, 0), false);
        } catch (Throwable ignored) { }
    }

    private static void release() {
        try { NavGlasses.release(OWNER); } catch (Throwable ignored) { }
    }

    /**
     * 语音 / 模型入口。
     *
     * 工具调用路径**不走这里** —— 它需要阻塞拿到全文交给模型（见
     * {@code LocalTools.call}）；这里是语音场景：异步搜，搜到推眼镜，
     * 先回一句"正在搜"，避免把主线程卡住。
     */
    static String handleVoice(Context app, String raw) {
        if (app == null || raw == null) return null;
        if (!WebSearch.isSearchQuery(raw)) return null;
        final String q = WebSearch.query(raw);
        if (q.isEmpty()) return "没听清要搜什么，再说一次（比如「搜一下量子计算最新进展」）";
        if (!ready(app)) return "还没填 Tavily API Key：去首页「联网搜索（Tavily）」里粘贴一次就能用";
        new Thread(new Runnable() {
            public void run() {
                try {
                    search(app, q);
                    MAIN.post(new Runnable() {
                        public void run() { pushGlasses(); }
                    });
                } catch (Throwable ignored) { }
            }
        }, "TurboIO-websearch").start();
        return "正在联网搜「" + q + "」，结果会推到眼镜";
    }

    /** 给首页状态行用的一句话。 */
    static String describe(Context app) {
        if (!ready(app)) return "填 Tavily API Key 后可用";
        if (last.isEmpty()) return "已配置 Key · 语音「搜一下 …」直达";
        return "已配置 Key · 上次 " + last.size() + " 条结果";
    }

    static void releaseAll() { release(); }
}

package com.turboio.addon;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * 热搜 / 热榜新闻页（手机端 + 眼镜端）。
 *
 * 数据源与解析全在纯逻辑层 {@link News}（那里有单测）；本类只管取数、排版、
 * 推眼镜、接语音，观感沿用天气 / 运动那一套（TurboStyle 整屏面板 + 卡片 +
 * 主次按钮 + 显示控制条）。
 *
 * ⚠️ 字幕通道是全局单会话（见 {@link NavGlasses}）：推之前先 acquire，
 *    页面关闭时 release，别把导航 / 运动挤掉。
 */
final class NewsUI {
    private NewsUI() {}

    private static final String OWNER = "news";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static String board = News.WEIBO;
    private static final List<News.Item> items = new ArrayList<>();
    private static int page = 0;

    private static final TextView[] statusRef = {null};
    private static final LinearLayout[] listRef = {null};

    /** 一句话描述当前状态（首页状态行 / 语音回复用）。 */
    static String describe() {
        if (items.isEmpty()) return "还没取过热榜，点开选一个榜单";
        return News.name(board) + " · " + items.size() + " 条 · 眼镜第 "
            + (page + 1) + "/" + Math.max(1, News.pages(items)) + " 页";
    }

    // ══════════════════════════════════════════════════════════════
    //  取数
    // ══════════════════════════════════════════════════════════════

    /** 同步取一个榜单（只在后台线程调）。失败返回 null。 */
    static List<News.Item> fetchSync(String which) {
        String json;
        try {
            json = httpGet("https://60s.viki.moe/v2/" + News.path(which));
        } catch (Throwable t) {
            return null;
        }
        if (json == null || json.isEmpty()) return null;
        List<News.Item> got = News.parse(which, json);
        return got.isEmpty() ? null : got;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return "";
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), "UTF-8");
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
            }
        } finally {
            c.disconnect();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  眼镜
    // ══════════════════════════════════════════════════════════════

    private static void push() {
        push("");
    }

    private static void push(final String fallback) {
        MAIN.post(new Runnable() {
            public void run() {
                try {
                    NavGlasses.acquire(OWNER);
                    // 注册“被挤掉时”的清理，避免语音路径的 owner 变成孤儿
                    NavGlasses.onRelease(OWNER, "热搜", new Runnable() {
                        public void run() { release(); }
                    });
                    String text = (fallback != null && !fallback.isEmpty()) ? fallback
                        : News.compose(board, items, page);
                    NavGlasses.show(text, false);
                } catch (Throwable ignored) { }
            }
        });
    }

    private static void toast(final Context app, final String msg) {
        MAIN.post(new Runnable() {
            public void run() {
                try { android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show(); }
                catch (Throwable ignored) { }
            }
        });
    }

    private static void nextPage() {
        int pages = News.pages(items);
        if (pages <= 0) return;
        page = (page + 1) % pages;
        push();
    }

    private static void release() {
        MAIN.post(new Runnable() {
            public void run() {
                try { NavGlasses.release(OWNER); } catch (Throwable ignored) { }
            }
        });
    }

    // ══════════════════════════════════════════════════════════════
    //  手机页
    // ══════════════════════════════════════════════════════════════

    static void show(final Activity host) {
        LinearLayout box = TurboStyle.column(host);
        Dialog dialog = TurboStyle.screen(host, "热搜 · 热榜新闻", box, new Runnable() {
            public void run() { AyaSuperAddon.showHome(); }
        });
        TurboStyle.onDismissExtra(dialog, new Runnable() {
            public void run() { release(); }
        });

        statusRef[0] = TurboStyle.text(host, describe(), 13, TurboStyle.MUTED);
        box.addView(statusRef[0]);

        TurboStyle.sectionTitle(host, box, "选榜单");
        // 四个榜一行排开（数据源免 Key，点哪个取哪个）
        LinearLayout rowBar = TurboStyle.rowBox(host);
        box.addView(rowBar);
        for (final String b : News.boards()) {
            TurboStyle.ghost(host, rowBar, News.name(b), new Runnable() {
                public void run() { switchBoard(host, b); }
            });
        }

        listRef[0] = TurboStyle.card(host, box);
        paintList(host);

        TurboStyle.button(host, box, "推到眼镜", true, new Runnable() {
            public void run() { push(); }
        });
        TurboStyle.button(host, box, "眼镜翻下一页", false, new Runnable() {
            public void run() { nextPage(); refreshStatus(); }
        });
        TurboStyle.displayControl(host, box,
            "热榜会占用字幕通道（眼镜一屏 4 条，可翻页）；测别的功能前点「退出眼镜显示」。");
    }

    private static void switchBoard(final Activity host, final String b) {
        board = b;
        page = 0;
        statusRef[0].setText(News.name(b) + " 正在取…");
        new Thread(new Runnable() {
            public void run() {
                final List<News.Item> got = fetchSync(b);
                MAIN.post(new Runnable() {
                    public void run() {
                        items.clear();
                        if (got != null) items.addAll(got);
                        refreshStatus();
                        paintList(host);
                    }
                });
            }
        }, "TurboIO-news").start();
    }

    private static void refreshStatus() {
        if (statusRef[0] != null) statusRef[0].setText(describe());
    }

    private static void paintList(Activity host) {
        LinearLayout card = listRef[0];
        if (card == null) return;
        card.removeAllViews();
        card.addView(TurboStyle.label(host, "榜单（前 10 条）", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, card, 8);
        if (items.isEmpty()) {
            card.addView(TurboStyle.text(host, "还没取到 —— 点上面任意一个榜单按钮。", 13, TurboStyle.MUTED));
            return;
        }
        int shown = Math.min(10, items.size());
        for (int i = 0; i < shown; i++) {
            News.Item it = items.get(i);
            String line = (i + 1) + ". " + it.title;
            if (!it.hot.isEmpty()) line = line + "  " + it.hot;
            card.addView(TurboStyle.text(host, line, 13, TurboStyle.INK));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  语音
    // ══════════════════════════════════════════════════════════════

    /**
     * 语音 / 模型入口。命中"热搜 / 新闻"类问句才处理，其余返回 null 交给别的模块。
     *
     * 取数是**异步**的：语音分发跑在主线程上，阻塞等 HTTP 会把整个 App 卡住，
     * 所以这里先回一句"正在取"，拿到了再自己推到眼镜。
     */
    static String handleVoice(final Context app, String raw) {
        if (app == null || raw == null) return null;
        // 翻页**先**判：「下一页」本身不是热搜问句（isNewsQuery 会否掉它），
        // 但在已经有榜的语境下它就是翻页 —— 排在问句判断前面才接得住。
        if (raw.contains("下一页") || raw.contains("下一条") || raw.contains("换一批")) {
            if (items.isEmpty()) return "还没取过热榜，先说「看看热搜」";
            nextPage();
            return "已翻到" + News.name(board) + "第 " + (page + 1) + " 页";
        }
        if (!News.isNewsQuery(raw)) return null;
        final String b = News.board(raw);
        board = b;
        page = 0;
        new Thread(new Runnable() {
            public void run() {
                final List<News.Item> got = fetchSync(b);
                if (got == null) {
                    String err = News.name(b) + "获取失败，检查网络或稍后重试";
                    toast(app, err);
                    push("◎ " + err);
                    return;
                }
                items.clear();
                items.addAll(got);
                push();
                toast(app, News.name(b) + "已推到眼镜，当前第 1/" + News.pages(items) + " 页");
            }
        }, "TurboIO-news").start();
        return "正在取" + News.name(b);
    }
}

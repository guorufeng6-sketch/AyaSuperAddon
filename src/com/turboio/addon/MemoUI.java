package com.turboio.addon;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.widget.*;

import java.util.List;

/**
 * 「备忘录」—— 手机端查看与管理界面（语音入口在同类的 handleVoice）。
 *
 * ── 用户需求 ────────────────────────────────────────────────────
 * 「另加备忘录功能 语音打开备忘录 写入备忘录 手机可查看 可以取名随口记」
 * 2026-09-18 追加：「工具里的随口记改成备忘录」—— 于是一律叫**备忘录**，
 * 「随口记 / 随手记」保留为别名（见 {@link Memo}）。
 *
 * 三个入口都落在这里：
 *   · **语音写入** → `handleVoice` 直接落盘（不走模型，秒回）；
 *   · **语音打开** → `openFromVoice`（有界面就打开，同时把最近几条推到眼镜）；
 *   · **手机查看** → `show()`（逐条删除、清空、手动补一条、推到眼镜）。
 *
 * ── 界面设计上的取舍 ────────────────────────────────────────────
 * 这是"车里一句话记下来、回头在手机上看"的场景，所以：
 *   · 列表**最新在上**（刚记的最可能是要马上核对的）；
 *   · 每条只显示 `时间 + 内容`，不做富文本 —— 内容本身就是全部信息；
 *   · 删除做成每行一个「删」而不是长按菜单：开车后单手操作，少一层就少一次点错；
 *   · 保留一个手动输入框：语音在嘈杂环境识别不准时，用户能补一条。
 */
final class MemoUI {
    private MemoUI() {}

    private static final String OWNER = "memo";

    static {
        // 别的模块抢显示通道时，把我们停掉（我们只占一帧，没什么可停的，
        // 但登记上能让"显示占用"诊断正确显示当前占用者）。
        NavGlasses.onRelease(OWNER, Memo.NAME, () -> { });
    }

    // ══════════════════════════════════════════════════════════════
    //  语音入口
    // ══════════════════════════════════════════════════════════════

    /**
     * 语音口令的本地执行（不经过模型）。返回给用户的一句回复；null = 这句话不是备忘录口令。
     *
     * 由 `AyaSuperAddon.dispatchIntercept` 调用，**运行在后台线程**，
     * 所以这里绝不碰 View，需要界面时交给 {@link #openFromVoice}。
     */
    static String handleVoice(Context app, String query) {
        Memo.Action action = Memo.parse(query);
        if (action == Memo.Action.NONE) return null;
        if (app != null) MemoStore.bind(app);

        switch (action) {
            case ADD: {
                String text = Memo.text(query);
                if (!Memo.hasContent(text)) {
                    // 只说了「记一下」——
                    // 这种情况**不要**替他记一条空的，追问一句最有用。
                    openFromVoice(app);
                    return Memo.needContentReply();
                }
                if (!MemoStore.add(System.currentTimeMillis(), text)) {
                    return "记录失败了（本机存储不可写），请稍后再试";
                }
                return Memo.addedReply(text);
            }
            case OPEN: {
                openFromVoice(app);
                List<Memo.Item> items = MemoStore.all();
                if (items.isEmpty()) return Memo.NAME + "还是空的，说「记一下 买牛奶」就能记一条";
                push(Memo.glasses(items, 4));
                return Memo.NAME + "共 " + items.size() + " 条，最近一条：" + Memo.clip(items.get(items.size() - 1).text, 20);
            }
            case LIST: {
                List<Memo.Item> items = MemoStore.all();
                if (!items.isEmpty()) push(Memo.glasses(items, 4));
                return Memo.spoken(items, 3);
            }
            case DELETE_LAST: {
                Memo.Item removed = MemoStore.removeLast();
                if (removed == null) return Memo.deletedReply(false);
                push(Memo.glasses(MemoStore.all(), 4));
                return "已删掉「" + Memo.clip(removed.text, 20) + "」";
            }
            case CLEAR: {
                int was = MemoStore.clear();
                if (was < 0) return "清空失败了（本机存储不可写）";
                if (was == 0) return Memo.clearedReply(0);
                push(Memo.glasses(MemoStore.all(), 4));
                return Memo.clearedReply(was);
            }
            default:
                return null;
        }
    }

    /** 语音"打开"时弹界面：必须在主线程，且宿主 Activity 还活着才有得弹。 */
    static void openFromVoice(Context app) {
        if (app != null) MemoStore.bind(app);
        AyaSuperAddon.runOnMain(() -> {
            Activity host = AyaSuperAddon.hostOrNull();
            if (host != null) show(host);
        });
    }

    private static void push(String text) {
        try {
            NavGlasses.acquire(OWNER);
            NavGlasses.show(text, false);
        } catch (Throwable ignored) { }
    }

    /**
     * 这条口令下，**镜片上该看的东西已经由我们推上去了**。
     *
     * ── 为什么调用方需要知道（用户实测 bug）────────────────────────
     * 「语音调用电子书存在 bug：打开后显示进度，而不是内容」。
     * 根因是 `AyaSuperAddon.reportTakeover` 会把"回复文案"再推一次镜片，
     * 而回复文案恰恰是**进度**（"备忘录共 3 条，最近一条：…"）——
     * 于是刚推上去的正文/清单被这句话覆盖掉了。
     *
     * 所以打开 / 朗读 / 删除 / 清空这几条（回复只是"状态说明"）→ 不覆盖镜片；
     * 而 ADD（回复是"已记到备忘录：买牛奶"，用户正需要看到这一句回执）→ 覆盖。
     */
    static boolean ownScreen(String query) {
        Memo.Action action = Memo.parse(query);
        return action == Memo.Action.OPEN || action == Memo.Action.LIST
            || action == Memo.Action.DELETE_LAST || action == Memo.Action.CLEAR;
    }

    // ══════════════════════════════════════════════════════════════
    //  界面
    // ══════════════════════════════════════════════════════════════

    static void show(Activity host) {
        if (host == null) return;
        MemoStore.bind(host);
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, Memo.NAME, box, () -> AyaSuperAddon.showHome());

        // ── 顶部：条数 + 说明 ──
        LinearLayout head = TurboStyle.card(host, box);
        TextView summary = TurboStyle.title(host, "", 16);
        head.addView(summary);
        TurboStyle.gap(host, head, 6);
        head.addView(TurboStyle.text(host,
            "语音：「记一下 买牛奶」「帮我记一下 交电费」「念念备忘录」「打开备忘录」\n"
            + "别名也能用：随口记 / 随手记。全部内容只存在本机，不上传。\n"
            + "「待办 / 日程」留给官方 App（它有真实提醒），这里只做纯文本记录。", 12, TurboStyle.MUTED));

        // ── 手动补一条（语音没听清时用） ──
        LinearLayout addCard = TurboStyle.card(host, box);
        addCard.addView(TurboStyle.label(host, "手动记一条", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, addCard, 8);
        EditText field = new EditText(host);
        field.setHint("例如：明天下午取快递");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        TurboStyle.field(host, field);
        addCard.addView(field, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout listCard = TurboStyle.card(host, box);
        final TextView listTitle = TurboStyle.label(host, "", 11, TurboStyle.MUTED);
        final TextView empty = TurboStyle.text(host,
            "还没有内容。\n语音说「随口记 买牛奶」就能记一条；也可以在上面输入框里手写。",
            14, TurboStyle.FAINT);
        final LinearLayout rows = TurboStyle.column(host);

        final Runnable[] repaint = new Runnable[1];
        repaint[0] = () -> {
            List<Memo.Item> items = MemoStore.snapshotNewestFirst();
            summary.setText(Memo.NAME + " · " + items.size() + " 条"
                + (items.isEmpty() ? "" : " · 最近 " + Memo.stamp(items.get(0).at)));
            listCard.removeAllViews();
            listCard.addView(listTitle);
            TurboStyle.gap(host, listCard, 8);
            if (items.isEmpty()) {
                listCard.addView(empty);
                return;
            }
            listTitle.setText("全部内容（最新在上）");
            rows.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                Memo.Item it = items.get(i);
                final int index = i;   // 界面顺序 = 最新在上；删除时换算成存储顺序的下标
                LinearLayout row = TurboStyle.rowBox(host);
                row.setPadding(0, TurboStyle.dp(host, 8), 0, TurboStyle.dp(host, 8));

                LinearLayout body = TurboStyle.column(host);
                body.addView(TurboStyle.text(host, Memo.stamp(it.at), 11, TurboStyle.FAINT));
                TextView line = TurboStyle.text(host, it.text, 15, TurboStyle.INK);
                line.setLineSpacing(TurboStyle.dp(host, 3), 1f);
                TurboStyle.gap(host, body, 3);
                body.addView(line);
                row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));

                TextView delete = TurboStyle.text(host, "删", 13, TurboStyle.BAD);
                delete.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                delete.setPadding(TurboStyle.dp(host, 12), TurboStyle.dp(host, 6),
                    TurboStyle.dp(host, 6), TurboStyle.dp(host, 6));
                delete.setOnClickListener(v -> {
                    int stored = MemoStore.count() - 1 - index;
                    Toast.makeText(host, MemoStore.removeAt(stored) ? "已删除" : "删除失败",
                        Toast.LENGTH_SHORT).show();
                    repaint[0].run();
                });
                LinearLayout.LayoutParams del = new LinearLayout.LayoutParams(-2, -2);
                del.leftMargin = TurboStyle.dp(host, 10);
                row.addView(delete, del);
                rows.addView(row);
                // 分隔线放在 rows 内部，保证"行 → 线 → 行"的绘制顺序不会错位。
                if (i < items.size() - 1) {
                    LinearLayout sep = new LinearLayout(host);
                    sep.setBackgroundColor(TurboStyle.STROKE);
                    rows.addView(sep, new LinearLayout.LayoutParams(-1, 1));
                }
            }
            listCard.addView(rows);
        };

        TurboStyle.button(host, box, "记下这一条", true, () -> {
            String text = field.getText().toString().trim();
            if (!Memo.hasContent(text)) {
                Toast.makeText(host, "请先输入内容", Toast.LENGTH_SHORT).show();
                return;
            }
            if (MemoStore.add(System.currentTimeMillis(), text)) {
                field.setText("");
                Toast.makeText(host, Memo.addedReply(text), Toast.LENGTH_SHORT).show();
                repaint[0].run();
            } else {
                Toast.makeText(host, "记录失败（本机存储不可写）", Toast.LENGTH_LONG).show();
            }
        });

        TurboStyle.button(host, box, "把最近几条推到眼镜", false, () -> {
            List<Memo.Item> items = MemoStore.all();
            if (items.isEmpty()) { Toast.makeText(host, Memo.NAME + "还是空的", Toast.LENGTH_SHORT).show(); return; }
            push(Memo.glasses(items, 4));
            Toast.makeText(host, NavGlasses.ready() ? "已推到眼镜" : "正在开启显示通道，稍等会自动显示", Toast.LENGTH_LONG).show();
        });
        TurboStyle.button(host, box, "清空全部", false, () -> {
            int was = MemoStore.clear();
            if (was < 0) Toast.makeText(host, "清空失败", Toast.LENGTH_SHORT).show();
            else Toast.makeText(host, was == 0 ? "本来就是空的" : ("已清空 " + was + " 条"), Toast.LENGTH_SHORT).show();
            repaint[0].run();
        });

        TurboStyle.gap(host, box, 14);
        TurboStyle.displayControl(host, box, "推眼镜只占一帧，看完可以点「退出眼镜显示」。");
        repaint[0].run();
        dialog.show();
    }
}

package com.turboio.addon;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.*;
import java.util.List;

/**
 * 对话记忆：把本机持久化的多轮对话（用户/助手）按时间倒序展示。
 *
 * 两个层次：
 *  1) 模型上下文记忆 —— 最近 50 条成功往返，会作为历史回放给自有模型；
 *  2) 本机存档 —— conversations.md（Markdown，可分享），仅含扩展成功完成的回复。
 *
 * 这里展示的是 (1)，并明确告知用户"超出 50 条就会被挤掉"，避免误解为永久记忆。
 */
final class HistoryUI {

    static void show(Activity host) {
        if (host == null) return;
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, "对话记忆", box, () -> AyaSuperAddon.showHome());

        List<String[]> rows = new ChatPolicy.HistoryAccessor().snapshot();
        int dropped = new ChatPolicy.HistoryAccessor().dropped();

        // 概览卡
        LinearLayout meta = TurboStyle.card(host, box);
        int turns = rows.size() / 2;
        meta.addView(TurboStyle.title(host, turns + " 轮对话在记忆里", 17));
        TurboStyle.gap(host, meta, 6);
        meta.addView(TurboStyle.text(host,
            "这些内容会作为上下文回放给自有模型，让它记住你之前说过什么。", 13, TurboStyle.MUTED));
        if (dropped > 0) {
            TurboStyle.gap(host, meta, 6);
            TextView warn = TurboStyle.text(host,
                "已有 " + dropped + " 轮因超出上限被移出上下文（存档里仍保留）。", 12, TurboStyle.WARN);
            meta.addView(warn);
        }

        if (rows.isEmpty()) {
            LinearLayout empty = TurboStyle.card(host, box);
            empty.addView(TurboStyle.text(host, "还没有对话记录。\n切到自有模型后，在眼镜上说一句话就会出现在这里。", 14, TurboStyle.MUTED));
        } else {
            // 倒序：最新的在最上面
            for (int i = rows.size() - 1; i >= 0; i--) {
                String[] row = rows.get(i);
                boolean user = "user".equals(row[0]);
                LinearLayout card = TurboStyle.card(host, box);
                card.setBackground(TurboStyle.stroked(host,
                    user ? TurboStyle.SURFACE_2 : TurboStyle.SURFACE, 20, TurboStyle.STROKE));
                TextView who = TurboStyle.label(host, user ? "你" : "Turbo IO", 11,
                    user ? TurboStyle.LIME : TurboStyle.BLUE);
                card.addView(who);
                TurboStyle.gap(host, card, 6);
                TextView body = TurboStyle.text(host, row[1], 15, TurboStyle.INK);
                card.addView(body);
                // 长回答折叠到 6 行，点击展开
                body.setMaxLines(6);
                body.setEllipsize(android.text.TextUtils.TruncateAt.END);
                if (row[1].length() > 180) {
                    card.setOnClickListener(v -> {
                        if (body.getMaxLines() == 6) { body.setMaxLines(Integer.MAX_VALUE); body.setEllipsize(null); }
                        else { body.setMaxLines(6); body.setEllipsize(android.text.TextUtils.TruncateAt.END); }
                    });
                }
            }
        }

        TurboStyle.button(host, box, "分享完整存档", false, () -> shareArchive(host));
        TurboStyle.button(host, box, "清空记忆", false, () -> {
            new android.app.AlertDialog.Builder(host)
                .setTitle("清空对话记忆？")
                .setMessage("会清除模型上下文里的 " + (rows.size() / 2) + " 轮对话。本机 Markdown 存档不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    new ChatPolicy.HistoryAccessor().clear();
                    dialog.dismiss();
                    show(host);
                }).show();
        });

        TurboStyle.gap(host, box, 14);
        box.addView(TurboStyle.text(host,
            "全部内容只存在本机应用私有目录，不上传。\n历史的工具调用痕迹在回放前会被自动清洗，避免模型误判「已经执行过」。",
            11, TurboStyle.FAINT));

        dialog.show();
    }

    /** 与 AyaSuperAddon.archive 的落盘文件保持一致，这里只读分享。 */
    private static void shareArchive(Activity host) {
        final android.content.Context ctx = host.getApplicationContext();
        new Thread(() -> {
            java.io.File file = new java.io.File(ctx.getFilesDir(), "turboio_android/conversations.md");
            if (!file.isFile() || file.length() == 0) {
                host.runOnUiThread(() -> Toast.makeText(host, "还没有 Markdown 存档", Toast.LENGTH_SHORT).show());
                return;
            }
            if (file.length() > 300000) {
                host.runOnUiThread(() -> Toast.makeText(host, "存档过大，请用「录音与文件」导出", Toast.LENGTH_LONG).show());
                return;
            }
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                final String text = out.toString("UTF-8");
                host.runOnUiThread(() -> {
                    try {
                        android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/markdown")
                            .putExtra(android.content.Intent.EXTRA_TEXT, text)
                            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Turbo IO 对话.md");
                        host.startActivity(android.content.Intent.createChooser(share, "分享对话存档"));
                    } catch (Exception e) {
                        Toast.makeText(host, "没有可用分享应用", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                host.runOnUiThread(() -> Toast.makeText(host, "存档读取失败", Toast.LENGTH_LONG).show());
            }
        }, "TurboIO-history-share").start();
    }
}

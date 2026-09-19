package com.turboio.addon;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 提词器：把一段长文按语速滚动推到眼镜上，边走边念。
 *
 * 与电子书的区别：
 *   电子书 —— 用户要"逐屏读"，节奏由用户控制；
 *   提词器 —— 用户要"照着念"，节奏由时间控制，每 N 秒自动进一段。
 *
 * 实现：复用 ReaderUI 的断页逻辑思路，但段长更小（便于一眼扫到），
 *      用 Handler 定时推进，可选循环。
 */
final class TeleprompterUI {

    private static final String OWNER = "prompter";
    private static String script = "";
    private static List<String> chunks = new ArrayList<>();
    private static int index = 0;
    private static boolean running = false;
    private static int seconds = 8;
    private static android.os.Handler timer;
    private static Runnable task;
    private static final int PICK_TXT = 4702;

    static {
        NavGlasses.onRelease(OWNER, "提词器", TeleprompterUI::stop);
    }

    static void show(Activity host) {
        if (host == null) return;
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, "提词器", box, () -> AyaSuperAddon.showHome());

        LinearLayout meta = TurboStyle.card(host, box);
        meta.addView(TurboStyle.title(host, running ? "正在滚动" : "就绪", 17));
        TurboStyle.gap(host, meta, 6);
        meta.addView(TurboStyle.text(host,
            chunks.isEmpty() ? "粘贴或导入一段文字，按语速分段推到眼镜上。"
                             : ("第 " + (index + 1) + " / " + chunks.size() + " 段" + (running ? " · 每段 " + seconds + " 秒" : "")),
            13, TurboStyle.MUTED));

        LinearLayout editCard = TurboStyle.card(host, box);
        editCard.addView(TurboStyle.label(host, "文稿", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, editCard, 8);
        final EditText field = new EditText(host);
        field.setText(script);
        field.setHint("在这里粘贴要念的稿子…");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setTextSize(15); field.setTextColor(TurboStyle.INK);
        field.setHintTextColor(TurboStyle.FAINT);
        field.setMinLines(4); field.setMaxLines(8);
        field.setBackground(TurboStyle.stroked(host, TurboStyle.SURFACE_2, 14, TurboStyle.STROKE));
        field.setPadding(TurboStyle.dp(host, 12), TurboStyle.dp(host, 10),
                         TurboStyle.dp(host, 12), TurboStyle.dp(host, 10));
        editCard.addView(field, new LinearLayout.LayoutParams(-1, -2));

        TurboStyle.button(host, box, "使用这段文稿", true, () -> {
            script = field.getText().toString().trim();
            if (script.isEmpty()) { Toast.makeText(host, "文稿是空的", Toast.LENGTH_SHORT).show(); return; }
            chunks = split(script);
            index = 0;
            Toast.makeText(host, "已切成 " + chunks.size() + " 段", Toast.LENGTH_SHORT).show();
            show(host);
        });

        TurboStyle.button(host, box, "导入 .txt", false, () -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain");
                host.startActivityForResult(intent, PICK_TXT);
            } catch (Exception e) {
                Toast.makeText(host, "无法打开文件选择器", Toast.LENGTH_LONG).show();
            }
        });

        if (!chunks.isEmpty()) {
            TurboStyle.button(host, box, running ? "停止" : "开始滚动", true, () -> {
                if (running) stop();
                else start(host);
                show(host);
            });

            LinearLayout speed = TurboStyle.grid(host, box);
            int[][] opts = {{5, 0}, {8, 1}, {15, 2}};
            for (int i = 0; i < opts.length; i++) {
                final int secs = opts[i][0];
                LinearLayout cell = TurboStyle.gridCell(host, speed, false);
                cell.addView(TurboStyle.text(host, secs + "s", 17, TurboStyle.INK));
                TurboStyle.gap(host, cell, 4);
                cell.addView(TurboStyle.text(host, "每段", 11, TurboStyle.MUTED));
                cell.setOnClickListener(v -> { seconds = secs; Toast.makeText(host, "每段 " + secs + " 秒", Toast.LENGTH_SHORT).show(); });
            }

            LinearLayout preview = TurboStyle.card(host, box);
            preview.addView(TurboStyle.label(host, "当前段", 11, TurboStyle.MUTED));
            TurboStyle.gap(host, preview, 6);
            preview.addView(TurboStyle.text(host, chunks.get(Math.min(index, chunks.size() - 1)), 15, TurboStyle.INK));
        }

        TurboStyle.gap(host, box, 14);
        // ── 眼镜显示控制（退出 / 重置）──
        TurboStyle.displayControl(host, box, "提词器会持续占用字幕通道；测别的功能前先停掉。");
        box.addView(TurboStyle.text(host,
            "眼镜端每 N 秒自动换下一段，适合彩排、演讲、镜头前口播。\n"
            + "会话满 4 分钟会自动续接。文字只在本机处理。", 11, TurboStyle.FAINT));

        dialog.show();
    }

    /** 提词器分段：比电子书更短，一段 = 一眼能扫完的一句话。 */
    static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            sb.append(c);
            boolean end = c == '。' || c == '！' || c == '？' || c == '；' || c == '\n'
                       || c == '.' || c == '!' || c == '?' || c == ';';
            if (end && sb.toString().trim().length() >= 12) {
                out.add(sb.toString().trim()); sb.setLength(0);
            } else if (sb.length() >= 60) {
                out.add(sb.toString().trim()); sb.setLength(0);
            }
        }
        if (sb.toString().trim().length() > 0) out.add(sb.toString().trim());
        return out;
    }

    private static void start(Activity host) {
        stop();
        if (chunks.isEmpty()) return;
        running = true;
        timer = new android.os.Handler(android.os.Looper.getMainLooper());
        task = new Runnable() {
            @Override public void run() {
                if (!running) return;
                if (index >= chunks.size()) { stop(); return; }
                NavGlasses.acquire(OWNER);
                NavGlasses.show(chunks.get(index), false);
                index++;
                timer.postDelayed(this, seconds * 1000L);
            }
        };
        task.run();   // 立刻推第一段
    }

    static void stop() {
        running = false;
        if (timer != null && task != null) timer.removeCallbacks(task);
        timer = null; task = null;
    }

    /** 是否正在滚动（调试页/首页状态用）。 */
    static boolean running() { return running; }

    static void onResult(Activity host, int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_TXT || resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        new Thread(() -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = host.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("无法读取");
                    byte[] buf = new byte[65536]; int n; long total = 0;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > 5L * 1024 * 1024) throw new IOException("文件超过 5 MB");
                        out.write(buf, 0, n);
                    }
                }
                byte[] raw = out.toByteArray();
                String utf8 = new String(raw, StandardCharsets.UTF_8);
                int bad = 0;
                for (int i = 0; i < utf8.length(); i++) if (utf8.charAt(i) == '\uFFFD') bad++;
                final String text = bad == 0 ? utf8 : new String(raw, "GBK");
                host.runOnUiThread(() -> {
                    script = text;
                    chunks = split(text);
                    index = 0;
                    Toast.makeText(host, "已导入 " + chunks.size() + " 段", Toast.LENGTH_SHORT).show();
                    show(host);
                });
            } catch (Exception e) {
                host.runOnUiThread(() -> Toast.makeText(host,
                    e.getMessage() == null ? "读取失败" : e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "TurboIO-prompter-load").start();
    }
}

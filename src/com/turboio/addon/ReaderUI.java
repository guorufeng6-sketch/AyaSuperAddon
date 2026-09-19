package com.turboio.addon;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 电子书：导入 txt，分页，推到眼镜（字幕通道）。
 *
 * 为什么是"分页推文字"而不是画布：
 *   眼镜端只能通过 business 19 / AI_SUBTITLE 字幕通道显示纯文本，
 *   最多 5 行、替换式、单会话 4 分钟上限。所以电子书必须：
 *     ① 在 App 侧把整本书切成一屏能读下的片段；
 *     ② 每次只推一屏，用户"翻页"时推下一屏；
 *     ③ 会话过期（4 分钟）时自动重新 start()，用户无感。
 *
 * 翻页方式（表冠已排除：事件只在 Dart 层，Java 拿不到）：
 *   - App 内按钮（主用）
 *   - 语音「下一页 / 下一页 上一页」（拦 ASR，见 PageTurnVoice）
 *   - 自动翻页（定时器，可调间隔）
 */
final class ReaderUI {

    private static final String OWNER = "reader";

    // ---- 当前阅读状态（进程内单例，够用） ----
    private static final List<String> pages = new ArrayList<>();
    private static String rawText = "";
    private static String bookName = "";
    private static int cursor = 0;
    private static boolean auto = false;
    private static int autoSeconds = 15;
    private static android.os.Handler timer;
    private static Runnable autoTask;
    /**
     * Application context：续读要落盘/读盘，而语音链路可能在任何线程调进来。
     * 每次拿到都顺手更新（幂等），不依赖某个页面先被打开过。
     */
    private static volatile android.content.Context appCtx;

    private static final int PICK_TXT = 4701;

    /**
     * 眼镜端字幕：**最多 5 行**。分页必须按"行"来切，不能只按字数。
     *
     * ── 为什么从"每屏 130 字"改成"每屏 5 行" ──────────────────────
     * 用户实测：按 130 字切的屏，手机端预览是 6 行、眼镜端只有 5 行，
     * 剩下的要**转表冠往下滚**才能看全 —— 眼镜上读书最不该有的操作。
     * 根因是"字数"和"行数"不是一回事：同样的字数，行宽不同就是不同的行数。
     * 所以这里改成先按行宽折行、再每 5 行切一屏，并在页内写死换行符，
     * 让**手机预览和眼镜完全一致**（预览看到的 5 行就是眼镜上的 5 行）。
     *
     * ── 每行多少字（lineWidth）为什么要可调 ──────────────────────
     * `content_width` / `max_lines` 这两个参数在原生协议里根本不存在
     * （整个 smali 零命中），是官方 Dart 层的约定，真实可容字数只能实测。
     * 所以做成设置项，默认 20 —— 宁可少放两个字，也不要出现"要滚屏"。
     */
    private static final int MAX_LINES = TextPage.MAX_LINES;
    private static int lineWidth = TextPage.DEFAULT_LINE_WIDTH;

    static {
        NavGlasses.onRelease(OWNER, "电子书", ReaderUI::stopAuto);
    }

    /** 由 AyaSuperAddon.install() 恢复上次设的行宽。 */
    static void setLineWidth(int w) {
        lineWidth = Math.max(12, Math.min(w, 32));
        if (!pages.isEmpty()) { reflow(); }
    }
    static int lineWidth() { return lineWidth; }

    /** 设置 prefs（与设置页、断点续读共用同一个文件）。 */
    private static final String PREFS = "turboio_settings";

    // ══════════════════════════════════════════════════════════════
    //  语音入口（官方意图接管 / 自有模型工具都会走到这里）
    // ══════════════════════════════════════════════════════════════

    /**
     * 电子书口令的本地执行。返回给用户的一句回复；null = 这句话不是电子书口令。
     *
     * ── 为什么需要它（用户实测反馈）────────────────────────────────
     * 「自有模型里叫不动」「电子书貌似不支持」。
     *
     * 之前电子书只有两条**都不完整**的入口：
     *   · `VoicePager`：只在 ASR 回调里等值匹配「下一页」这种 ≤8 字的极短指令，
     *     整句「帮我打开电子书」进不来，模型更不知道有这回事；
     *   · App 内按钮：要手动点。
     * 于是"电子书"在语音/模型链路里等于不存在。
     *
     * 本方法把三个入口（官方意图接管、自有模型工具、语音口令）统一到一处，
     * 动作解析交给纯逻辑的 {@link ReaderCmd}，文案交给 {@link ReaderBook}，
     * 这样"语音能翻、模型不能"这类半残状态不会再出现。
     *
     * 运行在**后台线程**（native 回调线程 / 工具调用线程），所以这里绝不碰 View：
     * 需要界面时交给 {@link #openFromVoice}。
     */
    static String handleVoice(android.content.Context app, String query) {
        ReaderCmd.Action action = ReaderCmd.parse(query);
        if (action == ReaderCmd.Action.NONE) return null;
        if (app != null) appCtx = app.getApplicationContext();

        // 没有书时：只有"打开/查进度"回话有意义，其余动作让上层放行给官方。
        if (!hasBook() && !ReaderCmd.worksWithoutBook(action)) return null;

        switch (action) {
            case OPEN: {
                openFromVoice(app);
                if (!hasBook()) return "还没有导入书，先在电子书里选一个 txt 文件";
                pushCurrentToGlasses();
                return "打开电子书，" + ReaderBook.progress(cursor, pages.size());
            }
            case READ:
            case STATUS: {
                if (!hasBook()) return "还没有导入书，先在电子书里选一个 txt 文件";
                if (action == ReaderCmd.Action.READ) {
                    pushCurrentToGlasses();
                    return ReaderBook.spokenResume(bookName, cursor, pages.size());
                }
                return ReaderBook.progress(cursor, pages.size()) + " · " + shortTitle();
            }
            case NEXT:
            case PREV: {
                if (!hasBook()) return null;
                int delta = action == ReaderCmd.Action.NEXT ? +1 : -1;
                Integer moved = move(delta);
                if (moved == null) {
                    return delta > 0 ? "已经是最后一屏了" : "已经是第一屏了";
                }
                pushCurrentToGlasses();
                return "第 " + (cursor + 1) + "/" + pages.size() + " 屏";
            }
            case CLOSE: {
                boolean wasAuto = auto;
                stopAuto();
                // 只有显示通道确实被电子书占着才去关，别把正在跑的导航一起关掉。
                if (OWNER.equals(NavGlasses.ownerId())) NavGlasses.stop();
                return wasAuto ? "已停止自动翻页" : "已退出电子书";
            }
            default:
                return null;
        }
    }

    /** 语音"打开电子书"时弹界面：必须在主线程，且宿主 Activity 还活着才有得开。 */
    static void openFromVoice(android.content.Context app) {
        if (app != null) appCtx = app.getApplicationContext();
        AyaSuperAddon.runOnMain(() -> {
            Activity host = AyaSuperAddon.hostOrNull();
            if (host != null) show(host);
        });
    }

    /** 把当前屏推到眼镜（抢通道 + 未就绪自动开会话），不依赖 Activity。 */
    private static void pushCurrentToGlasses() {
        if (pages.isEmpty()) return;
        try {
            NavGlasses.acquire(OWNER);
            NavGlasses.show(pages.get(cursor), false);
        } catch (Throwable ignored) { }
    }

    /**
     * 这条口令下，**镜片上该看的正文已经推出去了**吗。
     *
     * ── 为什么调用方需要知道（用户实测 bug）────────────────────────
     * 「语音调用电子书存在 bug：打开后显示进度，而不是内容」。
     *
     * 电子书是**唯一没有定时刷新**的模块：正文推上去之后就一直停在那里，
     * 而 `AyaSuperAddon.reportTakeover` 会把"回复文案"再推一次镜片 ——
     * 回复恰好是进度（"打开电子书，第 3/120 屏"／"第 4/120 屏"），
     * 于是正文被这句话**永久**盖掉，用户看到的就是"只有进度、没有内容"。
     *
     * 规则：
     *   · 没有书 → false（回复就是唯一信息，比如"还没导入书"）；
     *   · 打开 / 继续读 / 翻页 → true（正文已推，回复只朗读、不覆盖）；
     *   · 查进度 / 退出 → false（这两条本来就不推正文，回复该让用户看到）。
     */
    static boolean ownScreen(String query) {
        if (!hasBook()) return false;
        ReaderCmd.Action action = ReaderCmd.parse(query);
        return action == ReaderCmd.Action.OPEN || action == ReaderCmd.Action.READ
            || action == ReaderCmd.Action.NEXT || action == ReaderCmd.Action.PREV;
    }

    /** 标题的首段（去掉路径与扩展名），没有书时返回空串。 */
    private static String shortTitle() {
        if (bookName == null || bookName.isEmpty()) return "";
        String t = bookName;
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0) t = t.substring(slash + 1);
        if (t.toLowerCase().endsWith(".txt")) t = t.substring(0, t.length() - 4);
        return t.length() > 14 ? t.substring(0, 14) + "…" : t;
    }

    // ══════════════════════════════════════════════════════════════
    //  续读：落盘 / 恢复
    // ══════════════════════════════════════════════════════════════

    /**
     * 正文落盘（导入成功后调一次）。
     *
     * 为什么把**正文**也存下来、而不是只存书名：重新导入要用户再挑一次文件，
     * 而 20MB 的 txt 手动翻回原来那一屏更是不可能完成的操作 ——
     * 那样"续读"就只是个摆设。
     * 写在后台线程（`IO` 池），失败只影响续读能力，不影响本次阅读。
     */
    private static void persistBook(final String text, final String name) {
        final android.content.Context ctx = appCtx;
        if (ctx == null) return;
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(ctx.getFilesDir(), ReaderBook.DIR);
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                java.io.File f = new java.io.File(dir, ReaderBook.CACHE_FILE);
                // 先写临时文件再改名，与 MemoStore 同一套路：写到一半被杀不会留半截书。
                java.io.File tmp = new java.io.File(dir, ReaderBook.CACHE_FILE + ".tmp");
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                try (java.io.OutputStream out = new java.io.FileOutputStream(tmp)) {
                    out.write(data);
                    out.flush();
                }
                if (f.exists() && !f.delete()) { /* 落到 rename 失败分支 */ }
                if (!tmp.renameTo(f)) {
                    try (java.io.OutputStream out = new java.io.FileOutputStream(f)) {
                        out.write(data);
                        out.flush();
                    }
                    tmp.delete();
                }
            } catch (Throwable ignored) { }
            // 书名与光标一起写，保证"有光标必有书名"。
            saveProgress(ctx, name, cursor);
        }, "TurboIO-reader-save").start();
    }

    /** 只更新进度（每次翻页都调；prefs 写入是毫秒级，且 apply() 不阻塞）。 */
    private static void persistProgress() {
        final android.content.Context ctx = appCtx;
        if (ctx == null) return;
        saveProgress(ctx, bookName, cursor);
    }

    private static void saveProgress(android.content.Context ctx, String name, int at) {
        try {
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putString(ReaderBook.KEY_NAME, name == null ? "" : name)
                .putInt(ReaderBook.KEY_CURSOR, at)
                .apply();
        } catch (Throwable ignored) { }
    }

    /**
     * 启动时恢复上次的书与进度（由 `AyaSuperAddon.install` 调一次）。
     *
     * 恢复的是"正文 + 屏号"，屏列表由 `ReaderBook` 的确定性分页重新算出来 ——
     * 所以换了每行字数也能停在大致原来的位置（见 `reflow()`）。
     */
    static void restoreFromCache(android.content.Context app) {
        if (app == null) return;
        appCtx = app.getApplicationContext();
        String name = "", text = "";
        int saved = 0;
        try {
            android.content.SharedPreferences p = app.getSharedPreferences(PREFS, 0);
            name = p.getString(ReaderBook.KEY_NAME, "");
            saved = p.getInt(ReaderBook.KEY_CURSOR, 0);
        } catch (Throwable ignored) { }
        try {
            java.io.File f = new java.io.File(new java.io.File(app.getFilesDir(), ReaderBook.DIR),
                ReaderBook.CACHE_FILE);
            if (!f.isFile() || !ReaderBook.withinLimit(f.length())) {
                // 没有缓存/超限：仍然把"恢复结论"记进状态，界面里才知道能不能续。
                restoreNote = ReaderBook.restore(name, saved, 0).note;
                return;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            text = decode(out.toByteArray());
        } catch (Throwable ignored) { }

        List<String> result = text == null || text.trim().isEmpty()
            ? new ArrayList<String>() : paginate(text);
        ReaderBook.Restore r = ReaderBook.restore(name, saved, result.size());
        restoreNote = r.note;
        if (!r.usable || result.isEmpty()) return;
        pages.clear(); pages.addAll(result);
        rawText = text;
        bookName = name == null ? "" : name;
        cursor = r.cursor;
    }

    /** 上次的恢复结论（给家面板的状态行看；没恢复过就是空串）。 */
    private static volatile String restoreNote = "";
    static String restoreNote() { return restoreNote; }
    /** 供界面"从现在开始重读"用：只把光标归零。 */
    static void rewindToStart() { cursor = 0; persistProgress(); }

    static void show(Activity host) {
        if (host == null) return;
        appCtx = host.getApplicationContext();
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, "电子书", box, () -> AyaSuperAddon.showHome());

        // ---- 当前书目 / 进度 ----
        LinearLayout meta = TurboStyle.card(host, box);
        TextView title = TurboStyle.title(host, bookName.isEmpty() ? "还没有导入书" : bookName, 17);
        meta.addView(title);
        TurboStyle.gap(host, meta, 6);
        TextView progress = TurboStyle.text(host,
                pages.isEmpty() ? "支持 UTF-8 / GBK 编码的 .txt 纯文本"
                                : ReaderBook.progress(cursor, pages.size())
                                  + (restoreNote.isEmpty() ? "" : " · " + restoreNote),
                13, TurboStyle.MUTED);
        meta.addView(progress);

        // ---- 当前屏预览 ----
        LinearLayout preview = TurboStyle.card(host, box);
        preview.addView(TurboStyle.label(host, "当前屏（眼镜上看到的内容 · 5 行）", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, preview, 8);
        // 12sp 是有意的：只有在手机上也能一行放下一整行，预览才是"所见即所得"。
        // 之前 15sp 会比眼镜端多折断一次，用户看到 6 行以为眼镜也是 6 行。
        TextView body = TurboStyle.text(host,
                pages.isEmpty() ? "导入后这里会显示即将推到眼镜上的文字。" : pages.get(cursor),
                12, TurboStyle.INK);
        body.setLineSpacing(TurboStyle.dp(host, 5), 1f);
        body.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        preview.addView(body);

        Runnable refresh = () -> {
            progress.setText(pages.isEmpty() ? "支持 UTF-8 / GBK 编码的 .txt 纯文本"
                    : (ReaderBook.progress(cursor, pages.size())
                       + (auto ? " · 自动翻页 " + autoSeconds + "s" : "")
                       + (restoreNote.isEmpty() ? "" : " · " + restoreNote)));
            body.setText(pages.isEmpty() ? "导入后这里会显示即将推到眼镜上的文字。" : pages.get(cursor));
            title.setText(bookName.isEmpty() ? "还没有导入书" : bookName);
        };

        // ---- 操作 ----
        TurboStyle.button(host, box, "选择 .txt 文件", true, () -> pickFile(host, dialog, refresh));

        if (!pages.isEmpty()) {
            LinearLayout nav = TurboStyle.grid(host, box);
            LinearLayout prev = TurboStyle.gridCell(host, nav, false);
            prev.addView(TurboStyle.text(host, "‹", 22, TurboStyle.INK));
            TurboStyle.gap(host, prev, 4);
            prev.addView(TurboStyle.text(host, "上一屏", 14, TurboStyle.MUTED));
            prev.setOnClickListener(v -> turn(host, -1, refresh));

            LinearLayout next = TurboStyle.gridCell(host, nav, true);
            next.addView(TurboStyle.text(host, "›", 22, TurboStyle.INK));
            TurboStyle.gap(host, next, 4);
            next.addView(TurboStyle.text(host, "下一屏", 14, TurboStyle.MUTED));
            next.setOnClickListener(v -> turn(host, +1, refresh));

            TurboStyle.button(host, box, "推到眼镜看这一屏", true, () -> pushCurrent(host));
            // 续读的"退路"：想重读就点它。按钮比"清掉进度再导入"自然得多。
            TurboStyle.button(host, box, "从头开始读", false, () -> {
                rewindToStart();
                refresh.run();
                Toast.makeText(host, "已回到第一屏", Toast.LENGTH_SHORT).show();
            });

            boolean[] autoState = {auto};
            Button autoBtn = TurboStyle.button(host, box,
                    auto ? ("自动翻页中 · " + autoSeconds + "s · 点此停止") : "开始自动翻页", false,
                    () -> { /* 见下方 setOnClickListener 覆盖 */ });
            // 单独接 listener 以便改文案
            autoBtn.setOnClickListener(v -> {
                if (auto) { stopAuto(); }
                else { startAuto(host, refresh); }
                autoState[0] = auto;
                autoBtn.setText(auto ? ("自动翻页中 · " + autoSeconds + "s · 点此停止")
                                     : "开始自动翻页");
                refresh.run();
            });

            LinearLayout speed = TurboStyle.grid(host, box);
            for (int[] opt : new int[][]{{10, 0}, {20, 1}}) {
                final int secs = opt[0];
                LinearLayout cell = TurboStyle.gridCell(host, speed, opt[1] == 1);
                cell.addView(TurboStyle.text(host, secs + " 秒", 16, TurboStyle.INK));
                TurboStyle.gap(host, cell, 4);
                cell.addView(TurboStyle.text(host, "每屏停留", 11, TurboStyle.MUTED));
                cell.setOnClickListener(v -> { autoSeconds = secs; refresh.run(); });
            }

            // ---- 每行字数（决定每屏能放多少字） ----
            // 眼镜固件对"一行能放几个字"没有公开参数，只能实测。
            // 给一组档位让用户调：调小了更保险（绝不滚屏），调大了更省屏。
            LinearLayout widths = TurboStyle.card(host, box);
            widths.addView(TurboStyle.label(host, "每行字数（眼镜一行能放下多少字）", 11, TurboStyle.MUTED));
            TurboStyle.gap(host, widths, 8);
            final TextView widthNote = TurboStyle.text(host, "", 12, TurboStyle.MUTED);
            final Runnable[] refreshWidth = new Runnable[1];
            LinearLayout wRow = TurboStyle.grid(host, widths);
            final LinearLayout[] cells = new LinearLayout[3];
            final int[] opts = {16, 20, 24};
            for (int i = 0; i < opts.length; i++) {
                final int w = opts[i];
                final int slot = i;
                LinearLayout cell = TurboStyle.gridCell(host, wRow, i == opts.length - 1);
                cell.addView(TurboStyle.text(host, w + " 字", 16, TurboStyle.INK));
                TurboStyle.gap(host, cell, 4);
                cell.addView(TurboStyle.text(host, w == 16 ? "最保险" : w == 20 ? "推荐" : "最省屏", 11, TurboStyle.MUTED));
                cells[slot] = cell;
                cell.setOnClickListener(v -> {
                    setLineWidth(w);
                    host.getSharedPreferences("turboio_settings", 0).edit()
                        .putInt("reader_line_width", lineWidth).apply();
                    if (refreshWidth[0] != null) refreshWidth[0].run();
                    refresh.run();
                });
            }
            refreshWidth[0] = () -> {
                for (int i = 0; i < cells.length; i++) {
                    boolean on = opts[i] == lineWidth;
                    cells[i].setBackground(TurboStyle.stroked(host,
                        on ? TurboStyle.LIME_DIM : TurboStyle.SURFACE, 18,
                        on ? TurboStyle.LIME : TurboStyle.STROKE));
                }
                widthNote.setText("当前每行 " + lineWidth + " 字 · 每屏最多 " + MAX_LINES + " 行 · 共 "
                    + pages.size() + " 屏（已按此字号重排）");
            };
            refreshWidth[0].run();
            TurboStyle.gap(host, widths, 6);
            widths.addView(widthNote);
        }

        // ---- 眼镜显示控制（退出 / 重置） ----
        TurboStyle.displayControl(host, box, "电子书会占用字幕通道；测别的功能前点「退出眼镜显示」。");

        TurboStyle.gap(host, box, 14);
        box.addView(TurboStyle.text(host,
            "分页按「行」切：每屏最多 " + MAX_LINES + " 行、每行 " + lineWidth + " 字，"
            + "手机预览就是眼镜上的内容，不需要滚屏。\n"
            + "翻页：App 按钮 / 自动翻页（语音叫不动电子书，已确认的缺陷）。\n"
            + "文件只在本机解析，不上传。", 11, TurboStyle.FAINT));

        dialog.show();
    }

    private static void pickFile(Activity host, android.app.Dialog dialog, Runnable refresh) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/plain", "text/*", "application/octet-stream"});
            host.startActivityForResult(intent, PICK_TXT);
        } catch (Exception e) {
            Toast.makeText(host, "无法打开文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    /** 由 AyaSuperAddon.onActivityResult 转发进来。 */
    static void onResult(Activity host, int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_TXT || resultCode != Activity.RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        appCtx = host.getApplicationContext();   // 落盘要用，别依赖"对话框先被打开过"
        Toast.makeText(host, "正在解析文本…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String text = null, err = null;
            try {
                ContentResolver resolver = host.getContentResolver();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = resolver.openInputStream(uri)) {
                    if (in == null) throw new IOException("无法读取文件");
                    byte[] buf = new byte[65536]; int n; long total = 0;
                    while ((n = in.read(buf)) != -1) {
                        total += n;
                        if (total > 20L * 1024 * 1024) throw new IOException("文件超过 20 MB");
                        out.write(buf, 0, n);
                    }
                }
                byte[] raw = out.toByteArray();
                text = decode(raw);
                if (text == null || text.trim().isEmpty()) err = "文件是空的或无法识别编码";
            } catch (Exception e) {
                err = e.getMessage() == null ? "读取失败" : e.getMessage();
            }
            final String book = text, fail = err;
            host.runOnUiThread(() -> {
                if (fail != null) { Toast.makeText(host, fail, Toast.LENGTH_LONG).show(); return; }
                List<String> result = paginate(book);
                if (result.isEmpty()) { Toast.makeText(host, "没有可显示的内容", Toast.LENGTH_LONG).show(); return; }
                pages.clear(); pages.addAll(result);
                rawText = book;
                cursor = 0;
                bookName = displayName(host, uri);
                restoreNote = "已导入「" + bookName + "」共 " + result.size() + " 屏";
                // ★ 立刻落盘：这样"导入后被系统杀掉"也不会丢掉这本书与进度。
                persistBook(book, bookName);
                Toast.makeText(host, "已导入 " + result.size() + " 屏", Toast.LENGTH_LONG).show();
                show(host);   // 重开以刷新 UI
            });
        }, "TurboIO-reader-load").start();
    }

    /** 先按 UTF-8 解，失败或全是替换符就退回 GBK（国内 txt 常见）。 */
    private static String decode(byte[] raw) {
        String utf8 = new String(raw, StandardCharsets.UTF_8);
        int bad = 0;
        for (int i = 0; i < utf8.length(); i++) if (utf8.charAt(i) == '\uFFFD') bad++;
        if (bad == 0) return stripBom(utf8);
        try {
            String gbk = new String(raw, "GBK");
            int badGbk = 0;
            for (int i = 0; i < gbk.length(); i++) if (gbk.charAt(i) == '\uFFFD') badGbk++;
            return stripBom(badGbk < bad ? gbk : utf8);
        } catch (Exception e) {
            return stripBom(utf8);
        }
    }
    private static String stripBom(String s) {
        return s != null && s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    /**
     * 断页。规则全在 {@link TextPage}（纯逻辑、可单测）：
     *  1) 统一换行；2) 按行宽折行（全角 1 / 半角 0.5），行首不留收尾标点；
     *  3) 每 {@link TextPage#MAX_LINES} 行切一屏，页内写死 '\n'；
     *  4) 手机预览与眼镜看到的行结构**完全一致**，不需要滚动。
     */
    static List<String> paginate(String text) {
        return TextPage.paginate(text, lineWidth, MAX_LINES);
    }

    static List<String> paginate(String text, int width) {
        return TextPage.paginate(text, width, MAX_LINES);
    }

    /** 行宽改了以后按新行宽重新分页，尽量停在原来那一屏附近。 */
    private static void reflow() {
        if (rawText.isEmpty()) return;
        int oldCursor = cursor;
        List<String> result = paginate(rawText, lineWidth);
        if (result.isEmpty()) return;
        pages.clear(); pages.addAll(result);
        cursor = Math.max(0, Math.min(oldCursor, pages.size() - 1));
        persistProgress();   // 换行宽会改总屏数 → 存下来的屏号也得跟着更新
    }

    private static String displayName(Activity host, Uri uri) {
        try {
            android.database.Cursor c = host.getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (c.moveToFirst() && idx >= 0) return c.getString(idx);
                } finally { c.close(); }
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "未命名" : last;
    }

    // ---- 翻页 ----

    static void turn(Activity host, int delta, Runnable refresh) {
        if (pages.isEmpty()) return;
        Integer moved = move(delta);
        if (moved == null) {
            if (delta < 0) Toast.makeText(host, "已经是第一屏", Toast.LENGTH_SHORT).show();
            else { Toast.makeText(host, "已经是最后一屏", Toast.LENGTH_SHORT).show(); stopAuto(); }
            return;
        }
        if (refresh != null) refresh.run();
        pushCurrent(host);
    }

    /**
     * 移动光标（**不推送、不 Toast**）。返回新光标；动不了（到头了）返回 null。
     *
     * 抽出来的原因：手机按钮、语音翻页、自动翻页三条路都要"边界判断 + 落盘"，
     * 之前各写一遍 —— 语音那条就漏了落盘，于是"用语音翻到第 30 屏、重启回到第 1 屏"。
     * 现在边界、落盘、返回值语义只有这一处。
     */
    private static Integer move(int delta) {
        if (pages.isEmpty()) return null;
        int next = cursor + delta;
        if (next < 0 || next >= pages.size()) return null;
        cursor = next;
        persistProgress();
        return cursor;
    }

    /** 供语音翻页调用：不看 Activity，只要能推就推。 */
    static boolean turnByVoice(int delta) {
        if (move(delta) == null) return false;
        NavGlasses.acquire(OWNER);
        NavGlasses.show(pages.get(cursor), false);
        return NavGlasses.ready();
    }

    static boolean hasBook() { return !pages.isEmpty(); }
    static String currentPage() { return pages.isEmpty() ? "" : pages.get(cursor); }
    static String bookTitle() { return bookName; }
    static int cursor() { return cursor; }
    static int pageCount() { return pages.size(); }

    private static void pushCurrent(Activity host) {
        if (pages.isEmpty()) { Toast.makeText(host, "先导入一本书", Toast.LENGTH_SHORT).show(); return; }
        NavGlasses.acquire(OWNER);          // 抢通道：会让上一个占用者先停掉
        NavGlasses.show(pages.get(cursor), false);
        Toast.makeText(host, NavGlasses.ready() ? "已推到眼镜" : "正在开启显示通道，稍等会自动显示", Toast.LENGTH_LONG).show();
    }

    /** 会话过期（4 分钟）时自动重开，用户不用管。 */
    private static void ensureSession() {
        if (!NavGlasses.ready()) {
            NavGlasses.start("正在打开电子书…");
        }
    }

    // ---- 自动翻页 ----

    private static void startAuto(Activity host, Runnable refresh) {
        stopAuto();
        auto = true;
        timer = new android.os.Handler(android.os.Looper.getMainLooper());
        autoTask = new Runnable() {
            @Override public void run() {
                if (!auto) return;
                if (move(+1) == null) {
                    stopAuto();
                    Toast.makeText(host, "已读到最后一屏", Toast.LENGTH_SHORT).show();
                    if (refresh != null) refresh.run();
                    return;
                }
                NavGlasses.acquire(OWNER);
                NavGlasses.show(pages.get(cursor), false);
                if (refresh != null) refresh.run();
                timer.postDelayed(this, autoSeconds * 1000L);
            }
        };
        timer.postDelayed(autoTask, autoSeconds * 1000L);
        Toast.makeText(host, "自动翻页已开始，每屏 " + autoSeconds + " 秒", Toast.LENGTH_SHORT).show();
    }

    static void stopAuto() {
        auto = false;
        if (timer != null && autoTask != null) timer.removeCallbacks(autoTask);
        timer = null; autoTask = null;
    }
    static boolean autoRunning() { return auto; }
}

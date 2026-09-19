package com.turboio.addon;

import java.util.List;

/**
 * 电子书的**续读状态**规则 —— 纯逻辑层，可单测。
 *
 * ══ 用户需求 ═══════════════════════════════════════════════════
 * 「另外加电子书续读功能」
 *
 * 旧的电子书状态全在**进程内存**里（`pages` / `cursor` / `bookName` 都是 static
 * 字段）。App 一被杀掉（或用户只是切出去了一会儿被系统回收），
 * 书和进度**全没了** —— 重新打开要重新导入 txt、重新翻到原来那一屏。
 * 20MB 的 txt 手动翻回去，等于没有这个功能。
 *
 * 所以现在把三样东西落盘：
 *   ① 解析好的正文 → `filesDir/reader_book.txt`（重新分页的原料）；
 *   ② 书名 → prefs `reader_book_name`；
 *   ③ 读到第几屏 → prefs `reader_cursor`（每次翻页都写）。
 * 下次启动时重新分页 + 恢复光标，就是"接着上次读"。
 *
 * 为什么"存入的是屏号、而不是字数偏移"：分页是**确定性函数**
 * （正文 + 每行字数 + 每屏行数 → 屏列表），同一份正文与同一个行宽一定得到
 * 同一套屏；换行宽时 `ReaderUI.reflow()` 也会按比例保住位置。
 * 存屏号最简单，且不会因为半角/全角折算的细节漂移。
 *
 * 本类只做**判断与文案**（能不能续、续到哪、怎么跟用户说），
 * 读写文件/prefs 在 `ReaderUI` 里 —— 这样边界条件（进度越界、书换了、
 * 缓存缺失）全都能进单测。
 */
public final class ReaderBook {
    private ReaderBook() {}

    /** 本 App 自己的数据目录（与 `MemoStore` 同目录，整机只有一个地方要备份）。 */
    public static final String DIR = "turboio_android";
    /** 正文缓存文件名（放在 filesDir/{@link #DIR} 下）。 */
    public static final String CACHE_FILE = "reader_book.txt";
    /** prefs 键（与 ReaderUI 共用，改一处必须改另一处 —— 有单测钉着）。 */
    public static final String KEY_NAME = "reader_book_name";
    public static final String KEY_CURSOR = "reader_cursor";
    /** 缓存正文的上限，与导入时的 20MB 限制保持一致。 */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /** 恢复结论。 */
    public static final class Restore {
        /** 缓存里确实有书、可以续读 */
        public final boolean usable;
        /** 应该定位到哪一屏（已夹取到合法范围） */
        public final int cursor;
        /** 给用户/状态行看的一句话 */
        public final String note;
        Restore(boolean usable, int cursor, String note) {
            this.usable = usable; this.cursor = cursor; this.note = note;
        }
    }

    /**
     * 该不该恢复、恢复到哪一屏。
     *
     * @param savedName  上次存的书名（空 = 上次没导入过书）
     * @param savedCursor 上次存的屏号（可能是 -1 / 超出范围 —— 文件被换过、
     *                    或者用户改了行宽导致总屏数变少）
     * @param pageCount  重新分页后的总屏数（0 = 缓存正文读不出来）
     */
    public static Restore restore(String savedName, int savedCursor, int pageCount) {
        if (pageCount <= 0) {
            return new Restore(false, 0, "没有可续读的书（下次导入后会自动记住进度）");
        }
        if (savedName == null || savedName.trim().isEmpty()) {
            return new Restore(true, 0, "已恢复上次导入的书，从头开始");
        }
        int cursor = clampCursor(savedCursor, pageCount);
        String where = "第 " + (cursor + 1) + "/" + pageCount + " 屏";
        if (cursor <= 0) {
            return new Restore(true, 0, "已续读「" + savedName.trim() + "」，从头开始（" + where + "）");
        }
        return new Restore(true, cursor, "已续读「" + savedName.trim() + "」，接着上次的 " + where);
    }

    /** 把屏号夹到 [0, pageCount-1]。任何非法输入都落到 0，绝不返回越界值。 */
    public static int clampCursor(int saved, int pageCount) {
        if (pageCount <= 0) return 0;
        if (saved < 0) return 0;
        return Math.min(saved, pageCount - 1);
    }

    /** 进度文案：「第 3/120 屏 · 2%」。 */
    public static String progress(int cursor, int pageCount) {
        if (pageCount <= 0) return "还没有导入书";
        int c = clampCursor(cursor, pageCount);
        int percent = (int) Math.round((c + 1) * 100.0 / pageCount);
        return "第 " + (c + 1) + "/" + pageCount + " 屏 · " + percent + "%";
    }

    /**
     * 续读时的播报/展示文案（说话的入口用）。
     * 只给「书名 + 进度」，正文由调用方推眼镜 —— 正文不该出现在 TTS 里。
     */
    public static String spokenResume(String name, int cursor, int pageCount) {
        String title = name == null || name.trim().isEmpty() ? "这本书" : "「" + name.trim() + "」";
        if (pageCount <= 0) return "还没有导入书，先在电子书里选一个 txt 文件";
        return "继续读" + title + "，" + progress(cursor, pageCount);
    }

    /** 缓存正文是否超出上限（读文件前先判长度，避免把内存吃爆）。 */
    public static boolean withinLimit(long bytes) {
        return bytes > 0 && bytes <= MAX_BYTES;
    }

    /** 分页是不是空的（空 = 正文全是空白，续读没有意义）。 */
    public static boolean hasPages(List<String> pages) {
        return pages != null && !pages.isEmpty();
    }
}

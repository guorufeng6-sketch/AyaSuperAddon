package com.turboio.addon;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「随口记」的落盘层 —— 一个极小的 TSV 文件，**同步读写**。
 *
 * ── 为什么不用 SharedPreferences / 数据库 ────────────────────────
 *   · prefs 存列表要手工拼字符串，且读写在主线程，条目多了会卡；
 *   · SQLite/ Room 对"最多几百条短文本"是杀鸡用牛刀，还引进依赖。
 *   · 一行一条的纯文本**坏了一条不影响其它条**（`Memo.parseAll` 会跳过坏行），
 *     而且用户想导出/备份时直接拷文件就能读。
 *
 * ── 线程 ────────────────────────────────────────────────────────
 * 所有方法都可能被 native 回调线程（语音链路）调用，所以内部有锁；
 * 文件很小（500 条上限 × 200 字），同步 IO 的耗时在毫秒级，
 * 不值得为它引入线程池与"写失败静默丢数据"的风险。
 * **落盘成功才更新内存缓存** —— 宁可这次失败（有 Toast 告诉用户），
 * 也不要让用户以为记下了、实际重启就没了。
 */
final class MemoStore {
    private MemoStore() {}

    // 包内可见（调试页要报告"文件多大"）。目录与文件名只有这一处定义。
    static final String DIR = "turboio_android";
    static final String FILE = "memos.tsv";
    private static final Object LOCK = new Object();

    private static volatile Context app;
    private static List<Memo.Item> cache;

    /** 由 AyaSuperAddon.install() 调用一次即可（幂等）。 */
    static void bind(Context context) {
        if (context != null) app = context.getApplicationContext();
    }

    /** 全部条目（老 → 新）。没有数据时返回空列表，绝不返回 null。 */
    static List<Memo.Item> all() {
        synchronized (LOCK) {
            if (cache == null) cache = readLocked();
            return new ArrayList<>(cache);
        }
    }

    static int count() { return all().size(); }

    /** 最新一条；没有则 null。 */
    static Memo.Item latest() {
        List<Memo.Item> items = all();
        return items.isEmpty() ? null : items.get(items.size() - 1);
    }

    /** 记一条。返回 true = 已经落盘。 */
    static boolean add(long at, String text) {
        if (!Memo.hasContent(text)) return false;
        synchronized (LOCK) {
            if (cache == null) cache = readLocked();
            List<Memo.Item> next = Memo.append(cache, at, text);
            if (!writeLocked(next)) return false;
            cache = next;
            return true;
        }
    }

    /** 删掉最后一条。返回被删掉的那条（null = 本来就没有）。 */
    static Memo.Item removeLast() {
        synchronized (LOCK) {
            if (cache == null) cache = readLocked();
            if (cache.isEmpty()) return null;
            Memo.Item removed = cache.get(cache.size() - 1);
            List<Memo.Item> next = Memo.removeLast(cache);
            if (!writeLocked(next)) return null;
            cache = next;
            return removed;
        }
    }

    /** 删掉指定下标（界面里逐条删除）。返回是否成功。 */
    static boolean removeAt(int index) {
        synchronized (LOCK) {
            if (cache == null) cache = readLocked();
            if (index < 0 || index >= cache.size()) return false;
            List<Memo.Item> next = new ArrayList<>(cache);
            next.remove(index);
            if (!writeLocked(next)) return false;
            cache = next;
            return true;
        }
    }

    /** 清空。返回清掉了几条（0 = 本来就是空的；-1 = 落盘失败）。 */
    static int clear() {
        synchronized (LOCK) {
            if (cache == null) cache = readLocked();
            int was = cache.size();
            if (was == 0) return 0;
            if (!writeLocked(new ArrayList<Memo.Item>())) return -1;
            cache = new ArrayList<>();
            return was;
        }
    }

    // ── 读写 ──

    private static List<Memo.Item> readLocked() {
        Context ctx = app;
        if (ctx == null) return new ArrayList<>();
        try {
            File f = new File(new File(ctx.getFilesDir(), DIR), FILE);
            if (!f.isFile() || f.length() == 0) return new ArrayList<>();
            // 上限定得比 MAX_ITEMS 宽松，只用来防"文件被外部写爆导致 OOM"。
            if (f.length() > 4L * 1024 * 1024) return new ArrayList<>();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            List<Memo.Item> items = Memo.parseAll(out.toString("UTF-8"));
            return new ArrayList<>(items);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static boolean writeLocked(List<Memo.Item> items) {
        Context ctx = app;
        if (ctx == null) return false;
        File dir = new File(ctx.getFilesDir(), DIR);
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File f = new File(dir, FILE);
            // ★ 先写临时文件再改名：写到一半被杀掉时不会留下半截文件
            //   （半截文件里的第一条可能是坏的 —— 虽然 parseAll 会跳过，
            //    但"整本都没了"是不可接受的）。
            File tmp = new File(dir, FILE + ".tmp");
            byte[] data = Memo.serialize(items).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = new FileOutputStream(tmp)) { out.write(data); out.flush(); }
            if (f.exists() && !f.delete()) { /* 落到下面的 rename 失败分支 */ }
            if (!tmp.renameTo(f)) {
                // 改名失败（某些设备对同目录 rename 也挑）：退化成直接写。
                try (OutputStream out = new FileOutputStream(f)) { out.write(data); out.flush(); }
                tmp.delete();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 只读快照（给界面用，避免界面持有内部列表）。 */
    static List<Memo.Item> snapshotNewestFirst() {
        List<Memo.Item> out = new ArrayList<>(all());
        Collections.reverse(out);
        return out;
    }
}

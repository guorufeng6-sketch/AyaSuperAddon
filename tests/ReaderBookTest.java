import com.turboio.addon.ReaderBook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ReaderBook：电子书"续读"的规则层。
 *
 * 由来（用户原话）：「另外加电子书续读功能」。
 * 旧实现的书名/屏号全在进程内存里，App 一被杀就全没 —— 重新导入 20MB 的 txt
 * 再手动翻回原来那一屏，等于这个功能不存在。
 *
 * 重点钉四件事：
 *   ① 进度**永远不越界**（文件被换过、行宽改过导致总屏数变少都会发生）；
 *   ② 缓存缺失 / 空正文时要老实说"没得续"，不能假装有；
 *   ③ 分页是确定性函数，所以"存屏号"是安全的（这里用同一份正文反证一次）；
 *   ④ 上限判断（读文件前先看长度，别把内存吃爆）。
 */
public class ReaderBookTest {
    private static int checks = 0;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void eq(Object expected, Object actual, String message) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + "（期望 " + expected + "，实际 " + actual + "）");
        }
    }

    public static void main(String[] args) {
        // ── ① 缓存缺失 ──
        ReaderBook.Restore none = ReaderBook.restore("", 0, 0);
        ok(!none.usable, "没有缓存时必须说清「续不了」");
        ok(none.note.contains("没有"), "缺缓存文案要说清，实际：" + none.note);
        ok(!ReaderBook.restore("三体.txt", 12, 0).usable, "正文读不出来时也不能续");
        ok(!ReaderBook.restore(null, 0, -5).usable, "总屏数为负同样不能续");

        // ── ② 有缓存但没有书名（上次导入中途被杀）──
        ReaderBook.Restore anon = ReaderBook.restore("", 3, 50);
        ok(anon.usable, "有正文就能读，书名丢了不该影响阅读");
        eq(0, anon.cursor, "没有书名时从头开始");
        ok(anon.note.contains("恢复"), "要说明是恢复了上次的书，实际：" + anon.note);

        // ── ③ 正常续读 ──
        ReaderBook.Restore r = ReaderBook.restore("三体.txt", 41, 120);
        ok(r.usable, "正常情况可续");
        eq(41, r.cursor, "光标应原样恢复");
        ok(r.note.contains("三体.txt"), "文案要带书名");
        ok(r.note.contains("42/120"), "文案要带进度（1 基），实际：" + r.note);
        ok(ReaderBook.restore("三体.txt", 0, 120).note.contains("从头"), "第 1 屏要说「从头开始」");

        // ── ④ 越界夹取（这三种在真实使用里都会遇到）──
        eq(0, ReaderBook.clampCursor(-1, 120), "负数屏号 → 第 1 屏");
        eq(0, ReaderBook.clampCursor(0, 0), "没有屏时落 0");
        eq(119, ReaderBook.clampCursor(500, 120), "超过总屏数 → 最后一屏");
        eq(119, ReaderBook.clampCursor(999, 120), "离谱大数 → 最后一屏");
        eq(0, ReaderBook.clampCursor(50, -3), "总屏数非法 → 0");
        // 场景：用户改了"每行字数"，120 屏变 90 屏，原来存的是 110
        eq(89, ReaderBook.restore("三体.txt", 110, 90).cursor, "改行宽后总屏数变少，光标应夹到最后一屏");
        // 场景：换了一本更短的书
        eq(9, ReaderBook.restore("新书.txt", 400, 10).cursor, "换书后光标夹到新书最后一屏");

        // ── ⑤ 文案 ──
        eq("还没有导入书", ReaderBook.progress(0, 0), "无书时的进度文案");
        ok(ReaderBook.progress(0, 100).contains("1/100"), "进度是 1 基，实际：" + ReaderBook.progress(0, 100));
        ok(ReaderBook.progress(0, 100).contains("1%"), "第 1 屏约占 1%，实际：" + ReaderBook.progress(0, 100));
        ok(ReaderBook.progress(99, 100).contains("100%"), "最后屏是 100%");
        ok(ReaderBook.progress(3, 120).contains("3%"), "第 4 屏约占 3%，实际：" + ReaderBook.progress(3, 120));
        ok(ReaderBook.progress(999, 120).contains("120/120"), "越界也要夹住，实际：" + ReaderBook.progress(999, 120));

        String spoken = ReaderBook.spokenResume("三体.txt", 41, 120);
        ok(spoken.contains("三体"), "续读播报要带书名");
        ok(spoken.contains("42/120"), "续读播报要带进度");
        ok(ReaderBook.spokenResume("", 0, 10).contains("这本书"), "没书名时用「这本书」兜住");
        ok(ReaderBook.spokenResume("三体.txt", 0, 0).contains("没有"), "没书时播报要引导导入");

        // ── ⑥ 上限与空页 ──
        ok(ReaderBook.withinLimit(1024), "1KB 合法");
        ok(ReaderBook.withinLimit(ReaderBook.MAX_BYTES), "正好上限合法");
        ok(!ReaderBook.withinLimit(ReaderBook.MAX_BYTES + 1), "超上限不读（防 OOM）");
        ok(!ReaderBook.withinLimit(0), "0 字节当作没有缓存");
        ok(!ReaderBook.withinLimit(-1), "负长度不合法");
        ok(!ReaderBook.hasPages(null), "null 不算有页");
        ok(!ReaderBook.hasPages(new ArrayList<String>()), "空列表不算有页");
        ok(ReaderBook.hasPages(Arrays.asList("正文")), "有内容才算有页");

        // ── ⑦ 分页确定性（"存屏号"这件事的前提）──
        // 同一份正文 + 同一个行宽 → 一定得到同一套屏；否则续读会漂。
        String text = "第一段。第二段比较长一些，用来凑出行数。\n第三段。\n第四段。";
        List<String> a = com.turboio.addon.TextPage.paginate(text, 20, 5);
        List<String> b = com.turboio.addon.TextPage.paginate(text, 20, 5);
        eq(a, b, "同一份正文的分页结果必须完全一致（续读存屏号的前提）");

        System.out.println("ReaderBook: " + checks
            + " checks PASS (进度不越界 · 缺缓存诚实回报 · 文案 · 上限 · 分页确定性)");
    }
}

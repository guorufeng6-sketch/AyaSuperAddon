import com.turboio.addon.TextPage;

import java.util.List;

/**
 * 分页 / 行宽 / 截断的纯逻辑测试。
 *
 * ★ 为什么必须钉死"每页 ≤5 行" ★
 *   眼镜字幕通道只有 5 行、替换式，第 6 行根本画不出来。
 *   旧实现按"每屏 130 字"切，结果手机 6 行、眼镜 5 行，
 *   用户得转表冠往下滚才能读全 —— 所以这里把行数当成硬约束测。
 */
public class TextPageTest {
    static int n = 0;
    static void check(boolean ok, String why) {
        n++;
        if (!ok) throw new AssertionError(why);
    }
    static void eq(Object a, Object b, String why) {
        n++;
        if (a == null ? b != null : !a.equals(b))
            throw new AssertionError(why + "：期望 <" + b + "> 实际 <" + a + ">");
    }

    public static void main(String[] args) {
        // ① 行宽折算
        eq(TextPage.units('中'), 1.0, "CJK 算 1 个全角");
        eq(TextPage.units('a'), 0.5, "ASCII 算 0.5 个全角");
        eq(TextPage.units('，'), 1.0, "全角标点算 1");
        eq(TextPage.width("中文ab"), 3.0, "混排宽度");

        // ② 长段落必须被切成多页，且每页 ≤5 行
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) big.append("测");
        List<String> pages = TextPage.paginate(big.toString(), 20);
        check(pages.size() > 1, "400 字在每行 20 字下必须分成多页，实际 " + pages.size() + " 页");
        for (int i = 0; i < pages.size(); i++) {
            int lines = TextPage.lineCount(pages.get(i));
            check(lines <= TextPage.MAX_LINES,
                "第 " + i + " 页有 " + lines + " 行，超过眼镜的 " + TextPage.MAX_LINES + " 行上限");
            double widest = TextPage.widestLine(pages.get(i));
            check(widest <= 20.5, "第 " + i + " 页最宽一行 " + widest + " 超过行宽 20");
        }
        // 每页恰好 5 行（400 字足够整除前若干页）
        eq(TextPage.lineCount(pages.get(0)), 5, "第一页应当排满 5 行");

        // ③ 行首不放收尾标点
        List<String> punct = TextPage.paginate(
            "这是一句刚好卡在行宽边界上的话，后面还有内容继续写下去看看断行位置对不对", 10);
        for (String page : punct) {
            for (String line : page.split("\n")) {
                if (line.isEmpty()) continue;
                check(!TextPage.breaksAtStart(line.charAt(0)),
                    "行首出现了收尾标点 <" + line.charAt(0) + ">：" + line);
            }
        }

        // ④ 段落之间的空行不会被切到页首
        List<String> paras = TextPage.paginate("第一段\n\n第二段", 20);
        eq(paras.size(), 1, "两小段应当在同一页");
        check(!paras.get(0).startsWith("\n"), "页首不该是空行");

        // ⑤ 边界输入
        check(TextPage.paginate(null, 20).isEmpty(), "null 返回空表");
        check(TextPage.paginate("", 20).isEmpty(), "空串返回空表");
        check(TextPage.paginate("   ", 20).isEmpty(), "全空白返回空表");

        // ⑥ 截断（行宽不足时加省略号）
        eq(TextPage.clip("一二三四五", 3), "一二三…", "按全角截断加省略号");
        eq(TextPage.clip("ab", 1), "ab", "2 个半角 = 1 个全角位，正好放得下");
        eq(TextPage.clip("abc", 1), "ab…", "第 3 个半角就超了，要加省略号");
        eq(TextPage.clip("短", 10), "短", "没超长就不加省略号");

        // ⑦ 每行宽 16 时，5 行最多 80 个全角字 —— 一页绝不超
        List<String> narrow = TextPage.paginate(big.toString(), 16);
        for (String page : narrow) {
            check(TextPage.lineCount(page) <= TextPage.MAX_LINES, "窄行宽下仍然不能超过 5 行");
            check(TextPage.widestLine(page) <= 16.5, "窄行宽下不能超过行宽");
        }

        System.out.println("TextPage: " + n + " checks PASS (每页 ≤5 行 · 行宽 · 标点 · 截断)");
    }
}

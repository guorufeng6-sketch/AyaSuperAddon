import com.turboio.addon.NavCore;
import com.turboio.addon.NavGuide;
import com.turboio.addon.NavGuide.Style;
import com.turboio.addon.NavGuide.Turn;

import java.util.Arrays;
import java.util.List;

/**
 * NavGuide 负责"把高德的一句指令变成眼镜上一个箭头 + 一行短句"，
 * 并组出 5 行画面（箭头/原话/进度条/剩余里程/目的地）。
 *
 * ★ 关键约定：**默认样式 SAFE 只用 GB2312 内码表里的符号**。
 *   眼镜是中文固件，`↰ ↱ ⤴ ⟲ ↻ •` 这些连 GBK 都不在码表里，
 *   画不出来就是空白 —— 用户看到的效果等于"没有箭头"。
 *   所以这里的期望值全是 ← ↑ → ↓ ● ○ ■ □，改代码时别手滑换回花哨箭头。
 */
public class NavGuideTest {
    static int n = 0;
    static void eq(Object a, Object b, String why) {
        n++;
        if (a == null ? b != null : !a.equals(b))
            throw new AssertionError(why + "：期望 <" + b + "> 实际 <" + a + ">");
    }
    static void near(double a, double b, String why) {
        n++;
        if (Math.abs(a - b) > 0.5) throw new AssertionError(why + "：期望 <" + b + "> 实际 <" + a + ">");
    }
    static void check(boolean ok, String why) {
        n++;
        if (!ok) throw new AssertionError(why);
    }

    public static void main(String[] args) {
        NavGuide.setStyle(Style.SAFE);   // 默认样式，全程以此为准

        // ── 方向判定（高德真实语句）──
        eq(NavGuide.classify("沿长风西街向西行驶500米，右转"), Turn.RIGHT, "右转");
        eq(NavGuide.classify("左转进入建设南路"), Turn.LEFT, "左转");
        eq(NavGuide.classify("左前方转弯进入迎泽大街"), Turn.SLIGHT_LEFT, "左前方转弯应判为稍向左，不能被左转抢");
        eq(NavGuide.classify("右前方转弯"), Turn.SLIGHT_RIGHT, "右前方转弯");
        eq(NavGuide.classify("在道路尽头左转"), Turn.LEFT, "道路尽头左转");
        eq(NavGuide.classify("沿滨河西路行驶1.2公里"), Turn.STRAIGHT, "无方向词按直行");
        eq(NavGuide.classify("直行通过路口"), Turn.STRAIGHT, "直行");
        eq(NavGuide.classify("到达目的地"), Turn.ARRIVE, "到达");
        eq(NavGuide.classify("前方掉头"), Turn.UTURN, "掉头");
        eq(NavGuide.classify("调头"), Turn.UTURN, "调头=掉头");
        eq(NavGuide.classify("进入环岛，从第2出口驶出"), Turn.ROUNDABOUT, "环岛");
        eq(NavGuide.classify("靠右上匝道"), Turn.RAMP, "匝道");
        eq(NavGuide.classify(""), Turn.UNKNOWN, "空串 → UNKNOWN");
        eq(NavGuide.classify(null), Turn.UNKNOWN, "null → UNKNOWN");
        eq(NavGuide.classify("经过途经点"), Turn.WAYPOINT, "途经点");

        // ── 距离抠取 ──
        near(NavGuide.extractDistance("沿长风西街向西行驶500米，右转"), 500, "500米");
        near(NavGuide.extractDistance("行驶1.2公里后右转"), 1200, "1.2公里 → 1200米");
        near(NavGuide.extractDistance("行驶1公里后右转"), 1000, "1公里");
        near(NavGuide.extractDistance("直行"), -1, "无距离 → -1");
        near(NavGuide.extractDistance(null), -1, "null → -1");
        near(NavGuide.extractDistance(""), -1, "空 → -1");

        // ── 第一行组装（SAFE 字形）──
        NavGuide.Guidance g1 = NavGuide.of("沿长风西街向西行驶500米，右转", 500);
        eq(g1.turn, Turn.RIGHT, "转向类型");
        eq(g1.topLine, "→ 前方 500 米 右转", "第一行格式（SAFE：→ 而不是 ↱）");
        eq(g1.detail, "沿长风西街向西行驶500米，右转", "详情保留原话");
        near(g1.distance, 500, "距离");

        NavGuide.Guidance g2 = NavGuide.of("行驶1.2公里后左转", -1);
        eq(g2.topLine, "← 前方 1.2 公里 左转", "从原话抠距离并格式化公里（SAFE：← 而不是 ↰）");

        NavGuide.Guidance g3 = NavGuide.of("到达目的地", -1);
        eq(g3.turn, Turn.ARRIVE, "到达类型");
        eq(g3.topLine, "◎ 已到达目的地", "到达文案（◎ 本来就在 GB2312 里）");
        eq(g3.arrived(), true, "arrived=true");

        NavGuide.Guidance g4 = NavGuide.of("沿滨河西路行驶", -1);
        eq(g4.turn, Turn.STRAIGHT, "直行");
        eq(g4.topLine.startsWith("↑"), true, "无距离时也用箭头开头");

        NavGuide.Guidance g5 = NavGuide.of(null, -1);
        eq(g5.turn, Turn.UNKNOWN, "null 指令 → UNKNOWN");
        eq(g5.detail, "（无指令）", "null 指令的详情占位");

        // ── 三套字形都必须齐全 ──
        eq(Turn.RIGHT.safe, "→", "RIGHT.safe");
        eq(Turn.LEFT.safe, "←", "LEFT.safe");
        eq(Turn.STRAIGHT.safe, "↑", "STRAIGHT.safe");
        eq(Turn.UTURN.safe, "◆", "UTURN 在 GB2312 里没有掉头箭头，用 ◆ + 文字「掉头」");
        eq(Turn.ROUNDABOUT.safe, "○", "ROUNDABOUT 用圆圈表示环岛");
        eq(Turn.RIGHT.ascii, ">", "RIGHT.ascii");
        eq(Turn.UTURN.ascii, "U", "UTURN.ascii");
        for (Turn t : Turn.values()) {
            n++;
            if (t.fancy == null || t.fancy.isEmpty()) throw new AssertionError(t + " 缺精细箭头");
            if (t.safe == null || t.safe.isEmpty()) throw new AssertionError(t + " 缺安全箭头");
            if (t.ascii == null || t.ascii.isEmpty()) throw new AssertionError(t + " 缺 ASCII 箭头");
            if (t.label == null || t.label.isEmpty()) throw new AssertionError(t + " 缺标签");
            // ASCII 字形必须是纯 7-bit，这是"连 GB2312 都没有"时的最后兜底
            for (char c : t.ascii.toCharArray()) {
                if (c > 127) throw new AssertionError(t + " 的 ASCII 字形含非 ASCII 字符：" + c);
            }
        }

        // ── 样式切换 ──
        NavGuide.setStyle(Style.FANCY);
        eq(NavGuide.of("右转", 100).topLine, "↱ 前方 100 米 右转", "FANCY 用精细箭头");
        NavGuide.setStyle(Style.ASCII);
        eq(NavGuide.of("右转", 100).topLine, "> 前方 100 米 右转", "ASCII 用西文符号");
        eq(NavGuide.of("左转", 100).topLine, "< 前方 100 米 左转", "ASCII 左转");
        NavGuide.setStyle(Style.SAFE);
        eq(NavGuide.of("右转", 100).topLine, "→ 前方 100 米 右转", "切回 SAFE");

        eq(NavGuide.styleIndex(), 0, "SAFE 序号 0");
        eq(NavGuide.cycleStyle(), 1, "cycleStyle 单调递增");
        eq(NavGuide.style(), Style.FANCY, "cycleStyle 后样式跟着变");
        NavGuide.setStyleIndex(99);
        eq(NavGuide.style(), Style.ASCII, "越界序号夹紧到最后一个");
        NavGuide.setStyleIndex(-5);
        eq(NavGuide.style(), Style.SAFE, "负序号夹紧到 0");
        NavGuide.setStyle(Style.SAFE);

        // ── 进度条（只有 GB2312 字符）──
        eq(NavGuide.bar(0), "□□□□□□□□ 0%", "0% 全空");
        eq(NavGuide.bar(1), "■■■■■■■■ 100%", "100% 全满");
        eq(NavGuide.bar(0.5), "■■■■□□□□ 50%", "50% 半格");
        eq(NavGuide.bar(-3), "□□□□□□□□ 0%", "负值夹到 0");
        eq(NavGuide.bar(9), "■■■■■■■■ 100%", "超 1 夹到 100%");
        eq(NavGuide.bar(Double.NaN), "□□□□□□□□ 0%", "NaN 当 0");
        eq(NavGuide.barCellCount(), 8, "SAFE 8 格");
        NavGuide.setStyle(Style.FANCY);
        eq(NavGuide.bar(0.5), "●●●●●○○○○○ 50%", "FANCY 10 格");
        NavGuide.setStyle(Style.ASCII);
        eq(NavGuide.bar(0.5), "######------ 50%", "ASCII 12 格");
        NavGuide.setStyle(Style.SAFE);

        // ── 进度比例 ──
        near(NavGuide.progressRatio(1000, 400), 0.6, "已走 60%");
        near(NavGuide.progressRatio(1000, 0), 1.0, "剩 0 → 100%");
        near(NavGuide.progressRatio(1000, 1000), 0.0, "剩满 → 0%");
        near(NavGuide.progressRatio(1000, -1), -1, "剩余未知 → -1（不画进度条）");
        near(NavGuide.progressRatio(-1, 500), -1, "没有全程 → -1");
        near(NavGuide.progressRatio(1000, 9999), 0.0, "剩余超过全程时夹到 0");

        // ── 剩余里程 ──
        NavCore.Point p1 = new NavCore.Point(37.80, 112.50);
        NavCore.Point p2 = new NavCore.Point(37.81, 112.51);
        NavCore.Point p3 = new NavCore.Point(37.82, 112.52);
        NavCore.Point p4 = new NavCore.Point(37.83, 112.53);
        List<NavCore.Step> steps = Arrays.asList(
            new NavCore.Step("a", Arrays.asList(p1, p2), 1000),
            new NavCore.Step("b", Arrays.asList(p2, p3), 2000),
            new NavCore.Step("c", Arrays.asList(p3, p4), 500));
        near(NavGuide.remainingDistance(steps, 0, 400), 2900, "当前段剩 400 + 后两段 2500");
        near(NavGuide.remainingDistance(steps, 1, -1), 2500, "当前段未知 → 用本段全长 2000 + 500");
        near(NavGuide.remainingDistance(steps, 2, 0), 500, "最后一段");
        near(NavGuide.remainingDistance(steps, 9, 100), 100, "越界 idx 夹到最后一段");
        near(NavGuide.remainingDistance(null, 0, 100), -1, "空路线 → -1");
        // 高德没给 step_distance 时按折线几何兜底
        List<NavCore.Step> geo = Arrays.asList(new NavCore.Step("g", Arrays.asList(p1, p2), -1));
        check(NavGuide.remainingDistance(geo, 0, -1) > 0, "没有 step_distance 时用几何长度兜底");

        // ── 剩余时间按里程折算 ──
        eq(NavGuide.etaSeconds(1000, 500, 600), 300L, "剩一半 → 时间也一半");
        eq(NavGuide.etaSeconds(1000, 0, 600), 0L, "已到达 → 0 秒");
        eq(NavGuide.etaSeconds(1000, -1, 600), 600L, "剩余未知 → 退回全程时间");
        eq(NavGuide.etaSeconds(0, 100, 600), 600L, "没有全程 → 退回全程时间");
        eq(NavGuide.etaSeconds(1000, 100, -1), -1L, "没有时间 → -1（不显示分钟）");

        // ── 5 行组装：全信息（剩余里程已知）──
        String screen = NavGuide.compose(g1, "太原南站", 8200, 3100, 900, 2, 9);
        String[] lines = screen.split("\n");
        eq(lines[0], "→ 前方 500 米 右转", "第1行=箭头+距离+动作");
        eq(lines[1], "沿长风西街向西行驶500米，右转", "第2行=高德原话");
        eq(lines[2], "■■■■■□□□ 62%", "第3行=进度条（已走 5100/8200）");
        eq(lines[3], "剩 3.1 公里 · 约 6 分钟", "第4行=剩余里程 + 折算后的剩余时间");
        eq(lines[4], "→ 太原南站", "第5行=目的地");
        eq(lines.length, 5, "恰好 5 行");

        // ── 剩余里程未知：不画进度条，退回全程时间 ──
        String[] unknown = NavGuide.compose(g1, "太原南站", 8200, 900, 2, 9).split("\n");
        eq(unknown.length, 4, "没有剩余里程时少一行");
        eq(unknown[2], "全程 8.2 公里 · 约 15 分钟", "退回全程口径");

        // ── 到达：不重复原话，进度 100% ──
        String[] arrived = NavGuide.compose(NavGuide.of("到达目的地", -1), "太原南站", 8200, 0, 900, 8, 9).split("\n");
        eq(arrived[0], "◎ 已到达目的地", "到达第一行");
        eq(arrived[1], "■■■■■■■■ 100%", "到达进度 100%");
        eq(arrived[2], "已到达终点", "到达不再显示剩余分钟");
        eq(arrived[3], "→ 太原南站", "到达仍显示目的地");
        eq(arrived.length, 4, "到达时省略重复的原话行");

        // ── 边界 ──
        eq(NavGuide.compose(null, "公司", -1, -1, 0, 0).startsWith("↑ 等待定位"), true, "无指引 → 等待定位");
        eq(NavGuide.compose(null, "公司", -1, -1, 0, 0).split("\n").length, 2, "等待定位时只有两行");
        eq(NavGuide.headline(null), "↑ 等待定位…", "headline 兜底");
        eq(NavGuide.headline(g1), "→ 前方 500 米 右转", "headline 取值");

        // ── sample()：给「导航」页做样式预览，必须也是合法的 5 行 ──
        String[] sample = NavGuide.sample().split("\n");
        eq(sample.length, 5, "sample 5 行");
        eq(sample[0], "→ 前方 500 米 右转", "sample 第一行");
        eq(sample[4], "→ 太原南站", "sample 末行=目的地");

        // ── 长原话会被截断，保证一行放得下 ──
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 30; i++) longText.append("东");
        eq(NavGuide.clipHan(longText.toString(), 14).length(), 15, "超长截断+省略号");
        eq(NavGuide.clipHan("短", 14), "短", "短文本不截断");

        // ── ★ 默认样式的所有箭头都必须落在 GB2312 内码表内 ★ ──
        // 这份白名单是用 Python `ch.encode('gb2312')` 一个个验出来的；
        // 眼镜是中文固件，不在这张表里的符号画不出来（那一格是空白）。
        String gb2312 = "←↑→↓●○◎◇◆■□▲△★☆※";
        for (Turn t : Turn.values()) {
            n++;
            for (char c : t.safe.toCharArray()) {
                if (gb2312.indexOf(c) >= 0) continue;
                throw new AssertionError(t + " 的 SAFE 字形 <" + c + "> 不在 GB2312 白名单里，眼镜上会变空白");
            }
        }
        n++;
        for (char c : NavGuide.barOn().toCharArray()) {
            if (gb2312.indexOf(c) < 0) throw new AssertionError("进度条填充字符不在 GB2312：" + c);
        }
        n++;
        for (char c : NavGuide.barOff().toCharArray()) {
            if (gb2312.indexOf(c) < 0) throw new AssertionError("进度条空白字符不在 GB2312：" + c);
        }

        // ── 样式切换必须能绕回第一项（v3r12 修复）──
        // 旧实现 cycleStyle = setStyleIndex(ordinal+1)，而 setStyleIndex 越界是
        // "夹紧"不是"回绕"：切到最后一项（ASCII）之后再点还是 ASCII。
        // 用户实测「切到西文后就不能再继续切换了」就是这个。
        n++;
        NavGuide.setStyleIndex(0);
        for (int i = 0; i < NavGuide.styleCount(); i++) {
            int before = NavGuide.styleIndex();
            int after = NavGuide.cycleStyle();
            if (after == before) throw new AssertionError("cycleStyle 卡住了：序号 " + after + " 无法继续切换");
        }
        n++;
        if (NavGuide.styleIndex() != 0) {
            throw new AssertionError("转满一圈应当回到第 0 项，实际 " + NavGuide.styleIndex());
        }

        // ── 每项样式的预览都必须是 GB2312 安全的 ──
        for (int i = 0; i < NavGuide.styleCount(); i++) {
            n++;
            String preview = NavGuide.sampleFor(i);
            if (preview.isEmpty()) throw new AssertionError("样式 " + i + " 的样例为空");
            // 预览用完之后当前样式不能被改坏
            if (NavGuide.styleIndex() != 0) throw new AssertionError("sampleFor 不该改变当前样式");
        }
        n++;
        NavGuide.setStyleIndex(0);

        System.out.println("NavGuide: " + n + " checks PASS");
    }
}

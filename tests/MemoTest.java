import com.turboio.addon.Memo;

import java.util.ArrayList;
import java.util.List;

/**
 * Memo（备忘录）纯逻辑层。
 *
 * 这层的由来（用户原话）：
 *   「另加备忘录功能 语音打开备忘录 写入备忘录 手机可查看 可以取名随口记」
 * 2026-09-18 追加：
 *   「工具里的随口记改成备忘录，同步修改 tool 调用，随口记老是和官方待办冲突」
 *   → 模块名改成**备忘录**，「随口记」降级成别名；写入触发词里删掉官方也认的
 *   「备忘录 / 记事本 / 笔记」裸词，句首是「待办 / 日程」的一律让给官方。
 *
 * 重点钉六件事：
 *   ① **判序**：清空/朗读 必须先于 打开/写入 判，否则「清空备忘录」会被当成"打开"、
 *      「念念备忘录」也一样；
 *   ② **写入不能太贪**：「记一下 买牛奶」要能切出内容，但「我记不起来了」
 *      「备忘录在哪」绝不能记成一条笔记；
 *   ③ **与官方待办划清界线**：「备忘录 买牛奶」「添加待办 …」都不该被我们吃掉；
 *   ④ **内容保真**：句末语气词、「9:30」里的冒号都要留住，不能"顺手清理"；
 *   ⑤ 存储格式可往返（写坏的行跳过而不是整本丢）；
 *   ⑥ 上限生效（500 条裁最老；单条 200 字截断）。
 */
public class MemoTest {
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
        ok(Memo.NAME.equals("备忘录"), "模块名必须是「备忘录」（v3r20 用户改名）");
        ok(Memo.aliases().contains("备忘录"), "别名要含「备忘录」");
        ok(Memo.aliases().contains("随口记"), "别名要保留「随口记」（老用户嘴顺了还能用）");
        ok(Memo.aliases().contains("随手记"), "别名要含「随手记」");

        // ── ① 写入：切内容 ──
        eq(Memo.Action.ADD, Memo.parse("记一下 买牛奶"), "「记一下 X」应判为写入");
        eq("买牛奶", Memo.text("记一下 买牛奶"), "应切出内容");
        eq("买牛奶", Memo.text("随口记 买牛奶"), "别名做唤醒词也要能切内容");
        eq("买牛奶", Memo.text("随口记一下：买牛奶"), "中文冒号应被当首部标点去掉");
        eq("买牛奶", Memo.text("帮我记一下 买牛奶"), "长触发词优先");
        eq("买牛奶", Memo.text("记录一下 买牛奶"), "「记录一下」是触发词");
        eq("明天 9:30 开会", Memo.text("记一下 明天 9:30 开会"), "内容里的冒号必须保留（保真）");
        eq("提醒他一下", Memo.text("记一下 提醒他一下"), "句末语气词不剥（那是内容）");
        ok(Memo.hasContent("买牛奶"), "正常内容有效");
        ok(Memo.hasContent("好"), "单字内容也算有效（别把用户的话吞了）");
        ok(!Memo.hasContent("   "), "空白无效");
        ok(!Memo.hasContent("..."), "纯标点无效");
        ok(!Memo.hasContent(new String(new char[300]).replace('\0', 'x')), "超长无效");

        // 只说了触发词没说内容 → 仍是 ADD，由调用方追问
        eq(Memo.Action.ADD, Memo.parse("记一下"), "只说了「记一下」也判 ADD（追问比沉默好）");
        eq("", Memo.text("记一下"), "没有内容时 text() 返回空串");

        // ── ② 打开 / 朗读 / 清空 / 删除 ──
        eq(Memo.Action.OPEN, Memo.parse("打开随口记"), "「打开随口记」= 打开");
        eq(Memo.Action.OPEN, Memo.parse("打开备忘录"), "「打开备忘录」= 打开");
        eq(Memo.Action.OPEN, Memo.parse("打开备忘录吧"), "ASR 尾巴要容忍");
        eq(Memo.Action.OPEN, Memo.parse("随口记"), "光念一句别名 = 打开");
        eq(Memo.Action.LIST, Memo.parse("念念随口记"), "「念念随口记」= 朗读（不能让 GO 抢走）");
        eq(Memo.Action.LIST, Memo.parse("念一下备忘录"), "「念一下备忘录」= 朗读");
        eq(Memo.Action.LIST, Memo.parse("我记了什么"), "「我记了什么」= 朗读");
        eq(Memo.Action.LIST, Memo.parse("备忘录里有什么"), "「备忘录里有什么」= 朗读");
        eq(Memo.Action.CLEAR, Memo.parse("清空随口记"), "「清空随口记」= 清空（不能让 OPEN 抢走）");
        eq(Memo.Action.CLEAR, Memo.parse("清空备忘录"), "「清空备忘录」= 清空");
        eq(Memo.Action.DELETE_LAST, Memo.parse("删掉最后一条"), "「删掉最后一条」= 删最后");
        eq(Memo.Action.DELETE_LAST, Memo.parse("撤销刚才记的"), "「撤销刚才记的」= 删最后");

        // ── ③ 不能误抓 ──
        eq(Memo.Action.NONE, Memo.parse("我记不起来了"), "「记」不能裸当触发词");
        // 「备忘录在哪 / 怎么写」是**问这个功能**，不是要记东西。
        // 这两种最容易变成一条内容叫"在哪"／"怎么写" —— 那是本模块能犯的最糟的错误。
        eq(Memo.Action.NONE, Memo.parse("备忘录在哪"), "问「在哪」不得记成内容");
        eq(Memo.Action.NONE, Memo.parse("备忘录怎么写"), "问「怎么写」不得记成内容");
        eq(Memo.Action.NONE, Memo.parse("随口记怎么用"), "问「怎么用」不得记成内容");
        // …但明说了要记的，里面的疑问词是**内容**：
        eq(Memo.Action.ADD, Memo.parse("记一下 导航怎么用"), "有显式触发词时，「怎么用」是内容不是提问");
        eq("导航怎么用", Memo.text("记一下 导航怎么用"), "内容要保真");
        eq(Memo.Action.NONE, Memo.parse("今天天气怎么样"), "无关句不接管");
        eq(Memo.Action.NONE, Memo.parse(""), "空串不接管");
        eq(Memo.Action.NONE, Memo.parse(null), "null 不接管");
        eq(Memo.Action.NONE, Memo.parse(new String(new char[80]).replace('\0', '啊')), "超长句当聊天");
        eq("", Memo.text("今天天气怎么样"), "非写入句切不出内容");

        // ── ③b 与官方「待办 / 日程」划界（v3r20 用户报的"老是和官方待办冲突"）──
        // 「备忘录」现在是模块名，但**不再是写入触发词** ——
        // 官方 App 也认这个词，两边同时记一条就是用户说的"冲突"。
        ok(Memo.text("备忘录 买牛奶").isEmpty(), "光秃秃的「备忘录 X」不该被我们当写入");
        eq(Memo.Action.NONE, Memo.parse("备忘录 买牛奶"), "「备忘录 X」让给官方（它有真待办）");
        eq(Memo.Action.NONE, Memo.parse("待办 明天交电费"), "句首「待办」= 官方地盘");
        eq(Memo.Action.NONE, Memo.parse("添加待办 明天交电费"), "「添加待办」也是官方地盘");
        eq(Memo.Action.NONE, Memo.parse("日程 明天下午三点开会"), "「日程」= 官方地盘");
        eq(Memo.Action.NONE, Memo.parse("日程安排一下"), "句首「日程」且没有显式触发词 → 让路");
        // 但用户明说了要记，就照记（内容里的"待办"是内容）：
        eq(Memo.Action.ADD, Memo.parse("记一下 明天待办的事"), "显式触发词优先于让路规则");
        eq("明天待办的事", Memo.text("记一下 明天待办的事"), "内容保真，不去动「待办」两个字");
        // 别名「随口记 / 随手记」是官方绝不会用的词，仍然能当写入口令：
        eq(Memo.Action.ADD, Memo.parse("随口记 买牛奶"), "「随口记 X」仍然可用");
        eq("买牛奶", Memo.text("随手记 买牛奶"), "「随手记 X」仍然可用");

        // ── ④ 存储往返 ──
        List<Memo.Item> items = new ArrayList<>();
        items.add(new Memo.Item(1700000000000L, "买牛奶"));
        items.add(new Memo.Item(1700000060000L, "交电费"));
        String stored = Memo.serialize(items);
        List<Memo.Item> back = Memo.parseAll(stored);
        eq(2, back.size(), "往返条数一致");
        eq("买牛奶", back.get(0).text, "第一条内容一致");
        eq(1700000060000L, back.get(1).at, "时间戳一致");
        // 内容里的换行/制表符必须被压掉，否则一行变两行
        List<Memo.Item> one = new ArrayList<>();
        one.add(new Memo.Item(1L, "第一行\n第二行\t带制表"));
        String flat = Memo.serialize(one);
        eq(1, flat.split("\n").length, "一条内容必须压成一行");
        eq(1, Memo.parseAll(flat).size(), "压平后仍能读回一条");
        // 坏行跳过，好行保留
        List<Memo.Item> mixed = Memo.parseAll("坏行没有制表符\n1700000000000\t好的\nabc\t时间坏了\n1700000000001\t其次\n");
        eq(2, mixed.size(), "坏行应被跳过而不是整本丢，实际 " + mixed.size());
        eq("好的", mixed.get(0).text, "跳过后顺序不乱");
        eq(0, Memo.parseAll("").size(), "空串读出空列表");
        eq(0, Memo.parseAll(null).size(), "null 读出空列表");

        // ── ⑤ 上限与顺序 ──
        List<Memo.Item> big = new ArrayList<>();
        for (int i = 0; i < Memo.MAX_ITEMS; i++) big.add(new Memo.Item(i, "第" + i));
        List<Memo.Item> grown = Memo.append(big, 999, "新的");
        eq(Memo.MAX_ITEMS, grown.size(), "超过上限应裁掉最老的");
        eq("新的", grown.get(grown.size() - 1).text, "新的在末尾");
        eq("第1", grown.get(0).text, "最老的「第0」被裁掉");
        eq(0, Memo.append(null, 1, "x").size() - 1, "append(null) 应容错");
        eq(0, Memo.removeLast(new ArrayList<Memo.Item>()).size(), "空列表删最后不炸");
        // 单条上限在 append 里挡一次 —— 它是条目进入列表的唯一通道，
        // 在这里挡就不可能出现"界面 300 字、文件 200 字、重启后对不上"。
        List<Memo.Item> trimmed = Memo.append(new ArrayList<Memo.Item>(), 1, new String(new char[300]).replace('\0', 'y'));
        eq(Memo.MAX_TEXT, trimmed.get(0).text.length(), "单条超长应被截断到上限");
        eq(0, Memo.append(new ArrayList<Memo.Item>(), 1, "   ").size(), "空内容不该被追加（否则内存有、盘上没有）");
        eq(0, Memo.append(new ArrayList<Memo.Item>(), 1, null).size(), "null 内容不该被追加");

        List<Memo.Item> ordered = new ArrayList<>();
        ordered.add(new Memo.Item(1, "old"));
        ordered.add(new Memo.Item(2, "new"));
        eq("new", Memo.newestFirst(ordered).get(0).text, "最新在前");
        eq("old", ordered.get(0).text, "newestFirst 不得改动原列表");

        // ── ⑥ 文案 ──
        String empty = Memo.glasses(new ArrayList<Memo.Item>(), 4);
        ok(empty.contains(Memo.NAME), "空状态文案要带模块名");
        ok(empty.contains("记一下"), "空状态要教用户怎么说");
        String card = Memo.glasses(items, 4);
        ok(card.startsWith(Memo.NAME), "眼镜文案首行是标题");
        ok(card.contains("2 条"), "要带条数");
        ok(card.contains("交电费"), "最近一条要在里面");
        ok(Memo.glasses(items, 1).contains("还有 1 条"), "超出上限要提示还有多少条");
        String spoken = Memo.spoken(items, 3);
        ok(spoken.contains("2 条"), "朗读文案要报总数");
        ok(spoken.contains("交电费"), "朗读要念出最新内容");
        ok(!Memo.spoken(items, 3).contains("\n"), "朗读文案不能有换行（TTS 会念出奇怪停顿）");
        ok(Memo.addedReply("买牛奶").contains("买牛奶"), "写入回执要带上内容");
        ok(Memo.clearedReply(0).contains("空"), "清空空库要说清本来就空");
        ok(Memo.clearedReply(3).contains("3"), "清空要报条数");
        ok(Memo.deletedReply(false).contains("没有"), "无可删要说清");
        ok(Memo.needContentReply().contains("记一下"), "追问要给出示范说法");
        ok(Memo.stamp(1700000000000L).length() >= 11, "时间戳应形如 MM-dd HH:mm，实际 " + Memo.stamp(1700000000000L));
        ok(Memo.clip("一二三四五", 3).endsWith("…"), "过长要截断加省略号");
        eq("一二三", Memo.clip("一二三", 5), "不超长时原样返回");

        System.out.println("Memo: " + checks
            + " checks PASS (判序 · 内容保真 · 不误抓 · TSV 往返容错 · 上限与文案)");
    }
}

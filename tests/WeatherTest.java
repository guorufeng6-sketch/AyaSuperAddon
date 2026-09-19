import com.turboio.addon.Weather;

/**
 * 天气排版 + 语音解析的纯逻辑测试。
 *
 * ★ 两条硬约束 ★
 *   ① 眼镜上**只能 5 行**：多出来的行不存在，字段缺失也不能顶出第 6 行；
 *   ② 每行只用 **GB2312 内码表**里的字符（中文固件画不出的字符 = 空白格，
 *      用户看到的是"数据缺了一块"）。所以这里逐个字符查白名单。
 */
public class WeatherTest {
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

    /**
     * GB2312 白名单：ASCII + 常用中文 + 我们允许的符号。
     * 用"必须命中白名单"而不是"逐个排除"—— 新增字段忘了检查时会直接失败。
     */
    static boolean safe(char c) {
        if (c == '\n') return true;
        if (c >= 0x20 && c <= 0x7E) return true;            // ASCII 可见字符
        if (c >= 0x4E00 && c <= 0x9FFF) return true;        // 常用汉字
        if ("℃·。，、；：！？（）【】《》「」…—％".indexOf(c) >= 0) return true;
        if ("←↑→↓●○◎◇◆■□▲△★☆※".indexOf(c) >= 0) return true;
        return false;
    }

    public static void main(String[] args) {
        Weather.Now full = new Weather.Now(
            "山西太原", "多云", 24.4, 22.1, "38", "西北", "3",
            "0.0", "1002", "25", "2026-09-18T15:20+08:00", "12", "26");
        String frame = Weather.compose(full);

        // ① 行数 ≤ 5
        int lines = 1;
        for (char c : frame.toCharArray()) if (c == '\n') lines++;
        check(lines <= Weather.LINES, "天气画面 " + lines + " 行，超过眼镜的 " + Weather.LINES + " 行上限");
        eq(lines, 5, "字段齐全时应当用满 5 行");

        // ② 每行宽度
        for (String line : frame.split("\n")) {
            check(Weather.clip(line, Weather.LINE_MAX).equals(line),
                "有一行超过 " + Weather.LINE_MAX + " 个全角字：" + line);
        }

        // ③ 字符白名单
        for (char c : frame.toCharArray()) {
            if (!safe(c)) throw new AssertionError("字符 <" + c + "> (U+" + Integer.toHexString(c)
                + ") 可能不在 GB2312 内码表里，眼镜上会变空白");
        }

        // ④ 内容确实包含关键数据
        check(frame.contains("24℃"), "应当显示气温 24℃");
        check(frame.contains("22℃"), "体感与气温差 ≥1 时应当显示体感");
        check(frame.contains("38%"), "应当显示湿度");
        check(frame.contains("西北"), "应当显示风向（去掉尾部'风'字）");
        check(frame.contains("12~26℃"), "应当显示今天区间");
        check(frame.contains("太原"), "应当显示城市");

        // ⑤ 字段缺失时不崩、不超行
        Weather.Now bare = new Weather.Now("", "", 18, 18, "", "", "", "", "", "", "", "", "");
        String onlyTemp = Weather.compose(bare);
        eq(onlyTemp, "◎ 当前\n气温 18℃", "只剩气温时应当只有 2 行");
        check(onlyTemp.split("\n").length <= Weather.LINES, "字段缺失也不能超过 5 行");

        // ⑥ 体感与气温相近时不重复占位
        Weather.Now same = new Weather.Now("太原", "晴", 20, 20.0, "50", "东", "2",
            "0.0", "1010", "20", "15:20", "10", "22");
        check(!Weather.compose(same).contains("体感"), "体感与气温相同就不该再显示一行体感");

        // ⑦ 风向裁剪
        eq(Weather.stripWind("西北风"), "西北", "去掉尾部的'风'字");
        eq(Weather.stripWind("微风"), "微", "仍然只去尾部一个'风'");
        eq(Weather.stripWind(""), "", "空串原样返回");

        // ⑧ 语音：城市抽取
        eq(Weather.voiceCity("太原天气"), "太原", "「太原天气」");
        eq(Weather.voiceCity("查一下北京的天气"), "北京", "「查一下北京的天气」");
        eq(Weather.voiceCity("上海今天天气怎么样"), "上海", "「上海今天天气怎么样」");
        eq(Weather.voiceCity("今天天气怎么样"), "", "没说城市时返回空串（交给默认城市）");
        eq(Weather.voiceCity("天气"), "", "光说天气不构成城市");
        eq(Weather.voiceCity("this is weather"), "", "英文/非中文不当城市");

        // ⑨ 语音：哪些算天气问句
        check(Weather.isWeatherQuery("今天天气怎么样"), "含'天气'应当命中");
        check(Weather.isWeatherQuery("外面多少度"), "含'多少度'应当命中");
        check(Weather.isWeatherQuery("要带伞吗"), "含'要带伞'应当命中");
        check(!Weather.isWeatherQuery("导航去太原南站"), "导航不该被当成天气");
        check(!Weather.isWeatherQuery(""), "空串不命中");

        // ⑩ 时间/日期压缩
        eq(Weather.shortTime("2026-09-18T15:20+08:00"), "15:20", "取 HH:mm");
        eq(Weather.shortDate("2026-09-18"), "09/18", "取 MM/DD");

        // ══════════════════════════════════════════════════════════
        //  第 2~4 页：空气质量 / 未来三天 / 生活指数
        //  数据用 2026-09-18 对太原的**真实返回**，排版是照着真数据调的。
        // ══════════════════════════════════════════════════════════

        // ⑪ 空气质量页
        Weather.Air air = new Weather.Air("68", "良", "O3", "34", "52", "88", "12", "5", "0.6",
            "空气质量可接受，但某些污染物可能对极少数异常敏感人群健康有较弱影响。");
        String airFrame = Weather.composeAir(air);
        check(airFrame.split("\n").length <= Weather.LINES, "空气质量页不能超过 5 行");
        check(airFrame.startsWith("◎ 空气质量 良"), "首行应当是 AQI 等级");
        check(airFrame.contains("AQI 68"), "应当显示 AQI 数值");
        check(airFrame.contains("主要 O3"), "应当显示首要污染物");
        check(airFrame.contains("PM2.5 34"), "应当显示 PM2.5");
        for (String line : airFrame.split("\n")) {
            check(Weather.clip(line, Weather.LINE_MAX).equals(line), "空气质量页有超宽行：" + line);
        }
        for (char c : airFrame.toCharArray()) {
            if (!safe(c)) throw new AssertionError("空气质量页出现 GB2312 外字符 <" + c + ">");
        }
        check(airFrame.split("\n").length <= Weather.LINES, "长建议也必须收敛到 5 行内");

        // ⑫ 空气质量：字段缺失也不能崩、不能超行
        Weather.Air sparse = new Weather.Air("55", "良", "", "20", "", "", "", "", "", "");
        String sparseFrame = Weather.composeAir(sparse);
        check(sparseFrame.split("\n").length <= Weather.LINES, "缺字段时不能超过 5 行");
        check(!sparseFrame.contains("null"), "缺失字段不得打印 null");
        eq(Weather.composeAir(null), "◎ 没有拿到空气质量", "null 输入给明确提示");

        // ⑬ 未来三天页
        java.util.List<Weather.Day> days = new java.util.ArrayList<>();
        days.add(new Weather.Day("09/18", "多云", "12", "26", "06:14", "18:36"));
        days.add(new Weather.Day("09/19", "晴", "13", "27", "06:15", "18:34"));
        days.add(new Weather.Day("09/20", "阴", "14", "25", "06:16", "18:33"));
        String dayFrame = Weather.composeDays(days);
        check(dayFrame.startsWith("◎ 未来 3 天"), "首行应当是表头");
        check(dayFrame.contains("09/18 多云 12~26℃"), "应当有完整的一天：日期 天气 区间");
        check(dayFrame.contains("日出 06:14"), "第 5 行应当用日出日落收尾");
        eq(dayFrame.split("\n").length, 5, "表头 + 3 天 + 日出日落 = 正好 5 行");
        for (String line : dayFrame.split("\n")) {
            check(Weather.clip(line, Weather.LINE_MAX).equals(line), "预报页有超宽行：" + line);
        }
        java.util.List<Weather.Day> oneDay = new java.util.ArrayList<>();
        oneDay.add(new Weather.Day("09/18", "多云", "12", "26", "06:14", "18:36"));
        check(Weather.composeDays(oneDay).split("\n").length <= 5, "单日预报也要 ≤5 行");
        eq(Weather.composeDays(null), "◎ 没有拿到预报", "null 输入给明确提示");

        // ⑭ 生活指数页
        java.util.List<Weather.Index> indices = new java.util.ArrayList<>();
        indices.add(new Weather.Index("穿衣", "舒适"));
        indices.add(new Weather.Index("紫外线", "中等"));
        indices.add(new Weather.Index("运动", "适宜"));
        indices.add(new Weather.Index("洗车", "适宜"));
        indices.add(new Weather.Index("钓鱼", "适宜"));   // 第 5 条必须被丢掉
        String idxFrame = Weather.composeIndices(indices);
        check(idxFrame.startsWith("◎ 今日生活指数"), "首行应当是表头");
        check(idxFrame.contains("穿衣 舒适"), "应当有条目");
        eq(idxFrame.split("\n").length, 5, "表头 + 最多 4 条 = 5 行");
        check(!idxFrame.contains("钓鱼"), "第 5 条指数不该出现（会顶出第 6 行）");
        eq(Weather.composeIndices(null), "◎ 没有拿到生活指数", "null 输入给明确提示");

        // ⑮ API Host 归一化 —— 从控制台粘贴时最容易带上的多余字符
        eq(Weather.normalizeHost("qg6tuqynjn.re.qweatherapi.com"), "qg6tuqynjn.re.qweatherapi.com", "原样");
        eq(Weather.normalizeHost("https://qg6tuqynjn.re.qweatherapi.com"), "qg6tuqynjn.re.qweatherapi.com", "去掉协议头");
        eq(Weather.normalizeHost("https://qg6tuqynjn.re.qweatherapi.com/"), "qg6tuqynjn.re.qweatherapi.com", "去掉结尾斜杠");
        eq(Weather.normalizeHost("  qg6tuqynjn.re.qweatherapi.com  "), "qg6tuqynjn.re.qweatherapi.com", "去掉首尾空格");
        eq(Weather.normalizeHost("http://devapi.qweather.com/v7/weather/now?key=x"), "devapi.qweather.com", "带路径和查询串只留主机");
        eq(Weather.normalizeHost(""), "", "空串");
        eq(Weather.normalizeHost(null), "", "null");
        String host = Weather.normalizeHost("https://abc.re.qweatherapi.com/geo/v2/");
        check(!host.contains("://") && !host.contains("/") && !host.contains("?"),
            "归一化结果里不该再有协议头、路径或查询串：" + host);

        System.out.println("Weather: " + n + " checks PASS (≤5 行 · GB2312 · 语音城市抽取 · 4 页翻页 · Host 归一化)");
    }
}

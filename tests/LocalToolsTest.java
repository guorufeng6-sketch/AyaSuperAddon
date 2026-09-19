import com.turboio.addon.Countdown;
import com.turboio.addon.LocalCapabilities;
import com.turboio.addon.NavCore;
import com.turboio.addon.Weather;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/**
 * LocalCapabilities：把"本机自建能力"注册成模型工具时的规则层。
 *
 * 这层的存在理由（用户实测反馈）：
 *   模型回答「导航不在我能操作的范围内」「倒计时不在我可控制的范围」——
 *   因为导航/倒计时/天气只挂在语音拦截器上，**从没进过 tools 数组**，
 *   而米家进了，所以米家一直好用。这里钉死"哪些工具注册、参数怎么合成"。
 *
 * 重点钉四件事：
 *   ① 门控：没填和风 Key 就不注册天气（否则会把能用的官方天气换成"请填 Key"）；
 *   ② 倒计时口令合成后，**必须能被语音解析器原样解析回同样的秒数**（闭环）；
 *   ③ 目的地/城市名清洗的边界（太长、太短、带语气词、带控制字符）；
 *   ④ 高德错误码 → 人话（规划失败要能说清是限流还是 Key 失效）。
 */
public class LocalToolsTest {
    private static int checks = 0;
    private static void ok(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        // ── ① 门控 ──
        // 第三参数 = Tavily Key 配好没（v3r24 起的联网搜索门控）。
        // 下面这几条只验证"天气门控"，所以先把联网搜索置为已配置，隔离变量。
        List<String> withWeather = LocalCapabilities.names(true, true, true);
        List<String> withoutWeather = LocalCapabilities.names(false, true, true);
        ok(withWeather.contains(LocalCapabilities.WEATHER_NOW), "配好 Key 后应注册天气工具");
        ok(!withoutWeather.contains(LocalCapabilities.WEATHER_NOW), "没配 Key 时不得注册天气工具");
        ok(withoutWeather.contains(LocalCapabilities.NAV_START), "导航工具必须无条件注册");
        ok(withoutWeather.contains(LocalCapabilities.NAV_STOP), "导航结束工具必须无条件注册");
        ok(withoutWeather.contains(LocalCapabilities.NAV_STATUS), "导航状态工具必须无条件注册");
        ok(withoutWeather.contains(LocalCapabilities.TIMER_START), "倒计时工具必须无条件注册");
        ok(withoutWeather.contains(LocalCapabilities.TIMER_CANCEL), "倒计时取消必须无条件注册");
        ok(withoutWeather.contains(LocalCapabilities.TIMER_STATUS), "倒计时状态必须无条件注册");
        ok(withoutWeather.size() == LocalCapabilities.ALL.size() - 1, "只应少掉天气一个工具");
        ok(withWeather.size() == LocalCapabilities.ALL.size(), "配好 Key 后应为全集");
        // ── ①b 联网搜索门控（v3r24）：没填 Tavily Key 就不得注册 ──
        ok(!LocalCapabilities.names(true, true, false).contains(LocalCapabilities.WEB_SEARCH),
            "没填 Tavily Key 时不得注册联网搜索");
        ok(LocalCapabilities.names(true, true, true).contains(LocalCapabilities.WEB_SEARCH),
            "填了 Tavily Key 才注册联网搜索");
        // 热搜走免 Key 数据源，必须无条件可用
        ok(LocalCapabilities.names(false, true, false).contains(LocalCapabilities.NEWS_HOT),
            "热搜免 Key，应无条件注册");
        ok(LocalCapabilities.handles("nav_start"), "handles 认导航");
        ok(LocalCapabilities.handles(" nav_start "), "handles 应容忍首尾空格");
        ok(!LocalCapabilities.handles("mijia_control"), "米家工具不归本类管");
        ok(!LocalCapabilities.handles(null), "null 不认");
        ok(LocalCapabilities.normalizeName("NAV_START").equals("nav_start"), "工具名应可小写化比对");

        // ── ② 倒计时口令合成（用语音解析器反证）──
        String c1 = LocalCapabilities.timerCommand("3", "", "煮面");
        ok("倒计时 3 分钟 煮面".equals(c1), "合成口令应含时长与名字，实际：" + c1);
        ok(Countdown.parseDuration(c1) == 180, "3 分钟应解析成 180 秒");
        ok("煮面".equals(Countdown.parseName(c1)), "名字应被解析出来，实际：" + Countdown.parseName(c1));

        String c2 = LocalCapabilities.timerCommand("", "30", "");
        ok(Countdown.parseDuration(c2) == 30, "30 秒应解析成 30 秒，实际：" + c2);
        ok("倒计时".equals(Countdown.parseName(c2)), "没给名字时用默认名");

        String c3 = LocalCapabilities.timerCommand("1", "30", null);
        ok(Countdown.parseDuration(c3) == 90, "1 分 30 秒应相加成 90 秒，实际：" + c3);

        String c4 = LocalCapabilities.timerCommand("0", "45", "");
        ok(Countdown.parseDuration(c4) == 45, "0 分钟不该被写进口令，实际：" + c4);
        ok(!c4.contains("0 分钟"), "0 分钟不该出现在口令里");

        ok(LocalCapabilities.timerCommand("25", "", "番茄钟") != null, "番茄钟时长合法");
        // 番茄钟名字带进口令后仍能被语音解析成 25 分钟
        ok(Countdown.parseDuration(LocalCapabilities.timerCommand("25", "", "番茄钟")) == 1500, "25 分钟 = 1500 秒");

        ok(LocalCapabilities.timerCommand("", "", "") == null, "没给时长应拒绝");
        ok(LocalCapabilities.timerCommand(null, null, "煮面") == null, "null 时长应拒绝");
        ok(LocalCapabilities.timerCommand("0", "0", "") == null, "0 秒应拒绝");
        ok(LocalCapabilities.timerCommand("-5", "", "") == null, "负数应拒绝");
        ok(LocalCapabilities.timerCommand("3.5", "", "") == null, "小数应拒绝（倒计时不需要这个精度）");
        ok(LocalCapabilities.timerCommand("3分钟", "", "") == null, "带单位应拒绝（schema 要的是整数）");
        ok(LocalCapabilities.timerCommand("1441", "", "") == null, "超过 24 小时应拒绝");
        ok(LocalCapabilities.timerCommand("1440", "", "") != null, "正好 24 小时应接受");
        ok(LocalCapabilities.timerCommand("", "86400", "") != null, "正好 86400 秒应接受");
        ok(LocalCapabilities.timerCommand("", "86401", "") == null, "超一天应拒绝");
        ok(LocalCapabilities.timerCommand("999999", "", "") == null, "离谱大数应拒绝");
        ok(LocalCapabilities.timerCommand("", "", new String(new char[40]).replace('\0', 'x')) == null,
            "既没有时长又没有合法名字，应当拒绝");
        String longName = LocalCapabilities.timerCommand("5", "", new String(new char[40]).replace('\0', 'x'));
        ok("倒计时 5 分钟".equals(longName), "超长名字应被丢弃、时长仍成立，实际：" + longName);

        // ── ③ 目的地清洗 ──
        ok("太原南站".equals(LocalCapabilities.destination("太原南站")), "普通地名应原样保留");
        ok("太原南站".equals(LocalCapabilities.destination(" 太原南站 ")), "空格应被去掉");
        ok("太原南站".equals(LocalCapabilities.destination("太原南站吧")), "语气词应被剥掉");
        ok("太原南站".equals(LocalCapabilities.destination("太原南站\u0007")), "控制字符应被去掉");
        ok(LocalCapabilities.destination("").isEmpty(), "空串拒绝");
        ok(LocalCapabilities.destination(null).isEmpty(), "null 拒绝");
        ok(LocalCapabilities.destination("那").isEmpty(), "单字指代拒绝（语音拿不到上下文）");
        ok(LocalCapabilities.destination("等一下").isEmpty(), "纯语气词拒绝");
        ok(LocalCapabilities.destination(new String(new char[80]).replace('\0', '京')).isEmpty(),
            "过长（整句被吞）应拒绝");

        // 模型会把「导航到」一起塞进 destination —— 必须剥掉，不能去搜"导航到太原南站"
        ok("太原南站".equals(LocalCapabilities.destination("导航到太原南站")), "应剥掉「导航到」前缀");
        ok("太原南站".equals(LocalCapabilities.destination("带我去太原南站")), "应剥掉「带我去」前缀");
        ok("太原南站".equals(LocalCapabilities.destination("导航到 太原南站吧")), "前缀与语气词应一起剥");
        ok(LocalCapabilities.destination("导航到").isEmpty(), "只有前缀没有地名应拒绝");
        ok(LocalCapabilities.destination("导航").isEmpty(), "只有「导航」两字应拒绝");

        // 用户只是在问功能，模型不该顺手开导航
        ok(LocalCapabilities.destination("导航怎么用").isEmpty(), "问功能句不得当目的地");
        ok(LocalCapabilities.destination("太原站在哪").isEmpty(), "问「在哪」不得当目的地");
        ok(LocalCapabilities.destination("导航是什么意思").isEmpty(), "问「是什么」不得当目的地");
        // 但不能误伤正常地名
        ok("万象城".equals(LocalCapabilities.destination("万象城")), "正常地名不受问句规则影响");
        ok("长风商务区".equals(LocalCapabilities.destination("长风商务区")), "正常地名不受影响");

        // ── ④ 天气口令 ──
        String w1 = LocalCapabilities.weatherCommand("太原");
        ok("太原天气".equals(w1), "城市名应拼成问句，实际：" + w1);
        ok(Weather.isWeatherQuery(w1), "合成问句应被天气模块认出来");
        ok("太原".equals(Weather.voiceCity(w1)), "城市应能被反解出来");
        String w2 = LocalCapabilities.weatherCommand("北京市");
        ok("北京天气".equals(w2), "「市」应被去掉，实际：" + w2);
        ok("北京".equals(Weather.voiceCity(w2)), "去掉「市」后仍能反解");
        ok("天气".equals(LocalCapabilities.weatherCommand("")), "空城市应回落到「天气」（用设置里的城市）");
        ok("天气".equals(LocalCapabilities.weatherCommand(null)), "null 城市应回落");
        ok("天气".equals(LocalCapabilities.weatherCommand("New York")), "非中文城市名应回落到默认城市");
        ok("天气".equals(LocalCapabilities.weatherCommand(new String(new char[20]).replace('\0', '京'))),
            "超长城市名应回落到默认城市");

        // ── ⑤ 参数白名单 ──
        ok(LocalCapabilities.onlyKeys(new HashSet<>(Arrays.asList("destination")), "destination"), "允许的 key");
        ok(!LocalCapabilities.onlyKeys(new HashSet<>(Arrays.asList("destination", "evil")), "destination"), "多余 key 拒绝");
        ok(LocalCapabilities.onlyKeys(new HashSet<String>(), "destination"), "空参数集合合法");

        // ── ⑥ 高德错误码 → 人话 ──
        String r1 = NavCore.amapReason("DAILY_QUERY_OVER_LIMIT", "10003");
        ok(r1.contains("额度"), "限流要说清额度用尽，实际：" + r1);
        ok(r1.contains("10003"), "错误码要带上，便于排查，实际：" + r1);
        String r2 = NavCore.amapReason("INVALID_USER_KEY", "10001");
        ok(r2.contains("Key"), "Key 失效要说清，实际：" + r2);
        String r3 = NavCore.amapReason("USERKEY_PLAT_NOMATCH", "10009");
        ok(r3.contains("Android 平台"), "平台不符要指向正确的 Key 类型（Android 平台），实际：" + r3);
        String r4 = NavCore.amapReason("INVALID_PARAMS", "20000");
        ok(r4.contains("参数"), "参数错误要说清，实际：" + r4);
        String r5 = NavCore.amapReason("SOMETHING_NEW", "99999");
        ok(r5.startsWith("高德："), "未知码也要有可读前缀，实际：" + r5);
        ok(r5.contains("SOMETHING_NEW"), "未知码要保留原始 info，实际：" + r5);
        ok(NavCore.amapReason("", "").startsWith("高德："), "空值不应崩");

        System.out.println("LocalTools: " + checks
            + " checks PASS (本机能力已注册为工具 · 门控 · 倒计时口令可被语音解析器反解 · 高德错误码人话化)");
    }
}

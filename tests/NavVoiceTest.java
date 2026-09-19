import com.turboio.addon.NavVoice;
import com.turboio.addon.NavVoice.Action;

/**
 * NavVoice 决定"这句话要不要变成一次导航、目的地是哪几个字"。
 * 这是语音直达导航的第一道闸门，错了要么误触发、要么叫不动。
 * 这里把常见的说法逐条钉死。
 */
public class NavVoiceTest {
    static int n = 0;
    static void eq(Object a, Object b, String why) {
        n++;
        if (a == null ? b != null : !a.equals(b))
            throw new AssertionError(why + "：期望 <" + b + "> 实际 <" + a + ">");
    }

    public static void main(String[] args) {
        // ── 开始导航：各种前缀 ──
        for (String s : new String[]{
            "导航去太原南站", "导航到太原南站", "导航至太原南站", "导航前往太原南站",
            "带我去太原南站", "带我到太原南站", "我要去太原南站", "我想去太原南站",
            "前往太原南站", "去往太原南站", "开车去太原南站"}) {
            NavVoice.Command c = NavVoice.parse(s, false);
            eq(c.action, Action.START, s + " 应识别为 START");
            eq(c.destination, "太原南站", s + " 目的地应剥掉前缀");
        }

        // ── 目的地带语气词 / 标点 ──
        eq(NavVoice.parse("导航去公司吧", false).destination, "公司", "句末语气词应剥掉");
        eq(NavVoice.parse("导航到万象城！", false).destination, "万象城", "句末标点应剥掉");
        eq(NavVoice.parse("带我去长风街 谢谢", false).destination, "长风街", "礼貌词应剥掉");
        eq(NavVoice.parse("导航到 太原 站", false).destination, "太原站", "空格应忽略");

        // ── 只说目的地（官方 domain=navigate 时）──
        eq(NavVoice.parse("太原南站", true).action, Action.START, "官方 navigate 且只说地名 → START");
        eq(NavVoice.parse("太原南站", true).destination, "太原南站", "整句作目的地");
        eq(NavVoice.parse("太原南站", false).action, Action.NONE, "没说是导航也没官方 domain → 不触发");
        eq(NavVoice.parse("太原南站", true).reason.contains("官方 navigate"), true, "应说明来自官方 domain");

        // ── 结束 / 取消 ──
        for (String s : new String[]{"退出导航", "结束导航", "停止导航", "取消导航", "关掉导航", "不导航了"}) {
            eq(NavVoice.parse(s, false).action, Action.STOP, s + " 应识别为 STOP");
        }
        eq(NavVoice.parse("取消导航", true).action, Action.STOP, "取消优先于开始，即使官方说是 navigate");

        // ── 巡航 ──
        eq(NavVoice.parse("打开巡航", false).action, Action.CRUISE, "打开巡航 → CRUISE");
        eq(NavVoice.parse("路况播报", false).action, Action.CRUISE, "路况播报 → CRUISE");
        eq(NavVoice.parse("前方路况怎么样", false).action, Action.CRUISE, "前方路况 → CRUISE");

        // ── 问功能，不是要导航 ──
        eq(NavVoice.parse("导航怎么用", false).action, Action.NONE, "问功能不触发");
        eq(NavVoice.parse("导航是什么意思", false).action, Action.NONE, "问含义不触发");
        eq(NavVoice.parse("导航好用吗", false).action, Action.NONE, "问好不好用不触发");

        // ── 空 / null ──
        eq(NavVoice.parse("", false).action, Action.NONE, "空串不触发");
        eq(NavVoice.parse(null, false).action, Action.NONE, "null 不触发");
        eq(NavVoice.parse("   ", false).action, Action.NONE, "纯空白不触发");

        // ── 有导航词但没说目的地：START 但 destination 为空，供上层追问 ──
        NavVoice.Command ask = NavVoice.parse("导航一下", false);
        eq(ask.action, Action.START, "有导航词 → START");
        eq(ask.destination, "", "没目的地 → 空");
        eq(NavVoice.clarify(ask), "去哪？说「导航到 太原南站」", "应给出追问文案");
        eq(NavVoice.clarify(NavVoice.parse("导航去公司", false)), null, "有目的地不需要追问");

        // ── 目的地过短 / 过长 ──
        eq(NavVoice.parse("导航去", false).action, Action.START, "导航去 → START 但无目的地");
        eq(NavVoice.parse("导航去", false).destination, "", "无目的地");
        eq(NavVoice.isUsableDestination("站"), false, "单字不可用");
        eq(NavVoice.isUsableDestination("太原南站"), true, "正常地名可用");
        eq(NavVoice.isUsableDestination("。"), false, "纯标点不可用");
        StringBuilder long60 = new StringBuilder();
        for (int i = 0; i < 61; i++) long60.append("东");
        eq(NavVoice.isUsableDestination(long60.toString()), false, "超长不可用");

        // ── 前缀不在句首时不应误伤 ──
        eq(NavVoice.parse("我朋友说他导航去那边了", false).action, Action.NONE, "前缀不在句首附近不误伤");

        // ── 非导航语句 ──
        eq(NavVoice.parse("讲个笑话", false).action, Action.NONE, "闲聊不触发");
        eq(NavVoice.parse("今天天气怎么样", false).action, Action.NONE, "天气不触发");

        System.out.println("NavVoice: " + n + " checks PASS");
    }
}

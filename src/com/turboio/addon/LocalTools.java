package com.turboio.addon;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 把「本机自建能力」绑定成模型可调用的工具（OpenAI function calling 的 android 侧）。
 *
 * 规则、门控、参数合成全在纯逻辑层 {@link LocalCapabilities}（那里有单测）。
 * 本类只做三件事：
 *   ① 生成给模型看的 JSON schema（`specs`）；
 *   ② 把一次调用**转交给语音链路用的同一个入口**执行（`NavEngine` / `CountdownUI` /
 *      `WeatherUI`）—— 保证"说出来的"和"模型调出来的"跑的是同一条代码路径；
 *   ③ 把结果/失败原因整理成模型能转述给用户的文字。
 *
 * ⚠️ 线程：`AyaSuperAddon.request` 在线程池里调 `call`，所以这里调用的每个入口
 *    都必须是非阻塞的（它们自己会转主线程或另起线程），不要在这里 sleep 之外碰 View。
 */
final class LocalTools {
    private LocalTools() {}

    /** 本机工具是否已注册（按门控判，两次判断同源，避免"声明了却执行不了"）。 */
    static boolean handles(String name) {
        return LocalCapabilities.handles(name);
    }

    /** 给模型看的工具数组；没有可用工具时返回空数组。 */
    static JSONArray specs(Context app) throws JSONException {
        JSONArray out = new JSONArray();
        for (String name : available(app)) out.put(spec(name));
        return out;
    }

    /** 本轮可注册的工具（门控只在一处，避免"声明了却执行不了"）。 */
    private static List<String> available(Context app) {
        boolean hasBook = false;
        try { hasBook = ReaderUI.hasBook(); } catch (Throwable ignored) { }
        return LocalCapabilities.names(weatherReady(app), hasBook, WebSearchUI.ready(app));
    }

    private static JSONObject spec(String name) throws JSONException {
        if (LocalCapabilities.NAV_START.equals(name)) {
            return function(name,
                "在智能眼镜上开始**真实驾车导航**（高德实时路况 + 分段转向提示 + 剩余里程）。"
                + "用户说「导航到 X」「带我去 X」「去 X」时必须调用本工具，"
                + "禁止回答「导航不在我能操作的范围内」。",
                new JSONObject().put("destination", str(2, 60,
                    "纯地名，不要带「导航到」三个字。例：太原南站 / 万象城 / 长风商务区")),
                "destination");
        }
        if (LocalCapabilities.NAV_STOP.equals(name)) {
            return function(name,
                "结束当前导航、退出实时指引。用户说「退出导航」「不导航了」时调用。",
                new JSONObject());
        }
        if (LocalCapabilities.NAV_STATUS.equals(name)) {
            return function(name,
                "查询当前导航状态：目的地、走到第几段、剩余里程与剩余时间。"
                + "只在用户明确问「还有多远」「导航到哪了」时调用。",
                new JSONObject());
        }
        if (LocalCapabilities.TIMER_START.equals(name)) {
            return function(name,
                "在智能眼镜上起一个倒计时（眼镜端秒级读秒 + 显示当前时间 + 结束时刻）。"
                + "用户说「倒计时 5 分钟」「计时 30 秒」「番茄钟」「提醒我 10 分钟后」时"
                + "必须调用本工具。minutes 与 seconds 至少给一个，会相加。",
                new JSONObject()
                    .put("minutes", num(0, 1440, "分钟数，整数。番茄钟就是 25。"))
                    .put("seconds", num(0, 86400, "秒数，整数。可与 minutes 同时给。"))
                    .put("label", str(0, 16, "给这个倒计时起个短名，显示在眼镜第二行，如「煮面」「停车」。可省略。")),
                new String[0]);
        }
        if (LocalCapabilities.TIMER_CANCEL.equals(name)) {
            return function(name, "取消当前正在跑的倒计时。", new JSONObject());
        }
        if (LocalCapabilities.TIMER_STATUS.equals(name)) {
            return function(name, "查询当前倒计时还剩多久。用户问「还剩几分钟」时调用。", new JSONObject());
        }
        if (LocalCapabilities.WEATHER_NOW.equals(name)) {
            return function(name,
                "查询**真实天气**（和风天气实况 / 空气 / 预报 / 生活指数，结果会推到眼镜）。"
                + "用户问天气、气温、多少度、下不下雨、要不要带伞、空气质量时调用本工具，"
                + "不要凭记忆回答。",
                new JSONObject().put("city", str(0, 12, "纯中文城市名，如「太原」「北京」。省略则用设置里保存的城市。")),
                new String[0]);
        }
        // ── 电子书（v3r19）─────────────────────────────────────────
        // 词条描述里刻意写明「不要说不在能力范围内」：模型对"读书"这种请求
        // 的默认反应是推给官方阅读功能，不点明它会一直答"我做不到"。
        if (LocalCapabilities.READER_OPEN.equals(name)) {
            return function(name,
                "打开本机的电子书（支持导入 txt、分屏推到眼镜阅读）。"
                + "用户说「打开电子书」「我要看书」时调用本工具。",
                new JSONObject());
        }
        if (LocalCapabilities.READER_RESUME.equals(name)) {
            return function(name,
                "**接着上次的进度继续读书**（本机已记住书名与读到的屏号），并把当前一屏推到眼镜。"
                + "用户说「继续读」「接着上次读」「念书给我听」时调用本工具，"
                + "禁止回答「阅读不在我能操作的范围内」。",
                new JSONObject());
        }
        if (LocalCapabilities.READER_NEXT.equals(name)) {
            return function(name,
                "电子书翻到**下一屏**并推到眼镜。用户说「下一页」「翻页」时调用。",
                new JSONObject());
        }
        if (LocalCapabilities.READER_PREV.equals(name)) {
            return function(name,
                "电子书退回**上一屏**并推到眼镜。用户说「上一页」「往回翻」时调用。",
                new JSONObject());
        }
        if (LocalCapabilities.READER_STATUS.equals(name)) {
            return function(name,
                "查询电子书进度：书名、读到第几屏、总屏数与百分比。"
                + "用户问「读到哪了」时调用。",
                new JSONObject());
        }
        // ── 备忘录（v3r19 起叫「随口记」，v3r20 随用户要求改名）───────
        if (LocalCapabilities.MEMO_ADD.equals(name)) {
            return function(name,
                "往本机备忘录「" + Memo.NAME + "」里记一条。"
                + "用户说「记一下 买牛奶」「帮我记一下 交电费」「记一笔 …」「随口记 买牛奶」时调用本工具。"
                + "text 只放**要记的内容本身**，不要把「记一下」写进去，"
                + "也不要改写或总结用户的原话。\n"
                + "注意：官方 App 自己有待办与日程（有真实提醒），"
                + "「待办 …」「日程 …」这类不要调用本工具。",
                new JSONObject().put("text", str(1, Memo.MAX_TEXT,
                    "要记下的原始内容，例如「买牛奶」。保留用户原话（含数字、时间、人名）。")),
                "text");
        }
        if (LocalCapabilities.MEMO_LIST.equals(name)) {
            return function(name,
                "念一下备忘录「" + Memo.NAME + "」里记的内容（同时把最近几条推到眼镜）。"
                + "用户问「我记了什么」「念念备忘录」时调用本工具。",
                new JSONObject());
        }
        if (LocalCapabilities.MEMO_OPEN.equals(name)) {
            return function(name,
                "打开备忘录「" + Memo.NAME + "」界面（手机上查看与管理）。"
                + "用户说「打开备忘录」时调用本工具。",
                new JSONObject());
        }
        // ── 运动追踪（v3r22）──────────────────────────────────────
        //   同样转交给 SportUI.handleVoice 的同一条口令链路（"开始跑步 / 开始骑行 /
        //   运动进度 / 停止运动"），保证"说出来"和"模型调出来"跑同一条代码。
        if (LocalCapabilities.SPORT_START.equals(name)) {
            return function(name,
                "开始一次**跑步或骑行运动追踪**（手机 GPS 计时 + 距离累计，进度同步到眼镜）。"
                + "用户说「开始跑步」「开始骑行」「跑步 1 公里」「骑行 3 公里」时调用本工具，"
                + "禁止回答「运动不在我能操作的范围内」。",
                new JSONObject()
                    .put("type", str(0, 4, "运动类型：填「跑步」或「骑行」，省略默认跑步。"))
                    .put("goal_km", num(0, 500, "目标距离（公里），可带小数，如 1 或 3.5。省略则只记录距离不设目标。")),
                new String[0]);
        }
        if (LocalCapabilities.SPORT_STATUS.equals(name)) {
            return function(name,
                "查询当前运动进度：已运动时长、已跑/骑距离、目标完成度。"
                + "用户问「运动进度」「跑到哪了」「骑到哪了」「运动多久了」时调用。",
                new JSONObject());
        }
        if (LocalCapabilities.SPORT_STOP.equals(name)) {
            return function(name,
                "结束当前运动，并给出本次总时长与总距离。用户说「停止运动」「结束跑步」「结束骑行」时调用。",
                new JSONObject());
        }
        // ── 巡航模式（v3r23）──────────────────────────────────────
        //   同样转交给 CruiseUI.handleVoice 的同一条口令链路（"打开巡航 / 关闭巡航"），
        //   保证"说出来"和"模型调出来"跑同一条代码。
        if (LocalCapabilities.CRUISE_START.equals(name)) {
            return function(name,
                "开始**巡航模式**（不问目的地，持续播报你正前方的路况：畅通/缓行/拥堵/严重拥堵 + 前方红绿灯数，每 30 秒一次推到眼镜）。"
                + "用户说「打开巡航」「开始巡航」时调用本工具，禁止回答「巡航不在我能操作的范围内」。",
                new JSONObject());
        }
        if (LocalCapabilities.CRUISE_STOP.equals(name)) {
            return function(name,
                "结束巡航模式、停止前方路况播报。用户说「关闭巡航」「停止巡航」时调用。",
                new JSONObject());
        }
        // ── 热搜 / 热榜新闻（v3r24）──────────────────────────────
        //   数据源免 Key，无条件注册。描述里必须点明"不要凭记忆编造热点"——
        //   模型对"今天热搜"的默认反应是凭训练数据猜，那一定是错的。
        if (LocalCapabilities.NEWS_HOT.equals(name)) {
            return function(name,
                "查**实时热搜 / 热榜新闻**（微博热搜 / 头条热搜 / 知乎热榜 / 今日新闻），结果推到眼镜。"
                + "用户问「热搜」「热榜」「有什么新闻」「今天有什么事」「知乎热榜」时调用本工具。"
                + "**禁止凭记忆编造热点** —— 你的训练数据里没有今天的热搜，不查就直说查不到。",
                new JSONObject().put("board", str(0, 8,
                    "榜单：填「微博」「头条」「知乎」「新闻」之一。省略默认微博热搜。")),
                new String[0]);
        }
        if (LocalCapabilities.NEWS_NEXT.equals(name)) {
            return function(name,
                "热榜翻到**下一页**（眼镜一屏 4 条）。用户说「下一页」「换一批」时调用。",
                new JSONObject());
        }
        // ── 联网搜索（Tavily，v3r24）──────────────────────────────
        //   只在填了 Key 时才出现（门控见 LocalCapabilities.names）。
        //   描述里必须写死"禁止凭记忆回答"：模型碰到"最新 / 现在多少钱"这类问题的
        //   默认反应是凭训练数据编一个，那比直说"我不知道"危险得多。
        if (LocalCapabilities.WEB_SEARCH.equals(name)) {
            return function(name,
                "**联网检索真实信息**（Tavily）。用户问到需要实时 / 最新资料的问题时调用"
                + "（新闻、某个东西现在什么价、最新版本是多少、最近发生了什么）。\n"
                + "**禁止凭记忆回答这类问题** —— 你的训练数据有截止日期，编出来的内容会以假乱真。"
                + "返回结果里带来源 URL，回答时说明信息来自哪个来源；搜不到就直说搜不到。",
                new JSONObject().put("query", str(2, 200,
                    "检索词。写关键词而不是整句话，例：「量子计算 最新进展」。")),
                "query");
        }
        throw new JSONException("unknown_tool");
    }

    // ══════════════════════════════════════════════════════════════
    //  执行
    // ══════════════════════════════════════════════════════════════

    /**
     * 执行一次本机工具调用。
     *
     * 抛异常 = 失败原因会被原样回传给模型（见 AyaSuperAddon.request 的 catch），
     * 所以异常文案要**写给人看**：模型会照实转述给用户。
     */
    static JSONObject call(Context app, String name, JSONObject rawArgs) throws Exception {
        if (app == null) throw new IllegalArgumentException("没有运行上下文");
        if (!available(app).contains(name))
            throw new IllegalArgumentException("本机工具未启用：" + name);
        JSONObject args = rawArgs == null ? new JSONObject() : rawArgs;

        if (LocalCapabilities.NAV_START.equals(name)) {
            if (!LocalCapabilities.onlyKeys(keys(args), "destination")) throw new IllegalArgumentException("参数不合法");
            String dest = LocalCapabilities.destination(args.optString("destination", ""));
            if (dest.isEmpty()) throw new IllegalArgumentException("目的地没听清，请让用户说清具体地名");
            NavEngine.startAsync(app, dest);
            // 等一小会拿到真实结果：成功报全程，失败报原因（定位失败 / 高德限流 / Key 失效…）。
            // 这一步是给模型用的 —— 没有它，模型只能猜"应该开始了吧"。
            String outcome = NavEngine.awaitPlan(7000);
            if (outcome == null || outcome.isEmpty()) outcome = "已开始规划去" + dest + "的路线";
            return ok(outcome).put("destination", dest);
        }
        if (LocalCapabilities.NAV_STOP.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            NavEngine.stop();
            NavGlasses.stop();
            return ok("已退出导航");
        }
        if (LocalCapabilities.NAV_STATUS.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            return ok(NavEngine.statusText());
        }
        if (LocalCapabilities.TIMER_START.equals(name)) {
            if (!LocalCapabilities.onlyKeys(keys(args), "minutes", "seconds", "label"))
                throw new IllegalArgumentException("参数不合法");
            String command = LocalCapabilities.timerCommand(
                args.has("minutes") ? args.get("minutes").toString() : "",
                args.has("seconds") ? args.get("seconds").toString() : "",
                args.optString("label", ""));
            if (command == null) throw new IllegalArgumentException("时长不合法：请给 1 秒到 24 小时之间的整数分钟或秒数");
            String reply = CountdownUI.handleVoice(app, command);
            if (reply == null) throw new IllegalArgumentException("倒计时没起来，请重说一次时长");
            return ok(reply);
        }
        if (LocalCapabilities.TIMER_CANCEL.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            CountdownUI.cancel();
            return ok("已取消倒计时");
        }
        if (LocalCapabilities.TIMER_STATUS.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            return ok(CountdownUI.describe());
        }
        if (LocalCapabilities.WEATHER_NOW.equals(name)) {
            if (!LocalCapabilities.onlyKeys(keys(args), "city")) throw new IllegalArgumentException("参数不合法");
            String command = LocalCapabilities.weatherCommand(args.optString("city", ""));
            String reply = WeatherUI.handleVoice(app, command);
            if (reply == null) throw new IllegalArgumentException("天气问句没解析出来");
            return ok(reply);
        }
        // ── 电子书：一律转给 ReaderUI.handleVoice 的同一条口令链路 ──────
        //  为什么不再写一遍执行逻辑：那样"语音能翻页、模型翻不动"或反过来
        //  （边界判断、落盘、通道抢占各写一份）迟早会漂移。这里只做口令合成。
        if (LocalCapabilities.READER_OPEN.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = ReaderUI.handleVoice(app, "打开电子书");
            if (reply == null) throw new IllegalArgumentException("电子书没打开，请重说一次");
            return ok(reply);
        }
        if (LocalCapabilities.READER_RESUME.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = ReaderUI.handleVoice(app, "继续读");
            if (reply == null) throw new IllegalArgumentException("还没有导入书：请让用户先在电子书里选一个 txt 文件");
            return ok(reply);
        }
        if (LocalCapabilities.READER_NEXT.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = ReaderUI.handleVoice(app, "下一页");
            if (reply == null) throw new IllegalArgumentException("现在没法翻页：还没有导入书");
            return ok(reply);
        }
        if (LocalCapabilities.READER_PREV.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = ReaderUI.handleVoice(app, "上一页");
            if (reply == null) throw new IllegalArgumentException("现在没法翻页：还没有导入书");
            return ok(reply);
        }
        if (LocalCapabilities.READER_STATUS.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = ReaderUI.handleVoice(app, "阅读进度");
            if (reply == null) throw new IllegalArgumentException("还没有导入书");
            return ok(reply);
        }
        // ── 随口记 ──
        if (LocalCapabilities.MEMO_ADD.equals(name)) {
            if (!LocalCapabilities.onlyKeys(keys(args), "text")) throw new IllegalArgumentException("参数不合法");
            String text = LocalCapabilities.memoText(args.optString("text", ""));
            if (text.isEmpty()) throw new IllegalArgumentException("要记的内容是空的，请让用户重说一遍");
            MemoStore.bind(app);
            if (!MemoStore.add(System.currentTimeMillis(), text))
                throw new IllegalArgumentException("记录失败：本机存储不可写");
            return ok(Memo.addedReply(text)).put("text", text);
        }
        if (LocalCapabilities.MEMO_LIST.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            MemoStore.bind(app);
            String reply = MemoUI.handleVoice(app, "念念" + Memo.NAME);
            if (reply == null) throw new IllegalArgumentException("备忘录读取失败");
            return ok(reply);
        }
        if (LocalCapabilities.MEMO_OPEN.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = MemoUI.handleVoice(app, "打开" + Memo.NAME);
            if (reply == null) throw new IllegalArgumentException("备忘录打开失败");
            return ok(reply);
        }
        // ── 运动追踪（v3r22）：一律转给 SportUI.handleVoice 的同一条口令链路 ──
        if (LocalCapabilities.SPORT_START.equals(name)) {
            if (!LocalCapabilities.onlyKeys(keys(args), "type", "goal_km"))
                throw new IllegalArgumentException("参数不合法");
            String type = args.optString("type", "").trim();
            String motion = type.contains("骑行") ? "骑行" : "跑步";
            StringBuilder cmd = new StringBuilder("开始" + motion);
            if (args.has("goal_km")) {
                String g = args.get("goal_km").toString().trim();
                if (!g.isEmpty() && g.matches("[0-9]+(?:\\.[0-9]+)?")) cmd.append(" ").append(g).append("公里");
            }
            String reply = SportUI.handleVoice(app, cmd.toString());
            if (reply == null) throw new IllegalArgumentException("运动没开始，请重说一次");
            return ok(reply);
        }
        if (LocalCapabilities.SPORT_STATUS.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = SportUI.handleVoice(app, "运动进度");
            if (reply == null) throw new IllegalArgumentException("还没有开始运动");
            return ok(reply);
        }
        if (LocalCapabilities.SPORT_STOP.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = SportUI.handleVoice(app, "停止运动");
            if (reply == null) throw new IllegalArgumentException("当前没有进行中的运动");
            return ok(reply);
        }
        // ── 巡航模式 ────────────────────────────────────────────
        //   v3r23 只在 spec() / hint() 里注册了 cruise_start / cruise_stop，
        //   call() 里漏了分支 —— 模型一旦真调用就落到最下面抛「未知工具」。
        //   「声明了却执行不了」比不声明更糟：模型会以为是自己的调用失败。
        //   这里补上，仍然转交给 CruiseUI.handleVoice 的同一条口令链路。
        if (LocalCapabilities.CRUISE_START.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = CruiseUI.handleVoice(app, "打开巡航");
            if (reply == null) throw new IllegalArgumentException("巡航没起来，请重说一次");
            return ok(reply);
        }
        if (LocalCapabilities.CRUISE_STOP.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = CruiseUI.handleVoice(app, "关闭巡航");
            if (reply == null) throw new IllegalArgumentException("当前没有在巡航");
            return ok(reply);
        }
        // ── 热搜 / 热榜新闻（v3r24）──────────────────────────────
        //   数据源免 Key，永远可用。同样只合成口令，执行走 NewsUI.handleVoice。
        if (LocalCapabilities.NEWS_HOT.equals(name)) {
            if (!LocalCapabilities.onlyKeys(keys(args), "board")) throw new IllegalArgumentException("参数不合法");
            String b = args.optString("board", "").trim();
            String cmd = b.contains("知乎") ? "知乎热榜"
                       : b.contains("头条") ? "头条热搜"
                       : b.contains("新闻") ? "今日新闻" : "热搜";
            String reply = NewsUI.handleVoice(app, cmd);
            if (reply == null) throw new IllegalArgumentException("没认出要看哪个榜，请让用户说「热搜 / 知乎热榜 / 新闻」");
            return ok(reply);
        }
        if (LocalCapabilities.NEWS_NEXT.equals(name)) {
            if (args.length() != 0) throw new IllegalArgumentException("参数不合法");
            String reply = NewsUI.handleVoice(app, "下一页");
            if (reply == null) throw new IllegalArgumentException("还没取过热榜");
            return ok(reply);
        }
        // ── 联网搜索（v3r24）─────────────────────────────────────
        //   这里**刻意阻塞**等检索结果：模型要靠这段真实文本才能回答，
        //   先回一句"正在搜"等于什么都没给它（语音场景才走异步，见 WebSearchUI）。
        if (LocalCapabilities.WEB_SEARCH.equals(name)) {
            if (!LocalCapabilities.onlyKeys(keys(args), "query")) throw new IllegalArgumentException("参数不合法");
            String q = args.optString("query", "").trim();
            if (q.length() < 2) throw new IllegalArgumentException("检索词太短，请让用户说清要查什么");
            return ok(WebSearchUI.search(app, q));
        }
        throw new IllegalArgumentException("未知工具：" + name);
    }

    /** 给系统提示词用的一句话能力清单（为空表示没有本机工具）。 */
    static String hint(Context app) {
        List<String> names = available(app);
        StringBuilder b = new StringBuilder();
        append(b, names, LocalCapabilities.NAV_START, "导航（nav_start/nav_stop/nav_status）");
        append(b, names, LocalCapabilities.TIMER_START, "倒计时（timer_start/timer_cancel/timer_status）");
        append(b, names, LocalCapabilities.WEATHER_NOW, "真实天气（weather_now）");
        append(b, names, LocalCapabilities.READER_OPEN, "电子书（reader_open/reader_resume/reader_next/reader_prev/reader_status）");
        append(b, names, LocalCapabilities.MEMO_ADD, Memo.NAME + "（memo_add/memo_list/memo_open）");
        append(b, names, LocalCapabilities.SPORT_START, "运动追踪（sport_start/sport_status/sport_stop）");
        append(b, names, LocalCapabilities.CRUISE_START, "巡航模式（cruise_start/cruise_stop）");
        append(b, names, LocalCapabilities.NEWS_HOT, "热搜热榜新闻（news_hot/news_next）");
        append(b, names, LocalCapabilities.WEB_SEARCH, "联网搜索（web_search）");
        return b.toString();
    }

    private static void append(StringBuilder b, List<String> names, String probe, String label) {
        if (!names.contains(probe)) return;
        if (b.length() > 0) b.append("、");
        b.append(label);
    }

    /** 和风 Key 是否配好（与 WeatherUI 用同一个 pref）。 */
    static boolean weatherReady(Context app) {
        try {
            SharedPreferences prefs = app.getSharedPreferences("turboio_settings", 0);
            String key = prefs.getString("qweather_key", "");
            return key != null && !key.trim().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 小工具 ──

    private static JSONObject ok(String reply) throws JSONException {
        return new JSONObject().put("status", "ok").put("reply", reply == null ? "" : reply);
    }

    private static Set<String> keys(JSONObject args) {
        Set<String> out = new HashSet<>();
        for (Iterator<String> it = args.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    private static JSONObject function(String name, String description, JSONObject properties, String... required)
            throws JSONException {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        return new JSONObject().put("type", "function").put("function", new JSONObject()
            .put("name", name).put("description", description)
            .put("parameters", new JSONObject().put("type", "object")
                .put("properties", properties).put("required", req).put("additionalProperties", false)));
    }

    private static JSONObject str(int min, int max, String description) throws JSONException {
        return new JSONObject().put("type", "string").put("minLength", min).put("maxLength", max)
            .put("description", description);
    }

    private static JSONObject num(int min, int max, String description) throws JSONException {
        return new JSONObject().put("type", "integer").put("minimum", min).put("maximum", max)
            .put("description", description);
    }
}

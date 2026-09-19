package com.turboio.addon;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「使用说明」页 —— 大白话写清楚每个模块怎么用、要配什么、边界在哪。
 *
 * ══ 用户需求（2026-09-18）═══════════════════════════════════════
 * > 在插件界面最下方，写一个使用说明，点开查看使用说明，
 * > 包括各个模块如何使用、需要获取哪些 key、或者哪些 mcp、
 * > 如何创建对应 mcp、能力与边界等等，大白话，让人能看懂。
 *
 * ── 这一页的写法约定 ────────────────────────────────────────────
 *   · **先说「要说什么话」**（用户最想知道的就是这个），再说「要配什么」，
 *     最后说"做不到什么"。顺序反过来就成了说明书式的自嗨。
 *   · 能用日常词就不用术语。「MCP」在这页里一律解释成「一个能被插件调用的
 *     HTTPS 服务「，因为用户真正关心的不是协议名，而是」我得自己去搭一个「。
 *   · 密钥申请步骤写成「点哪 → 选哪 → 复制什么」，不写「参见官方文档」。
 *   · 边界必须写。用户被"我以为它能…"坑过一次，整个功能就不信了。
 *
 * 纯界面代码：不持有状态、不发网络请求，只把 TurboStyle 的卡片拼出来。
 */
final class UsageGuide {

    private UsageGuide() {}

    static void show(Activity host) {
        if (host == null) return;
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, "使用说明", box, () -> AyaSuperAddon.showHome());

        // ── 0. 三句话讲清这东西是什么 ──
        LinearLayout intro = TurboStyle.card(host, box);
        intro.addView(TurboStyle.title(host, "一句话：给眼镜加一层「本机能力」", 17));
        TurboStyle.gap(host, intro, 8);
        intro.addView(body(host,
            "官方眼镜 App 擅长聊天、拍照、翻译。这个插件补的是它没有的那部分：\n"
            + "导航、路况、倒计时、天气、家里设备、看书、提词、记东西。\n\n"
            +             "怎么用：\n"
            + "  · 眼镜语音（目前唯一可用的方式）—— 对着眼镜说「导航到太原南站」，\n"
            + "    本机直接干活，不等模型；\n"
            + "  · 打字 —— 调不动本机工具，这是已确认的缺陷（详见⑩），\n"
            + "    打字时只有官方模型自己在答。\n\n"
            + "眼镜端的显示能力有限，三条常识先记住（后面还会再讲）：\n"
            + "  · 只能显示纯文字，一屏最多 5 行；\n"
            + "  · 是「单会话」：同一时刻只有一个模块能往镜片写字；\n"
            + "  · 一个会话最长约 4 分钟，插件会在到期前自动续接。"));

        // ── 1. 导航 ──
        LinearLayout nav = TurboStyle.card(host, box);
        nav.addView(TurboStyle.title(host, "① 导航（要配：高德 Key）", 17));
        TurboStyle.gap(host, nav, 8);
        nav.addView(body(host,
            "说什么：\n"
            + "  「导航到太原南站」「带我去万象城」「退出导航」\n\n"
            + "需要什么：高德 Key（服务平台选「Android 平台」）。\n"
            + "  怎么拿：高德开放平台（lbs.amap.com）→ 注册并实名 → 控制台「应用管理」\n"
            + "  → 创建应用 → 「添加 Key」；服务平台必须选「Android 平台」\n"
            + "  （选「Web 服务」「Web端(JS)」的 Key 用不了）→\n"
            + "  按控制台要求填应用包名（本插件的包名是 com.rayneo.venus.pub）→ 复制那串 Key。\n"
            + "  填在哪：插件首页 → 出行 → 「导航 · 抬头指引」页里。\n"
            + "  现在内置了一个演示 Key，不填也能跑；但它有并发限制，想稳就自己填一个。\n\n"
            + "怎么工作：手机定位 → 高德推荐的驾车路线（带实时路况）→\n"
            + "  眼镜上显示「转向箭头 + 多远了 + 走哪条路 + 剩余里程/时间」，每 3 秒刷新。\n\n"
            + "边界：\n"
            + "  · 目的地要有名字（不支持「从 A 到 B」这种多点路线）；\n"
            + "  · 进隧道/地库会短暂断 GPS，恢复后自动接上；\n"
            + "  · 走岔超过 80 米会自动重新规划路线（15 秒内只算一次，\n"
            + "    免得把高德 Key 打到限流）；\n"
            + "  · 规划失败时会如实显示原因（定位拿不到 / Key 失效 / 高德限流）。"));

        // ── 2. 巡航 ──
        LinearLayout cruise = TurboStyle.card(host, box);
        cruise.addView(TurboStyle.title(host, "② 巡航（不用额外 Key）", 17));
        TurboStyle.gap(host, cruise, 8);
        cruise.addView(body(host,
            "说什么：「开始巡航」「退出巡航」\n\n"
            + "做什么：播报你正前方 5 公里的路况 —— 拥堵、缓行、事故、施工。\n"
            + "  每 30 秒自动轮一次；方向按真实车头朝向算（往南走就播南边那条路）。\n\n"
            + "边界：它不是导航，不规划目的地、不给转向提示。只是「开在路上听一耳朵」。\n"
            + "  用的是高德的路况数据，所以同样需要高德 Key。"));

        // ── 3. 倒计时 ──
        LinearLayout timer = TurboStyle.card(host, box);
        timer.addView(TurboStyle.title(host, "③ 倒计时（不用额外 Key）", 17));
        TurboStyle.gap(host, timer, 8);
        timer.addView(body(host,
            "说什么：「倒计时 5 分钟」「计时 30 秒」「番茄钟」「取消倒计时」\n\n"
            + "眼镜上会常显：还剩多久 + 现在几点。\n"
            + "边界：最长一天；到点用手机响铃（眼镜不会响）。"));

        // ── 4. 天气 ──
        LinearLayout weather = TurboStyle.card(host, box);
        weather.addView(TurboStyle.title(host, "④ 实时天气（要配：和风天气 Key）", 17));
        TurboStyle.gap(host, weather, 8);
        weather.addView(body(host,
            "说什么：「太原天气」「今天多少度」「要带伞吗」「空气质量怎么样」\n\n"
            + "需要什么：和风天气 Key。\n"
            + "  怎么拿：dev.qweather.com → 注册 → 控制台「项目管理」→ 创建项目\n"
            + "  → 生成 Key（免费订阅就够用）→ 复制。\n"
            + "  填在哪：插件首页 → 工具 → 「实时天气」页里（顺便可以设默认城市）。\n\n"
            + "没填 Key 会怎样：天气问句交给官方回答 —— 官方本来也能答天气，\n"
            + "  我们不去抢一个自己答不好的问题。\n\n"
            + "边界：免费版有每日调用上限；问句里带中文城市名最准（「太原天气」）。"));

        // ── 5. 米家 MCP ──
        LinearLayout mijia = TurboStyle.card(host, box);
        mijia.addView(TurboStyle.title(host, "⑤ 米家 MCP（要自己搭一个网关）", 17));
        TurboStyle.gap(host, mijia, 8);
        mijia.addView(body(host,
            "先说人话：这里的「MCP」不是一个装好就能用的 App，\n"
            + "而是你自己搭的一个 HTTPS 小服务，插件通过它读设备、开关设备。\n"
            + "为什么不直连米家云：那要小米账号密码，既不安全也容易被风控；\n"
            + "用自建网关，插件手里只有一个你随时能换掉的 Token。\n\n"
            + "「米家 MCP」到底是个什么：它不是一个新服务，而是「在你原有的\n"
            + "ha-mcp（Home Assistant MCP）上打的两块补丁」——\n"
            + "  · 块一 /api/mijia/*：设备清单 + 开关，米家设备经 HA 接进来；\n"
            + "  · 块二 /api/home/*：家况聚合，一次给全温湿度、空气、灯光、门窗。\n"
            + "所以前提是：你本来就有一个能跑起来的 ha-mcp（或等价的 HA 网关）。\n\n"
            + "支持哪些设备类型：\n"
            + "  · 能开关的 —— 灯、灯带、筒灯、轨道灯、窗帘、插座、开关；\n"
            + "  · 只读的 —— 温湿度、甲醛、PM2.5、光照、人体存在、水浸、门窗传感器。\n"
            + "  凡是 HA 里接进来的实体都在清单里；能不能动，取决于它是开关类还是传感器。\n\n"
            + "插件只认这四个接口（地址后面自动拼）：\n"
            + "  GET  /api/mijia/ping         自检，返回 200 就算通\n"
            + "  POST /api/mijia/devices      设备清单（可选 room：hall/master/book/second/kitchen）\n"
            + "  POST /api/mijia/control      开 / 关 / 切换（参数形如 device=客厅灯带、action=on）\n"
            + "  GET  /api/home/status        家况聚合（温湿度、灯光、门窗…一次给全）\n"
            + "  鉴权：请求头 Authorization: Bearer <你设的 Token>\n\n"
            + "怎么搭（四步，跟作者家里用的是同一套）：\n"
            + "  1) 家里要有能控制设备的东西 —— Home Assistant 最省事（米家设备用\n"
            + "     插件接进来），或者任何自建脚本；\n"
            + "  2) 写一个小服务把上面四个接口暴露出来（作者的是 Python/Docker，\n"
            + "     读 HA 的设备状态、调用 HA 的服务开关灯）；\n"
            + "  3) 把它挂到公网 HTTPS：Cloudflare Tunnel 或 cpolar 都行\n"
            + "     （必须 HTTPS，插件会拒绝 http 与带路径的地址）；\n"
            + "  4) 回到插件 →「米家 MCP 接入」填域名 + Token → 点「测试连接」，\n"
            + "     通了再打开开关、保存。\n\n"
            + "说什么：「把客厅灯带打开」「卧室灯关掉」「家里温度多少」「家里什么情况」\n\n"
            + "边界：\n"
            + "  · 只能开 / 关 / 切换，不支持亮度和颜色这类参数化调节；\n"
            + "  · 传感器只能查不能控；\n"
            + "  · 设备名要说全（不确定就先问「家里有哪些灯」）；\n"
            + "  · 电脑/服务重启后网关和隧道会掉，要么设开机自启，要么手动拉起来。"));

        // ── 6. RAG 知识库 ──
        LinearLayout rag = TurboStyle.card(host, box);
        rag.addView(TurboStyle.title(host, "⑥ RAG 知识库（要自建，可选）", 17));
        TurboStyle.gap(host, rag, 8);
        rag.addView(body(host,
            "用途：让模型回答「你自己的资料」，而不是凭记忆编。\n\n"
            + "需要什么：一个 HTTPS 地址 + 一个 API Key。地址路径必须正好是\n"
            + "  /api/v1/chat/completions（FastGPT 的标准端点，别的实现了同样的也能用）。\n\n"
            + "怎么搭（以 FastGPT 为例）：\n"
            + "  1) 用 Docker 把 FastGPT 跑起来（官方有一键 compose）；\n"
            + "  2) 建「知识库」→ 上传你的文档（txt/pdf/docx）；\n"
            + "  3) 建「应用」，模型随便选一个，把知识库挂上去；\n"
            + "  4) 应用里「API 访问」→ 复制 API Key 和接口地址；\n"
            + "  5) 回到插件 → 模型与设置 →「RAG 知识库」→ 填进去 → 测试 → 开启。\n\n"
            + "边界：只有你问的那句话会被发过去；查不到就如实说没查到，不许编；\n"
            + "  只读，不会写回你的知识库。"))  ;

        // ── 7. 电子书 ──
        LinearLayout book = TurboStyle.card(host, box);
        book.addView(TurboStyle.title(host, "⑦ 电子书（不用 Key）", 17));
        TurboStyle.gap(host, book, 8);
        book.addView(body(host,
            "用法：先导入一本 .txt（UTF-8 或 GBK 都认，不超过 20 MB）→\n"
            + "  自动分屏（每屏最多 5 行）→ 推到眼镜看。\n\n"
            + "怎么操作：只能用手机上的按钮（语音指令叫不动电子书，已确认的缺陷）：\n"
            + "  上一屏 / 下一屏 / 推到眼镜 / 从头开始读 / 自动翻页（10 或 20 秒一屏）\n\n"
            + "续读：会记住书名和读到第几屏，杀掉 App 再进来自动接着读。\n\n"
            + "每行字数（16 / 20 / 24）是给眼镜调的：眼镜一行能放几个字官方没有公开参数，\n"
            + "  只能实测。16 最保险（绝不会出现要滚屏的情况）。\n\n"
            + "边界：只支持纯文本；眼镜端不能滚动，所以分页按「行」切而不是按字数切 ——\n"
            + "  手机预览看到的那 5 行，就是眼镜上的那 5 行。"));

        // ── 8. 备忘录 ──
        LinearLayout memo = TurboStyle.card(host, box);
        memo.addView(TurboStyle.title(host, "⑧ 备忘录（不用 Key，纯本机）", 17));
        TurboStyle.gap(host, memo, 8);
        memo.addView(body(host,
            "名字：备忘录。别名也能用：随口记 / 随手记。\n\n"
            + "说什么：\n"
            + "  「记一下 买牛奶」「帮我记一下 交电费」  → 记一条（会立刻回你一句）\n"
            + "  「念念备忘录」                        → 念出来\n"
            + "  「打开备忘录」                        → 打开手机上的列表\n"
            + "  「删掉最后一条」「清空备忘录」          → 删\n\n"
            + "为什么写入不经过模型：开车时记东西要的是「秒回」。走模型就要联网、等 token，\n"
            + "  还可能被改写或只回答不记录；纯本机口令是即时且离线的。\n\n"
            + "手机端：最新在上，每条右边一个「删」；也能手动补一条。\n\n"
            + "边界：不接「待办 / 日程」。官方 App 有真正的待办和提醒，\n"
            + "  说「添加待办 明天交电费」会交给官方处理；这样两边不会各记一条。\n"
            + "  内容只存在本机，不联网、不上传；上限 500 条 / 单条 200 字。"));

        // ── 8b. 运动追踪（v3r22 新增）──
        LinearLayout sport = TurboStyle.card(host, box);
        sport.addView(TurboStyle.title(host, "运动 · 健身追踪（v3r22 新增 · 不用 Key）", 17));
        TurboStyle.gap(host, sport, 8);
        sport.addView(body(host,
            "能做什么：跑步 / 骑行时，用手机 GPS 实时计时、累计距离，"
            + "还能设一个目标（比如 1 公里），镜片上同步显示「时长 + 距离 + 目标进度」。\n\n"
            + "说什么：\n"
            + "  「开始跑步」「开始骑行」                  → 开始（不设目标）\n"
            + "  「跑步 1 公里」「骑行 3.5 公里」          → 带目标开始\n"
            + "  「运动进度」「跑到哪了」                  → 念当前进度到眼镜\n"
            + "  「停止运动」                            → 结束并报告总时长 / 总距离\n\n"
            + "也能在首页 → 工具 →「运动追踪 · 跑步骑行」里用按钮开始 / 停止，并填目标。\n\n"
            + "边界（先说清楚，免得你以为它坏了）：\n"
            + "  · 距离按 GPS 轨迹累计，室内、高楼夹道、隧道会偏少甚至不动；\n"
            + "  · 需要手机或车机的定位权限（GPS 开得越稳，距离越准）；\n"
            + "  · 镜片是单会话，被导航占用时会先暂停上屏，等导航让出通道再推；\n"
            + "  · 这是一次会话内的记录，杀掉 App 不会保留上次的运动数据；\n"
            + "  · 距离单位用公里（1 公里 = 1000 米），目标最高 500 公里。"));

        // ── 8c. 热搜 / 热榜新闻（v3r24 新增 · 免 Key）──
        LinearLayout newsCard = TurboStyle.card(host, box);
        newsCard.addView(TurboStyle.title(host, "热搜 · 热榜新闻（v3r24 新增 · 不用 Key）", 17));
        TurboStyle.gap(host, newsCard, 8);
        newsCard.addView(body(host,
            "能看什么：微博热搜、头条热搜、知乎热榜、今日新闻四个榜，点开就能看；\n"
            + "眼镜上一屏 4 条、可翻页，手机上一次列前 10 条。\n\n"
            + "说什么：\n"
            + "  「看看热搜」「热榜」          → 微博热搜（默认）\n"
            + "  「头条热搜」                  → 头条\n"
            + "  「知乎热榜」                  → 知乎\n"
            + "  「今天有什么新闻」            → 今日新闻\n"
            + "  「下一页」「换一批」          → 眼镜翻页\n\n"
            + "也能在首页 → 工具 →「热搜 · 热榜新闻」里选榜、推到眼镜。\n\n"
            + "数据源说明（为什么选它）：用的是开源项目 60s.viki.moe 的公开接口 ——\n"
            + "  · 免 Key：装完就能看，不用先去注册申请（这是选源时最看重的一条）；\n"
            + "  · 免费接口有并发限制，偶尔取不到会显示「没拿到」，过一会儿再试即可；\n"
            + "  · 榜单内容与排序以该接口为准，第三方聚合，不保证与官方 App 完全一致。"));

        // ── 8d. 联网搜索 · Tavily（v3r24 新增 · 要填自己的 Key）──
        LinearLayout webCard = TurboStyle.card(host, box);
        webCard.addView(TurboStyle.title(host, "联网搜索 · Tavily（v3r24 新增 · 要填 Key）", 17));
        TurboStyle.gap(host, webCard, 8);
        webCard.addView(body(host,
            "干什么用的：填了自己的 Key 之后，**你的自有模型就具备联网搜索能力** ——\n"
            + "碰到需要实时信息的问题，模型会自己调用 web_search 真的去搜一次，\n"
            + "再基于检索结果（含来源链接）回答；搜不到就直说搜不到，不会凭记忆编。\n\n"
            + "怎么配：首页 → 模型与设置 →「联网搜索 · Tavily」→ 粘贴 Key（形如 tvly-…）。\n"
            + "  · Key 去 tavily.com 注册就有，免费额度够日常用；\n"
            + "  · Key 用 Android Keystore 加密存在本机，不显示、不写日志、不上传；\n"
            + "  · 没填 Key 时**不注册**这个工具，模型也不会假装搜过；\n"
            + "  · 每次检索按 Tavily 计费，额度用尽会提示 429。\n\n"
            + "说什么：\n"
            + "  「搜一下量子计算最新进展」    → 联网检索，结果推眼镜\n"
            + "  「帮我查一下 XX 现在多少钱」  → 同上\n\n"
            + "边界：只有模型判断这个问题需要联网时才会调用 —— 你问「你好」不会触发搜索。"));

        // ── 9. 提词器 ──
        LinearLayout prompter = TurboStyle.card(host, box);
        prompter.addView(TurboStyle.title(host, "⑨ 提词器（不用 Key）", 17));
        TurboStyle.gap(host, prompter, 8);
        prompter.addView(body(host,
            "导入一段长文本（演讲稿、话术、流程），按固定间隔一段一段推到眼镜上，\n"
            + "边走边念。手机上可以随时暂停 / 继续。\n"
            + "边界：和电子书一样受「5 行 + 单会话」限制，别和导航同时用。"));

        // ── 10. 模型与对话 ──
        LinearLayout model = TurboStyle.card(host, box);
        model.addView(TurboStyle.title(host, "⑩ 模型选择（两种模式）", 17));
        TurboStyle.gap(host, model, 8);
        model.addView(body(host,
            "首页「模型选择」里二选一，悬浮按钮也能一键切。\n\n"
            + "· 自有模型（推荐）—— 自己填地址、模型 ID、API Key。\n"
            + "  所有插件功能都能正常调用：导航、巡航、倒计时、天气、电子书、\n"
            + "  备忘录、米家设备、家况、知识库。\n"
            + "  地址要 https 且以 /chat/completions 结尾（DeepSeek / 通义 / Kimi / 自建都行）。\n\n"
            + "· 官方模型 —— 闲聊、问答交给官方 App 自己答。\n"
            + "  已知问题：官方模型下「意图接管」有 bug —— 本机接管了指令，官方仍会\n"
            + "  回一句「抱歉，暂不支持」，插件功能在官方模型下用不顺。\n"
            + "  想正常用插件，请切到自有模型。\n\n"
            + "个人提示词：决定它说话的风格与长度（眼镜只有 5 行，短一点更好）。\n\n"
            + "语音读回复：打开后，被本机接管的那些指令由手机朗读结果。\n"
            + "  为什么需要它：接管成功时官方那一轮问答被吃掉了，如果本机不出声，\n"
            + "  就会变成「叫不动」；而如果不吃掉官方那一轮，又会出现\n"
            + "  「嘴上说「不支持」、后台却在规划路线」这种自相矛盾的情况。\n\n"
            + "意图接管：这个开关决定「本机能力要不要在官方之前拦下来」。\n"
            + "  开着 → 导航 / 倒计时 / 电子书 / 备忘录 / 家况由本机执行；\n"
            + "  关掉 → 一律走官方，行为和没装插件时一样。\n"
            + "  边界：只拦本机确实有实现的那几类口令，其他闲聊一句都不抢。\n\n"
            + "打字（文本对话）调不动本机工具：这是已确认的缺陷 —— 工具确实注册给了\n"
            + "  模型，但实测打字说「导航到…」仍是模型自己回答。目前请只用眼镜语音。"));

        // ── 11. 眼镜显示常识 ──
        LinearLayout glass = TurboStyle.card(host, box);
        glass.addView(TurboStyle.title(host, "⑪ 关于眼镜显示，三条必须知道的", 17));
        TurboStyle.gap(host, glass, 8);
        glass.addView(body(host,
            "1) 只能显示纯文字，一屏最多 5 行 —— 所以所有内容都是提前分好屏的。\n\n"
            + "2) 是单会话：同一时刻只有一个模块能写字。\n"
            + "   开电子书之前如果导航还在推，先「退出导航」，或者点任意页面里的\n"
            + "   「退出眼镜显示」把通道让出来。\n\n"
            + "3) 一个会话最长约 4 分钟。插件会在到期前 20 秒自动续接，\n"
            + "   期间镜片会闪一下，属于正常。\n\n"
            + "镜片长期不刷新怎么办：插件首页 → 调试 → 「重置显示通道」。\n"
            + "  这条能解决绝大多数「眼镜没反应」的问题。"));

        // ── 12. 权限与隐私 ──
        LinearLayout privacy = TurboStyle.card(host, box);
        privacy.addView(TurboStyle.title(host, "⑫ 权限与隐私", 17));
        TurboStyle.gap(host, privacy, 8);
        privacy.addView(body(host,
            "需要的权限：\n"
            + "  · 定位 —— 导航起点、巡航方向（不给就规划不了路线）；\n"
            + "  · 文件读取 —— 导入电子书 / 提词稿；\n"
            + "  · 蓝牙 —— 眼镜连接由官方负责，插件不额外要。\n\n"
            + "密钥怎么存：所有 Key / Token 用 Android Keystore 加密，只在本机，\n"
            + "  不显示、不写日志、不上传。\n\n"
            + "什么会被发出去（只在你主动使用时）：\n"
            + "  · 你问模型的那句话 → 你配置的模型服务；\n"
            + "  · 导航目的地 → 高德；\n"
            + "  · 天气城市 → 和风；\n"
            + "  · 你问设备的话 → 你自建的网关；\n"
            + "  · 你问知识库的话 → 你自建的 RAG。\n"
            + "  备忘录与电子书从不外发。"));

        // ── 13. 常见问题 ──
        LinearLayout faq = TurboStyle.card(host, box);
        faq.addView(TurboStyle.title(host, "⑬ 常见问题", 17));
        TurboStyle.gap(host, faq, 8);
        faq.addView(body(host,
            "Q 说了没反应，眼镜上也不刷新？\n"
            + "  A 首页 → 调试 → 「重置显示通道」，然后再说一次。\n\n"
            + "Q 说了「导航到…」，眼镜却回「抱歉，暂不支持」？\n"
            + "  A 这是官方模型下意图接管的已知缺陷：本机已经接管并在干活，\n"
            + "    官方那句是它自己回的，会盖掉我们的回执。切到自有模型即可避开。\n\n"
            + "Q 语音叫不动导航 / 倒计时？\n"
            + "  A 检查「意图接管」是不是开着（首页状态卡第二屏就能看到）；\n"
            + "    再看调试页的「最近动作」，会写清楚是接管了还是放行给官方了。\n\n"
            + "Q 打字说「导航到…」没反应？\n"
            + "  A 打字这条路目前调不动本机工具（已确认的缺陷），请改用眼镜语音。\n"
            + "    工具注册是否正常，可以在调试页「本机口令」那一行看到。\n\n"
            + "Q 电子书能用语音翻页吗？\n"
            + "  A 目前不行（已确认的缺陷），请用手机上的「上一屏 / 下一屏」。\n\n"
            + "Q 米家能查到设备，但开不了灯？\n"
            + "  A 传感器本来就只能查；灯要确认设备名和你网关里的一致（先说「家里有哪些灯」）。\n\n"
            + "Q 关掉电脑后米家就断了？\n"
            + "  A 网关和隧道都跑在电脑上，重启后要重新拉起来，或者给它们设开机自启。\n\n"
            + "Q 电子书读了一半要换一行字数？\n"
            + "  A 直接点那个档位就行，会按新行宽重排并停在大致原来的位置，进度不会丢。"));

        TurboStyle.gap(host, box, 18);
        box.addView(TurboStyle.text(host,
            "ANDROID / 非商业研究扩展\n"
            + "本页只讲用法；密钥与服务的搭建、维护由你自己负责。\n"
            + "不同机型、不同眼镜固件的表现可能有差异，请以实机验收为准。",
            11, TurboStyle.FAINT));
        TurboStyle.gap(host, box, 12);

        dialog.show();
    }

    /** 卡片内的正文：比 TurboStyle.text 松一点的行距，长段落才读得下去。 */
    private static TextView body(Activity host, String text) {
        TextView t = TurboStyle.text(host, text, 13, TurboStyle.MUTED);
        t.setLineSpacing(TurboStyle.dp(host, 5), 1f);
        return t;
    }
}

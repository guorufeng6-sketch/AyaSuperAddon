package com.turboio.addon;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;

/**
 * 巡航模式 = 前方路况播报。**与导航完全无关**。
 *
 * ── 用户对上一版的意见（v3r7 的定义是错的）─────────────────────
 *   旧定义：「定位当前位置 → 规划到目的地 → 播报路况 + 红绿灯计数」
 *   用户原话：
 *     "巡航模式我没有看太懂，我的期望是巡航模式和导航没有关系，
 *      正常路上开巡航，进行前方的路况播报，比如拥堵，汇车，事故等等。"
 *
 * 所以现在它做的是：**不问目的地**，只看你当前在哪条路上，
 * 播报你正前方几公里的路况事件（拥堵 / 缓行 / 事故 / 施工 / 管制 / 会车风险）。
 *
 * ── 技术上怎么实现"前方路况"────────────────────────────────────
 * 高德 Web 服务没有"沿当前道路向前 N 公里"的免费接口，能拿到的路况是
 * 由【路径规划】带出来的 tmcs（交通态势分段）。所以做法是：
 *   ① 拿当前定位；
 *   ② 沿当前行驶方向（按最近两次定位算方位角）投一个 5 公里的假想点；
 *   ③ 用 v5/direction/driving 从"我在的位置"规划到那个假想点；
 *   ④ 从返回的 steps[].tmc_status 里读前方分段路况；
 *   ⑤ 汇总成一句话推眼镜。
 * 这样拿到的确实是"正前方那条路"的路况，而不是"去某个目的地沿途"的路况。
 *
 * ── 能力边界（必须照实说）──────────────────────────────────────
 *   ✅ 前方分段路况：畅通 / 缓行 / 拥堵 / 严重拥堵，以及每段距离
 *   ✅ 前方红绿灯数量（cost.traffic_lights，只有数量）
 *   ❌ 红绿灯倒计时 / 当前颜色 —— 免费 API 没有
 *   ❌ 具体事故 / 施工的文字描述 —— tmcs 只给拥堵等级，不给事件类型
 *      （要事件文字得用 v3/traffic/status/road，但那需要按道路 ID 查，
 *        且很多城市不覆盖；本模块不假装能拿到。）
 */
final class CruiseUI {

    private static final String OWNER = "cruise";
    /** 探测前方多远的范围。5 公里覆盖大多数城市主干道的一个"前方路况"尺度。 */
    private static final double LOOKAHEAD_METERS = 5000;

    static {
        NavGlasses.onRelease(OWNER, "巡航", CruiseUI::stop);
    }

    private static volatile boolean cruising = false;
    private static android.os.Handler ticker;
    private static Runnable tick;
    /** 上一版遗留的字段保留，供旧调用方编译通过。 */
    private static android.app.Dialog dialog;
    /**
     * 上一次用过的定位点与航向 —— 用来算"车头朝哪"。
     *
     * 旧实现拿不到航向，硬编码朝正北投 5 公里。往南开的车于是被播报
     * "北边那条路"的路况，跟用户毫无关系。有了连续两次定位就能算真实方位角。
     */
    private static volatile NavCore.Point lastPoint;
    private static volatile double lastHeading = -1;
    /** 两次定位之间至少要挪这么远，才认为"在动"、值得用它算航向（防静止抖动）。 */
    private static final double HEADING_MIN_METERS = 30;

    static boolean cruising() { return cruising; }

    static void show(Activity host) {
        if (host == null) return;
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog screen = TurboStyle.screen(host, "巡航 · 前方路况", box, () -> AyaSuperAddon.showHome());
        dialog = screen;

        // ── 状态卡 ──
        LinearLayout status = TurboStyle.card(host, box);
        status.addView(TurboStyle.statusRow(host, "●",
            cruising ? "巡航中 · 每 30 秒播报一次" : "未启动",
            cruising ? TurboStyle.OK : TurboStyle.FAINT));
        TurboStyle.gap(host, status, 6);
        status.addView(TurboStyle.text(host,
            "巡航看的是你【正前方】那条路的路况，跟目的地无关。\n"
            + "说「打开巡航」或点下面的按钮开始。", 13, TurboStyle.MUTED));
        // ★ 定位状态必须显示出来。
        //   用户对巡航的要求是"同步更新位置获取 确保可用" ——
        //   而"可用"与否只有把定位来源/新鲜度摆出来才能自查：
        //   没有这一行，用户只能靠"路况好像不对"来猜。
        TurboStyle.gap(host, status, 8);
        TextView fixLine = TurboStyle.text(host, "定位：" + NavEngine.fixText(host), 12, TurboStyle.MUTED);
        fixLine.setLineSpacing(TurboStyle.dp(host, 4), 1f);
        status.addView(fixLine);
        // 每 2 秒刷一次定位状态（页面开着的时候才刷，关掉就停）。
        final android.os.Handler fixPaint = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] fixTick = new Runnable[1];
        fixTick[0] = () -> {
            try { fixLine.setText("定位：" + headingText() + " · " + NavEngine.fixText(host)); }
            catch (Throwable ignored) { }
            fixPaint.postDelayed(fixTick[0], 2000);
        };
        fixPaint.postDelayed(fixTick[0], 2000);
        TurboStyle.onDismissExtra(screen, () -> fixPaint.removeCallbacks(fixTick[0]));

        // ── 播报区 ──
        LinearLayout result = TurboStyle.card(host, box);
        result.addView(TurboStyle.label(host, "最近一次播报", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, result, 8);
        TextView spoken = TurboStyle.text(host, "—", 15, TurboStyle.INK);
        spoken.setLineSpacing(TurboStyle.dp(host, 5), 1f);
        result.addView(spoken);

        // ── 主操作：一次播报 ──
        TurboStyle.button(host, box, "播报一次前方路况", true, () -> {
            spoken.setTextColor(TurboStyle.MUTED);
            spoken.setText("正在获取当前位置（会等几秒拿到实时定位）与前方路况…");
            new Thread(() -> {
                String text;
                try {
                    text = buildBriefing(host);
                } catch (Exception e) {
                    text = "路况获取失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
                } finally {
                    // 一次性播报：用完注销（连续巡航没开的话，订阅就该关掉省电）。
                    NavEngine.dropFix(OWNER);
                }
                final String out = text;
                host.runOnUiThread(() -> {
                    spoken.setTextColor(TurboStyle.INK);
                    spoken.setText(out);
                    push(out);
                });
            }, "TurboIO-cruise").start();
        });

        // ── 持续巡航开关 ──
        Button toggle = TurboStyle.button(host, box,
            cruising ? "停止持续巡航" : "开始持续巡航（30 秒一次）", false, () -> { });
        toggle.setOnClickListener(v -> {
            if (cruising) {
                stop();
                toggle.setText("开始持续巡航（30 秒一次）");
                Toast.makeText(host, "已停止巡航", Toast.LENGTH_SHORT).show();
            } else {
                start(host);
                toggle.setText("停止持续巡航");
                Toast.makeText(host, "巡航已开始，每 30 秒播报一次", Toast.LENGTH_SHORT).show();
            }
            // 刷新状态卡太麻烦，直接重开页面最省事也最直观。
            screen.dismiss();
            show(host);
        });

        TurboStyle.gap(host, box, 14);
        // ── 眼镜显示控制（退出 / 重置）──
        TurboStyle.displayControl(host, box, "持续巡航会每 30 秒占用一次字幕通道；测别的功能前先退出。");
        // ── 能力边界 ──
        LinearLayout caps = TurboStyle.card(host, box);
        caps.addView(TurboStyle.label(host, "能播什么 / 播不了什么", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, caps, 8);
        caps.addView(TurboStyle.text(host,
            "✅ 前方 5 公里内的路况分级：畅通 / 缓行 / 拥堵 / 严重拥堵\n"
            + "✅ 每一段有多长、前方共几个红绿灯\n"
            + "✅ 用真实行驶方向探测：连续两次定位算方位角（原地不动时按正北）\n\n"
            + "❌ 红绿灯倒计时 / 当前是红还是绿\n"
            + "   免费高德接口只给红绿灯「数量」，不给倒计时。\n"
            + "❌ 「前方 200 米有事故」这种事件文字\n"
            + "   免费接口只给拥堵等级，不给事故/施工的具体描述。\n"
            + "   要真正的事故播报，需接入交管数据源或车机端 OCR 识别导航画面。",
            13, TurboStyle.INK));

        TurboStyle.gap(host, box, 12);
        box.addView(TurboStyle.text(host,
            "定位只用于本次路况查询，不记录轨迹、不上传。", 11, TurboStyle.FAINT));

        dialog = screen;
    }

    // ---- 持续巡航 ----

    private static void start(Context ctx) {
        stop();
        cruising = true;
        // ★ 巡航也要**实时定位**（用户要求："巡航模式同步更新位置获取 确保可用"）。
        //   旧实现每秒读 getLastKnownLocation 的缓存值 —— 那是"别的 App 上次定位的
        //   结果"，没人请求新定位它就永远不变，于是"前方路况"永远按同一个位置算。
        //   用 wantFix 登记（与导航共用同一份订阅；谁都不用时才真正拆掉，省电）。
        NavEngine.wantFix(ctx.getApplicationContext(), OWNER);
        ticker = new android.os.Handler(android.os.Looper.getMainLooper());
        tick = new Runnable() {
            @Override public void run() {
                if (!cruising) return;
                new Thread(() -> {
                    try { push(buildBriefing(ctx)); } catch (Exception ignored) { }
                }, "TurboIO-cruise-tick").start();
                ticker.postDelayed(this, 30000);
            }
        };
        // 先立刻播一次，再进 30 秒循环。
        tick.run();
    }

    static void stop() {
        cruising = false;
        if (ticker != null && tick != null) ticker.removeCallbacks(tick);
        ticker = null; tick = null;
        // 注销定位登记：导航还在跑的话它的登记还在，订阅不受影响。
        NavEngine.dropFix(OWNER);
    }

    /**
     * 语音 / 工具入口：开始 / 停止巡航，或来一次前方路况播报。
     * 返回给用户的反馈；null = 不是巡航指令（交给后面模块判断）。
     *
     * 与导航完全无关：关键词是「巡航 / 前方路况」，不会和导航的「去 X」混淆。
     */
    static String handleVoice(Context app, String raw) {
        if (app == null || raw == null) return null;
        String s = raw.replaceAll("[\\s　]+", "");
        if (s.contains("停止巡航") || s.contains("关闭巡航") || s.contains("退出巡航") || s.contains("不巡航")) {
            stop();
            return "已停止巡航";
        }
        if (s.contains("巡航") || s.contains("前方路况")) {
            if (s.contains("前方路况")) {
                // 一次性播报：放后台线程去拿，不阻塞语音/工具调用线程。
                final Context c = app;
                new Thread(() -> briefToGlasses(c), "TurboIO-cruise-brief").start();
                return "已请求前方路况，结果同步到眼镜";
            }
            start(app);
            return "已开始持续巡航，每 30 秒把前方路况播报到眼镜";
        }
        return null;
    }

    /** 把一段文字推到眼镜（会话没开就先开；未就绪的内容会等会话 ready 后自动补发）。 */
    private static void push(String text) {
        if (text == null || text.isEmpty()) return;
        try {
            NavGlasses.acquire(OWNER);
            NavGlasses.show(text, false);
        } catch (Exception ignored) { }
    }

    /** 语音触发：从任意线程播报一次前方路况。 */
    static void briefToGlasses(Context app) {
        try {
            String text = buildBriefing(app);
            push(text);
        } catch (Exception e) {
            push("路况获取失败");
        } finally {
            // 一次性播报：用完就注销（导航若也在用，它的登记还在，订阅不会被拆）。
            NavEngine.dropFix(OWNER);
        }
    }

    // ---- 位置 ----

    /**
     * 拿当前位置。**优先用实时订阅点**（导航/巡航共用那一份），
     * 拿不到再退系统缓存 lastKnownLocation。
     *
     * ── 为什么顺序不能反 ────────────────────────────────────────────
     * 缓存值（getLastKnownLocation）"看着有、其实是不动的"：没人请求新定位时
     * 它从开机起就不变。用它算"前方路况"，用户会看到一段永远不变、且往往
     * 与自己所在位置无关的路况 —— 这正是导航当初"位置不更新"的同一个坑。
     */
    private static NavCore.Point currentPoint(Context app) {
        if (app == null) return null;
        // ① 实时订阅点（先登记订阅，再等最多 6 秒首点）
        NavEngine.wantFix(app, OWNER);
        NavCore.Point live = NavEngine.awaitFix(app, 6000);
        if (live != null) return live;
        // ② 退系统缓存（5 分钟内的才算）
        return cachedPoint(app);
    }

    /** 系统缓存定位（WGS-84 → GCJ-02）。 */
    private static NavCore.Point cachedPoint(Context app) {
        try {
            LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location best = null;
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (!lm.isProviderEnabled(provider)) continue;
                    Location loc = lm.getLastKnownLocation(provider);
                    if (loc == null) continue;
                    if (best == null || loc.getTime() > best.getTime()) best = loc;
                } catch (SecurityException ignored) { }
            }
            if (best == null) return null;
            if (System.currentTimeMillis() - best.getTime() > 300000) return null;
            return wgs84ToGcj02(best.getLatitude(), best.getLongitude());
        } catch (Exception e) {
            return null;
        }
    }

    /** 更新"车头朝哪"。返回这次该用的航向（度）。 */
    private static double trackHeading(NavCore.Point here) {
        NavCore.Point prev = lastPoint;
        double heading = lastHeading;
        if (prev != null && here != null) {
            double moved = NavCore.distance(prev, here);
            // 至少挪动 30 米才算"在走"：原地 GPS 抖动算出来的方位角是随机数。
            if (moved >= HEADING_MIN_METERS) {
                double fresh = NavCore.bearing(prev, here);
                // 平滑一下：车头不会瞬间转 180°，取与上次相差较小的一侧慢慢跟。
                heading = smooth(heading, fresh);
            }
        }
        lastPoint = here;
        lastHeading = heading;
        return heading;
    }

    /** 航向平滑：单次变化最多 60°，避免一个跳变点把"前方"指到反方向。 */
    static double smooth(double previous, double fresh) {
        if (previous < 0 || !Double.isFinite(previous)) return fresh;
        double delta = ((fresh - previous + 540) % 360) - 180;   // 归一化到 [-180,180)
        if (delta > 60) delta = 60;
        if (delta < -60) delta = -60;
        return (previous + delta + 360) % 360;
    }

    /** 定位/航向的人话（给状态行显示）。 */
    static String headingText() {
        double h = lastHeading;
        if (h < 0) return "还没有行驶方向（原地不动时按正北探测）";
        String[] names = {"正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北"};
        int idx = (int) Math.round(h / 45.0) % 8;
        return "朝" + names[idx] + "（" + Math.round(h) + "°）";
    }

    /** WGS-84 → GCJ-02（火星坐标）。高德只吃 GCJ-02。 */
    public static NavCore.Point wgs84ToGcj02(double lat, double lon) {
        if (outOfChina(lat, lon)) return new NavCore.Point(lat, lon);
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - 0.00669342162296594323 * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((6335552.717000426 / (magic * sqrtMagic)) * Math.PI);
        dLon = (dLon * 180.0) / ((6378245.0 / sqrtMagic * Math.cos(radLat)) * Math.PI);
        return new NavCore.Point(lat + dLat, lon + dLon);
    }
    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }
    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }
    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    // ---- 高德 ----

    static String key(Context ctx) {
        return NavEngine.amapKey(ctx);
    }

    /**
     * 纯逻辑：把前方路况分段汇总成一句播报。抽出来是为了能单测。
     *
     * @param statuses 每段的 tmc_status 文本（可能含"畅通/缓行/拥堵/严重拥堵"，可能为空）
     * @param lengths  每段的距离（米），与 statuses 一一对应；缺省传 null
     * @param lights   前方红绿灯总数，未知传 -1
     */
    static String summarize(List<String> statuses, List<Double> lengths, int lights) {
        int jam = 0, slow = 0, clear = 0, unknown = 0;
        double jamMeters = 0, slowMeters = 0;
        if (statuses != null) {
            for (int i = 0; i < statuses.size(); i++) {
                String s = statuses.get(i) == null ? "" : statuses.get(i);
                double len = (lengths != null && i < lengths.size() && lengths.get(i) != null) ? lengths.get(i) : 0;
                if (s.contains("严重")) { jam++; jamMeters += len; }
                else if (s.contains("拥堵")) { jam++; jamMeters += len; }
                else if (s.contains("缓行")) { slow++; slowMeters += len; }
                else if (s.isEmpty()) unknown++;
                else clear++;
            }
        }
        StringBuilder b = new StringBuilder();
        if (jam > 0) {
            b.append("前方有 ").append(jam).append(" 段拥堵");
            if (jamMeters > 0) b.append("，约 ").append(NavCore.meters(jamMeters));
            b.append("，建议耐心跟车或考虑绕行");
        } else if (slow > 0) {
            b.append("前方有 ").append(slow).append(" 段缓行");
            if (slowMeters > 0) b.append("，约 ").append(NavCore.meters(slowMeters));
            b.append("，注意保持车距");
        } else if (clear > 0) {
            b.append("前方路况畅通，注意安全");
        } else {
            b.append("前方暂无路况数据");
        }
        if (lights > 0) b.append("。前方共 ").append(lights).append(" 个红绿灯");
        return b.toString();
    }

    /**
     * 拿"正前方"的路况：定位 → 沿行驶方向投一个 5 公里点 → 规划 → 读 tmcs。
     * 不传目的地。
     */
    static String buildBriefing(Context app) throws Exception {
        // 先登记订阅（一次性播报的注销在各调用方；连续巡航在 start/stop 里管）。
        if (NavEngine.amapKey(app).isEmpty()) return "没有高德 Key，请在「导航 → 高德 Key」里填一个 Android 平台 Key";
        NavEngine.wantFix(app, OWNER);
        NavCore.Point here = currentPoint(app);
        if (here == null) {
            // ★ 说清"为什么拿不到"：没授权 / 定位开关没开 / 就是没数据，
            //   三者处理方式完全不同，笼统一句"拿不到位置"用户没法动手。
            return "拿不到当前位置（" + NavEngine.fixText(app) + "）\n到空旷处或打开定位开关再试";
        }
        // ★ 用真实航向而不是硬编码正北（见 headingText / NavCore.bearing 的说明）。
        double heading = trackHeading(here);
        double use = heading < 0 ? 0 : heading;
        NavCore.Point ahead = project(here, use, LOOKAHEAD_METERS);
        String origin = String.format(Locale.ROOT, "%.6f,%.6f", here.lon, here.lat);
        String dest = String.format(Locale.ROOT, "%.6f,%.6f", ahead.lon, ahead.lat);

        // 高德只吃 GCJ-02（currentPoint 已转换）；show_fields 带上 tmcs 才有路况。
        JSONObject route = getJson("https://restapi.amap.com/v5/direction/driving?key=" + key(app)
            + "&origin=" + origin + "&destination=" + dest
            + "&show_fields=cost,tmcs&strategy=32");
        JSONObject routeObj = route.optJSONObject("route");
        if (routeObj == null) {
            String info = route.optString("info", "");
            String code = route.optString("infocode", "");
            if ("DAILY_QUERY_OVER_LIMIT".equals(info)) return "今日路况查询次数已用完，明天再试。";
            // 复用导航那张错误码表：Key 失效/填错类型/限流 都能说清。
            return "前方路况暂不可用\n" + NavCore.amapReason(info, code);
        }
        JSONArray paths = routeObj.optJSONArray("paths");
        if (paths == null || paths.length() == 0) return "前方没有可探测的道路。";

        List<String> statuses = new ArrayList<>();
        List<Double> lengths = new ArrayList<>();
        int lights = -1;
        for (int p = 0; p < paths.length(); p++) {
            JSONObject path = paths.optJSONObject(p);
            if (path == null) continue;
            JSONObject cost = path.optJSONObject("cost");
            if (cost != null && lights < 0) lights = cost.optInt("traffic_lights", -1);
            JSONArray steps = path.optJSONArray("steps");
            if (steps == null) continue;
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) continue;
                String status = step.optString("tmc_status", "");
                double len = step.optDouble("step_distance", 0);
                statuses.add(status);
                lengths.add(len);
            }
            break;   // 只看第一条方案
        }
        String base = summarize(statuses, lengths, lights);
        // 方向必须说出来：用户看到"北边堵"时得知道这句话的"前方"是怎么定义的。
        return base + "\n" + (heading < 0 ? "（还没动，按正北探测）" : "（按行驶方向探测）");
    }

    /**
     * 从 (lat,lon) 沿方位角 bearing（度，0=正北）走 meters 米，返回新点。
     * 纯球面近似，5 公里内误差可忽略。
     */
    static NavCore.Point project(NavCore.Point from, double bearing, double meters) {
        double rad = Math.toRadians(bearing);
        double dLat = meters * Math.cos(rad) / 111195.0;
        double dLon = meters * Math.sin(rad) / (111195.0 * Math.cos(Math.toRadians(from.lat)));
        double lat = from.lat + dLat, lon = from.lon + dLon;
        // 高德可能对越界坐标报错，做个夹取。
        lat = Math.max(-89.9, Math.min(89.9, lat));
        lon = ((lon + 540) % 360) - 180;
        try { return new NavCore.Point(lat, lon); }
        catch (IllegalArgumentException e) { return from; }
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            conn.disconnect();
        }
    }
}

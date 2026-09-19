package com.turboio.addon;

import android.app.Activity;
import android.content.Context;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「实时天气」页：城市查询 → 和风天气 → 眼镜 5 行。
 *
 * ── 设计取舍 ────────────────────────────────────────────────────
 * · **不内置 Key**。和风天气的 Key 与账号绑定，内置进 APK 等于把作者配额
 *   送给所有人（而且一泄露就会被限流）。所以由用户在手机端自己填，
 *   存进独立的 prefs，并明确告知存在哪。
 * · 接口域名**必须逐字填成账号自己的「API Host」**。和风 2025 改版后
 *   每个账号有一个专属域名（形如 「随机串.re.qweatherapi.com」），
 *   老的 `devapi.qweather.com` / `api.qweather.com` 对这类 Key 会直接
 *   返回 `403 Invalid Host`。改版前"免费=devapi、付费=api"的经验已经失效，
 *   所以这里做成一个可编辑的输入框，并把常见形态写进提示。
 * · 2025 版接口路径也变了（实测确认）：
 *   城市查询 `{host}/geo/v2/city/lookup`（旧 `/v2/…` → 404）；
 *   空气质量 `{host}/airquality/v1/current/{lat}/{lon}`（旧 `v7/air/now` → 403 废弃，
 *   且改成按**经纬度**查，坐标从城市查询的返回里取）。
 * · 数据尽量丰富（实况 / 空气质量 / 未来 3 天 / 生活指数），
 *   但**推到眼镜上的只有 5 行** —— 所以拆成 4 页，用底下按钮翻页，
 *   手机端一次看全部。
 */
final class WeatherUI {

    private static final String OWNER = "weather";
    private static final String KEY_PREF = "qweather_key";
    private static final String CITY_PREF = "weather_city";
    private static final String HOST_PREF = "qweather_host";
    /**
     * 默认的 API Host —— 本机账号的专属域名（2025 改版后的形态）。
     * 只是**默认值**，界面上可改：换了和风账号就要改成新账号那一串。
     * 域名本身不是密钥（Key 才是），所以内置它不泄露配额。
     */
    private static final String DEFAULT_HOST = "qg6tuqynjn.re.qweatherapi.com";

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    /** 主线程 Handler：推送必须回主线程（见 pushPage 的注释）。 */
    private static final android.os.Handler UI = new android.os.Handler(android.os.Looper.getMainLooper());

    private static volatile Weather.Now lastNow;
    private static volatile Weather.Air lastAirObj;
    private static volatile List<Weather.Day> lastDays = new ArrayList<>();
    private static volatile List<Weather.Index> lastIndices = new ArrayList<>();
    private static volatile String lastError = "", lastCityId = "", lastHost = "";
    /** 组装好的眼镜页面（实况 / 空气 / 预报 / 指数），翻页按钮用。 */
    private static volatile List<String> pages = new ArrayList<>();
    private static volatile int pageIndex = 0;

    static {
        NavGlasses.onRelease(OWNER, "天气", () -> { /* 一次性推送，没有循环要停 */ });
    }

    /** 眼镜上正在显示的那一帧（诊断面板/预览用）—— 当前页。 */
    static String lastFrame() {
        List<String> p = pages;
        if (p == null || p.isEmpty()) return "";
        int i = Math.max(0, Math.min(pageIndex, p.size() - 1));
        return p.get(i);
    }

    static void show(Activity host) {
        if (host == null) return;
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, "实时天气", box, () -> AyaSuperAddon.showHome());
        final android.content.SharedPreferences prefs =
            host.getSharedPreferences("turboio_settings", 0);

        // ── Key / 专属域名 ──
        LinearLayout cfg = TurboStyle.card(host, box);
        cfg.addView(TurboStyle.label(host, "和风天气 Key（自己填，不内置）", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, cfg, 8);
        EditText key = new EditText(host);
        key.setHint("32 位十六进制串，到 console.qweather.com 复制");
        key.setSingleLine();
        key.setText(prefs.getString(KEY_PREF, ""));
        TurboStyle.field(host, key);
        cfg.addView(key);

        TurboStyle.gap(host, cfg, 10);
        cfg.addView(TurboStyle.label(host, "API Host（账号专属域名，照抄）", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, cfg, 8);
        EditText apiHost = new EditText(host);
        apiHost.setHint("xxxxxxxxxx.re.qweatherapi.com");
        apiHost.setSingleLine();
        apiHost.setText(prefs.getString(HOST_PREF, DEFAULT_HOST));
        TurboStyle.field(host, apiHost);
        cfg.addView(apiHost);

        TurboStyle.gap(host, cfg, 6);
        cfg.addView(TurboStyle.text(host,
            "到「控制台 → 设置 → API Host」复制那一串，连 https:// 都不用带。\n"
            + "⚠ 2025 改版后每账号一个专属域名；老的 devapi / api.qweather.com\n"
            + "  对这种 Key 会直接报 403 Invalid Host，别再填那两个了。\n"
            + "Key 只存在本机应用私有设置里，不上传、不写日志、不打进 APK。", 11, TurboStyle.FAINT));

        // ── 城市 ──
        LinearLayout cityCard = TurboStyle.card(host, box);
        cityCard.addView(TurboStyle.label(host, "城市", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, cityCard, 8);
        EditText city = new EditText(host);
        city.setHint("如 太原 / 北京 / 上海");
        city.setSingleLine();
        city.setText(prefs.getString(CITY_PREF, ""));
        TurboStyle.field(host, city);
        cityCard.addView(city);
        TurboStyle.gap(host, cityCard, 6);
        cityCard.addView(TurboStyle.text(host,
            "中文名即可，和风会自己匹配到城市 ID（如 太原 → 101100101）。\n"
            + "语音也能用：「太原天气」「查一下北京的天气」。", 11, TurboStyle.FAINT));

        // ── 结果 ──
        LinearLayout result = TurboStyle.card(host, box);
        result.addView(TurboStyle.label(host, "查询结果", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, result, 8);
        final TextView big = TurboStyle.text(host, "还没有查询", 24, TurboStyle.LIME);
        big.setLineSpacing(TurboStyle.dp(host, 5), 1f);
        result.addView(big);
        TurboStyle.gap(host, result, 8);
        final TextView detail = TurboStyle.text(host, "填好 Key 和城市，点下面的按钮。", 13, TurboStyle.MUTED);
        detail.setLineSpacing(TurboStyle.dp(host, 4), 1f);
        result.addView(detail);
        TurboStyle.gap(host, result, 8);
        result.addView(TurboStyle.label(host, "眼镜上会显示（5 行）", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, result, 6);
        final TextView glass = TurboStyle.text(host, "（还没有内容）", 12, TurboStyle.BLUE);
        glass.setLineSpacing(TurboStyle.dp(host, 4), 1f);
        glass.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        result.addView(glass);

        final Runnable[] render = new Runnable[1];

        // ── 翻页：眼镜一屏只有 5 行，丰富的数据只能分页看 ──
        TurboStyle.gap(host, result, 10);
        final TextView pageLabel = TurboStyle.text(host, "", 11, TurboStyle.FAINT);
        result.addView(pageLabel);
        TurboStyle.gap(host, result, 8);
        LinearLayout pager = TurboStyle.rowBox(host);
        result.addView(pager);
        TurboStyle.button(host, pager, "上一页", false, () -> { pushPage(page() - 1); render[0].run(); });
        TurboStyle.button(host, pager, "下一页", false, () -> { pushPage(page() + 1); render[0].run(); });

        render[0] = () -> {
            Weather.Now now = lastNow;
            if (now != null) {
                big.setText(Weather.round(now.temp) + "℃  " + (now.text.isEmpty() ? "" : now.text));
            } else if (!lastError.isEmpty()) {
                big.setText("查询失败");
            }
            if (!lastError.isEmpty()) {
                detail.setText(lastError);
            } else if (now != null) {
                StringBuilder d = new StringBuilder();
                d.append(now.city.isEmpty() ? "" : now.city + "\n");
                d.append("气温 ").append(Weather.round(now.temp)).append("℃");
                if (Math.abs(now.feels - now.temp) >= 1) d.append(" · 体感 ").append(Weather.round(now.feels)).append("℃");
                d.append("\n湿度 ").append(now.humidity).append("%");
                String wind = Weather.wind(now);
                if (!wind.isEmpty()) d.append(" · 风 ").append(wind);
                d.append("\n降水 ").append(now.precip).append("mm");
                d.append(" · 气压 ").append(now.pressure).append("hPa");
                d.append(" · 能见度 ").append(now.vis).append("km");
                d.append("\n观测时间 ").append(now.obsTime);
                if (!lastCityId.isEmpty()) d.append(" · 城市 ID ").append(lastCityId);
                Weather.Air air = lastAirObj;
                if (air != null) {
                    d.append("\n\n空气质量 ").append(air.category);
                    d.append("\nAQI ").append(air.aqi);
                    if (!air.primary.isEmpty()) d.append(" · 主要 ").append(air.primary);
                    d.append("\nPM2.5 ").append(air.pm25).append(" · PM10 ").append(air.pm10);
                    if (!air.o3.isEmpty()) d.append("\nO3 ").append(air.o3);
                    if (!air.no2.isEmpty()) d.append(" · NO2 ").append(air.no2);
                    if (!air.so2.isEmpty()) d.append(" · SO2 ").append(air.so2);
                    if (!air.co.isEmpty()) d.append(" · CO ").append(air.co);
                    if (!air.advice.isEmpty()) d.append("\n").append(air.advice);
                }
                if (!lastDays.isEmpty()) {
                    d.append("\n\n未来三天");
                    for (Weather.Day day : lastDays) d.append("\n").append(Weather.dayLine(day));
                    Weather.Day firstDay = lastDays.get(0);
                    if (!firstDay.sunrise.isEmpty() && !firstDay.sunset.isEmpty()) {
                        d.append("\n日出 ").append(firstDay.sunrise).append(" · 日落 ").append(firstDay.sunset);
                    }
                }
                if (!lastIndices.isEmpty()) {
                    d.append("\n\n生活指数");
                    for (Weather.Index it : lastIndices) d.append("\n").append(it.name).append(' ').append(it.level);
                }
                detail.setText(d.toString());
            }
            int total = pageCount();
            pageLabel.setText(total == 0 ? "" : ("第 " + (page() + 1) + " / " + total + " 页 · 眼镜上就看这一页"));
            String frame = lastFrame();
            glass.setText(frame.isEmpty() ? "（还没有内容）" : frame);
        };
        render[0].run();

        TurboStyle.button(host, box, "查询并推送到眼镜", true, () -> {
            String k = key.getText().toString().trim();
            String c = city.getText().toString().trim();
            String h = apiHost.getText().toString().trim();
            if (k.isEmpty()) { Toast.makeText(host, "先填和风天气 Key", Toast.LENGTH_LONG).show(); return; }
            if (h.isEmpty()) { Toast.makeText(host, "先填 API Host（账号专属域名）", Toast.LENGTH_LONG).show(); return; }
            if (c.isEmpty()) { Toast.makeText(host, "先填城市，比如 太原", Toast.LENGTH_LONG).show(); return; }
            prefs.edit().putString(KEY_PREF, k).putString(CITY_PREF, c)
                .putString(HOST_PREF, h).apply();
            // Key 有了，语音接管天气这条链路也一起打开（否则语音问天气会走官方）。
            NlpRouter.setWeatherEnabled(true);
            lastError = "正在查询…";
            render[0].run();
            fetch(host, k, c, h, render[0], true);
        });

        TurboStyle.displayControl(host, box, "天气是一次性推送，不占用通道；但推送前会先接管。");

        TurboStyle.gap(host, box, 14);
        box.addView(TurboStyle.text(host,
            "数据源：和风天气（qweather.com）—— 实况 / 空气质量 / 未来 3 天 / 生活指数。\n"
            + "眼镜一屏只有 5 行、每行不超过 " + Weather.LINE_MAX + " 个字，"
            + "所以数据分成 4 页，用上面的「上一页 / 下一页」翻着看。\n"
            + "只用 GB2312 内码表里的符号（中文固件画不出的字符会变成空白）。\n"
            + "本页只做查询与展示，不做定位 —— 城市由你指定，避免拿错地方。", 11, TurboStyle.FAINT));

        dialog.show();
    }

    // ══════════════════════════════════════════════════════════════
    //  网络
    // ══════════════════════════════════════════════════════════════

    static void fetch(Activity host, String key, String cityName, String apiHost,
                      Runnable onDone, boolean pushToGlasses) {
        final String hostName = normalizeHost(apiHost);
        NET.execute(() -> {
            try {
                if (hostName.isEmpty()) throw new IllegalStateException("API Host 没填（形如 xxxxx.re.qweatherapi.com）");
                lastHost = hostName;
                final String k = URLEncoder.encode(key, "UTF-8");

                // ① 城市 → ID + 经纬度
                //    ⚠ 2025 版路径变了：城市查询在 /geo/v2/city/lookup（旧的 /v2/… 实测 404）。
                //      number=1 只要最匹配的那一条，避免"太原"匹配出一串下辖县区。
                JSONObject lookup = getJson("https://" + hostName + "/geo/v2/city/lookup?key=" + k
                    + "&location=" + URLEncoder.encode(cityName, "UTF-8") + "&number=1&lang=zh");
                if (!"200".equals(lookup.optString("code"))) {
                    throw new IllegalStateException(hint(lookup.optString("code"), "城市查询"));
                }
                JSONArray list = lookup.optJSONArray("location");
                if (list == null || list.length() == 0) throw new IllegalStateException("找不到这个城市：" + cityName);
                JSONObject place = list.getJSONObject(0);
                String id = place.optString("id", "");
                String name = place.optString("name", cityName);
                String adm = place.optString("adm1", "");
                String lat = place.optString("lat", "");
                String lon = place.optString("lon", "");
                lastCityId = id;
                if (id.isEmpty()) throw new IllegalStateException("城市 ID 为空");

                // ② 实时天气
                JSONObject now = getJson("https://" + hostName + "/v7/weather/now?key=" + k
                    + "&location=" + id + "&lang=zh");
                if (!"200".equals(now.optString("code"))) {
                    throw new IllegalStateException(hint(now.optString("code"), "实时天气"));
                }
                JSONObject n = now.optJSONObject("now");
                if (n == null) throw new IllegalStateException("实时天气返回为空");

                // ③ 3 天预报（拿今天的区间 + 日出日落；失败不影响主流程）
                String min = "", max = "";
                List<Weather.Day> days = new ArrayList<>();
                try {
                    JSONObject d3 = getJson("https://" + hostName + "/v7/weather/3d?key=" + k
                        + "&location=" + id + "&lang=zh");
                    JSONArray daily = d3.optJSONArray("daily");
                    if (daily != null) {
                        for (int i = 0; i < daily.length(); i++) {
                            JSONObject day = daily.getJSONObject(i);
                            days.add(new Weather.Day(
                                shortDate(day.optString("fxDate", "")),
                                day.optString("textDay", ""),
                                trimNum(day.optString("tempMin", "")),
                                trimNum(day.optString("tempMax", "")),
                                shortTime(day.optString("sunrise", "")),
                                shortTime(day.optString("sunset", ""))));
                        }
                    }
                    if (!days.isEmpty()) { min = days.get(0).min; max = days.get(0).max; }
                } catch (Exception ignored) { }

                // ④ 空气质量
                //    ⚠ v7/air/now 已废弃（实测 403 Deprecated）。新版是
                //      /airquality/v1/current/{lat}/{lon} —— 按经纬度查，
                //      坐标直接用城市查询返回的 lat/lon，不用再单独地理编码。
                Weather.Air air = null;
                try {
                    if (!lat.isEmpty() && !lon.isEmpty()) {
                        air = parseAir(getJson("https://" + hostName + "/airquality/v1/current/"
                            + URLEncoder.encode(lat, "UTF-8") + "/" + URLEncoder.encode(lon, "UTF-8")
                            + "?key=" + k + "&lang=zh"));
                    }
                } catch (Exception ignored) { }

                // ⑤ 生活指数（type=0 = 全套）
                List<Weather.Index> indices = new ArrayList<>();
                try {
                    JSONArray arr = getJson("https://" + hostName + "/v7/indices/1d?key=" + k
                        + "&location=" + id + "&type=0&lang=zh").optJSONArray("daily");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject one = arr.getJSONObject(i);
                            indices.add(new Weather.Index(one.optString("name", ""),
                                one.optString("category", "")));
                        }
                    }
                } catch (Exception ignored) { }

                Weather.Now data = new Weather.Now(
                    adm.isEmpty() || name.startsWith(adm) ? name : adm + name,
                    n.optString("text", ""),
                    parseDouble(n.optString("temp", "")),
                    parseDouble(n.optString("feelsLike", "")),
                    n.optString("humidity", ""),
                    stripWind(n.optString("windDir", "")),
                    n.optString("windScale", ""),
                    n.optString("precip", ""),
                    n.optString("pressure", ""),
                    n.optString("vis", ""),
                    shortTime(n.optString("obsTime", "")),
                    min, max);

                // ⑥ 组装成最多 4 页 —— 眼镜一屏只有 5 行，"数据丰富"只能靠翻页
                List<String> built = new ArrayList<>();
                built.add(Weather.compose(data));
                if (air != null) built.add(Weather.composeAir(air));
                if (!days.isEmpty()) built.add(Weather.composeDays(days));
                if (!indices.isEmpty()) built.add(Weather.composeIndices(indices));

                lastNow = data;
                lastAirObj = air;
                lastDays = days;
                lastIndices = indices;
                lastError = "";
                pages = built;
                pageIndex = 0;

                if (pushToGlasses) pushPage(0);
                if (onDone != null) MAIN(host, onDone);
            } catch (Throwable e) {
                lastNow = null; lastAirObj = null; lastDays = new ArrayList<>();
                lastIndices = new ArrayList<>(); pages = new ArrayList<>(); pageIndex = 0;
                lastError = e.getMessage() == null ? "查询失败" : e.getMessage();
                if (onDone != null) MAIN(host, onDone);
            }
        });
    }

    /** 解析新版空气质量返回（indexes[] 定 AQI，pollutants[] 给分项浓度）。 */
    static Weather.Air parseAir(JSONObject raw) {
        if (raw == null) return null;
        try {
            JSONArray idxArr = raw.optJSONArray("indexes");
            if (idxArr == null || idxArr.length() == 0) return null;
            JSONObject idx = idxArr.getJSONObject(0);
            for (int i = 0; i < idxArr.length(); i++) {      // 优先国标 cn-mee
                JSONObject cand = idxArr.getJSONObject(i);
                if ("cn-mee".equals(cand.optString("code", ""))) { idx = cand; break; }
            }
            String primary = "", advice = "";
            JSONObject pp = idx.optJSONObject("primaryPollutant");
            if (pp != null) primary = pp.optString("name", "");
            JSONObject health = idx.optJSONObject("health");
            if (health != null) {
                JSONObject ad = health.optJSONObject("advice");
                if (ad != null) advice = ad.optString("generalPopulation", "");
            }
            String pm25 = "", pm10 = "", o3 = "", no2 = "", so2 = "", co = "";
            JSONArray pols = raw.optJSONArray("pollutants");
            if (pols != null) {
                for (int i = 0; i < pols.length(); i++) {
                    JSONObject p = pols.getJSONObject(i);
                    String v = concentration(p);
                    switch (p.optString("code", "")) {
                        case "pm2p5": case "pm25": pm25 = v; break;
                        case "pm10": pm10 = v; break;
                        case "o3": o3 = v; break;
                        case "no2": no2 = v; break;
                        case "so2": so2 = v; break;
                        case "co": co = v; break;
                        default: break;
                    }
                }
            }
            return new Weather.Air(idx.optString("aqiDisplay", idx.optString("aqi", "")),
                idx.optString("category", ""), primary, pm25, pm10, o3, no2, so2, co, advice);
        } catch (Exception e) {
            // 空气质量是可选页：结构对不上就当作没有，不影响实况与预报。
            return null;
        }
    }

    /** 取污染物浓度：新版在 concentration.value，整数就不带小数（省行宽）。 */
    static String concentration(JSONObject pollutant) {
        JSONObject c = pollutant.optJSONObject("concentration");
        if (c == null) return "";
        double v = c.optDouble("value", Double.NaN);
        if (!Double.isFinite(v)) return c.optString("value", "");
        if (Math.abs(v - Math.rint(v)) < 0.05) return String.valueOf((long) Math.rint(v));
        return String.valueOf(Math.round(v * 10) / 10.0);
    }

    /**
     * 域名归一化：容忍用户直接粘贴整条 URL（带 https:// 或尾部路径），只留主机名。
     * 实现在 {@link Weather#normalizeHost}（纯逻辑、有单测）。
     */
    static String normalizeHost(String raw) { return Weather.normalizeHost(raw); }

    /** 推送第 index 页到眼镜（翻页用 push 绕过节流：点一下就要立刻看到）。 */
    static void pushPage(int index) {
        List<String> p = pages;
        if (p == null || p.isEmpty()) return;
        final int i = Math.max(0, Math.min(index, p.size() - 1));
        pageIndex = i;
        final String frame = p.get(i);
        // ★ 一律回主线程推送。本方法在 fetch() 的网络线程里也会被调用，而 NavGlasses
        //   的推文最终要用反射调厂商 SDK —— SDK 只能在主线程发，从网络线程调用会
        //   静默失败：表现就是"天气查到了却推不上屏、眼镜上还残留上一模块的画面"。
        UI.post(() -> {
            NavGlasses.acquire(OWNER);
            if (NavGlasses.ready()) NavGlasses.push(frame); else NavGlasses.show(frame, false);
        });
    }

    static int pageCount() { List<String> p = pages; return p == null ? 0 : p.size(); }
    static int page() { return pageIndex; }

    /**
     * 把和风的业务错误码翻译成人话。
     * 最常见的是 403：域名不是账号专属的 API Host（老的 devapi/api 已经不行了），
     * 或者某个接口在 2025 改版里被废弃了。
     */
    static String hint(String code, String what) {
        switch (code) {
            case "401": return "Key 无效，或与当前 API Host 不匹配（域名要照抄控制台那一串）";
            case "402": return "Key 余额或访问次数用尽";
            case "403": return "无权访问（" + what + "）—— 多为域名不是账号专属 API Host，"
                + "或该接口已废弃（如空气质量旧路径 v7/air/now）";
            case "404": return "查不到这个城市（或接口路径不对），换个写法试试";
            case "429": return "请求太频繁，等一会儿再试";
            default: return what + "失败，错误码 " + code;
        }
    }

    private static void MAIN(Activity host, Runnable run) {
        if (host == null || host.isFinishing()) return;
        host.runOnUiThread(run);
    }

    /** 和风返回值的压缩函数统一放在 {@link Weather} 里（那边是纯逻辑、有单测）。 */
    static String stripWind(String dir) { return Weather.stripWind(dir); }
    static String trimNum(String v) { return Weather.trimNum(v); }
    static String shortTime(String iso) { return Weather.shortTime(iso); }
    static String shortDate(String iso) { return Weather.shortDate(iso); }

    static double parseDouble(String v) {
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return Double.NaN; }
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream stream = in) {
                byte[] buf = new byte[8192]; int n;
                while ((n = stream.read(buf)) != -1) out.write(buf, 0, n);
            }
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            conn.disconnect();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  语音入口（由 NlpRouter 判定为天气问句后调用）
    // ══════════════════════════════════════════════════════════════

    /**
     * 语音问天气。返回给用户的一句话；null = 不是天气问句。
     *
     * 行为：能解析出城市就用它，否则用设置里存的城市，再没有就提示先说城市。
     */
    static String handleVoice(Context app, String raw) {
        if (app == null || !Weather.isWeatherQuery(raw)) return null;
        android.content.SharedPreferences prefs = app.getSharedPreferences("turboio_settings", 0);
        String key = prefs.getString(KEY_PREF, "");
        if (key.isEmpty()) return "还没填和风天气 Key，请到「实时天气」里填一下";
        String city = Weather.voiceCity(raw);
        if (city.isEmpty()) city = prefs.getString(CITY_PREF, "");
        if (city.isEmpty()) return "要查哪个城市？说「太原天气」这样就行";
        final String target = city;
        final String apiHost = prefs.getString(HOST_PREF, DEFAULT_HOST);
        // 语音路径没有 Activity，用 Application context 也能跑（只做网络 + 推眼镜）。
        NET.execute(() -> fetch(null, key, target, apiHost, null, true));
        return "正在查" + target + "的天气…";
    }

    /** 供状态/诊断展示。 */
    static List<String> debugLines() {
        List<String> out = new ArrayList<>();
        out.add("城市 ID：" + (lastCityId.isEmpty() ? "—" : lastCityId));
        out.add("API Host：" + (lastHost.isEmpty() ? "—" : lastHost));
        Weather.Air air = lastAirObj;
        out.add("空气：" + (air == null ? "—" : "AQI " + air.aqi + " " + air.category));
        out.add("页面：" + (pageCount() == 0 ? "—" : (page() + 1) + "/" + pageCount()));
        return out;
    }
}

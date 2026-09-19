package com.turboio.addon;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/**
 * 家况播报：把网关聚合好的家里状态显示在 App 里，并一键推到眼镜字幕通道。
 *
 * 数据源：网关 GET /api/home/status（只读）。
 * 眼镜端只推文字 —— 所以这里把 spoken 整句直接送字幕通道，不经过模型。
 */
final class HomeStatusUI {

    private static final String OWNER = "home";

    /**
     * 语音路径：把家里情况念到眼镜上并返回一句话摘要。
     *
     * 与 show() 的区别：不碰 View，可以在任意后台线程调用。
     * 由 AyaSuperAddon.dispatchIntercept 在 domain=local/iot/home 时调用。
     *
     * 只读聚合，绝不改变设备状态。
     * 返回 null 表示"这句话不是问家况"，让调用方继续放行。
     */
    static String answer(android.content.Context app, String query) {
        if (app == null || query == null) return null;
        if (!looksLikeHomeQuestion(query)) return null;
        try {
            JSONObject result = new ToolClient(app).homeStatus();
            String spoken = result.optString("spoken", "");
            if (spoken == null || spoken.trim().isEmpty()) return "家里状态读到了，但网关没有给出可播报的内容。";
            // 直接推到眼镜（会话没开就先开；未就绪的内容会等 ready 后自动补发）。
            try {
                NavGlasses.acquire(OWNER);
                NavGlasses.show(spoken, false);
            } catch (Exception ignored) { }
            return spoken;
        } catch (Exception e) {
            String why = e.getMessage() == null ? "读取失败" : e.getMessage();
            return "读取家里状态失败：" + why;
        }
    }

    /** 粗判：这句话是不是在问家里的情况。宁可漏判也别误判（误判会把闲聊变成设备查询）。 */
    static boolean looksLikeHomeQuestion(String query) {
        String q = query == null ? "" : query;
        String[] keys = {"家里", "屋内", "室内", "家里情况", "家的情况", "家里怎么", "温度", "湿度",
            "空调", "灯", "窗帘", "门窗", "净化器", "插座", "pm2.5", "甲醛"};
        for (String k : keys) if (q.contains(k)) return true;
        return false;
    }

    static void show(Activity host) {
        if (host == null) return;
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, "家况播报", box, () -> AyaSuperAddon.showHome());

        TextView headline = TurboStyle.text(host, "正在读取家里状态…", 17, TurboStyle.MUTED);
        headline.setLineSpacing(TurboStyle.dp(host, 6), 1f);
        LinearLayout card = TurboStyle.card(host, box);
        card.addView(headline);
        TextView stamp = TurboStyle.text(host, "", 12, TurboStyle.FAINT);
        stamp.setPadding(0, TurboStyle.dp(host, 8), 0, 0);
        card.addView(stamp);

        LinearLayout envCard = TurboStyle.card(host, box);
        LinearLayout zoneCard = TurboStyle.card(host, box);

        // 眼睛能看到的：推文字
        TurboStyle.button(host, box, "推到眼镜看", true, () -> {
            final String text = headline.getText().toString();
            if (text.isEmpty() || text.startsWith("正在读取")) {
                Toast.makeText(host, "还没读到内容", Toast.LENGTH_SHORT).show();
                return;
            }
            NavGlasses.acquire(OWNER);
            NavGlasses.show(text, false);
            Toast.makeText(host, NavGlasses.ready() ? "已推到眼镜" : "正在开启显示通道…", Toast.LENGTH_LONG).show();
        });
        TurboStyle.button(host, box, "刷新", false, () -> {
            headline.setText("正在读取家里状态…");
            load(host, headline, stamp, envCard, zoneCard);
        });

        TurboStyle.gap(host, box, 14);
        TurboStyle.displayControl(host, box, "家况是一次性推送，不占用通道；推不上时用「重置」。");
        box.addView(TurboStyle.text(host,
            "只读聚合，不会改变任何设备状态。\n数据来自本地网关（Home Assistant / 米家），不经第三方模型。",
            11, TurboStyle.FAINT));

        load(host, headline, stamp, envCard, zoneCard);
        dialog.show();
    }

    private static void load(Activity host, TextView headline, TextView stamp,
                             LinearLayout envCard, LinearLayout zoneCard) {
        new Thread(() -> {
            try {
                JSONObject result = new ToolClient(host.getApplicationContext()).homeStatus();
                String spoken = result.optString("spoken", "");
                String envText = "";
                JSONObject env = result.optJSONObject("env");
                if (env != null) {
                    StringBuilder sb = new StringBuilder();
                    for (Iterator<String> it = env.keys(); it.hasNext(); ) {
                        String k = it.next();
                        if (sb.length() > 0) sb.append("   ");
                        sb.append(k).append(" ").append(env.optString(k, "—"));
                    }
                    envText = sb.toString();
                }
                final String zoneText = zonesToText(result.optJSONArray("zones"));
                final String stampText = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                        .format(new Date()) + " · 网关直连";
                final int onCount = result.optInt("lights_on_count", 0);
                final String envFinal = envText;
                host.runOnUiThread(() -> {
                    headline.setTextColor(TurboStyle.INK);
                    headline.setText(spoken);
                    stamp.setText(stampText);
                    envCard.removeAllViews();
                    envCard.addView(TurboStyle.label(host, "环境", 12, TurboStyle.MUTED));
                    TurboStyle.gap(host, envCard, 6);
                    envCard.addView(TurboStyle.text(host,
                            envFinal.isEmpty() ? "暂无环境数据" : toLines(envFinal), 15, TurboStyle.INK));
                    zoneCard.removeAllViews();
                    zoneCard.addView(TurboStyle.label(host,
                            "灯光 · 亮了 " + onCount + " 个", 12, TurboStyle.MUTED));
                    TurboStyle.gap(host, zoneCard, 6);
                    zoneCard.addView(TurboStyle.text(host,
                            zoneText.isEmpty() ? "无区域数据" : zoneText, 15, TurboStyle.INK));
                });
            } catch (Exception e) {
                final String why = e.getMessage() == null ? "读取失败" : e.getMessage();
                host.runOnUiThread(() -> {
                    headline.setTextColor(TurboStyle.BAD);
                    headline.setText("读取失败：" + why + "\n请确认已启用米家网关并填好地址与 Token。");
                    stamp.setText("");
                });
            }
        }, "TurboIO-home-status").start();
    }

    /** 把 "温度 25.9°C   湿度 39%" 这种一行串拆成带缩进的每行一项。 */
    private static String toLines(String joined) {
        String[] parts = joined.split("\\s{3,}");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private static String zonesToText(JSONArray zones) {
        if (zones == null || zones.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < zones.length(); i++) {
            JSONObject z = zones.optJSONObject(i);
            if (z == null) continue;
            String zone = z.optString("zone", "");
            JSONArray on = z.optJSONArray("on");
            String state;
            if (on == null || on.length() == 0) {
                state = "关";
            } else {
                List<String> names = new ArrayList<>();
                for (int j = 0; j < on.length(); j++) names.add(on.optString(j, ""));
                state = String.join("、", names);
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append("· ").append(zone).append("：").append(state);
        }
        return sb.toString();
    }
}

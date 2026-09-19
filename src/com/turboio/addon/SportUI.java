package com.turboio.addon;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.*;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 健身运动追踪的 Android 层：UI + GPS 距离采集 + 计时 + 推眼镜。
 *
 * 逻辑全在 {@link Sport}（纯逻辑、可单测）；这里只做"把经纬度喂进去"和"把画面画出来"。
 *
 * ── 复用眼镜通道的约定（与导航 / 电子书一致）────────────────────────
 *   · 推之前先 {@link NavGlasses#acquire(String)} 抢通道；
 *   · 但若通道此刻被别的模块（导航）占着，**不要抢**——否则会把导航挤掉；
 *     此时只管后台累计距离，等对方让出通道再推。
 *   · 停止时 {@link NavGlasses#release(String)} 让出。
 */
public final class SportUI {

    private SportUI() {}

    static final String OWNER = "sport";
    static final Sport state = new Sport();

    /** 运动历史记录（本机保存，最多 50 条）。 */
    private static final List<Sport.Record> records = new ArrayList<>();
    private static final String REC_FILE = "turboio_sport_records.json";

    private static LocationManager lm;
    private static LocationListener listener;
    private static boolean tracking;
    private static final Handler tick = new Handler(Looper.getMainLooper());
    private static Runnable refresher;

    // ══════════════════════════════════════════════════════════════
    //  语音入口（被 AyaSuperAddon.dispatchIntercept 调用，运行在后台线程）
    // ══════════════════════════════════════════════════════════════

    static String handleVoice(Context app, String query) {
        Sport.SportCmd cmd = Sport.parse(query);
        if (cmd.action == Sport.Action.NONE) return null;
        switch (cmd.action) {
            case START:
                start(app, cmd.type, cmd.goalMeters);
                openFromVoice(app);
                return "已开始" + cmd.type.label
                        + (cmd.goalMeters > 0
                            ? (" · 目标 " + (cmd.goalMeters >= 1000
                                ? String.format(Locale.ROOT, "%.1f 公里", cmd.goalMeters / 1000.0)
                                : cmd.goalMeters + " 米"))
                            : "")
                        + " · 实时距离与进度已同步到眼镜";
            case STATUS:
                return statusText();
            case STOP:
                stop(app);
                return "已停止运动 · 共 " + Sport.fmtTime(state.elapsedMs())
                        + " · " + String.format(Locale.ROOT, "%.2f", state.distanceMeters() / 1000.0) + " 公里";
            default:
                return null;
        }
    }

    /** 镜片上该看的（进度）已经推出去了吗（与电子书同义：别再让接管回复盖掉）。 */
    static boolean ownScreen(String query) {
        Sport.SportCmd cmd = Sport.parse(query);
        return cmd.action == Sport.Action.START || cmd.action == Sport.Action.STATUS;
    }

    /** 给状态卡 / 调试页用的几个只读访问器。 */
    static boolean isRunning() { return state.running(); }
    static long elapsed() { return state.elapsedMs(); }
    static String distanceText() {
        return String.format(Locale.ROOT, "%.2f 公里", state.distanceMeters() / 1000.0);
    }
    static String statusText() {
        if (!state.running() && !state.hasRecord()) return "还没有开始运动";
        String base = "已运动 " + Sport.fmtTime(state.elapsedMs())
                + " · " + String.format(Locale.ROOT, "%.2f", state.distanceMeters() / 1000.0) + " 公里";
        if (state.hasGoal()) base += " · 目标 " + state.goalPercent() + "%";
        if (state.running()) base += " · 进行中";
        else base += " · 已结束";
        return base;
    }

    // ══════════════════════════════════════════════════════════════
    //  生命周期
    // ══════════════════════════════════════════════════════════════

    static void start(Context app, Sport.Type type, int goalMeters) {
        state.start(type, goalMeters);
        pushHud();
        startLocation(app);
        startTicker();
    }

    static void stop(Context app) {
        // 有实际里程才记一条历史（否则"误点停止"会留下 0km 的脏记录）。
        if (state.running() && state.distanceMeters() > 1) {
            records.add(0, new Sport.Record(state.type(), state.startedAt(), state.elapsedMs(),
                    state.distanceMeters(), state.avgSpeedKmh(), state.goalMeters()));
            if (records.size() > 50) records.remove(records.size() - 1);
            try { saveRecords(app); } catch (Throwable ignored) { }
        }
        state.stop();
        stopLocation();
        stopTicker();
        try { NavGlasses.release(OWNER); } catch (Throwable ignored) { }
    }

    private static void startLocation(Context app) {
        if (tracking) return;
        try {
            Context c = app.getApplicationContext();
            lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            listener = new LocationListener() {
                @Override public void onLocationChanged(Location loc) {
                    if (loc != null) { state.addPoint(loc.getLatitude(), loc.getLongitude()); pushHud(); }
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) { }
            };
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 1f, listener, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 5f, listener, Looper.getMainLooper());
            tracking = true;
        } catch (Throwable ignored) { }
    }

    private static void stopLocation() {
        try { if (lm != null && listener != null) lm.removeUpdates(listener); } catch (Throwable ignored) { }
        tracking = false;
    }

    private static void startTicker() {
        stopTicker();
        refresher = new Runnable() {
            @Override public void run() {
                if (!state.running()) { stopTicker(); return; }
                pushHud();
                tick.postDelayed(this, 3000);
            }
        };
        tick.postDelayed(refresher, 3000);
    }

    private static void stopTicker() {
        if (refresher != null) tick.removeCallbacks(refresher);
        refresher = null;
    }

    /** 推眼镜：通道空闲或被自己占着才推，绝不挤掉别的模块。 */
    static void pushHud() {
        try {
            if (!state.running() && !state.hasRecord()) return;
            String owner = NavGlasses.ownerId();
            if (owner != null && !owner.isEmpty() && !owner.equals(OWNER)) return;
            NavGlasses.acquire(OWNER);
            NavGlasses.show(state.hud(), false);
        } catch (Throwable ignored) { }
    }

    // ══════════════════════════════════════════════════════════════
    //  界面
    // ══════════════════════════════════════════════════════════════

    /** 语音触发时把页面打开（与 ReaderUI.openFromVoice 同一套路）。 */
    static void openFromVoice(Context app) {
        AyaSuperAddon.runOnMain(() -> {
            Activity h = AyaSuperAddon.hostOrNull();
            if (h != null) show(h);
        });
    }

    static void show(Activity host) {
        if (host == null) return;
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, "运动 · 跑步骑行", box, () -> AyaSuperAddon.showHome());

        // ── 当前状态卡 ──
        LinearLayout card = TurboStyle.card(host, box);
        card.addView(TurboStyle.title(host, "当前运动", 17));
        TurboStyle.gap(host, card, 6);
        TextView status = TurboStyle.text(host, statusText(), 14, TurboStyle.INK);
        card.addView(status);

        // ── 目标输入 ──
        LinearLayout goalCard = TurboStyle.card(host, box);
        goalCard.addView(TurboStyle.label(host, "目标（可选，单位公里，例如 1 或 3.5）", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, goalCard, 6);
        EditText goal = new EditText(host);
        goal.setHint("不填 = 只记录距离，不设目标");
        goal.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        TurboStyle.fieldStyle(host, goal);
        goalCard.addView(goal);

        // ── 动作 ──
        TurboStyle.button(host, box, "开始跑步", true, () -> {
            int g = parseGoal(goal.getText().toString());
            start(host, Sport.Type.RUN, g);
            refresh(status);
        });
        TurboStyle.button(host, box, "开始骑行", false, () -> {
            int g = parseGoal(goal.getText().toString());
            start(host, Sport.Type.RIDE, g);
            refresh(status);
        });
        TurboStyle.button(host, box, "运动进度（念到眼镜）", false, () -> {
            try { NavGlasses.show(statusText(), false); } catch (Throwable ignored) { }
            refresh(status);
        });
        TurboStyle.button(host, box, "停止运动", false, () -> {
            stop(host);
            refresh(status);
        });
        TurboStyle.button(host, box, "退出运动（停表 + 释放眼镜 + 返回）", false, () -> {
            stop(host);
            dialog.dismiss();
        });

        // ── 运动记录（本机保存，最多 50 条）──
        LinearLayout recCard = TurboStyle.card(host, box);
        recCard.addView(TurboStyle.label(host, "运动记录（本机保存）", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, recCard, 8);
        if (records.isEmpty()) {
            recCard.addView(TurboStyle.text(host, "还没有记录，跑完一次会自动保存到这里。", 13, TurboStyle.FAINT));
        } else {
            int shown = Math.min(records.size(), 12);
            for (int i = 0; i < shown; i++) recCard.addView(recordLine(host, records.get(i)));
        }

        // ── 眼镜显示控制（退出 / 重置）──
        TurboStyle.displayControl(host, box, "运动期间会持续占用字幕通道；测别的功能前点「退出眼镜显示」。");

        // 每秒刷新一次状态文字（停表观感）
        final Runnable[] live = { null };
        live[0] = new Runnable() {
            @Override public void run() {
                if (!state.running()) { if (live[0] != null) tick.removeCallbacks(live[0]); return; }
                refresh(status);
                tick.postDelayed(this, 1000);
            }
        };
        tick.postDelayed(live[0], 1000);

        TurboStyle.gap(host, box, 6);
        box.addView(TurboStyle.text(host,
                "语音说「开始跑步 / 开始骑行 / 停止运动 / 运动进度」也能控制。\n"
                + "运动时镜片显示进度条 + 实时速度 + 配速；「退出运动」停表并释放显示。\n"
                + "需要手机或车机的 GPS 定位权限；距离按 GPS 轨迹累计，室内/隧道会偏少。",
                11, TurboStyle.FAINT));
    }

    private static void refresh(TextView status) {
        if (status != null) status.setText(statusText());
    }

    /** 历史记录里的一行（日期 + 类型 + 距离 + 时长 + 平均配速）。 */
    private static TextView recordLine(Activity host, Sport.Record r) {
        String date = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date(r.startedAt));
        String dkm = String.format(Locale.ROOT, "%.2f", r.distanceMeters / 1000.0);
        int pace = r.avgSpeedKmh > 0 ? (int) Math.round(1000.0 / (r.avgSpeedKmh / 3.6)) : 0;
        TextView t = TurboStyle.text(host, date + "  " + r.type.label + "  " + dkm + "km  "
                + Sport.fmtTime(r.elapsedMs) + "  " + Sport.fmtPace(pace) + "/km", 13, TurboStyle.INK);
        t.setLineSpacing(TurboStyle.dp(host, 3), 1f);
        return t;
    }

    /** 从本机文件载入历史记录（解析失败就当没有）。 */
    private static void loadRecords(Context app) {
        try {
            File f = new File(app.getFilesDir(), REC_FILE);
            if (!f.exists()) return;
            String txt = readFile(f);
            if (txt == null || txt.isEmpty()) return;
            JSONArray arr = new JSONArray(txt);
            ArrayList<Sport.Record> loaded = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Sport.Type type = o.optString("type", "").contains("骑行") ? Sport.Type.RIDE : Sport.Type.RUN;
                loaded.add(new Sport.Record(type, o.optLong("startedAt"), o.optLong("elapsedMs"),
                        o.optDouble("distanceMeters"), o.optDouble("avgSpeedKmh"), o.optInt("goalMeters")));
            }
            records.clear();
            records.addAll(loaded);
        } catch (Throwable ignored) { }
    }

    /** 把历史记录写回本机文件。 */
    private static void saveRecords(Context app) {
        try {
            JSONArray arr = new JSONArray();
            for (Sport.Record r : records) {
                arr.put(new JSONObject()
                        .put("type", r.type.label)
                        .put("startedAt", r.startedAt)
                        .put("elapsedMs", r.elapsedMs)
                        .put("distanceMeters", r.distanceMeters)
                        .put("avgSpeedKmh", r.avgSpeedKmh)
                        .put("goalMeters", r.goalMeters));
            }
            writeFile(new File(app.getFilesDir(), REC_FILE), arr.toString());
        } catch (Throwable ignored) { }
    }

    private static String readFile(File f) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            return b.toString();
        } catch (Throwable e) { return ""; }
    }

    private static void writeFile(File f, String s) {
        try (FileWriter w = new FileWriter(f)) { w.write(s); } catch (Throwable ignored) { }
    }

    private static int parseGoal(String raw) {
        if (raw == null) return 0;
        String s = raw.trim().replaceAll("[^0-9.]", "");
        if (s.isEmpty()) return 0;
        try {
            double v = Double.parseDouble(s);
            if (v <= 0 || v > 500) return 0;
            return (int) (v * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

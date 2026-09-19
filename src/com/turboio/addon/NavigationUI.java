package com.turboio.addon;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.widget.*;
import java.util.*;

/**
 * 「导航」页。**薄层**，真正的逻辑全在 NavEngine。
 *
 * ── 为什么把旧的 221 行 NavigationUI 整个换掉 ───────────────────
 * 用户原话：
 *   "addon 里面的导航做的太复杂且不实用了……应该直接使用自有模型的时候，
 *    语音操控就可以直接开始导航，而不是去模块里实现手动导航功能。"
 *
 * 旧版有的东西（全部删除）：
 *   · 搜索框 + 城市输入 + POI 列表         → 改：直接说话
 *   · 高德 Android SDK 地图（要 AMap Key，重签必坏）→ 改：纯 HTTP，免 Key 绑定
 *   · 长按地图选点                          → 删除
 *   · 步行/骑行/驾车切换                    → 删除（导航就是驾车）
 *   · 模拟导航 + 倍速 + 暂停                → 删除
 *   · 路线方案手动选择                      → 删除（固定走高德推荐）
 *   · 会话退出确认弹窗                      → 删除（NavGlasses 自己管）
 *
 * 现在这一页只干三件事：
 *   ① 告诉你现在在导航去哪、下一步怎么走（和眼镜同步）；
 *   ② 提供一个"手动输入目的地"的兜底（语音不灵时用）；
 *   ③ 显示高德 Key 配置（Android 平台 Key；v3r21 按用户实测纠正）。
 */
public final class NavigationUI {

    private static NavigationUI current;
    private static android.app.Dialog shared;
    private Activity host;
    private TextView state, preview, meta, fixLine;
    private EditText input;
    private Runnable poller;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    public static void show(Activity a) {
        if (current != null && shared != null) { shared.show(); current.render(); return; }
        current = new NavigationUI(a);
        current.open();
    }
    public static void shutdown() {
        if (current != null) current.close();
    }
    private NavigationUI(Activity a) { host = a; }

    private void open() {
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, "导航 · 抬头指引", box, () -> AyaSuperAddon.showHome());
        shared = dialog;
        // v3r24：dismissal 由 TurboStyle.screen 独占（它要跑返回回调），
        // 页面自己的清理走 onDismissExtra —— 直接 setOnDismissListener 会把外壳回调顶掉。
        TurboStyle.onDismissExtra(dialog, () -> close());

        // ── 当前状态 ──
        LinearLayout card = TurboStyle.card(host, box);
        card.addView(TurboStyle.label(host, "现在", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, card, 8);
        state = TurboStyle.text(host, NavEngine.active() ? NavEngine.status() : "未在导航", 17, TurboStyle.INK);
        state.setLineSpacing(TurboStyle.dp(host, 5), 1f);
        card.addView(state);
        TurboStyle.gap(host, card, 10);
        card.addView(TurboStyle.label(host, "眼镜上正在显示", 11, TurboStyle.MUTED));
        preview = TurboStyle.text(host, "（还没有内容）", 15, TurboStyle.LIME);
        preview.setLineSpacing(TurboStyle.dp(host, 5), 1f);
        card.addView(preview);
        TurboStyle.gap(host, card, 8);
        meta = TurboStyle.text(host, "", 12, TurboStyle.FAINT);
        card.addView(meta);

        // ── 怎么用（语音优先） ──
        LinearLayout how = TurboStyle.card(host, box);
        how.addView(TurboStyle.label(host, "怎么开始", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, how, 8);
        how.addView(TurboStyle.text(host,
            "直接说：\n"
            + "  「导航到 太原南站」\n"
            + "  「带我去 万象城」\n"
            + "  「退出导航」\n\n"
            + "会自动：定位你现在的位置 → 认清你在哪座城市 → "
            + "只在该城市范围内搜目的地（避免重名地点跑外省）→ "
            + "走高德推荐路线 → 把「箭头 + 距离 + 动作」推到眼镜上。\n"
            + "刷新节奏：平时 3 秒一帧，快到路口（本段剩 < 400 米）自动提到 1 秒一帧。\n"
            + "导航期间会持续用 GPS 定位（状态栏会出现定位图标）——"
            + "如果画面不动，先看下面「定位」那张卡。", 14, TurboStyle.INK));

        // ── 定位（排"画面不动"的第一现场）──────────────────────────
        // 为什么单独一张卡：用户这次的反馈是「导航还是不刷新 貌似位置不更新？
        // 我没看到手机的位置图标」。这两句其实是同一件事 —— 导航期间如果
        // **没有 App 持续请求定位更新**，系统就不会点亮状态栏的定位图标，
        // 而每秒读到的只是 getLastKnownLocation 的缓存值，那个点不会变，
        // 于是段号永远不动、画面定死。
        //
        // 所以这一行必须能回答三个问题：定位在哪？（来源+精度）
        // 多久没更新了？（年龄）在不在订阅？（有没有真的在请求）
        // 拿不到的时候，下面那个按钮直接把用户送到系统权限页。
        LinearLayout fixCard = TurboStyle.card(host, box);
        fixCard.addView(TurboStyle.label(host, "定位（画面不动时先看这里）", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, fixCard, 8);
        fixLine = TurboStyle.text(host, "", 14, TurboStyle.INK);
        fixLine.setLineSpacing(TurboStyle.dp(host, 4), 1f);
        fixCard.addView(fixLine);
        TurboStyle.gap(host, fixCard, 8);
        TurboStyle.button(host, fixCard, "去系统设置给「雷鸟」开定位权限", false, this::openAppSettings);

        // ── 手动兜底 ──
        LinearLayout manual = TurboStyle.card(host, box);
        manual.addView(TurboStyle.label(host, "手动输入（语音不灵时用）", 11, TurboStyle.MUTED));
        input = new EditText(host);
        input.setHint("目的地，如 太原南站");
        input.setSingleLine();
        TurboStyle.field(host, input);
        manual.addView(input);
        TurboStyle.button(host, manual, "开始导航", true, this::startManual);
        TurboStyle.button(host, manual, "退出导航", false, () -> {
            NavEngine.stop();
            NavGlasses.releaseAll();
            render();
        });

        // ── 眼镜显示控制（退出 / 重置）——所有会占用字幕通道的页面统一都有一条 ──
        TurboStyle.displayControl(host, box, "导航会持续占用字幕通道；测别的功能前先点「退出眼镜显示」。");

        // ── 眼镜显示样式（箭头字形兼容性） ──
        // 眼镜固件是中文环境，字体只保证覆盖 GB2312/GBK。
        // 原来的 ↰ ↱ ⤴ ↻ 这些箭头连 GBK 都不在码表里，画不出来就是空白 ——
        // 用户看到的效果等于"纯文字，没有箭头"。
        //
        // ── 为什么从"点一下换一种"改成列表直选 ─────────────────────
        // 旧版是一个循环按钮，只能一路往后切；而 cycleStyle 当时是
        // `setStyleIndex(ordinal+1)`，越界是"夹紧"不是"回绕" ——
        // 切到最后一项（西文）之后再点还是西文，用户实测「切到西文就切不回去了」。
        // 现在改成三个可点选项，每个都带**自己的实时预览**，想选哪个点哪个。
        LinearLayout styleCard = TurboStyle.card(host, box);
        styleCard.addView(TurboStyle.label(host, "眼镜显示样式（箭头字形）", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, styleCard, 8);
        final TextView[] styleBoxes = new TextView[NavGuide.styleCount()];
        final LinearLayout[] styleCells = new LinearLayout[NavGuide.styleCount()];
        final Runnable[] paintStyle = new Runnable[1];
        for (int i = 0; i < NavGuide.styleCount(); i++) {
            final int idx = i;
            LinearLayout cell = TurboStyle.column(host);
            cell.setPadding(TurboStyle.dp(host, 14), TurboStyle.dp(host, 12), TurboStyle.dp(host, 14), TurboStyle.dp(host, 12));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.topMargin = TurboStyle.dp(host, 6);
            styleCard.addView(cell, cp);
            styleCells[idx] = cell;

            TextView head = TurboStyle.text(host, "○ " + NavGuide.styleAt(idx).label, 14, TurboStyle.INK);
            cell.addView(head);
            TurboStyle.gap(host, cell, 6);
            TextView sample = TurboStyle.text(host, NavGuide.sampleFor(idx), 12, TurboStyle.LIME);
            sample.setLineSpacing(TurboStyle.dp(host, 3), 1f);
            cell.addView(sample);
            styleBoxes[idx] = head;

            cell.setOnClickListener(v -> {
                NavGuide.setStyleIndex(idx);
                host.getSharedPreferences("turboio_settings", 0).edit()
                    .putInt("nav_glyph_style", NavGuide.styleIndex()).apply();
                paintStyle[0].run();
            });
        }
        paintStyle[0] = () -> {
            for (int i = 0; i < styleCells.length; i++) {
                boolean on = i == NavGuide.styleIndex();
                styleCells[i].setBackground(TurboStyle.stroked(host,
                    on ? TurboStyle.LIME_DIM : TurboStyle.SURFACE_2, 16,
                    on ? TurboStyle.LIME : TurboStyle.STROKE));
                styleBoxes[i].setText((on ? "● " : "○ ") + NavGuide.styleAt(i).label);
                styleBoxes[i].setTextColor(on ? TurboStyle.LIME : TurboStyle.INK);
            }
        };
        paintStyle[0].run();
        TurboStyle.gap(host, styleCard, 6);
        styleCard.addView(TurboStyle.text(host,
            "眼镜是中文字体，只保证画得出 GB2312 内的符号。\n"
            + "标准：← ↑ → ↓ ● ○ ■ □（最稳，推荐）\n"
            + "精细：↰ ↱ ↖ ↗ ⤴ ⤵ ⟲ ↻（好看，但部分固件画不出，会变空白）\n"
            + "西文：< > ^ U O（连 GB2312 都没有时兜底）\n"
            + "选完立刻生效，重新推送一次即可看到效果。", 11, TurboStyle.FAINT));

        // ── 权限 / Key ──
        LinearLayout perm = TurboStyle.card(host, box);
        perm.addView(TurboStyle.label(host, "必需的权限", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, perm, 8);
        boolean granted = NavEngine.hasLocationPermission(host);
        perm.addView(TurboStyle.statusRow(host, "●",
            granted ? "定位权限已授予" : "缺定位权限 · 导航拿不到起点",
            granted ? TurboStyle.OK : TurboStyle.BAD));
        if (!granted) {
            TurboStyle.button(host, perm, "授予定位权限", false, () -> host.requestPermissions(
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 7302));
        }

        TurboStyle.button(host, box, "高德 Key 设置（Android 平台）", false, this::keyDialog);

        TurboStyle.gap(host, box, 14);
        box.addView(TurboStyle.text(host,
            "本页不嵌高德地图 SDK，全部走 HTTP 接口 —— 所以不依赖签名，重装后依然能用。\n"
            + "眼镜 5 行：箭头+距离+动作 / 高德原话 / 进度条 / 剩余里程·时间 / 目的地。\n"
            + "进度条按「已走里程 ÷ 全程」算，剩余时间跟着剩余里程折算（不是一直显示全程时间）。\n"
            + "眼镜端单次显示约 5 行。定位只在本机使用，不上传轨迹。", 11, TurboStyle.FAINT));

        dialog.show();

        // 每 2 秒刷新一次界面（脚本化的"现在"卡片）。
        poller = new Runnable() {
            @Override public void run() {
                render();
                main.postDelayed(this, 2000);
            }
        };
        main.postDelayed(poller, 2000);
    }

    private void startManual() {
        String target = input.getText().toString().trim();
        if (target.isEmpty()) { input.setError("请输入目的地"); return; }
        if (!NavEngine.hasLocationPermission(host)) {
            Toast.makeText(host, "请先授予定位权限", Toast.LENGTH_LONG).show();
            host.requestPermissions(new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 7302);
            return;
        }
        Toast.makeText(host, "正在规划路线…", Toast.LENGTH_SHORT).show();
        NavEngine.startAsync(host.getApplicationContext(), target);
        input.setText("");
        main.postDelayed(this::render, 1500);
    }

    private void render() {
        try {
            // ★ 心跳保活：这是一个**独立于推送链路**的观察者。
            //   导航在跑但刷新心跳断了（异常被吞 / 被别的模块停掉）时，
            //   最坏 2 秒后这里会把它接回来 —— 用户不会再看到"卡在第一屏"。
            NavEngine.ensureTicker();
            NavGlasses.keepAlive();
            state.setText(NavEngine.active() ? NavEngine.status() : "未在导航");
            String screen = NavEngine.screen();
            preview.setText(screen == null || screen.isEmpty() ? "（还没有内容）" : screen);
            int count = NavEngine.stepCount();
            StringBuilder metaText = new StringBuilder();
            if (NavEngine.active()) {
                metaText.append("分段 ").append(NavEngine.stepIndex() + 1).append(" / ").append(count);
                metaText.append(" · 眼镜 ").append(NavGlasses.phase());
                metaText.append(" · ").append(NavGlasses.connection());
            } else {
                metaText.append("眼镜显示通道：").append(NavGlasses.connection());
            }
            // 目的地解析结果必须摆出来：市内导航却出现几千公里 / 导到别的城市，
            // 十有八九就是这里解析错了（重名 POI），用户一眼就能发现。
            String scope = NavEngine.searchScope();
            if (!scope.isEmpty()) metaText.append("\n搜索范围：").append(scope);
            String resolved = NavEngine.resolvedAddress();
            if (!resolved.isEmpty()) metaText.append("\n高德解析为：").append(resolved);
            String note = NavEngine.geometryNote();
            if (!note.isEmpty()) metaText.append("\n").append(note);
            meta.setText(metaText.toString());
            // ★ 定位状态：排"画面不动"的第一现场。
            //   以前"路真的没变"和"定位根本没数据"在屏幕上长得一模一样，
            //   用户只能猜。现在来源/年龄/精度/是否在订阅全都摆出来。
            fixLine.setText(NavEngine.fixText(host));
        } catch (Exception ignored) { }
    }

    /** 跳到本应用的系统设置页（开定位权限用）。 */
    private void openAppSettings() {
        try {
            android.content.Intent intent = new android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + host.getPackageName()));
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(host,
                "打不开系统设置，请手动到「设置 → 应用 → 雷鸟」里打开定位权限",
                Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 高德 Key 设置（v3r21：改成整屏面板 + 文案按实测修正）。
     *
     * ★ 服务平台类型（用户 2026-09-18 实测纠正）★
     *   插件走的是纯 HTTP（restapi.amap.com），但实测**必须用「Android 平台」类型的 Key**；
     *   Web 服务 / Web端(JS) 类型的 Key 用不了。之前这里写的是「Web 服务」，是错的。
     */
    private void keyDialog() {
        LinearLayout box = TurboStyle.column(host);
        android.app.Dialog dialog = TurboStyle.screen(host, "高德 Key", box, () -> NavigationUI.show(host));

        LinearLayout info = TurboStyle.card(host, box);
        info.addView(TurboStyle.label(host, "怎么拿这个 Key", 11, TurboStyle.MUTED));
        TurboStyle.gap(host, info, 8);
        info.addView(TurboStyle.text(host,
            "高德开放平台（lbs.amap.com）→ 注册并实名 → 控制台「应用管理」\n"
            + "→ 创建应用 → 「添加 Key」→ 服务平台选「Android 平台」\n"
            + "→ 按提示填应用包名 com.rayneo.venus.pub → 复制那串 Key。\n\n"
            +             "必须选 Android 平台：选「Web 服务」「Web端(JS)」的 Key 用不了。\n"
            + "不填 Key 则导航、巡航、路况功能无法规划路线。", 13, TurboStyle.MUTED));

        TurboStyle.sectionTitle(host, box, "Key");
        EditText field = new EditText(host);
        field.setSingleLine();
        field.setHint("粘贴高德 Key");
        String saved = host.getSharedPreferences("turboio_settings", 0).getString("amap_key", "");
        field.setText(saved == null ? "" : saved);
        TurboStyle.field(host, field);
        box.addView(field);

        LinearLayout out = TurboStyle.card(host, box);
        {   // 一行结论：现在用的是哪一个
            boolean own = saved != null && !saved.trim().isEmpty();
            out.addView(TurboStyle.statusRow(host, "●",
                own ? "已配置你自己的 Key" : "还没有配置 Key，导航不可用",
                own ? TurboStyle.OK : TurboStyle.WARN));
        }
        TurboStyle.button(host, box, "保存", true, () -> {
            host.getSharedPreferences("turboio_settings", 0).edit()
                .putString("amap_key", field.getText().toString().trim()).apply();
            Toast.makeText(host, "已保存，重新推送一次导航即可生效", Toast.LENGTH_SHORT).show();
            dialog.dismiss(); NavigationUI.show(host);
        });
        TurboStyle.button(host, box, "清空", false, () -> {
            host.getSharedPreferences("turboio_settings", 0).edit().remove("amap_key").apply();
            Toast.makeText(host, "已清空，导航将不可用", Toast.LENGTH_SHORT).show();
            dialog.dismiss(); NavigationUI.show(host);
        });
    }

    private void close() {
        if (poller != null) main.removeCallbacks(poller);
        poller = null;
        shared = null;
        current = null;
    }
}

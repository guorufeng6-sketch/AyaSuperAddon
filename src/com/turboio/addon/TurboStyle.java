package com.turboio.addon;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/**
 * 设计系统。深色系 —— 与眼镜端 UI、App 主界面保持一致的暗色观感，
 * 避免旧版浅色卡片在系统深色主题下"越看越像安卓 4.0"的割裂感。
 *
 * 约定：
 *  - 背景 BG 近黑、卡片 SURFACE 抬升一档、描边 STROKE 极淡
 *  - 强调色 LIME（品牌绿）+ ACCENT_BLUE / ACCENT_AMBER 用于状态
 *  - 所有圆角统一走 radius()，不散落魔法数字
 */
final class TurboStyle {
    // ---- 深色底 ----
    static final int BG        = 0xff0d1210;   // 全屏底
    static final int SURFACE   = 0xff161d19;   // 卡片
    static final int SURFACE_2 = 0xff1e2722;   // 次级卡片 / 输入框
    static final int STROKE    = 0xff2a3730;   // 描边
    static final int INK       = 0xffeaf3ed;   // 主文字（浅）
    static final int MUTED     = 0xff8ea198;   // 次文字
    static final int FAINT     = 0xff5c6b63;   // 三级文字
    static final int LIME      = 0xffc5f58d;   // 品牌强调
    static final int LIME_DIM  = 0xff1c2f22;   // 强调底
    static final int OK        = 0xff5ddba0;   // 状态：正常
    static final int WARN      = 0xffe6b455;   // 状态：注意
    static final int BAD       = 0xffe07a6a;   // 状态：异常
    static final int BLUE      = 0xff7fb4f5;   // 状态：信息

    static int dp(Activity a,int x){return (int)(a.getResources().getDisplayMetrics().density*x+.5f);}

    static GradientDrawable surface(Activity a,int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(a,radius));return d;
    }
    static GradientDrawable stroked(Activity a,int color,int radius,int strokeColor){
        GradientDrawable d=surface(a,color,radius);d.setStroke(dp(a,1),strokeColor);return d;
    }
    /** 左侧有一条彩色竖条的状态卡（状态一眼可辨）。 */
    static GradientDrawable barCard(Activity a,int fill,int barColor,int radius){
        GradientDrawable d=surface(a,fill,radius);d.setStroke(dp(a,1),STROKE);return d;
    }

    static LinearLayout column(Activity a){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static LinearLayout rowBox(Activity a){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}

    static TextView text(Activity a,String value,int size,int color){
        TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setLineSpacing(dp(a,3),1);t.setIncludeFontPadding(false);return t;
    }
    static TextView title(Activity a,String value,int size){TextView t=text(a,value,size,INK);t.setTypeface(null,Typeface.BOLD);return t;}
    static TextView label(Activity a,String value,int size,int color){TextView t=text(a,value,size,color);t.setTypeface(null,Typeface.BOLD);t.setLetterSpacing(.08f);return t;}

    static void gap(Activity a,LinearLayout l,int n){View v=new View(a);l.addView(v,new LinearLayout.LayoutParams(1,dp(a,n)));}

    static LinearLayout card(Activity a,LinearLayout parent){
        return card(a,parent,SURFACE);
    }
    /** 兼容旧调用：指定卡片填充色（NavigationUI 仍在用）。 */
    static LinearLayout card(Activity a,LinearLayout parent,int color){
        LinearLayout l=column(a);l.setPadding(dp(a,18),dp(a,16),dp(a,18),dp(a,16));
        l.setBackground(color==SURFACE?stroked(a,SURFACE,20,STROKE):surface(a,color,20));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(a,10);
        parent.addView(l,p);return l;
    }
    /** 无内边距的裸卡片，供自行排布（如状态条）。 */
    static LinearLayout bare(Activity a,LinearLayout parent){
        LinearLayout l=column(a);l.setBackground(stroked(a,SURFACE,20,STROKE));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(a,10);
        parent.addView(l,p);return l;
    }

    static void fieldStyle(Activity a,EditText f){
        f.setTextColor(INK);f.setHintTextColor(FAINT);f.setTextSize(15);
        f.setPadding(dp(a,14),dp(a,13),dp(a,14),dp(a,13));
        f.setBackground(stroked(a,SURFACE_2,14,STROKE));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(a,52));p.topMargin=dp(a,4);p.bottomMargin=dp(a,6);
        f.setLayoutParams(p);
    }
    /** 兼容旧调用名（NavigationUI 用 field）。 */
    static void field(Activity a,EditText f){ fieldStyle(a,f); }

    /** 兼容旧调用：一行入口（图标 + 标题 + 副标题 + 箭头）。 */
    static void row(Activity a,LinearLayout parent,String glyph,String title,String detail,Runnable run){
        entry(a,parent,glyph,title,detail,LIME,run);
    }

    static Button button(Activity a,LinearLayout l,String text,boolean primary,Runnable action){
        Button b=new Button(a);b.setText(text);b.setAllCaps(false);b.setTextSize(15);
        b.setTextColor(primary?0xff0d1210:INK);b.setTypeface(null,Typeface.BOLD);
        b.setBackground(surface(a,primary?LIME:SURFACE_2,16));
        b.setStateListAnimator(null);b.setElevation(0);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(a,50));p.topMargin=dp(a,10);
        l.addView(b,p);b.setOnClickListener(v->action.run());return b;
    }
    static Button ghost(Activity a,LinearLayout l,String text,Runnable action){
        Button b=new Button(a);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(MUTED);
        b.setBackground(surface(a,SURFACE_2,14));b.setStateListAnimator(null);b.setElevation(0);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(a,44));p.topMargin=dp(a,8);
        l.addView(b,p);b.setOnClickListener(v->action.run());return b;
    }

    /** 分组标题：小号、字距拉开、前面带一条短绿线。 */
    static void sectionTitle(Activity a,LinearLayout l,String text){
        LinearLayout r=rowBox(a);r.setPadding(dp(a,4),dp(a,16),0,dp(a,8));
        View bar=new View(a);bar.setBackground(surface(a,LIME,2));r.addView(bar,new LinearLayout.LayoutParams(dp(a,3),dp(a,14)));
        TextView t=label(a,text,12,MUTED);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=dp(a,8);r.addView(t,tp);
        l.addView(r);
    }

    /** 功能入口行：圆形图标 + 标题/副标题 + 箭头。 */
    static View entry(Activity a,LinearLayout l,String glyph,String title,String detail,int iconColor,Runnable run){
        return entry(a,l,glyph,title,detail,iconColor,null,0,run);
    }

    /** 取 color 的 RGB + 指定不透明度 —— 做"淡染底 / 淡描边"用。 */
    static int tint(int color,int alpha){return (color&0x00ffffff)|(alpha<<24);}

    /** 状态胶囊：`● 已开启`。颜色即语义，文字只剩两三个字。 */
    static TextView pill(Activity a,String status,int color){
        TextView t=text(a,"● "+status,11,color);
        t.setTypeface(null,Typeface.BOLD);
        t.setPadding(dp(a,9),dp(a,4),dp(a,9),dp(a,4));
        t.setBackground(stroked(a,tint(color,0x22),20,tint(color,0x66)));
        return t;
    }
    /** 就地刷新胶囊（开关翻转后调它，不用重建整行）。 */
    static void paintPill(Activity a,TextView t,String status,int color){
        if(t==null)return;
        t.setText("● "+status);
        t.setTextColor(color);
        t.setBackground(stroked(a,tint(color,0x22),20,tint(color,0x66)));
    }
    /** 把胶囊挂到一行里（返回它，供后续刷新）。 */
    static TextView addPill(Activity a,LinearLayout row,String status,int color){
        TextView t=pill(a,status,color);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);
        p.leftMargin=dp(a,8);
        row.addView(t,p);
        return t;
    }

    /**
     * 功能入口行（带状态胶囊）：图标 + 标题/副标题 + 状态 + 箭头。
     *
     * ── 为什么要加状态胶囊（2026-09-18 用户反馈）───────────────────
     * 原话「这几个按钮显示一下，有的不清楚状态，有的显得太老了」。
     * 旧版入口右侧只有一个 `›`，开关类功能（意图接管 / 米家接入 / 和风天气）
     * 到底开没开、配没配，只能点进去看 —— 首页等于没给结论。
     * 现在把状态提到行内右侧：一颗与语义同色的胶囊，两三个字说完。
     *
     * 顺带把图标底从"灰底方块"换成**图标色的半透明淡染 + 同色淡描边**：
     * 旧版灰底在深色卡片上像安卓 4.0 的列表项，淡染底才跟得上现在的观感。
     *
     * @param status 胶囊文字；null / 空串表示这一行没有状态可报（只显示箭头）
     * @return 胶囊控件（没有则 null），调用方可随时 {@link #paintPill} 刷新
     */
    static TextView entry(Activity a,LinearLayout l,String glyph,String title,String detail,
                          int iconColor,String status,int statusColor,Runnable run){
        LinearLayout c=bare(a,l);c.setPadding(0,0,0,0);
        LinearLayout r=rowBox(a);r.setPadding(dp(a,16),dp(a,14),dp(a,14),dp(a,14));
        TextView icon=text(a,glyph,19,iconColor);icon.setGravity(Gravity.CENTER);
        icon.setBackground(stroked(a,tint(iconColor,0x1f),15,tint(iconColor,0x52)));
        r.addView(icon,new LinearLayout.LayoutParams(dp(a,44),dp(a,44)));
        LinearLayout labels=column(a);labels.setPadding(dp(a,14),0,dp(a,10),0);
        labels.addView(title(a,title,16));
        if(detail!=null&&!detail.isEmpty()){
            TextView d=text(a,detail,12,MUTED);
            LinearLayout.LayoutParams dp2=new LinearLayout.LayoutParams(-2,-2);dp2.topMargin=dp(a,2);
            labels.addView(d,dp2);
        }
        r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        TextView badge=null;
        if(status!=null&&!status.isEmpty())badge=addPill(a,r,status,statusColor);
        TextView arrow=text(a,"›",22,FAINT);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,-2);ap.leftMargin=dp(a,8);
        r.addView(arrow,ap);
        c.addView(r);
        c.setOnClickListener(v->run.run());c.setContentDescription(title);
        c.setFocusable(true);c.setClickable(true);
        return badge;
    }

    /** 二列网格入口，用于紧凑排布次要功能。 */
    static LinearLayout grid(Activity a,LinearLayout parent){
        LinearLayout g=new LinearLayout(a);g.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(a,10);
        parent.addView(g,p);return g;
    }
    static LinearLayout gridCell(Activity a,LinearLayout grid,boolean right){
        LinearLayout c=column(a);c.setPadding(dp(a,14),dp(a,14),dp(a,14),dp(a,14));
        c.setBackground(stroked(a,SURFACE,18,STROKE));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(a,96),1);
        p.rightMargin=right?0:dp(a,10);p.leftMargin=right?dp(a,10):0;
        grid.addView(c,p);return c;
    }

    // ── 全局唯一的「页面级 Dialog」仲裁（v3r22 修复堆叠 / 返回错乱）──────
    //   旧版每个 show() 都 new 一个 Dialog 且不关掉前一个：home 对话框一直叠在底下，
    //   子页面返回时 back 回调又 showHome() 在它上面再叠一个 home —— 栈越积越深，
    //   于是「后退要按十几次」「退着退着就回到官方 App 主页」。
    //   现在：同时只存在一个 screen 页面；开新页先关旧页；同一标题已开就直接复用。
    static Dialog currentScreen;
    static String currentScreenTitle;
    // v3r24：按 Dialog 记账的「抑制开关」+ 页面自带的清理钩子。
    //   ① 旧版用一个静态布尔 suppressScreenBack：true → dismiss() → 立刻 false。
    //      但 dismiss/cancel 的回调是 post 到消息队列**异步**执行的，等它真正跑起来
    //      开关早已被重置 —— 抑制形同虚设，程序化切页时照样误弹 home。改成每个
    //      Dialog 记一笔账，回调执行时才清账，抑制才真的生效。
    //   ② setOnDismissListener / setOnCancelListener 一个 Dialog 都**只能挂一个**。
    //      NavigationUI / CountdownUI / CruiseUI 在 screen() 返回后又自己
    //      setOnDismissListener，会把外壳的返回回调整个顶掉 —— 于是那几页返回时
    //      又掉回官方主页。所以 dismissal 改由外壳**独占**，页面要清理请走
    //      onDismissExtra()。
    private static final java.util.Map<Dialog,Boolean> screenSuppress =
            new java.util.WeakHashMap<Dialog,Boolean>();
    private static final java.util.Map<Dialog,java.util.List<Runnable>> screenExtras =
            new java.util.WeakHashMap<Dialog,java.util.List<Runnable>>();

    /** 页面想在关闭时多做点事（停定时器 / 释放眼镜通道）走这里，别再 setOnDismissListener。 */
    static void onDismissExtra(Dialog d, Runnable extra){
        if (d == null || extra == null) return;
        java.util.List<Runnable> l = screenExtras.get(d);
        if (l == null) { l = new java.util.ArrayList<Runnable>(); screenExtras.put(d, l); }
        l.add(extra);
    }

    /** 全屏 Dialog 外壳。全局只允许存在一个此类页面。 */
    static Dialog screen(Activity a,String title,View content,Runnable back){
        if (currentScreen != null && currentScreen.isShowing()) {
            // 同一页已经开着：直接复用，避免「打开十几次」反复堆叠。
            if (title != null && title.equals(currentScreenTitle)) return currentScreen;
            // 不同页：先关旧的，并记账"这次是程序化切页，旧页别触发它的 back"。
            Dialog old = currentScreen;
            screenSuppress.put(old, Boolean.TRUE);
            try { old.dismiss(); } catch (Throwable ignored) { }
        }
        Dialog d=new Dialog(a);d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell=column(a);shell.setBackgroundColor(BG);
        shell.setPadding(dp(a,18),dp(a,12),dp(a,18),dp(a,10));
        LinearLayout header=rowBox(a);
        TextView close=text(a,"‹",30,INK);close.setGravity(Gravity.CENTER);
        close.setBackground(surface(a,SURFACE_2,14));
        header.addView(close,new LinearLayout.LayoutParams(dp(a,42),dp(a,42)));
        TextView t=title(a,title,22);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=dp(a,12);header.addView(t,tp);
        shell.addView(header);gap(a,shell,12);
        ScrollView scroll=new ScrollView(a);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        d.setContentView(shell);
        // ★ v3r24 真正的返回修复（v3r23 挂在 OnCancel 上是错的）：
        //   Dialog.dismiss() **只会**触发 OnDismissListener；OnCancelListener 只有
        //   cancel()（系统返回键 / 点外部）才会发。而头部「‹」走的是 d.dismiss()，
        //   于是回调永远不跑 → 页面直接消失、掉回官方 App 主页 —— 就是用户连着两轮
        //   反馈的"按返回还是回 App 主页"。改挂 OnDismiss 后，点「‹」和系统返回键
        //   都只触发一次，且 close 里只 dismiss、不再自行调 back，不会双触发。
        final Runnable backCb = back;
        close.setOnClickListener(v -> d.dismiss());
        d.setOnDismissListener(x -> {
            // 只在本页还指着 currentScreen 时才清空 —— 否则程序化切页后，旧页那个
            // 异步跑过来的 onDismiss 会把"新开的页"误清掉。
            if (currentScreen == d) { currentScreen = null; currentScreenTitle = null; }
            Boolean suppressed = screenSuppress.remove(d);
            java.util.List<Runnable> extras = screenExtras.remove(d);
            if (extras != null) for (Runnable r : extras) { try { r.run(); } catch (Throwable ignored) { } }
            if (suppressed != null && suppressed) return;   // 程序化切页关掉的：不触发 back
            if (backCb != null) backCb.run();
        });
        d.show();
        Window w=d.getWindow();
        if(w!=null){
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels,dp(a,760)),-1);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        currentScreen = d; currentScreenTitle = title;
        return d;
    }

    /** 一个带彩色圆点的状态行：`● 自有模型 · deepseek-flash`。 */
    static LinearLayout statusRow(Activity a,String dot,String text,int dotColor){
        LinearLayout r=rowBox(a);
        TextView d=text(a,dot,11,dotColor);d.setGravity(Gravity.CENTER);r.addView(d);
        TextView t=text(a,text,13,INK);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.leftMargin=dp(a,6);r.addView(t,tp);
        return r;
    }

    /**
     * 眼镜显示控制条 —— **所有会往字幕通道推送的页面都要放一条**。
     *
     * 字幕通道是全局单会话，谁占着谁写；换功能时如果前一个模块的定时器还在推，
     * 新内容会被立刻覆盖（用户实测：不点「退出导航」就看不到电子书）。
     * 这里给一个统一出口：退出显示（让出通道）+ 重置（推不上时硬清）。
     */
    static void displayControl(Activity a,LinearLayout box,String note0){
        LinearLayout card=card(a,box);
        card.addView(label(a,"眼镜显示",11,MUTED));
        gap(a,card,6);
        final TextView note=text(a,note0==null?"":note0,13,MUTED);
        note.setLineSpacing(dp(a,4),1f);
        card.addView(note);
        gap(a,card,2);
        button(a,card,"退出眼镜显示（把通道让给其他功能）",true,()->{
            NavGlasses.releaseAll();
            note.setText("已释放通道 · "+NavGlasses.status());
            Toast.makeText(a,"已退出眼镜显示，其他功能可立即推送",Toast.LENGTH_SHORT).show();
        });
        button(a,card,"重置显示通道（推不上时点这里）",false,()->{
            NavGlasses.forceIdle();
            note.setText("已重置 · "+NavGlasses.status());
            Toast.makeText(a,"显示通道已重置，重新推送即可",Toast.LENGTH_SHORT).show();
        });
    }
}

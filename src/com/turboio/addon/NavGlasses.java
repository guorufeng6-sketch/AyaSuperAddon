package com.turboio.addon;

import android.os.*;
import java.util.*;
import org.json.JSONObject;

/** Pure-display business 19 bridge, owns only its fresh SID. Never starts ASR. */
public final class NavGlasses {
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    /**
     * ★ 显示通道的每个入口都必须跑在主线程。
     *
     * ── 为什么（用户实测：「天气推不到屏上，而且残留其他应用画面」）──────
     * 所有推送最终都走 {@link #send}：它用反射调厂商 SDK 的
     * `manager.u(message, callback)`。这类 SDK 调用**只能在主线程**发，
     * 从网络线程 / 线程池线程调用会静默失败 —— {@link #push} 里 `send` 抛异常
     * 被 `catch(Exception)` 吞掉、返回 false；{@link #show} 走进 `uncertain`。
     * 表现就是"手机端明明查到了、眼镜上却没动，还停在上一个模块的画面"。
     *
     * 天气第一次推送正是在 fetch() 的网络线程里调 pushPage() → NavGlasses，
     * 所以它必挂；而导航 / 倒计时的心跳都在 Handler 主线程，所以没事。
     *
     * 这里统一兜底：非主线程一律 post 到主线程再执行。多个调用之间的先后
     * 顺序由 Handler 的 FIFO 保证（acquire → show → push 的顺序不会乱）。
     */
    private static boolean onMain(){return Looper.myLooper()==Looper.getMainLooper();}
    private static volatile String device="",sid="",phase="idle";
    private static String note="眼镜显示未开启",latest="",sent="";
    private static int textSubmitted,textCompleted;private static boolean navigationSession;
    /** 最近一次 type-5 推文是否被 SDK 拒绝（倒计时据此自动放慢推送节奏）。 */
    private static volatile boolean lastSendFailed;
    private static long deadline,started,lastSend;private static boolean pending;
    private static Runnable changed;
    // 续接流程状态（见 renew()）。renewing 期间 tick 不推文、start() 不抢跑。
    private static volatile boolean renewing;
    private static String renewKeep="";private static boolean renewNav;private static long renewStarted;

    // ══════════════════════════════════════════════════════════════
    //  显示通道「占用者」仲裁（2026-09-18 新增）
    //
    //  ── 为什么需要 ──────────────────────────────────────────────
    //  字幕通道是**全局单会话**：导航、电子书、提词器、倒计时、家况、
    //  天气全都往同一个 sid 里写文字。谁先 start() 谁就拿到会话，但
    //  **没有任何人负责让出**。于是出现用户实测到的现象：
    //    在导航里刷得正欢 → 打开电子书推一屏 → 眼镜上还是导航的箭头，
    //    因为 NavEngine 的 3 秒 ticker 还在往同一个会话里覆盖；
    //    必须点「退出导航」或去眼镜上手动开关一次实时字幕才能看到电子书。
    //
    //  这里加一层极薄的仲裁：每个会持续推送的模块用 onRelease(id,label,r)
    //  登记一个"停掉我自己"的回调；任何模块要开始推送前先 acquire(id)，
    //  仲裁层会先把**上一个占用者**的回调跑掉，再换主人。
    //  另外 releaseAll() 供各页面的「退出眼镜显示」按钮使用 ——
    //  一次把所有人停掉并关会话，通道立刻空出来。
    // ══════════════════════════════════════════════════════════════
    private static volatile String ownerId = "", ownerLabel = "";
    private static final java.util.Map<String,Runnable> releasers =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String,String> labels =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** 登记"我该怎么停"（幂等，可重复调用）。label 只用于给用户看的提示。 */
    public static void onRelease(String id,String label,Runnable stopMe){
        if(id==null||stopMe==null)return;
        releasers.put(id,stopMe);
        if(label!=null)labels.put(id,label);
    }
    public static String ownerId(){return ownerId;}
    public static String ownerLabel(){return ownerLabel;}

    /** 抢占显示通道：返回被让出的那一位的名字（没有则空串）。 */
    public static String acquire(String id){
        if(id==null||id.isEmpty())return "";
        if(!onMain()){MAIN.post(()->acquire(id));return "";}
        String prev=ownerId,prevLabel=ownerLabel;
        if(!prev.isEmpty()&&!prev.equals(id)){
            Runnable r=releasers.get(prev);
            if(r!=null){try{r.run();}catch(Throwable ignored){}}
        }
        ownerId=id;ownerLabel=labels.containsKey(id)?labels.get(id):id;
        return prev.isEmpty()||prev.equals(id)?"":prevLabel;
    }
    /** 我先不用了（不关会话，只是别让别人把我当成当前占用者）。 */
    public static void release(String id){
        if(id==null)return;
        if(!onMain()){MAIN.post(()->release(id));return;}
        if(id.equals(ownerId)){ownerId="";ownerLabel="";}
    }
    /**
     * 退出眼镜显示：停掉所有登记过的模块 + 关闭字幕会话。
     * 各页面的「退出眼镜显示」按钮调它。
     *
     * 两步走：
     *   ① 先 stop() 发 type-3 关闭包，让眼镜端把实时字幕退出（这样通道才真空出来）；
     *   ② 2.5 秒后无条件 forceIdle()。
     * 第 ② 步不能省 —— 眼镜没回执时 phase 会停在 stopping/uncertain，
     * 而 stop()/confirmIdle() 对这两个状态都是"早退"，不强制归 idle 的话
     * 用户点完按钮发现"通道还是被占"，体验跟以前一模一样。
     */
    public static void releaseAll(){
        if(!onMain()){MAIN.post(NavGlasses::releaseAll);return;}
        for(java.util.Map.Entry<String,Runnable> e:releasers.entrySet()){
            try{e.getValue().run();}catch(Throwable ignored){}
        }
        ownerId="";ownerLabel="";latest="";sent="";pending=false;
        if(!phase.equals("idle")){ try{stop();}catch(Throwable ignored){} }
        MAIN.postDelayed(NavGlasses::forceIdle,2500);
    }
    /** 无条件把通道清回 idle。**没有** confirmIdle 那种"starting/ready 就早退"的保护。 */
    public static void forceIdle(){
        if(!onMain()){MAIN.post(NavGlasses::forceIdle);return;}
        MAIN.removeCallbacks(tick);tickRunning=false;
        // 中止可能正在进行中的续接：用户点「退出眼镜显示」后
        // 不能被 1.5 秒后的续接定时器重新开起来。
        renewing=false;renewKeep="";
        sid="";device="";latest="";sent="";pending=false;
        mark("idle","显示通道已空闲");
    }

    public static void listen(Runnable r){changed=r;}
    private static void mark(String p,String n){phase=p;note=n;if(changed!=null)changed.run();}
    public static String status(){return note+" · "+phase+" · 文字提交 "+textSubmitted+" / SDK完成 "+textCompleted+" · "+(navigationSession?"路线":"测试");}
    public static String phase(){return phase;}
    public static boolean active(){return !phase.equals("idle");}
    private static Object manager()throws Exception{return NavReflect.field(NavReflect.type("E3.u"),"h");}
    private static String connected()throws Exception{
        List<?> list=(List<?>)NavReflect.call(NavReflect.type("E3.u"),"g");String found="";
        for(Object d:list)if(Boolean.TRUE.equals(NavReflect.call(d,"b"))){if(!found.isEmpty())throw new IllegalStateException("multiple");found=(String)NavReflect.field(d,"a");}return found;
    }
    public static String connection(){try{return connected().isEmpty()?"眼镜未连接":"已找到官方连接";}catch(Exception e){return "连接信息不可用";}}
    public static boolean start(String text){return start(text,false);}
    public static boolean start(String text,boolean navigation){
        if(!onMain()){MAIN.post(()->start(text,navigation));return false;}
        NavSessionPolicy.Action action=NavSessionPolicy.action(phase);
        if(action==NavSessionPolicy.Action.REUSE){
            try{if(!device.equals(connected())){stop();return false;}latest=NavCore.clip(text,384);navigationSession=navigation;mark("ready","复用本 App 已确认的显示会话，等待文字更新");return true;}
            catch(Exception e){stop();return false;}
        }
        if(action==NavSessionPolicy.Action.CONFIRM_EXIT){
            // 续接流程正在走（见 renew()）：它自己在 1.5 秒后重开新会话。
            // 这里只把内容记下来交给它补发，不要插手争抢 ——
            // 否则会把状态文案覆盖成"上一会话未结束"，用户看着像卡住了。
            if(renewing){latest=NavCore.clip(text,384);return false;}
            // 自愈：上一会话已过确认窗口（stop() 设的 8 秒）就自动收尾并以 idle 重开。
            // 没有这段，stopping/uncertain 会把通道永久锁死（confirmIdle 此前无人调用）。
            if(SystemClock.elapsedRealtime()>=deadline){confirmIdle();return start(text,navigation);}
            mark(phase,"上一会话未结束，稍后自动重开（或点「重置显示通道」）");
            MAIN.postDelayed(()->{
                if(phase.equals("stopping")||phase.equals("uncertain")){
                    confirmIdle();
                    if(!latest.isEmpty()||!text.isEmpty())start(latest.isEmpty()?text:latest,navigation);
                }
            },1500);
            return false;
        }
        if(action!=NavSessionPolicy.Action.START){mark(phase,"正在开启显示通道，请稍等");return false;}
        try{device=connected();if(device.isEmpty())throw new IllegalStateException();sid=UUID.randomUUID().toString().replace("-","");latest=NavCore.clip(text,384);sent="";lastSend=0;pending=false;started=SystemClock.elapsedRealtime();deadline=started+10000;
            textSubmitted=0;textCompleted=0;navigationSession=navigation;
            JSONObject config=new JSONObject().put("font_size",2).put("content_width",100).put("max_lines",5).put("position","center").put("is_display",true).put("straight_view","original");
            mark("starting","正在开启纯显示通道，等待眼镜回执");
            send(7,new JSONObject().put("sid",sid).put("force",false).put("scope","temporary").put("config",config));tickRunning=false;scheduleTick(1000);return true;
        }catch(Exception e){mark("uncertain","无法开启；请确认连接和镜片状态");return false;}
    }
    public static void offer(String text){latest=NavCore.clip(text,384);}
    /**
     * 即时推送一段文字到镜片（不走 3 秒节流队列）。
     * 电子书翻页 / 家况播报这类"用户点一下就要立刻看到"的场景用它 ——
     * 走 offer() 会被 tick 的 3 秒节流 + 去重挡住，体感像卡死。
     * 返回是否已提交（false 表示会话未就绪，调用方应先 start()）。
     */
    public static boolean push(String text){
        if(text==null||text.isEmpty())return false;
        // 非主线程：转主线程执行。返回值按"会话是否就绪"乐观给 ——
        // 真正结果由 tick 的回执处理（调用方都不依赖这里的返回值）。
        if(!onMain()){final String t=text;MAIN.post(()->push(t));return phase.equals("ready");}
        if(!phase.equals("ready")){
            // 未就绪 ≠ 放弃：先把内容记进 latest，等 tick 在会话 ready 后补发。
            // 否则「start() 紧接着 push()」这种写法会把内容整段丢掉
            // —— 提词器第一段、电子书当前页都踩过这个坑（眼镜上只剩占位文字）。
            if(active())latest=NavCore.clip(text,1024);
            return false;
        }
        String clipped=NavCore.clip(text,1024);
        try{
            sent=clipped;lastSend=SystemClock.elapsedRealtime();latest=clipped;pending=true;deadline=lastSend+8000;textSubmitted++;
            send(5,new JSONObject().put("sid",sid).put("mode",3).put("status",0).put("content",new JSONObject().put("source_transcript",clipped)));
            return true;
        }catch(Exception e){return false;}
    }
    /** 会话是否已就绪，可以直接 push。 */
    public static boolean ready(){return phase.equals("ready");}
    /**
     * 最近一次推文是否被 SDK 拒绝。
     * 倒计时每秒推一次，如果固件/通道吃不消（type-5 回调带错误），
     * 它会据此把节奏自动降到 {@link CountdownUI} 里的慢档 ——
     * 宁可读秒变粗，也不要整条通道被拒绝到看不见。
     */
    public static boolean lastSendFailed(){return lastSendFailed;}
    /**
     * 各功能模块**统一**使用的推送入口。
     *
     * 会话就绪 → 立刻发；未就绪 → 记下内容并（必要时）开会话，ready 后由 tick 自动补发。
     * 调用方不必再自己写 `if(ready())push else start` —— 那种写法在 starting 阶段
     * 会把内容丢掉（push 返回 false 且没人重试），是「App 端有内容、眼镜上空白」的直接原因。
     *
     * @param navigation true = 路线类会话（导航/倒计时），false = 普通显示
     */
    public static void show(String text,boolean navigation){
        if(text==null||text.isEmpty())return;
        if(!onMain()){final String t=text;MAIN.post(()->show(t,navigation));return;}
        String clipped=NavCore.clip(text,1024);
        if(phase.equals("ready")){push(clipped);return;}
        latest=clipped;
        start(clipped,navigation);
    }
    /**
     * 手动重置显示通道（诊断面板 / 导航页的按钮）。
     *
     * ★ 这里**不能**用 confirmIdle()：它有 `starting/ready 就早退` 的保护，
     *   而用户点"重置"往往正是因为在 ready 状态下推不上（通道被前一个模块占着）。
     *   早退 = 按钮点了没反应，这就是"重置显示通道按钮没用"的原因。
     *   改为无条件 forceIdle()。
     */
    public static void reset(){forceIdle();}
    /**
     * ★ 会话到期前**主动续接**（长导航 / 长倒计时 / 长时间提词的关键）。
     *
     * ── 为什么必须提前，而不是到期再救 ──────────────────────────────
     * 眼镜端的 AI_SUBTITLE 会话有硬窗口（{@link NavSessionPolicy#SESSION_WINDOW_MS}，240s），
     * 到点固件自己关掉字幕。旧实现是「到点了 → forceIdle → start」，
     * 等于是**眼镜已经关掉字幕之后**才开始抢救；新会话还要等眼镜回执（type-8）
     * 才进 ready，这段空隙用户实打实能看到镜片空一下。更糟的是若眼镜这时不理新 start，
     * 会走 10s 超时 → uncertain → 再 8s 自愈重开 —— 最坏二十秒没画面，
     * 行驶中正好在路口时最要命。
     *
     * 现在提前 {@link NavSessionPolicy#RENEW_LEAD_MS}（20 秒）动手，流程刻意模仿
     * 用户手动「退出实时字幕再打开」—— 那是实测唯一 100% 有效的路径：
     *   ① 发 type-3 让眼镜端干净退出（此刻旧会话还活着，画面不黑）；
     *   ② 1.5 秒后本地清干净，以**新 sid** 重开；
     *   ③ 各模块自己的定时器（导航 3s ticker / 倒计时 1s refresher）会把最新一帧补上，
     *      所以重开后一两秒画面就回来了，用户不需要做任何事。
     *
     * 兜底有三层：① 本方法 10 秒卡死守卫（见 tick）；② tick 里的「过期抢救」老路径；
     * ③ start() 遇 CONFIRM_EXIT 的 8 秒自愈。
     */
    private static void renew(){
        if(renewing)return;
        renewing=true;renewStarted=SystemClock.elapsedRealtime();
        renewKeep=latest;renewNav=navigationSession;
        MAIN.removeCallbacks(tick);tickRunning=false;
        try{
            mark("stopping","显示会话到期前自动续接（镜片会短暂闪一下）");
            send(3,new JSONObject().put("sid",sid).put("reason_code",10).put("text",""));
        }catch(Throwable ignored){ }
        // 不等回执：1.5 秒足够眼镜端退出实时字幕；超时也照走 ——
        // 卡在这里比多等更糟，后面的自愈链会接住。
        MAIN.postDelayed(()->{
            if(!renewing)return;
            // 续接窗口内来的新画面优先（导航 ticker 3s / 倒计时 1s 会往 latest 里刷），
            // 拿不到才退回窗口开始时的快照。
            String keep=latest.isEmpty()?renewKeep:latest;boolean nav=renewNav;
            renewing=false;
            forceIdle();
            start(keep,nav);
        },1500);
    }
    /** 是否正在续接（导航页状态行用）。 */
    public static boolean renewing(){return renewing;}
    public static void stop(){
        if(!onMain()){MAIN.post(NavGlasses::stop);return;}
        if(phase.equals("idle")||phase.equals("stopping")||phase.equals("uncertain"))return;
        try{mark("stopping","已请求关闭，待确认镜片退出");deadline=SystemClock.elapsedRealtime()+8000;pending=false;send(3,new JSONObject().put("sid",sid).put("reason_code",10).put("text",""));}
        catch(Exception e){mark("uncertain","退出未确认，请用眼镜按钮关闭");}
    }
    public static void confirmIdle(){
        if(!onMain()){MAIN.post(NavGlasses::confirmIdle);return;}
        if(phase.equals("starting")||phase.equals("ready"))return;
        MAIN.removeCallbacks(tick);tickRunning=false;sid="";device="";latest="";pending=false;mark("idle","已由用户确认镜片退出");
    }
    private static void send(int type,JSONObject json)throws Exception{
        if(!device.equals(connected()))throw new IllegalStateException("device_changed");
        Object business=NavReflect.call(NavReflect.type("P3.h"),"valueOf","AI_SUBTITLE"),priority=NavReflect.call(NavReflect.type("E3.b"),"valueOf","NORMAL");
        Object message=NavReflect.make("E3.Q",NavCore.packet(type,json.toString()),UUID.randomUUID().toString(),device,business,null,priority,false,false,null,null);
        String owner=sid;
        Object callback=NavReflect.proxy("kotlin.jvm.functions.Function2",(name,args)->MAIN.post(()->{
            if(!sid.equals(owner))return;if(type==5){pending=false;if(args.length>1&&args[1]==null){textCompleted++;lastSendFailed=false;mark(phase,"导航/测试文字已完成 SDK 发送，镜片效果以实际为准");}}
            if(args.length>1&&args[1]!=null){
                if(type==3)mark("uncertain","SDK拒绝退出，请用眼镜按钮关闭");
                else if(type==5){pending=false;lastSendFailed=true;mark(phase,"本次文字被 SDK 拒绝，通道保持");}
                else stop();
            }
        }));NavReflect.call(manager(),"u",message,callback);
    }
    private static final Runnable tick=new Runnable(){public void run(){
        long now=SystemClock.elapsedRealtime();
        try{
            if(renewing){
                // 续接窗口内 tick 不做任何事（推文 / 超时判定都让位给 renew 自己的定时器）。
                // 守卫：Handler 被长任务阻塞等极端情况下，卡超过 10 秒就强制走兜底重开，
                // 不让通道停在 stopping 出不来。
                if(now-renewStarted>=10000){
                    String keep=latest.isEmpty()?renewKeep:latest;boolean nav=renewNav;
                    renewing=false;forceIdle();start(keep,nav);
                }
            }
            else if(phase.equals("starting")&&now>=deadline){
                // 开启超时（眼镜没回执）：进 uncertain 让 start() 的自愈去处理，
                // 不要走 stop() 再转一圈（那样要 16 秒才恢复）。
                mark("uncertain","开启超时：眼镜没有回执，请确认镜片已连接/亮屏");
            }
            else if(phase.equals("ready")){
                long age=now-started;
                if(NavSessionPolicy.shouldRenew(age)){
                    // ★ 提前 20 秒主动续接：这是"导航超过 4 分钟会不会断"的答案 ——
                    //   不断。旧实现在这里什么都不做，等 240s 到点被眼镜踢掉后才救，
                    //   空隙必然可见。见 renew() 的注释。
                    renew();return;
                }
                if(NavSessionPolicy.expired(age)){
                    // 兜底：续接流程没走成（例如期间眼镜断连），到点了直接换新会话。
                    String keep=latest;boolean nav=navigationSession;
                    renewing=false;forceIdle();start(keep,nav);return;
                }
                else if(pending&&now>=deadline){pending=false;mark(phase,"上次文字未收到 SDK 回执，通道保持");}
                else if(!pending&&!latest.isEmpty()&&!latest.equals(sent)&&now-lastSend>=3000){
                    pending=true;deadline=now+8000;lastSend=now;sent=latest;
                    textSubmitted++;send(5,new JSONObject().put("sid",sid).put("mode",3).put("status",0).put("content",new JSONObject().put("source_transcript",latest)));
                }
            }else if(!renewing&&phase.equals("stopping")&&now>=deadline)mark("uncertain","退出已提交，请确认镜片已回首页");
        }catch(Exception e){stop();}
        if(phase.equals("starting")||phase.equals("ready")||phase.equals("stopping")||renewing){MAIN.postDelayed(this,1000);}
        else tickRunning=false;
    }};
    /**
     * 心跳是否还活着（避免重复排链）。
     *
     * ── 为什么这个标志是必须的 ────────────────────────────────────
     * 旧实现所有地方都写 `MAIN.removeCallbacks(tick); MAIN.postDelayed(tick,1000);`，
     * 于是"想保活"就等于"把正在排的那一条撤掉重排" —— 调用得比 1 秒还密时，
     * tick 会被无限推迟、永远不触发（心跳看着在、其实一次都不跑）。
     * 加了这个标志，保活才是真的保活：链活着就什么都不做。
     */
    private static volatile boolean tickRunning;

    private static void scheduleTick(long delay){
        if(tickRunning)return;
        tickRunning=true;
        MAIN.removeCallbacks(tick);
        MAIN.postDelayed(tick,delay);
    }
    /**
     * 心跳保活（幂等）。正在推送的模块可以随便调 ——
     * 之前 `uncertain` 之后心跳会彻底死掉（末尾那条 if 不成立、就没人再排了），
     * 只能等某个模块下次 show() 时才重启；万一那时没人推，通道就静默了。
     * 现在外部有独立的观察者（导航页 2 秒轮询）每隔一会儿调一次，断不了。
     */
    public static void keepAlive(){
        if(phase.equals("starting")||phase.equals("ready")||phase.equals("stopping")||renewing)scheduleTick(1000);
    }
    /** Called after official event delivery; never suppresses vendor processing. */
    public static void event(String kind,Map<?,?> data){
        if(!kind.equals("messageReceived")||!active())return;
        try{Object raw=data.get("message");if(!(raw instanceof Map))return;Map<?,?> m=(Map<?,?>)raw;
            String d=String.valueOf(m.get("deviceId"));if(!d.equals(device))return;
            String biz=String.valueOf(m.get("businessId"));if(!biz.equals("19")&&!biz.equals("AI_SUBTITLE"))return;
            Object bytes=m.get("payload");if(!(bytes instanceof byte[]))return;
            NavCore.Envelope e=NavCore.decode((byte[])bytes);if(e==null)return;
            // Audio bodies are not retained, parsed or uploaded.
            final JSONObject j=e.type==4?new JSONObject():new JSONObject(e.json);
            MAIN.post(()->{if(!device.equals(d))return;if(e.type==4){stop();return;}if(!sid.equals(j.optString("sid")))return;
                if(e.type==8&&phase.equals("starting")){Object code=j.opt("code");if(code instanceof Number&&(((Number)code).doubleValue()==1||((Number)code).doubleValue()==2)){mark("ready","眼镜已确认显示配置 · 文字可见性待验收");if(!latest.isEmpty()&&!latest.equals(sent)){MAIN.post(()->push(latest));}}else mark("uncertain","眼镜拒绝了显示配置（code="+code+"）");}
                if(e.type==3)stop();
            });
        }catch(Exception ignored){}
    }
}

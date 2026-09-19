package com.turboio.addon;

/** A ready display owned by us can be updated; uncertain exits need a visible decision. */
public final class NavSessionPolicy {
    public enum Action { START, REUSE, CONFIRM_EXIT, WAIT }
    public enum Position { WAIT_FIX, REPLAN, FOLLOW }

    // ══════════════════════════════════════════════════════════════
    //  显示会话的生命周期窗口
    //
    //  眼镜端的 AI_SUBTITLE 会话有**硬窗口 240s**：到点固件自己关掉字幕，
    //  之后我们推什么都不会显示。所以长任务（导航 / 电子书 / 提词器 /
    //  倒计时 / 巡航 / 天气）必须在这个窗口内主动换一次会话。
    //
    //  ── 为什么不能"到点再换" ──────────────────────────────────────
    //  到点才换 = 眼镜已经关机了才开始抢救，且新会话要等眼镜回执才 ready，
    //  中间那段空隙是**必然可见**的（镜片上空白）。提前 20 秒动手后，
    //  旧会话还是活的（还能显示），换完新会话立刻补上最新画面。
    //
    //  20 秒的余量拆解：关旧会话等 1.5s + 新会话等回执（上限 10s）+ 重推一帧。
    // ══════════════════════════════════════════════════════════════
    public static final long SESSION_WINDOW_MS = 240000L;
    public static final long RENEW_LEAD_MS = 20000L;

    /** 该主动续接了：进入窗口末段但仍未过期。 */
    public static boolean shouldRenew(long ageMs) {
        return ageMs >= SESSION_WINDOW_MS - RENEW_LEAD_MS && ageMs < SESSION_WINDOW_MS;
    }
    /** 已经过期（续接流程没走成时的兜底判断）。 */
    public static boolean expired(long ageMs) { return ageMs >= SESSION_WINDOW_MS; }
    public static boolean canOpen(boolean hasRoute,boolean modeMatches){return hasRoute&&modeMatches;}
    public static Position position(boolean fresh,boolean validated,double drift){
        if(!fresh)return Position.WAIT_FIX;
        if(!validated&&(!Double.isFinite(drift)||drift>80))return Position.REPLAN;
        return Position.FOLLOW;
    }
    public static Action action(String phase) {
        if("idle".equals(phase))return Action.START;
        if("ready".equals(phase))return Action.REUSE;
        if("stopping".equals(phase)||"uncertain".equals(phase))return Action.CONFIRM_EXIT;
        return Action.WAIT;
    }
}

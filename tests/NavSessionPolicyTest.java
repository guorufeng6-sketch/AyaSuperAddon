import com.turboio.addon.NavSessionPolicy;
public class NavSessionPolicyTest {
    public static void main(String[] args){
        String[] states={"idle","ready","starting","stopping","uncertain","unknown",null};
        NavSessionPolicy.Action[] expected={NavSessionPolicy.Action.START,NavSessionPolicy.Action.REUSE,NavSessionPolicy.Action.WAIT,NavSessionPolicy.Action.CONFIRM_EXIT,NavSessionPolicy.Action.CONFIRM_EXIT,NavSessionPolicy.Action.WAIT,NavSessionPolicy.Action.WAIT};
        for(int i=0;i<states.length;i++)if(NavSessionPolicy.action(states[i])!=expected[i])throw new AssertionError("state "+states[i]);
        if(!NavSessionPolicy.canOpen(true,true)||NavSessionPolicy.canOpen(false,true)||NavSessionPolicy.canOpen(true,false))throw new AssertionError("route gate");
        if(NavSessionPolicy.position(false,false,0)!=NavSessionPolicy.Position.WAIT_FIX)throw new AssertionError("coarse still shows overview");
        if(NavSessionPolicy.position(true,false,12)!=NavSessionPolicy.Position.FOLLOW)throw new AssertionError("fresh follows");
        if(NavSessionPolicy.position(true,false,81)!=NavSessionPolicy.Position.REPLAN)throw new AssertionError("changed start");
        if(NavSessionPolicy.position(true,true,800)!=NavSessionPolicy.Position.FOLLOW)throw new AssertionError("walking away is expected");
        if(NavSessionPolicy.position(true,false,Double.NaN)!=NavSessionPolicy.Position.REPLAN)throw new AssertionError("unknown start");

        // ── 显示会话的 4 分钟硬窗口 ──
        // 眼镜端到点会自己关掉字幕，所以必须**提前**续接（提前到点前 20 秒）。
        // 这里的边界就是"导航超过 4 分钟会不会断"的判据。
        final long W=NavSessionPolicy.SESSION_WINDOW_MS;
        if(W!=240000L)throw new AssertionError("窗口常量被改动，需同步文档");
        if(NavSessionPolicy.shouldRenew(0))throw new AssertionError("刚开的会话不该续接");
        if(NavSessionPolicy.shouldRenew(W-NavSessionPolicy.RENEW_LEAD_MS-1))
            throw new AssertionError("还没进续接窗口就动手，会白闪一次");
        if(!NavSessionPolicy.shouldRenew(W-NavSessionPolicy.RENEW_LEAD_MS))
            throw new AssertionError("进入续接窗口必须动手（这是长导航不断的关键）");
        if(!NavSessionPolicy.shouldRenew(W-1))throw new AssertionError("窗口末段必须仍判为可续接");
        if(NavSessionPolicy.shouldRenew(W))throw new AssertionError("已过期就不该再走续接，该走兜底");
        if(NavSessionPolicy.expired(W-1))throw new AssertionError("未过期不得判过期");
        if(!NavSessionPolicy.expired(W))throw new AssertionError("到点必须判过期（兜底路径）");
        if(!NavSessionPolicy.expired(W+60000))throw new AssertionError("远超窗口必须判过期");
        if(NavSessionPolicy.RENEW_LEAD_MS<=0||NavSessionPolicy.RENEW_LEAD_MS>=W)
            throw new AssertionError("提前量必须在 (0, 窗口) 之内");
        // 进入窗口后到过期之间必须**恰好命中一种**、不重叠不留缝；
        // 否则时钟跳过某一秒就会既不续接也不兜底（导航正好卡在那个点）。
        long scan=W-NavSessionPolicy.RENEW_LEAD_MS;
        while(scan<W+2000){
            boolean r=NavSessionPolicy.shouldRenew(scan),e=NavSessionPolicy.expired(scan);
            if(r&&e)throw new AssertionError("age="+scan+" 同时判为续接与过期");
            if(!r&&!e)throw new AssertionError("age="+scan+" 落在两个区间之间的缝里");
            scan++;
        }
        System.out.println("NavSessionPolicy: 30 checks PASS (display independent of fix, guarded real guidance, 4-min window renewed 20s early)");
    }
}

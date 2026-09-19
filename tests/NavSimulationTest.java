import com.turboio.addon.*;
import java.util.*;

public final class NavSimulationTest {
    private static int count;
    private static void check(boolean ok){count++;if(!ok)throw new AssertionError("check "+count);}
    public static void main(String[] args){
        NavCore.Point a=new NavCore.Point(0,0),b=new NavCore.Point(0,.0001),c=new NavCore.Point(.0001,.0001);
        List<NavCore.Step> route=Arrays.asList(new NavCore.Step("A",Arrays.asList(a,a,b)),new NavCore.Step("B",Arrays.asList(b,c)));
        NavSimulation s=new NavSimulation(route,"Walk",0);
        check(s.frame().step==0);check(s.frame().progress==0);
        s.advance(1000,false);check(s.frame().progress==0);
        s.advance(2000,true);check(s.frame().progress>0);check(s.frame().point.lon>0);
        double p=s.frame().progress;s.pause(true,2000);s.advance(3000,true);check(s.frame().progress==p);
        s.speed(8,3000);s.advance(4000,true);check(s.frame().progress==p);check(s.speed()==8);
        s.pause(false,4000);s.advance(5000,true);check(s.frame().step==1);
        s.advance(6000,true);check(s.frame().finished);check(s.frame().progress==1);check(s.frame().remainingStep==0);
        check(NavCore.distance(s.frame().point,c)<.01);s.advance(7000,true);check(s.frame().progress==1);
        NavSimulation stalled=new NavSimulation(route,"Walk",0);stalled.advance(100000,true);check(stalled.frame().progress<.2);
        boolean bad=false;try{s.speed(0,0);}catch(IllegalArgumentException e){bad=true;}check(bad);
        bad=false;try{new NavSimulation(Arrays.asList(new NavCore.Step("zero",Arrays.asList(a,a))),"Walk",0);}catch(IllegalArgumentException e){bad=true;}check(bad);
        NavSimulation drive=new NavSimulation(route,"Drive",0);drive.advance(1000,true);check(drive.frame().progress>stalled.frame().progress);
        NavSimulation ride=new NavSimulation(route,"Ride",0);ride.advance(1000,true);check(ride.frame().progress>stalled.frame().progress);
        List<NavCore.Step> mutable=new ArrayList<>(route);NavSimulation copied=new NavSimulation(mutable,"Walk",0);mutable.clear();check(copied.frame().step==0);
        System.out.println("NavSimulation: "+count+" checks passed");
    }
}

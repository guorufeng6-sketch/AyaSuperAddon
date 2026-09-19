import com.turboio.addon.NavCore;
import java.util.*;
import java.nio.charset.StandardCharsets;
public class NavCoreTest {
    static int n;static void check(boolean b){n++;if(!b)throw new AssertionError("case "+n);}
    public static void main(String[] args){
        NavCore.Point a=new NavCore.Point(39,116),b=new NavCore.Point(39,116.001),c=new NavCore.Point(39.001,116.001);
        check(NavCore.distance(a,a)==0);check(NavCore.distance(a,b)>80&&NavCore.distance(a,b)<90);
        List<NavCore.Step> steps=Arrays.asList(new NavCore.Step("直行",Arrays.asList(a,b)),new NavCore.Step("左转",Arrays.asList(b,c)));
        NavCore.Match match=NavCore.match(steps,new NavCore.Point(39,116.0005),0);check(match.step==0);check(match.offRoute<1);check(match.remainingStep>40&&match.remainingStep<45);
        check(NavCore.match(steps,new NavCore.Point(39.0005,116.001),0).step==1);check(NavCore.match(steps,new NavCore.Point(40,117),0).offRoute>1000);
        check(NavCore.meters(80).equals("80 米"));check(NavCore.meters(1200).equals("1.2 公里"));check(NavCore.meters(Double.NaN).equals("—"));
        String raw="导航测试🙂";check(NavCore.clip(raw,12).equals("导航测试"));check(NavCore.clip(raw,15).getBytes(StandardCharsets.UTF_8).length==12);
        for(int type:new int[]{3,5,7}){String json="{\"content\":\""+String.join("",Collections.nCopies(60,"导航"))+"\"}";NavCore.Envelope e=NavCore.decode(NavCore.packet(type,json));check(e!=null&&e.type==type&&e.json.equals(json));}
        check(NavCore.decode(new byte[]{8,1,16,8,26,127})==null);check(NavCore.decode(new byte[]{8,1,8,1,16,8})==null);check(NavCore.decode(new byte[]{8,2,16,8})==null);
        boolean rejected=false;try{NavCore.packet(4,"{}");}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        check(NavCore.decode(new byte[]{8,1,16,4,34,2,0,0}).type==4);
        System.out.println("NavCore: "+n+" checks PASS");
    }
}

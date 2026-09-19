package com.turboio.addon;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Original, platform-free navigation helpers. Coordinates must all be GCJ-02. */
public final class NavCore {
    public static final class Point {
        public final double lat, lon;
        public Point(double lat,double lon) {
            if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180) throw new IllegalArgumentException("coordinate");
            this.lat=lat;this.lon=lon;
        }
    }
    public static final class Step {
        public final String instruction;
        public final List<Point> points;
        /**
         * 高德给的本段距离（米）。-1 = 未知，调用方改用折线几何长度兜底。
         * 进度条要算"剩余里程"，必须知道每段多长，所以这里存下来
         * （之前只在 plan() 里用来算首段，算完就丢了）。
         */
        public final double distance;
        public Step(String instruction,List<Point> points){this(instruction,points,-1);}
        public Step(String instruction,List<Point> points,double distance){
            this.instruction=instruction;
            this.points=Collections.unmodifiableList(new ArrayList<>(points));
            this.distance=distance;
        }
    }
    public static double distance(Point a,Point b) {
        double x=Math.toRadians(b.lon-a.lon)*Math.cos(Math.toRadians((a.lat+b.lat)/2));
        double y=Math.toRadians(b.lat-a.lat);return Math.hypot(x,y)*6371000;
    }
    public static final class Match {
        public final int step;public final double offRoute,remainingStep;
        Match(int s,double d,double r){step=s;offRoute=d;remainingStep=r;}
    }
    public static Match match(List<Step> steps,Point p,int current) {
        double best=Double.POSITIVE_INFINITY,remaining=0;int selected=-1;
        // Do not jump across parallel roads or to distant later route sections.
        for(int s=Math.max(0,current);s<Math.min(steps.size(),current+2);s++){
            List<Point> line=steps.get(s).points;
            for(int i=1;i<line.size();i++){
                Point a=line.get(i-1),b=line.get(i);
                double scale=Math.cos(Math.toRadians(p.lat));
                double ax=(a.lon-p.lon)*scale,ay=a.lat-p.lat,bx=(b.lon-p.lon)*scale,by=b.lat-p.lat;
                double dx=bx-ax,dy=by-ay,den=dx*dx+dy*dy;
                double t=den==0?0:Math.max(0,Math.min(1,-(ax*dx+ay*dy)/den));
                double d=Math.hypot(ax+t*dx,ay+t*dy)*111195;
                if(d<best){best=d;selected=s;remaining=distance(a,b)*(1-t);for(int k=i+1;k<line.size();k++)remaining+=distance(line.get(k-1),line.get(k));}
            }
        }
        return new Match(selected,best,remaining);
    }
    public static String meters(double n){if(!Double.isFinite(n)||n<0)return "—";return n>=1000?String.format(Locale.ROOT,"%.1f 公里",n/1000):Math.round(n)+" 米";}

    /**
     * 从 a 指向 b 的方位角（度，0=正北，顺时针）。
     *
     * ── 巡航为什么要它 ──────────────────────────────────────────────
     * 「巡航 = 前方路况」，而"前方"必须知道**车头朝哪**。旧实现拿不到航向，
     * 于是硬编码朝北投 5 公里 —— 往南开的车会被播报"北边那条路"的路况，
     * 用户看到的是一段跟他毫无关系的拥堵。有了连续两次定位就能算出真实航向。
     *
     * 纯函数（球面近似，几公里内误差可忽略），可直接单测。
     */
    public static double bearing(Point a,Point b){
        double lat1=Math.toRadians(a.lat),lat2=Math.toRadians(b.lat);
        double dLon=Math.toRadians(b.lon-a.lon);
        double y=Math.sin(dLon)*Math.cos(lat2);
        double x=Math.cos(lat1)*Math.sin(lat2)-Math.sin(lat1)*Math.cos(lat2)*Math.cos(dLon);
        double deg=Math.toDegrees(Math.atan2(y,x));
        if(!Double.isFinite(deg))return 0;
        return (deg%360+360)%360;
    }

    /**
     * 高德 Web 服务返回的 info/infocode → 人话。
     *
     * ── 为什么要有这个 ──────────────────────────────────────────────
     * 之前 `getJson` 只看 HTTP 状态码：高德限流/Key 失效时它照样返回 200，
     * 只是 body 里 `geocodes`/`paths` 是空的。代码于是当成"没有匹配地点"，
     * 用户看到的就是一句含糊的「没有规划出路线」——**看不出真正原因**，
     * 分不清是"地名打错了"、"Key 过期了"还是"今天额度用完了"。
     * 三个原因的处理方式完全不同，必须分开说。
     *
     * 纯函数，无 Android 依赖，直接单测。
     */
    public static String amapReason(String info, String infocode) {
        String code = infocode == null ? "" : infocode.trim();
        String raw = info == null ? "" : info.trim();
        String friendly;
        switch (code) {
            case "10001": friendly = "Key 无效或已过期"; break;
            case "10002": friendly = "该 Key 没有开通这个服务"; break;
            case "10003": friendly = "Key 当日调用额度已用尽（限流）"; break;
            case "10004": friendly = "Key 访问过于频繁（限流）"; break;
            case "10005": friendly = "这台设备的 IP 不在 Key 的白名单里"; break;
            case "10006": friendly = "域名不在 Key 的白名单里"; break;
            case "10007": friendly = "数字签名校验失败"; break;
            // 10009 = USERKEY_PLAT_NOMATCH：Key 的「服务平台」类型和调用方式对不上。
            // 我们走的是纯 HTTP，实测必须配「Android 平台」类型的 Key（用户 2026-09-18 纠正；
            // 之前这里写的是"Web 服务"，是错的）。
            case "10009": friendly = "Key 的服务平台类型不对：请到高德控制台把该 Key 改成「Android 平台」"; break;
            case "10010": friendly = "该 IP 当日调用额度已用尽"; break;
            case "10012": friendly = "Key 已被删除或失效"; break;
            case "10013": friendly = "Key 当日调用额度已用尽（限流）"; break;
            case "10014": friendly = "Key 每秒调用次数超限"; break;
            // ⚠️ 10015~10021 是实测撞出来的（连续打几个接口就中），
            //    以前没有这几条分支 → 用户只看到一句"高德返回错误"，无从下手。
            case "10015": friendly = "高德网关超时（重试一次通常就好）"; break;
            case "10016": friendly = "高德服务器繁忙（稍后再试）"; break;
            case "10017": friendly = "Key 并发量超过限制（稍等一秒再试）"; break;
            case "10018": friendly = "Key 并发量超过限制（稍等一秒再试）"; break;
            case "10019": friendly = "Key 类型与当前接口不匹配"; break;
            case "10021": friendly = "Key 并发量超过限制（稍等一秒再试）"; break;
            case "10022": friendly = "Key 已被限制调用（今日额度或风控）"; break;
            case "20000": case "20001": case "20002": case "20003":
                friendly = "参数不合法（地名或坐标没被接受）"; break;
            case "20800": friendly = "高德没找到匹配的地点（换个更具体的说法）"; break;
            default: friendly = null; break;
        }
        if (friendly == null) {
            // 没有对应码就看 info 原文 —— 高德有些错误只给字符串。
            String up = raw.toUpperCase(Locale.ROOT);
            if (up.contains("ENGINE_RESPONSE_DATA_ERROR")) friendly = "高德没找到匹配的地点（换个更具体的说法）";
            else if (up.contains("CUQPS")) friendly = "Key 并发量超过限制（稍等一秒再试）";
            else if (up.contains("QPS") || up.contains("TOO_FREQUENT")) friendly = "Key 访问过于频繁（限流）";
            else if (up.contains("OVER_LIMIT")) friendly = "Key 调用额度已用尽（限流）";
            else if (up.contains("SERVICE_NOT_AVAILABLE")) friendly = "该 Key 没有开通这个服务";
            else friendly = "高德返回错误";
        }
        StringBuilder b = new StringBuilder("高德：" ).append(friendly);
        if (!raw.isEmpty() || !code.isEmpty()) {
            b.append("（");
            if (!raw.isEmpty()) b.append(raw);
            if (!raw.isEmpty() && !code.isEmpty()) b.append(' ');
            if (!code.isEmpty()) b.append(code);
            b.append('）');
        }
        return b.toString();
    }

    public static String clip(String text,int bytes){StringBuilder b=new StringBuilder();int n=0;for(int i=0;i<text.length();){int cp=text.codePointAt(i);String c=new String(Character.toChars(cp));int size=c.getBytes(StandardCharsets.UTF_8).length;if(n+size>bytes)break;b.append(c);n+=size;i+=Character.charCount(cp);}return b.toString();}
    public static byte[] packet(int type,String json) {
        if(type!=3&&type!=5&&type!=7)throw new IllegalArgumentException("type");
        byte[] data=json.getBytes(StandardCharsets.UTF_8);if(data.length>4096)throw new IllegalArgumentException("size");
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(8);out.write(1);out.write(16);out.write(type);out.write(26);
        int n=data.length;do{int v=n&127;n>>>=7;out.write(n==0?v:v|128);}while(n!=0);out.write(data,0,data.length);return out.toByteArray();
    }
    public static final class Envelope {public final int type;public final String json;Envelope(int t,String j){type=t;json=j;}}
    private static long var(byte[] b,int[] p){long v=0;for(int i=0;i<10;i++){if(p[0]>=b.length)throw new IllegalArgumentException();int c=b[p[0]++]&255;if(i==9&&(c&254)!=0)throw new IllegalArgumentException();v|=(long)(c&127)<<(7*i);if((c&128)==0)return v;}throw new IllegalArgumentException();}
    public static Envelope decode(byte[] b){
        if(b==null||b.length>262144) return null;
        try{int[] p={0};int seen=0,type=-1;long version=0;String json="{}";
            while(p[0]<b.length){long k=var(b,p);int tag=(int)(k>>3),wire=(int)(k&7);if(tag<1||tag>4||(seen&(1<<tag))!=0)return null;seen|=1<<tag;
                if(tag<=2){if(wire!=0)return null;long v=var(b,p);if(tag==1)version=v;else {if(v>255||v<0)return null;type=(int)v;}}
                else{if(wire!=2)return null;long n=var(b,p);if(n<0||n>b.length-p[0])return null;if(tag==3)json=new String(b,p[0],(int)n,StandardCharsets.UTF_8);p[0]+=(int)n;}}
            return version==1&&type>=0?new Envelope(type,json):null;
        }catch(RuntimeException e){return null;}
    }
}

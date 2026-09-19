package com.turboio.addon;

import java.util.*;

/** Route playback only. Never writes a location fix or invokes mock-location APIs. */
public final class NavSimulation {
    public static final class Frame {
        public final int step;
        public final double remainingStep, progress;
        public final NavCore.Point point;
        public final boolean finished;
        Frame(int s,double remaining,double progress,NavCore.Point p,boolean done){
            step=s;remainingStep=remaining;this.progress=progress;point=p;finished=done;
        }
    }
    private final List<NavCore.Step> steps;
    private final double[] lengths;
    private final double total,baseSpeed;
    private double traveled;
    private long last;
    private int speed=1;
    private boolean paused;
    public NavSimulation(List<NavCore.Step> route,String mode,long now){
        if(route==null||route.isEmpty())throw new IllegalArgumentException("route");
        steps=Collections.unmodifiableList(new ArrayList<>(route));lengths=new double[steps.size()];
        double sum=0;
        for(int s=0;s<steps.size();s++){
            List<NavCore.Point> p=steps.get(s).points;
            if(p.size()<2)throw new IllegalArgumentException("geometry");
            for(int i=1;i<p.size();i++)lengths[s]+=NavCore.distance(p.get(i-1),p.get(i));
            sum+=lengths[s];
        }
        if(!Double.isFinite(sum)||sum<=0)throw new IllegalArgumentException("length");
        total=sum;baseSpeed="Drive".equals(mode)?11.11:"Ride".equals(mode)?4.17:1.4;last=now;
    }
    public void advance(long now,boolean displayReady){
        // A suspended UI must not jump across the route on resume.
        long elapsed=Math.max(0,Math.min(2000,now-last));last=now;
        if(displayReady&&!paused)traveled=Math.min(total,traveled+elapsed/1000.0*baseSpeed*speed);
    }
    public void pause(boolean value,long now){paused=value;last=now;}
    public boolean paused(){return paused;}
    public int speed(){return speed;}
    public void speed(int value,long now){if(value!=1&&value!=2&&value!=4&&value!=8)throw new IllegalArgumentException("speed");speed=value;last=now;}
    public Frame frame(){
        double left=traveled;int s=0;
        while(s<steps.size()-1&&left>=lengths[s])left-=lengths[s++];
        List<NavCore.Point> p=steps.get(s).points;
        double inStep=left;
        for(int i=1;i<p.size();i++){
            NavCore.Point a=p.get(i-1),b=p.get(i);double len=NavCore.distance(a,b);
            if(left<=len||i==p.size()-1){double t=len==0?1:Math.max(0,Math.min(1,left/len));
                return new Frame(s,Math.max(0,lengths[s]-inStep),traveled/total,
                    new NavCore.Point(a.lat+(b.lat-a.lat)*t,a.lon+(b.lon-a.lon)*t),traveled>=total);
            }left-=len;
        }
        throw new IllegalStateException("geometry");
    }
}

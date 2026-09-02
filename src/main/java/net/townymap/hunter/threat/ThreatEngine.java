package net.townymap.hunter.threat;

import net.townymap.hunter.model.ApproachRoute;
import net.townymap.hunter.model.HunterState;
import net.townymap.hunter.front.HiddenThreatFrontEngine;
import java.util.*;

/** Authoritative combined physical/teleport assessment. No additive threat score. */
public final class ThreatEngine {
    public static final long TELEPORT_SETUP_MS=8_000L;
    public static final double PLAUSIBLE_TRAVEL_BLOCKS_PER_SECOND=8.0;
    private final long setupMs;private long offlineResidualMs;private final double blocksPerSecond,safetyFactor;
    public ThreatEngine(){this(PLAUSIBLE_TRAVEL_BLOCKS_PER_SECOND,TELEPORT_SETUP_MS/1000.0,1.0,15);}
    public ThreatEngine(double speed,double setupSeconds,double safetyFactor){this(speed,setupSeconds,safetyFactor,15);}
    public ThreatEngine(double speed,double setupSeconds,double safetyFactor,int offlineResidualMinutes){this.blocksPerSecond=Math.max(1,speed);this.setupMs=Math.round(Math.max(0,setupSeconds)*1000);this.safetyFactor=Math.max(.25,safetyFactor);this.offlineResidualMs=Math.clamp(offlineResidualMinutes,0,120)*60_000L;}
    public void setOfflineResidualMinutes(int minutes){offlineResidualMs=Math.clamp(minutes,0,120)*60_000L;}
    /** Display-only exposure trend; never feeds the authoritative hunter assessment. */
    public static int wildernessExposureBonus(long durationMs,double nearestClaimDistance){int time=durationMs>=300_000?20:durationMs>=120_000?14:durationMs>=60_000?8:durationMs>=30_000?4:0;return time+(nearestClaimDistance>=256?8:0);}
    public HunterState.ThreatAssessment assess(Input in){return assess(in,0);}
    public HunterState.ThreatAssessment assess(Input in,double exposureModifier){
        ArrayList<String> why=new ArrayList<>();
        HunterState.ThreatLevel physical=physical(in.visible,in.physicalDistance);
        HunterState.ThreatLevel teleport=HunterState.ThreatLevel.SAFE;
        ApproachRoute best=in.routes.isEmpty()?null:in.routes.getFirst();
        long arrival=Long.MAX_VALUE;
        String phase="none";
        if(best!=null&&in.visible){
            arrival=Math.round((setupMs+best.distanceToUser()/blocksPerSecond*1000.0)*safetyFactor);
            teleport=best.distanceToUser()<=500?HunterState.ThreatLevel.WATCH:HunterState.ThreatLevel.LOW;phase="visible-suppressed";
            why.add("approach "+best.name()+" "+Math.round(best.distanceToUser())+"m; "+phase);
        }
        if(Double.isFinite(in.physicalDistance))why.add((in.visible?"visible ":"last seen ")+Math.round(in.physicalDistance)+"m away");
        HunterState.ThreatLevel level=max(physical,teleport);
        if(!in.visible)level=HunterState.ThreatLevel.LOW;
        if(in.offline){level=offline(level,in.offlineForMs,offlineResidualMs);why.add("offline last-known location");}
        int score=switch(level){case SAFE->0;case LOW->25;case WATCH->50;case HIGH->75;case CRITICAL->100;};
        return new HunterState.ThreatAssessment(score,level,List.copyOf(why),physical,teleport,best,arrival,phase);
    }
    public HunterState.ThreatAssessment assessHidden(HiddenThreatFrontEngine.Summary fronts,boolean knownHunter,double exposureModifier,boolean offline,long offlineForMs){int warning=fronts==null?0:fronts.warningContainingPlayer(),plausible=fronts==null?0:fronts.plausibleContainingPlayer();HunterState.ThreatLevel level,teleport;if(plausible>0){level=knownHunter||plausible>1?HunterState.ThreatLevel.CRITICAL:HunterState.ThreatLevel.HIGH;teleport=level;}else if(warning>0){level=knownHunter?HunterState.ThreatLevel.HIGH:HunterState.ThreatLevel.WATCH;teleport=level;}else{level=HunterState.ThreatLevel.LOW;teleport=HunterState.ThreatLevel.LOW;}ArrayList<String>why=new ArrayList<>();why.add(plausible+" plausible and "+warning+" warning fronts contain player");if((warning>0||plausible>0)&&exposureModifier>=.5){level=raise(level);why.add("wilderness vulnerability");}if(offline)level=offline(level,offlineForMs,offlineResidualMs);int score=switch(level){case SAFE->0;case LOW->25;case WATCH->50;case HIGH->75;case CRITICAL->100;};return new HunterState.ThreatAssessment(score,level,List.copyOf(why),HunterState.ThreatLevel.SAFE,teleport,null,Long.MAX_VALUE,plausible>0?"front-plausible":warning>0?"front-warning":"front-outside");}
    private static HunterState.ThreatLevel physical(boolean visible,double d){
        if(!Double.isFinite(d))return HunterState.ThreatLevel.SAFE;
        if(visible&&d<=100)return HunterState.ThreatLevel.CRITICAL;
        if(visible&&d<=250)return HunterState.ThreatLevel.HIGH;
        if(d<=500)return HunterState.ThreatLevel.HIGH;
        if(d<=1000)return HunterState.ThreatLevel.WATCH;
        return HunterState.ThreatLevel.LOW;
    }
    private static HunterState.ThreatLevel offline(HunterState.ThreatLevel l,long ms,long residualMs){if(ms>=residualMs)return HunterState.ThreatLevel.SAFE;return l.ordinal()>HunterState.ThreatLevel.WATCH.ordinal()?HunterState.ThreatLevel.WATCH:l;}
    private static HunterState.ThreatLevel max(HunterState.ThreatLevel a,HunterState.ThreatLevel b){return a.ordinal()>=b.ordinal()?a:b;}
    private static HunterState.ThreatLevel raise(HunterState.ThreatLevel level){return switch(level){case WATCH->HunterState.ThreatLevel.HIGH;case HIGH->HunterState.ThreatLevel.CRITICAL;default->level;};}
    public record Input(boolean visible,double physicalDistance,long hiddenDurationMs,List<ApproachRoute> routes,boolean offline,long offlineForMs){public Input{routes=routes==null?List.of():routes;}}
}

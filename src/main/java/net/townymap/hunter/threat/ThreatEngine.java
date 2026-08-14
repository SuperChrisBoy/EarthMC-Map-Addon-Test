package net.townymap.hunter.threat;

import net.townymap.hunter.model.HunterState;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;

/** Centralized, deliberately conservative V1 scoring. */
public final class ThreatEngine {
    public HunterState.ThreatAssessment assess(HunterState h, double distance, boolean userVisible, boolean wilderness, long now) {
        return assess(h,distance,userVisible,wilderness,0,0,now);
    }
    public HunterState.ThreatAssessment assess(HunterState h,double distance,boolean userVisible,boolean wilderness,long wildernessExposureMs,double nearestClaimDistance,long now){
        if(h.online==HunterState.OnlineStatus.OFFLINE&&!h.offlineResidualActive(now))return HunterState.ThreatAssessment.safe();
        int score = 0; ArrayList<String> why = new ArrayList<>();
        if (distance <= 100) { score += 55; why.add(reason("within",100)); }
        else if (distance <= 250) { score += 42; why.add(reason("within",250)); }
        else if (distance <= 500) { score += 30; why.add(reason("within",500)); }
        else if (distance <= 1000) { score += 18; why.add(reason("within",1000)); }
        if (h.visibility == HunterState.Visibility.HIDDEN && h.online == HunterState.OnlineStatus.ONLINE) { score += 18; why.add(reason("online_lost")); }
        if (h.inferred != null && now - h.inferred.atMs() <= 60_000) { score += 20; why.add(reason("combat_estimate")); }
        if (!h.teleportOptions.isEmpty()) {
            double tp = h.teleportOptions.getFirst().distanceToLocalPlayer();
            if (tp <= 500) { score += 28; why.add(reason("arrival_within",500)); }
            else if (tp <= 2000) { score += 14; why.add(reason("arrival_within",2000)); }
        }
        if (!h.combatHistory.isEmpty() && now - h.combatHistory.getFirst().atMs() <= 5 * 60_000) { score += 12; why.add(reason("recent_combat")); }
        if (userVisible) { score += wilderness ? 18 : 7; why.add(reason(wilderness?"visible_wilderness":"visible")); }
        if(userVisible&&wilderness){int exposureBonus=wildernessExposureBonus(wildernessExposureMs,nearestClaimDistance);score+=exposureBonus;if(wildernessExposureMs>=30_000)why.add(reason("wilderness_exposure_time",Math.max(1,wildernessExposureMs/60_000)));if(nearestClaimDistance>=256)why.add(reason("far_from_claim",(int)Math.round(nearestClaimDistance)));}
        if(h.online==HunterState.OnlineStatus.OFFLINE){score=Math.max(20,(int)Math.round(score*0.60));why.add(reason("offline_residual"));}
        score = Math.min(100, score);
        HunterState.ThreatLevel level = score >= 80 ? HunterState.ThreatLevel.CRITICAL : score >= 60 ? HunterState.ThreatLevel.HIGH : score >= 40 ? HunterState.ThreatLevel.ELEVATED : score >= 20 ? HunterState.ThreatLevel.LOW : HunterState.ThreatLevel.SAFE;
        return new HunterState.ThreatAssessment(score, level, java.util.List.copyOf(why));
    }
    public static int wildernessExposureBonus(long durationMs,double nearestClaimDistance){int time=durationMs>=300_000?20:durationMs>=120_000?14:durationMs>=60_000?8:durationMs>=30_000?4:0;return time+(nearestClaimDistance>=256?8:0);}
    private static String reason(String key,Object...args){return Component.translatable("townymapaddon.hunter.reason."+key,args).getString();}
}

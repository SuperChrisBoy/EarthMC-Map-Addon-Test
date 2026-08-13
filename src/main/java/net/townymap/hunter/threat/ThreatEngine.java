package net.townymap.hunter.threat;

import net.townymap.hunter.model.HunterState;
import java.util.ArrayList;

/** Centralized, deliberately conservative V1 scoring. */
public final class ThreatEngine {
    public HunterState.ThreatAssessment assess(HunterState h, double distance, boolean userVisible, boolean wilderness, long now) {
        int score = 0; ArrayList<String> why = new ArrayList<>();
        if (distance <= 100) { score += 55; why.add("hunter within 100m"); }
        else if (distance <= 250) { score += 42; why.add("hunter within 250m"); }
        else if (distance <= 500) { score += 30; why.add("hunter within 500m"); }
        else if (distance <= 1000) { score += 18; why.add("hunter within 1000m"); }
        if (h.visibility == HunterState.Visibility.HIDDEN && h.online == HunterState.OnlineStatus.ONLINE) { score += 18; why.add("online position lost"); }
        if (h.inferred != null && now - h.inferred.atMs() <= 60_000) { score += 20; why.add("recent combat estimate"); }
        if (!h.teleportOptions.isEmpty()) {
            double tp = h.teleportOptions.getFirst().distanceToLocalPlayer();
            if (tp <= 500) { score += 28; why.add("possible arrival within 500m"); }
            else if (tp <= 2000) { score += 14; why.add("possible arrival within 2000m"); }
        }
        if (!h.combatHistory.isEmpty() && now - h.combatHistory.getFirst().atMs() <= 5 * 60_000) { score += 12; why.add("recent combat"); }
        if (userVisible) { score += wilderness ? 18 : 7; why.add(wilderness ? "you are visible in wilderness" : "you are visible"); }
        score = Math.min(100, score);
        HunterState.ThreatLevel level = score >= 80 ? HunterState.ThreatLevel.CRITICAL : score >= 60 ? HunterState.ThreatLevel.HIGH : score >= 40 ? HunterState.ThreatLevel.ELEVATED : score >= 20 ? HunterState.ThreatLevel.LOW : HunterState.ThreatLevel.SAFE;
        return new HunterState.ThreatAssessment(score, level, java.util.List.copyOf(why));
    }
}

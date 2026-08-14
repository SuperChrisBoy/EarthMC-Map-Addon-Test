package net.townymap.hunter.model;

import net.townymap.model.EarthMcPlayerData;

import java.util.ArrayList;
import java.util.List;
import net.townymap.hunter.teleport.TeleportCapabilityEngine.TeleportOption;

/** Mutable state owned only by HunterEarlyWarningSystem on the client thread. */
public final class HunterState {
    public enum OnlineStatus { ONLINE, OFFLINE, UNKNOWN }
    public enum Visibility { VISIBLE, HIDDEN, UNKNOWN }
    public enum ObservationType { DIRECT_DYNMAP, LAST_KNOWN, COMBAT_INFERRED }

    public final String configuredName;
    public String name;
    public String uuid = "";
    public OnlineStatus online = OnlineStatus.UNKNOWN;
    public long offlineSinceMs;
    public Visibility visibility = Visibility.UNKNOWN;
    public Observation direct;
    public Observation inferred;
    public String residenceTown = "";
    public String nation = "";
    public long residenceCheckedAt;
    public int proximityZone;
    public ThreatAssessment threat = ThreatAssessment.safe();
    public List<TeleportOption> teleportOptions = List.of();
    public final List<CombatEvent> combatHistory = new ArrayList<>();

    public HunterState(String configuredName) { this.configuredName = configuredName; this.name = configuredName; }
    public void applyIdentity(EarthMcPlayerData data) {
        if (data == null) return;
        name = data.name(); uuid = data.uuid(); residenceTown = safe(data.townName()); nation = safe(data.nationName());
        online = data.online() ? OnlineStatus.ONLINE : OnlineStatus.OFFLINE;
    }
    public Observation bestObservation() { return direct != null ? direct : inferred; }
    public boolean offlineResidualActive(long now){Observation o=bestObservation();return online==OnlineStatus.OFFLINE&&offlineSinceMs>0&&o!=null&&now-offlineSinceMs<=30*60_000L;}
    private static String safe(String s) { return s == null ? "" : s; }

    public record Observation(int x, int z, long atMs, ObservationType type, String claim, String claimNation, Confidence confidence) {}
    public enum Confidence { HIGH, MEDIUM, LOW, UNKNOWN }
    public record CombatEvent(String text, long atMs, boolean kill) {}
    public record ThreatAssessment(int score, ThreatLevel level, List<String> reasons) {
        public static ThreatAssessment safe() { return new ThreatAssessment(0, ThreatLevel.SAFE, List.of()); }
    }
    public enum ThreatLevel { SAFE, LOW, ELEVATED, HIGH, CRITICAL }
}

package net.townymap.model;

import java.util.List;
import java.util.Map;

/** The complete /nations record for one nation, as shown in the expanded nation panel. */
public record NationFullData(
        String name,
        String board,
        String wiki,
        String discord,
        String king,
        String capital,

        long registeredMs,

        boolean isPublic,
        boolean isOpen,
        boolean isNeutral,

        int nationBonus,
        int numTownBlocks,
        int numResidents,
        int numTowns,
        int numOutlaws,
        int numAllies,
        int numEnemies,
        double balance,

        int spawnX,
        int spawnY,
        int spawnZ,

        List<String> towns,
        List<String> residents,
        List<String> outlaws,
        List<String> allies,
        List<String> enemies,
        List<String> sanctioned,
        Map<String, List<String>> ranks,
        List<Pact> pacts,
        List<String> embargoesOwn,
        List<String> embargoesAgainst
) {
    /**
     * A diplomatic pact. The API reports {@code expiresAt} and {@code duration} as -1 when the pact runs
     * forever, so anything <= 0 means "no end".
     */
    public record Pact(String sender, String receiver, String status,
                       long createdMs, long expiresAtMs, long durationMs) {
        public String other(String self) { return sender.equalsIgnoreCase(self) ? receiver : sender; }
        public boolean forever() { return expiresAtMs <= 0; }
    }

    /** Ranks with at least one holder — EarthMC returns every rank name, mostly empty. */
    public Map<String, List<String>> occupiedRanks() {
        java.util.LinkedHashMap<String, List<String>> out = new java.util.LinkedHashMap<>();
        ranks.forEach((k, v) -> { if (v != null && !v.isEmpty()) out.put(k, v); });
        return out;
    }
}

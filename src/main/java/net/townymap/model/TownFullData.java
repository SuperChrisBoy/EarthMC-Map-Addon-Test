package net.townymap.model;

import java.util.List;
import java.util.Map;

/**
 * The complete /towns record for one town, as shown in the expanded town panel.
 *
 * <p>The map's popup only needs a handful of fields ({@link TownPopupData}); this keeps everything else
 * the same response already carries — resident/trusted/outlaw names, ranks, warps, quarters, spawn
 * coordinates and the permission flags — so opening the panel costs no extra API call.
 */
public record TownFullData(
        String name,
        String board,
        String founder,
        String wiki,
        String discord,
        String mayor,
        String nation,

        long registeredMs,
        long joinedNationAtMs,
        long ruinedAtMs,

        boolean isPublic,
        boolean isOpen,
        boolean isNeutral,
        boolean isCapital,
        boolean isOverClaimed,
        boolean isRuined,
        boolean isForSale,
        boolean hasNation,
        boolean canOutsidersSpawn,
        boolean canPassiveMobsSpawn,
        boolean hasSnowAccumulation,
        boolean hasFriendlyFire,

        int numTownBlocks,
        int maxTownBlocks,
        int bonusBlocks,
        int nationBonus,
        int numResidents,
        int numTrusted,
        int numOutlaws,
        double balance,
        double forSalePrice,      // -1 when not for sale

        boolean pvp,
        boolean explosion,
        boolean fire,
        boolean mobs,

        int spawnX,
        int spawnY,
        int spawnZ,

        List<String> residents,
        List<String> trusted,
        List<String> outlaws,
        List<String> quarters,
        Map<String, List<String>> ranks,
        List<Warp> warps
) {
    /** A town warp. {@code access} is EarthMC's own string (TOWN / NATION / ALLY / PUBLIC). */
    public record Warp(String name, String access, String createdBy, long createdAtMs,
                       int x, int y, int z) {}

    /** Ranks with at least one holder — EarthMC returns every rank name, mostly empty. */
    public Map<String, List<String>> occupiedRanks() {
        java.util.LinkedHashMap<String, List<String>> out = new java.util.LinkedHashMap<>();
        ranks.forEach((k, v) -> { if (v != null && !v.isEmpty()) out.put(k, v); });
        return out;
    }
}

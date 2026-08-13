package net.townymap.model;

public record EarthMcNationData(
        String name,
        String uuid,
        String discord,
        String board,
        String kingName,
        String capitalName,
        String founded,
        int townCount,
        int residentCount,
        int chunkCount,
        int outlawCount,
        int allyCount,
        int enemyCount,
        double balance,
        boolean publicNation,
        boolean open,
        boolean neutral,
        boolean hasSpawn,
        int spawnX,
        int spawnZ,
        int nationBonus,  // EarthMC's own nation chunk bonus (stats.nationBonus); -1 if absent
        int activeResidentCount, // residents active within 42 days; -1 if not looked up
        long foundedMs           // raw registration time; `founded` is only a formatted date
) {
    public EarthMcNationData(String name, String uuid) {
        this(name, uuid, "", "", "", "", "", 0, 0, 0, 0, 0, 0, 0,
                false, false, false, false, 0, 0, -1, -1, 0L);
    }

    /** Returns a copy with the active-resident count filled in (looked up on demand, off the
     *  base nation fetch so the map's bulk fetches stay cheap). */
    public EarthMcNationData withActiveResidentCount(int count) {
        return new EarthMcNationData(name, uuid, discord, board, kingName, capitalName, founded,
                townCount, residentCount, chunkCount, outlawCount, allyCount, enemyCount, balance,
                publicNation, open, neutral, hasSpawn, spawnX, spawnZ, nationBonus, count, foundedMs);
    }
}

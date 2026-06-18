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
        int nationBonus  // EarthMC's own nation chunk bonus (stats.nationBonus); -1 if absent
) {
    public EarthMcNationData(String name, String uuid) {
        this(name, uuid, "", "", "", "", "", 0, 0, 0, 0, 0, 0, 0,
                false, false, false, false, 0, 0, -1);
    }
}

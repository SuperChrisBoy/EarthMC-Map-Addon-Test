package net.townymap.model;

public record TownPopupData(
        String townName,
        String nationName,
        String discord,
        String board,
        String mayor,
        int    numChunks,
        String founded,
        boolean pvp,
        boolean isPublic,
        boolean canOutsidersSpawn,
        boolean isOverClaimed,
        boolean isOpen,
        boolean isForSale,
        boolean hasNation,
        int     residentCount,
        double  balance,
        int     activeResidentCount, // residents active within 42 days; -1 if not looked up
        int     maxChunks            // EarthMC's own claim max (stats.maxTownBlocks); -1 if absent
) {
    public static final TownPopupData WILDERNESS =
            new TownPopupData("Wilderness", "", "", "", "", 0, "", false, false, false, false,
                    false, false, false, 0, 0, -1, -1);

    /** Returns a copy with the active-resident count filled in (looked up on demand, off the
     *  base town fetch so the map's bulk fetches stay cheap). */
    public TownPopupData withActiveResidentCount(int count) {
        return new TownPopupData(townName, nationName, discord, board, mayor, numChunks, founded, pvp,
                isPublic, canOutsidersSpawn, isOverClaimed, isOpen, isForSale, hasNation, residentCount,
                balance, count, maxChunks);
    }
}

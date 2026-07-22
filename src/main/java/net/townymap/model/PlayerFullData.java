package net.townymap.model;

import java.util.List;

/** The complete /players record for one player, as shown in the expanded player panel. */
public record PlayerFullData(
        String name,
        String title,
        String surname,
        String formattedName,
        String about,
        String discord,
        String town,
        String nation,

        long registeredMs,
        long joinedTownAtMs,
        long lastOnlineMs,

        boolean isOnline,
        boolean isNPC,
        boolean isMayor,
        boolean isKing,
        boolean hasTown,
        boolean hasNation,

        double balance,
        int numFriends,

        List<String> friends,
        List<String> townRanks,
        List<String> nationRanks
) {}

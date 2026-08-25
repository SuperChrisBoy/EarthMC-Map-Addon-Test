package net.townymap.teleport;

import net.townymap.model.PlayerFullData;
import java.util.*;

/** Commands that change membership require an explicit per-view confirmation for leadership/staff. */
public final class AdvancedCommandGuard {
    private AdvancedCommandGuard(){}
    public static boolean requiresConfirmation(PlayerFullData player){
        if(player==null)return false;
        if(player.isMayor()||player.isKing())return true;
        return sensitiveRanks(player.townRanks())||sensitiveRanks(player.nationRanks());
    }
    private static boolean sensitiveRanks(List<String> ranks){return ranks!=null&&ranks.stream().anyMatch(AdvancedCommandGuard::sensitive);}
    private static boolean sensitive(String value){String rank=value==null?"":value.toLowerCase(Locale.ROOT);return rank.contains("counc")||rank.contains("chancellor")||rank.contains("assistant")||rank.contains("coleader")||rank.contains("co-leader")||rank.contains("leader");}
}

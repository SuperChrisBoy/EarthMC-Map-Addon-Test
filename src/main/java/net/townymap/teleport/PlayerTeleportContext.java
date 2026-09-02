package net.townymap.teleport;

import net.townymap.model.PlayerFullData;
import net.townymap.model.TownFullData;
import java.util.*;

/** Immutable, authoritative Towny state used by every teleport evaluator. */
public record PlayerTeleportContext(String player, String town, String nation,
        Set<String> townRanks, Set<String> nationRanks, Set<String> allies, Set<String> enemies,
        boolean mayor, boolean nationLeader, boolean trusted, boolean primaryTownOpen) {
    public static PlayerTeleportContext of(PlayerFullData player, TownFullData currentTown,
            Collection<String> allies, Collection<String> enemies, TownFullData primaryTown) {
        String name=player==null?"":player.name();
        return new PlayerTeleportContext(name,player==null?"":player.town(),player==null?"":player.nation(),
                normalized(player==null?List.of():player.townRanks()),normalized(player==null?List.of():player.nationRanks()),
                normalized(allies),normalized(enemies),player!=null&&player.isMayor(),player!=null&&player.isKing(),
                (primaryTown!=null?contains(primaryTown.trusted(),name):currentTown!=null&&contains(currentTown.trusted(),name)),primaryTown!=null&&primaryTown.isOpen());
    }
    public PlayerTeleportContext simulateJoin(TownFullData joined,Collection<String> joinedAllies,Collection<String> joinedEnemies){
        return new PlayerTeleportContext(player,joined.name(),joined.nation(),Set.of(),Set.of(),normalized(joinedAllies),
                normalized(joinedEnemies),false,false,contains(joined.trusted(),player),primaryTownOpen);
    }
    public boolean sameNation(String value){return !key(nation).isBlank()&&key(nation).equals(key(value));}
    public boolean allied(String value){return allies.contains(key(value));}
    public boolean enemy(String value){return enemies.contains(key(value));}
    public boolean hasStaffRank(){return townRanks.stream().anyMatch(PlayerTeleportContext::staff)||nationRanks.stream().anyMatch(PlayerTeleportContext::staff);}
    private static boolean staff(String rank){return rank.contains("counc")||rank.contains("chancellor")||rank.contains("assistant")||rank.contains("co-leader")||rank.contains("coleader");}
    static Set<String> normalized(Collection<String> values){Set<String> out=new HashSet<>();if(values!=null)values.forEach(v->out.add(key(v)));return Set.copyOf(out);}
    static boolean contains(Collection<String> values,String name){return values!=null&&values.stream().anyMatch(v->v.equalsIgnoreCase(name));}
    static String key(String value){return value==null?"":value.trim().toLowerCase(Locale.ROOT);}
}

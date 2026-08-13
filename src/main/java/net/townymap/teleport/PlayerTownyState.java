package net.townymap.teleport;

import net.townymap.model.PlayerFullData;
import net.townymap.model.TownFullData;
import java.util.*;

/** Immutable Towny membership used for both real-player and simulated-join access checks. */
public record PlayerTownyState(String player,String town,String nation,Set<String> townRanks,
                               Set<String> nationRanks,Set<String> enemyNations,boolean mayor,boolean king){
    public static PlayerTownyState current(PlayerFullData p,Set<String> enemies){return new PlayerTownyState(p.name(),p.town(),p.nation(),Set.copyOf(p.townRanks()),Set.copyOf(p.nationRanks()),normalize(enemies),p.isMayor(),p.isKing());}
    public PlayerTownyState simulateJoin(TownFullData joined,Set<String> enemies){return new PlayerTownyState(player,joined.name(),joined.nation(),Set.of(),Set.of(),normalize(enemies),false,false);}
    public boolean enemy(String otherNation){return otherNation!=null&&!otherNation.isBlank()&&enemyNations.contains(key(otherNation));}
    private static Set<String> normalize(Collection<String> values){Set<String>s=new HashSet<>();if(values!=null)values.forEach(v->s.add(key(v)));return Set.copyOf(s);}private static String key(String s){return s==null?"":s.toLowerCase(Locale.ROOT);}
}

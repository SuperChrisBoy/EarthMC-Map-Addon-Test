package net.townymap.teleport;

import net.townymap.model.*;

/** The single source of truth for town- and nation-spawn eligibility. */
public final class TeleportAccessEvaluator {
    public static final double OUTSIDER_SPAWN_DAILY_UPKEEP=5.0;
    public record Result(TeleportDestination.Eligibility status,TeleportDestination.Reason reason){}

    public Result town(PlayerTeleportContext player,TownFullData target){
        if(player==null||target==null)return uncertain(TeleportDestination.Reason.API_DATA_MISSING);
        if(player.enemy(target.nation()))return unavailable(TeleportDestination.Reason.ENEMY_NATION);
        if(target.name().equalsIgnoreCase(player.town()))return accessible(TeleportDestination.Reason.OWN_TOWN);
        if(player.sameNation(target.nation())){
            if(!target.isPublic())return unavailable(TeleportDestination.Reason.PUBLIC_SPAWN_DISABLED);
            // Nation members use the public same-nation spawn path; the outsider 5g
            // upkeep uncertainty does not apply to this relationship.
            return accessible(TeleportDestination.Reason.SAME_NATION_ACCESS);
        }
        if(!target.canOutsidersSpawn())return unavailable(TeleportDestination.Reason.OUTSIDER_SPAWN_DISABLED);
        return upkeep(target,TeleportDestination.Reason.OUTSIDER_SPAWN_ENABLED);
    }

    public Result nation(PlayerTeleportContext player,NationFullData target){
        if(player==null||target==null)return uncertain(TeleportDestination.Reason.API_DATA_MISSING);
        if(player.enemy(target.name()))return unavailable(TeleportDestination.Reason.ENEMY_NATION);
        if(player.sameNation(target.name()))return accessible(TeleportDestination.Reason.OWN_NATION);
        if(player.allied(target.name()))return accessible(TeleportDestination.Reason.ALLIED_NATION);
        // Towny's public-nation flag permits outsiders to use /n spawn <nation>.
        // Previously these destinations were discarded before distance ranking.
        if(target.isPublic())return accessible(TeleportDestination.Reason.OUTSIDER_SPAWN_ENABLED);
        return unavailable(TeleportDestination.Reason.NOT_MEMBER_OR_ALLY);
    }

    private Result upkeep(TownFullData town,TeleportDestination.Reason success){
        // EarthMC exposes balance and the 5g outsider-spawn cost, but no paid/insolvent flag.
        return town.balance()<OUTSIDER_SPAWN_DAILY_UPKEEP?uncertain(TeleportDestination.Reason.UPKEEP_UNCERTAIN):accessible(success);
    }
    private static Result accessible(TeleportDestination.Reason r){return new Result(TeleportDestination.Eligibility.ACCESSIBLE,r);}
    private static Result unavailable(TeleportDestination.Reason r){return new Result(TeleportDestination.Eligibility.UNAVAILABLE,r);}
    private static Result uncertain(TeleportDestination.Reason r){return new Result(TeleportDestination.Eligibility.UNCERTAIN,r);}
}

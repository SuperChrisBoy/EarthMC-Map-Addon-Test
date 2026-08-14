package net.townymap.teleport;

import net.townymap.model.TownData;
import java.util.*;

/** Safe degraded routes while authoritative EarthMC details are still loading/unavailable. */
public final class TeleportFallbackRoutes {
    private static final int MAX_RESULTS=25;
    private TeleportFallbackRoutes(){}
    public static List<TeleportRoute> nearest(List<TownData> towns,double targetX,double targetZ){
        if(towns==null||towns.isEmpty())return List.of();
        return towns.stream().filter(Objects::nonNull).filter(t->t.name()!=null&&!t.name().isBlank())
                .map(t->{int x=t.centerX(),z=t.centerZ();TeleportDestination d=new TeleportDestination(
                        TeleportDestination.Type.TOWN_SPAWN,t.name(),x,0,z,"/t spawn "+t.name(),
                        TeleportDestination.Eligibility.UNCERTAIN,TeleportDestination.PhysicalAccess.UNKNOWN,
                        TeleportDestination.Reason.API_DATA_MISSING);double distance=d.distanceTo(targetX,targetZ);
                    return new TeleportRoute(TeleportRoute.Mode.STANDARD,List.of(new TeleportRoute.Step(
                            TeleportRoute.StepType.TOWN_SPAWN,t.name(),d.command())),d,distance,
                            TeleportRoute.MembershipRisk.UNKNOWN,TeleportRoute.Quality.UNCERTAIN,0,distance+2_500,0);})
                .sorted(Comparator.comparingDouble(TeleportRoute::walkingDistance)).limit(MAX_RESULTS).toList();
    }
}

package net.townymap.teleport;

import java.util.*;

/** Deterministic ranking shared by Standard and Advanced route generation. */
public final class TeleportRouteRanking {
    private TeleportRouteRanking(){}
    public static List<TeleportRoute> rank(Collection<TeleportRoute> routes){return routes.stream().sorted(Comparator.comparingDouble(TeleportRoute::score)).toList();}
}

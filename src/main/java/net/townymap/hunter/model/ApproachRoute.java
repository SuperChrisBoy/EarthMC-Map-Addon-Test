package net.townymap.hunter.model;

/** A hunter-specific, verified spawn route into the user's current exposure area. */
public record ApproachRoute(String key, String name, Type type, int x, int z,
                            double distanceToUser, boolean recentEntryMatch) {
    public enum Type { TOWN, NATION }
}

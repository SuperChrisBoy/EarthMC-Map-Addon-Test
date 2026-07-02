package net.townymap.model;

/**
 * Both resident-derived stats for a nation, computed from a SINGLE resident-timestamp pass:
 * the active-resident count (for the inactive display) and the bonus-drop projection. Combining them
 * halves the API calls a focused nation needs.
 *
 * @param activeCount  residents active within 42 days (+ opted-out, counted active), or -1 if unknown
 * @param projection   when the bonus next drops a level, or {@link NationBonusProjection#NONE}
 */
public record NationResidentStats(int activeCount, NationBonusProjection projection) {
    public static final NationResidentStats NONE = new NationResidentStats(-1, NationBonusProjection.NONE);
}

package net.townymap.model;

/**
 * Projection of when a nation's chunk bonus will drop a level as inactive residents are purged
 * (EarthMC removes a resident 42 days after they were last online, lowering the nation's resident
 * count and, once it crosses a tier threshold, its bonus).
 *
 * The purge runs ~12:00 Europe/Berlin daily, so the drop is anchored to that moment; under a day out it's
 * expressed in hours (which, being an absolute instant, already reflects the viewer's offset from German time).
 *
 * @param nextBonus        the bonus level the nation drops to at {@link #dropDate()}
 * @param daysUntilDrop    whole calendar days (viewer-local) until the drop, or -1 if not predictable
 * @param hoursUntilDrop   whole hours until the drop; shown instead of days when under 24, or -1 if unknown
 * @param minutesUntilDrop whole minutes until the drop; shown instead of hours when under 60, or -1 if unknown
 * @param dropDate         human-readable date of the drop (e.g. "Jul 11 2026")
 */
public record NationBonusProjection(int nextBonus, int daysUntilDrop, int hoursUntilDrop,
                                    int minutesUntilDrop, String dropDate, long dropAtMs) {
    public static final NationBonusProjection NONE = new NationBonusProjection(0, -1, -1, -1, "", 0L);

    /**
     * Days remaining recomputed against {@code now}, so a panel left open counts down instead of showing
     * the snapshot taken when the projection was fetched.
     */
    public int daysUntilDropAt(long now) {
        if (dropAtMs <= 0) return daysUntilDrop;
        return (int) Math.max(0, Math.ceil((dropAtMs - now) / 86_400_000.0));
    }

    public int hoursUntilDropAt(long now) {
        if (dropAtMs <= 0) return hoursUntilDrop;
        return (int) Math.max(0, Math.ceil((dropAtMs - now) / 3_600_000.0));
    }

    public int minutesUntilDropAt(long now) {
        if (dropAtMs <= 0) return minutesUntilDrop;
        return (int) Math.max(0, Math.ceil((dropAtMs - now) / 60_000.0));
    }
}

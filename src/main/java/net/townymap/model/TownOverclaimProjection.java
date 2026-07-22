package net.townymap.model;

/**
 * When a town becomes overclaimable, driven by resident purges.
 *
 * <p>A town's claim limit is {@code residents x perResident + bonusBlocks + nationBonus}, so it shrinks
 * every time an inactive resident is purged (42 days after their last login). A town well inside its
 * limit today can therefore become overclaimable without claiming a single chunk — which is the case
 * a nation-bonus-only projection misses entirely, since the bonus is usually the smaller term.
 *
 * <p>{@code atMs} of 0 with {@code daysUntil} -1 means "not foreseeable from known resident activity".
 */
public record TownOverclaimProjection(long atMs, String date, int daysUntil, int residentsLost) {

    public static final TownOverclaimProjection NONE = new TownOverclaimProjection(0L, "", -1, 0);

    public boolean known() { return atMs > 0; }

    /** Days remaining recomputed against {@code now}, so a panel left open stays honest. */
    public int daysUntilAt(long now) {
        if (atMs <= 0) return daysUntil;
        return (int) Math.max(0, Math.ceil((atMs - now) / 86_400_000.0));
    }
}

package net.townymap.teleport;

/** Classifies an Advanced route by its walking-distance difference from the best Standard route. */
public final class TeleportRouteQuality {
    public enum Rating { EXCELLENT, GOOD, SLIGHT_IMPROVEMENT, SAME_DISTANCE, LONGER, NOT_COMPARABLE }

    private TeleportRouteQuality() {}

    public static Rating rate(double saving) {
        if (!Double.isFinite(saving)) return Rating.NOT_COMPARABLE;
        long blocks = Math.round(saving);
        if (blocks >= 1_000) return Rating.EXCELLENT;
        if (blocks >= 250) return Rating.GOOD;
        if (blocks > 0) return Rating.SLIGHT_IMPROVEMENT;
        if (blocks < 0) return Rating.LONGER;
        return Rating.SAME_DISTANCE;
    }

    public static long blockDifference(double saving) {
        if (!Double.isFinite(saving)) return 0;
        return Math.abs(Math.round(saving));
    }
}

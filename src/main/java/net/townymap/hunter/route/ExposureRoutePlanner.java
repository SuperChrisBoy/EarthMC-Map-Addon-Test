package net.townymap.hunter.route;

import net.townymap.model.TownData;
import java.util.Comparator;
import java.util.List;

/** Conservative V1 route: recommends the nearest claim refuge; it makes no terrain-safety guarantee. */
public final class ExposureRoutePlanner {
    public Route recommend(double x, double z, List<TownData> towns) {
        TownData nearest = towns.stream().min(Comparator.comparingDouble(t -> Math.hypot(t.centerX()-x, t.centerZ()-z))).orElse(null);
        if (nearest == null) return null;
        return new Route(nearest.centerX(), nearest.centerZ(), nearest.name(), Math.hypot(nearest.centerX()-x, nearest.centerZ()-z));
    }
    public record Route(int x, int z, String destination, double distance) {}
}

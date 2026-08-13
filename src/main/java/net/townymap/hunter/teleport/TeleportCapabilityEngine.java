package net.townymap.hunter.teleport;

import net.townymap.model.EarthMcNationData;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;
import net.townymap.model.TownFullData;
import net.townymap.model.NationFullData;
import java.util.*;

/** Computes possible—not guaranteed—arrival points from official spawn/access fields already cached. */
public final class TeleportCapabilityEngine {
    public List<TeleportOption> fromOfficialDetails(TownFullData residence, NationFullData nation, double userX, double userZ) {
        ArrayList<TeleportOption> out = new ArrayList<>();
        if (residence != null && (residence.canOutsidersSpawn() || residence.hasNation())) {
            out.add(option(residence.name(), Type.RESIDENCE, residence.spawnX(), residence.spawnZ(), Eligibility.LIKELY,
                    "residence spawn", userX, userZ));
        }
        if (nation != null && nation.spawnX() != 0 && nation.spawnZ() != 0) {
            out.add(option(nation.capital(), Type.NATION, nation.spawnX(), nation.spawnZ(), Eligibility.POSSIBLE,
                    "nation spawn access depends on current Towny permissions", userX, userZ));
        }
        out.sort(Comparator.comparingDouble(TeleportOption::distanceToLocalPlayer));
        return List.copyOf(out);
    }
    private static TeleportOption option(String name, Type type, int x, int z, Eligibility eligibility, String reason, double ux, double uz) {
        return new TeleportOption(name == null || name.isBlank() ? type.name() : name, type, x, z, eligibility, reason, Math.hypot(x-ux,z-uz));
    }
    public List<TeleportOption> compute(String residence, String nation, Collection<TownData> towns,
            Map<String,TownPopupData> details, Map<String,EarthMcNationData> nations, double userX, double userZ) {
        ArrayList<TeleportOption> out = new ArrayList<>();
        for (TownData town : towns) {
            TownPopupData d = details.get(town.key());
            if (d == null) continue;
            boolean own = town.name().equalsIgnoreCase(residence);
            boolean sameNation = nation != null && !nation.isBlank() && nation.equalsIgnoreCase(d.nationName());
            if (!(own || sameNation || d.canOutsidersSpawn() || d.isPublic())) continue;
            double distance = Math.hypot(town.centerX() - userX, town.centerZ() - userZ);
            out.add(new TeleportOption(town.name(), own ? Type.RESIDENCE : sameNation ? Type.NATION : Type.PUBLIC,
                    town.centerX(), town.centerZ(), own || sameNation ? Eligibility.LIKELY : Eligibility.POSSIBLE,
                    own ? "residence town" : sameNation ? "same nation" : "public/outsider spawn", distance));
        }
        out.sort(Comparator.comparingDouble(TeleportOption::distanceToLocalPlayer));
        return List.copyOf(out);
    }
    public enum Type { RESIDENCE, NATION, PUBLIC }
    public enum Eligibility { LIKELY, POSSIBLE, UNKNOWN }
    public record TeleportOption(String destinationName, Type type, int x, int z, Eligibility eligibility,
                                 String reason, double distanceToLocalPlayer) {}
}

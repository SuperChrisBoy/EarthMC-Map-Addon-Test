package net.townymap.api;

import com.google.gson.*;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
import net.townymap.model.NationBonusProjection;
import net.townymap.model.NationResidentStats;
import net.townymap.model.TownPopupData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * One-shot async lookups against the EarthMC v4 REST API.
 *
 *  Location  POST https://api.earthmc.net/v4/location
 *            body: {"query": [[x, z]]}
 *
 *  Towns     POST https://api.earthmc.net/v4/towns
 *            body: {"query": ["TownName"]}
 */
public class EarthMcApiClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final String BASE = "https://api.earthmc.net/v4";
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMM d yyyy");

    private final HttpClient http;
    private final ExecutorService executor;
    // Caps concurrent /players activity-batch POSTs. The API 429s past ~4 simultaneous requests, so we
    // fire a big entity's 100-id batches in parallel but throttled to this many at once (the safe max).
    // Base town/nation fetches are NOT gated (they have their own load cap), so the map stays responsive.
    private final java.util.concurrent.Semaphore activeBatchGate = new java.util.concurrent.Semaphore(4);

    // Per-resident activity cache (id → {removalDate, fetchedAt}), so a huge nation like France (1000+
    // residents = ~11 /players calls) is fetched once, then repeat views and other nations that share
    // residents resolve with zero API calls. removalDate is absolute (lastOnline + 42d), so it stays
    // correct as time passes; a short TTL picks up players who log on/off.
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> residentActivityCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RESIDENT_ACTIVITY_TTL_MS = 15L * 60 * 1000;

    public EarthMcApiClient() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .executor(executor)
                .build();
    }

    /**
     * Looks up the town at (worldX, worldZ) block coordinates.
     * Returns null for wilderness or on error.
     */
    public CompletableFuture<TownPopupData> fetchTownAt(double worldX, double worldZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Step 1 — location lookup
                JsonObject locBody = new JsonObject();
                JsonArray query = new JsonArray();
                JsonArray coord = new JsonArray();
                coord.add(Math.round(worldX));
                coord.add(Math.round(worldZ));
                query.add(coord);
                locBody.add("query", query);

                String locJson = post(BASE + "/location", locBody.toString());
                if (locJson == null) return null;

                JsonArray locArr = JsonParser.parseString(locJson).getAsJsonArray();
                if (locArr.isEmpty()) return null;

                JsonObject loc = locArr.get(0).getAsJsonObject();
                boolean wilderness = loc.has("isWilderness") && loc.get("isWilderness").getAsBoolean();
                if (wilderness) return TownPopupData.WILDERNESS;

                if (!loc.has("town") || loc.get("town").isJsonNull()) return TownPopupData.WILDERNESS;
                String townName = loc.getAsJsonObject("town").get("name").getAsString();

                return fetchTownNow(townName);

            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC API lookup failed: {}", e.getMessage());
                return null;
            }
        }, executor);
    }

    public CompletableFuture<TownPopupData> fetchTown(String townName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchTownNow(townName);
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC town lookup failed for {}: {}", townName, e.getMessage());
                return null;
            }
        }, executor);
    }

    /** /towns accepts up to 100 names per query. */
    private static final int TOWN_QUERY_BATCH = 100;

    /**
     * Bulk town-detail lookup. Instead of one POST per visible town, this collapses a whole screen into
     * ⌈N/100⌉ parallel (gated) requests. Returns a map keyed by lowercase town name; any name the API
     * didn't return (deleted/renamed, or a failed batch) is simply absent from the map.
     */
    public CompletableFuture<java.util.Map<String, TownPopupData>> fetchTowns(List<String> names) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.Map<String, TownPopupData> out = new ConcurrentHashMap<>();
            if (names == null || names.isEmpty()) return out;
            // Dedupe (preserve order) so repeated names don't waste query slots.
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            for (String n : names) if (n != null && !n.isBlank()) unique.add(n);
            List<String> list = new ArrayList<>(unique);

            // Fire the 100-name batches in parallel; activeBatchGate throttles them to a rate the API
            // tolerates, exactly like the resident-activity batches.
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < list.size(); i += TOWN_QUERY_BATCH) {
                List<String> batch = new ArrayList<>(list.subList(i, Math.min(list.size(), i + TOWN_QUERY_BATCH)));
                futures.add(CompletableFuture.runAsync(() -> fetchTownsBatch(batch, out), executor));
            }
            for (CompletableFuture<Void> f : futures) {
                try { f.join(); } catch (RuntimeException ignored) { /* a failed batch just yields fewer entries */ }
            }
            return out;
        }, executor);
    }

    /** Looks up one ≤100-name batch and drops each parsed town into {@code out}, keyed by lowercase name. */
    private void fetchTownsBatch(List<String> batch, java.util.Map<String, TownPopupData> out) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);

        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(120L * attempt); }   // backoff before retrying a transient failure
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            json = postGated(BASE + "/towns", body.toString());
        }
        if (json == null) return;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            TownPopupData data = parseTown(el.getAsJsonObject());
            if (data != null && data.townName() != null && !data.townName().isBlank()) {
                out.put(data.townName().toLowerCase(java.util.Locale.ROOT), data);
            }
        }
    }

    public CompletableFuture<List<EarthMcPlayerData>> fetchPlayerIndex() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = get(BASE + "/players");
                if (json == null) return List.of();
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                List<EarthMcPlayerData> players = new ArrayList<>();
                for (JsonElement element : arr) {
                    if (!element.isJsonObject()) continue;
                    JsonObject obj = element.getAsJsonObject();
                    String name = str(obj, "name", "");
                    if (name.isBlank()) continue;
                    players.add(new EarthMcPlayerData(name, str(obj, "uuid", "")));
                }
                return List.copyOf(players);
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC player index lookup failed: {}", e.getMessage());
                return List.of();
            }
        }, executor);
    }

    public CompletableFuture<List<EarthMcNationData>> fetchNationIndex() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = get(BASE + "/nations");
                if (json == null) return List.of();
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                List<EarthMcNationData> nations = new ArrayList<>();
                for (JsonElement element : arr) {
                    if (!element.isJsonObject()) continue;
                    JsonObject obj = element.getAsJsonObject();
                    String name = str(obj, "name", "");
                    if (name.isBlank()) continue;
                    nations.add(new EarthMcNationData(name, str(obj, "uuid", "")));
                }
                return List.copyOf(nations);
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC nation index lookup failed: {}", e.getMessage());
                return List.of();
            }
        }, executor);
    }

    public CompletableFuture<EarthMcPlayerData> fetchPlayer(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonArray query = new JsonArray();
                query.add(playerName);
                body.add("query", query);

                String json = post(BASE + "/players", body.toString());
                if (json == null) return null;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return null;
                return parsePlayer(arr.get(0).getAsJsonObject());
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC player lookup failed for {}: {}", playerName, e.getMessage());
                return null;
            }
        }, executor);
    }

    /**
     * Bulk player-detail lookup (≤100 names/query, parallel + gated), mirroring {@link #fetchTowns} /
     * {@link #fetchNations}. Replaces the one-POST-per-player pattern for search rows and visible players.
     * Returns a map keyed by lowercase player name; names the API didn't return (opted out) are absent.
     */
    public CompletableFuture<java.util.Map<String, EarthMcPlayerData>> fetchPlayers(List<String> names) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.Map<String, EarthMcPlayerData> out = new ConcurrentHashMap<>();
            if (names == null || names.isEmpty()) return out;
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            for (String n : names) if (n != null && !n.isBlank()) unique.add(n);
            List<String> list = new ArrayList<>(unique);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < list.size(); i += TOWN_QUERY_BATCH) {
                List<String> batch = new ArrayList<>(list.subList(i, Math.min(list.size(), i + TOWN_QUERY_BATCH)));
                futures.add(CompletableFuture.runAsync(() -> fetchPlayersBatch(batch, out), executor));
            }
            for (CompletableFuture<Void> f : futures) {
                try { f.join(); } catch (RuntimeException ignored) { /* a failed batch just yields fewer entries */ }
            }
            return out;
        }, executor);
    }

    private void fetchPlayersBatch(List<String> batch, java.util.Map<String, EarthMcPlayerData> out) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);

        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(120L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            json = postGated(BASE + "/players", body.toString());
        }
        if (json == null) return;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            EarthMcPlayerData data = parsePlayer(el.getAsJsonObject());
            if (data != null && data.name() != null && !data.name().isBlank()) {
                out.put(data.name().toLowerCase(java.util.Locale.ROOT), data);
            }
        }
    }

    public CompletableFuture<EarthMcNationData> fetchNation(String nationName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                JsonArray query = new JsonArray();
                query.add(nationName);
                body.add("query", query);

                String json = post(BASE + "/nations", body.toString());
                if (json == null) return null;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return null;
                return parseNation(arr.get(0).getAsJsonObject());
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] EarthMC nation lookup failed for {}: {}", nationName, e.getMessage());
                return null;
            }
        }, executor);
    }

    /**
     * Bulk nation-detail lookup (≤100 names/query, parallel + gated), mirroring {@link #fetchTowns}. Used
     * to warm the capital stars and search rows in a couple of requests instead of one POST per nation.
     * Returns a map keyed by lowercase nation name; names the API didn't return are absent.
     */
    public CompletableFuture<java.util.Map<String, EarthMcNationData>> fetchNations(List<String> names) {
        return CompletableFuture.supplyAsync(() -> {
            java.util.Map<String, EarthMcNationData> out = new ConcurrentHashMap<>();
            if (names == null || names.isEmpty()) return out;
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>();
            for (String n : names) if (n != null && !n.isBlank()) unique.add(n);
            List<String> list = new ArrayList<>(unique);

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < list.size(); i += TOWN_QUERY_BATCH) {
                List<String> batch = new ArrayList<>(list.subList(i, Math.min(list.size(), i + TOWN_QUERY_BATCH)));
                futures.add(CompletableFuture.runAsync(() -> fetchNationsBatch(batch, out), executor));
            }
            for (CompletableFuture<Void> f : futures) {
                try { f.join(); } catch (RuntimeException ignored) { /* a failed batch just yields fewer entries */ }
            }
            return out;
        }, executor);
    }

    private void fetchNationsBatch(List<String> batch, java.util.Map<String, EarthMcNationData> out) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);

        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(120L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
            json = postGated(BASE + "/nations", body.toString());
        }
        if (json == null) return;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            return;
        }
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            EarthMcNationData data = parseNation(el.getAsJsonObject());
            if (data != null && data.name() != null && !data.name().isBlank()) {
                out.put(data.name().toLowerCase(java.util.Locale.ROOT), data);
            }
        }
    }

    private TownPopupData fetchTownNow(String townName) {
        if (townName == null || townName.isBlank()) return null;

        JsonObject townBody = new JsonObject();
        JsonArray tq = new JsonArray();
        tq.add(townName);
        townBody.add("query", tq);

        String townJson = post(BASE + "/towns", townBody.toString());
        if (townJson == null) return null;

        JsonArray townArr = JsonParser.parseString(townJson).getAsJsonArray();
        if (townArr.isEmpty()) return null;

        return parseTown(townArr.get(0).getAsJsonObject());
    }

    private TownPopupData parseTown(JsonObject t) {
        String name    = str(t, "name", "?");
        String discord = str(t, "discord", "");
        String board   = str(t, "board", "");

        String mayor = "?";
        if (t.has("mayor") && t.get("mayor").isJsonObject()) {
            mayor = str(t.getAsJsonObject("mayor"), "name", "?");
        }

        String nation = "";
        if (t.has("nation") && t.get("nation").isJsonObject()) {
            nation = str(t.getAsJsonObject("nation"), "name", "");
        }

        int    chunks    = 0;
        int    residents = 0;
        int    maxChunks = -1;
        double balance   = 0;
        if (t.has("stats") && t.get("stats").isJsonObject()) {
            JsonObject stats = t.getAsJsonObject("stats");
            if (stats.has("numTownBlocks")) chunks    = stats.get("numTownBlocks").getAsInt();
            if (stats.has("numResidents"))  residents = stats.get("numResidents").getAsInt();
            if (stats.has("maxTownBlocks")) maxChunks = stats.get("maxTownBlocks").getAsInt();
            if (stats.has("balance"))       balance   = stats.get("balance").getAsDouble();
        }

        String founded = "";
        if (t.has("timestamps") && t.get("timestamps").isJsonObject()) {
            JsonObject ts = t.getAsJsonObject("timestamps");
            if (ts.has("registered")) {
                long ms = ts.get("registered").getAsLong();
                founded = Instant.ofEpochMilli(ms)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .format(DATE_FMT);
            }
        }

        boolean pvp = false;
        if (t.has("flags") && t.get("flags").isJsonObject()) {
            JsonObject flags = t.getAsJsonObject("flags");
            if (flags.has("pvp")) pvp = flags.get("pvp").getAsBoolean();
        }

        boolean isPublic = false;
        boolean canOutsidersSpawn = false;
        boolean isOverClaimed = false;
        boolean isOpen = false;
        boolean isForSale = false;
        boolean hasNation = !nation.isBlank();
        if (t.has("status") && t.get("status").isJsonObject()) {
            JsonObject status = t.getAsJsonObject("status");
            if (status.has("isPublic")) isPublic = status.get("isPublic").getAsBoolean();
            if (status.has("canOutsidersSpawn")) canOutsidersSpawn = status.get("canOutsidersSpawn").getAsBoolean();
            if (status.has("isOverClaimed")) isOverClaimed = status.get("isOverClaimed").getAsBoolean();
            if (status.has("isOpen")) isOpen = status.get("isOpen").getAsBoolean();
            if (status.has("isForSale")) isForSale = status.get("isForSale").getAsBoolean();
            if (status.has("hasNation")) hasNation = status.get("hasNation").getAsBoolean();
        }

        // NOTE: the active-resident lookup is intentionally NOT done here. parseTown runs for every
        // visible town when a status overlay is active (requestVisibleTownDetails), so a per-resident
        // bulk POST here would fire hundreds of extra calls, get rate-limited, and corrupt both the
        // inactive count AND this town's own data. It's looked up on demand for the focused town only
        // (fetchTownActiveResidents). -1 = "not looked up".
        return new TownPopupData(name, nation, discord, board, mayor, chunks, founded, pvp,
                isPublic, canOutsidersSpawn, isOverClaimed, isOpen, isForSale, hasNation, residents, balance,
                -1, maxChunks);
    }

    /** On-demand for ONE focused nation: BOTH the active-resident count and the bonus-drop projection from
     *  a single /nations fetch + a single resident-timestamp pass (was two separate fetches). The /nations
     *  request is gated to avoid the 429 storms that hid the inactive/bonus rows. */
    public CompletableFuture<NationResidentStats> fetchNationResidentStats(String nationName) {
        return CompletableFuture.supplyAsync(() -> {
            if (nationName == null || nationName.isBlank()) return NationResidentStats.NONE;
            try {
                JsonObject body = new JsonObject();
                JsonArray q = new JsonArray();
                q.add(nationName);
                body.add("query", q);
                String json = postGated(BASE + "/nations", body.toString());
                if (json == null) return NationResidentStats.NONE;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return NationResidentStats.NONE;
                return computeResidentStats(arr.get(0).getAsJsonObject());
            } catch (RuntimeException e) {
                return NationResidentStats.NONE;
            }
        }, executor);
    }

    /** One resident-timestamp pass → both the active count and the bonus-drop projection.
     *  active ⟺ removalDate &gt; now (removalDate = lastOnline + 42d, or now+42d if online). */
    private NationResidentStats computeResidentStats(JsonObject obj) {
        java.util.List<String> ids = extractResidentIds(obj);
        int n = ids.size();
        if (n == 0) return new NationResidentStats(0, NationBonusProjection.NONE);
        if (n > MAX_NATION_RESIDENT_LOOKUP) return NationResidentStats.NONE;   // too many → skip both
        long now = System.currentTimeMillis();
        java.util.List<Long> dates = collectRemovalDates(ids, now);
        if (dates == null) return NationResidentStats.NONE;                    // a batch failed → unreliable
        int returned = dates.size();
        int activeReturned = 0;
        for (long d : dates) if (d > now) activeReturned++;
        int active = activeReturned + (n - returned);   // opted-out residents are omitted → counted active
        int authBonus = -1;
        if (obj.has("stats") && obj.get("stats").isJsonObject()) {
            JsonObject st = obj.getAsJsonObject("stats");
            if (st.has("nationBonus")) authBonus = st.get("nationBonus").getAsInt();
        }
        NationBonusProjection proj = computeProjectionFrom(authBonus, active, dates, now);
        return new NationResidentStats(active, proj);
    }

    /** On-demand active-resident count for ONE focused town: one /towns fetch + one resident-timestamp pass
     *  (shared/cached with the nation lookups). Gated to avoid the 429 storms that would hide the Inactive row.
     *  Returns -1 if it can't be determined (caller falls back to the raw resident count). */
    public CompletableFuture<Integer> fetchTownActiveResidents(String townName) {
        return CompletableFuture.supplyAsync(() -> {
            if (townName == null || townName.isBlank()) return -1;
            try {
                JsonObject body = new JsonObject();
                JsonArray q = new JsonArray();
                q.add(townName);
                body.add("query", q);
                String json = postGated(BASE + "/towns", body.toString());
                if (json == null) return -1;
                JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
                if (arr.isEmpty()) return -1;
                return computeTownActive(arr.get(0).getAsJsonObject());
            } catch (RuntimeException e) {
                return -1;
            }
        }, executor);
    }

    private int computeTownActive(JsonObject t) {
        java.util.List<String> ids = extractResidentIds(t);
        int n = ids.size();
        if (n == 0) return 0;
        if (n > MAX_RESIDENT_LOOKUP) return -1;
        long now = System.currentTimeMillis();
        java.util.List<Long> dates = collectRemovalDates(ids, now);
        if (dates == null) return -1;                    // a batch failed → unreliable
        int returned = dates.size();
        int activeReturned = 0;
        for (long d : dates) if (d > now) activeReturned++;
        return activeReturned + (n - returned);          // opted-out residents are omitted → counted active
    }

    private static java.util.List<String> extractResidentIds(JsonObject obj) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        if (!obj.has("residents") || !obj.get("residents").isJsonArray()) return ids;
        for (com.google.gson.JsonElement el : obj.getAsJsonArray("residents")) {
            if (el.isJsonObject()) {
                JsonObject r = el.getAsJsonObject();
                String id = str(r, "uuid", null);
                if (id == null || id.isBlank()) id = str(r, "name", null);
                if (id != null && !id.isBlank()) ids.add(id);
            } else if (el.isJsonPrimitive()) {
                ids.add(el.getAsString());
            }
        }
        return ids;
    }

    private static final long INACTIVE_THRESHOLD_MS = 42L * 24 * 3600 * 1000;
    /** /players returns at most 100 entries per query — batch at the max, then run the batches in
     *  parallel (throttled by activeBatchGate) so even a big entity resolves in ~1-2s. */
    private static final int RESIDENT_QUERY_BATCH = 100;
    /** Cap the on-demand active lookup (per focused town). Parallel batching makes this cheap, so it can
     *  cover essentially every real town; above it the inactive count is omitted. */
    private static final int MAX_RESIDENT_LOOKUP = 2000;
    /** Nations span many towns, so allow a much larger (still bounded) lookup before falling back. */
    private static final int MAX_NATION_RESIDENT_LOOKUP = 3000;

    // ── Nation bonus-drop projection ──────────────────────────────────────────
    // EarthMC's nation chunk bonus is tiered by resident count. Residents are purged 42 days after they
    // were last online, so as inactivity accrues the count falls and eventually crosses a tier threshold,
    // dropping the bonus. We project the next drop from each resident's lastOnline.
    private static final int[] BONUS_TIER_FLOOR = { 200, 120, 80, 60, 40, 20 };
    private static final int[] BONUS_TIER_VALUE = { 100,  80, 60, 50, 30, 10 };

    private static int nationBonusFor(int residents) {
        for (int i = 0; i < BONUS_TIER_FLOOR.length; i++) {
            if (residents >= BONUS_TIER_FLOOR[i]) return BONUS_TIER_VALUE[i];
        }
        return 0;
    }
    private static int nationBonusFloor(int residents) {
        for (int floor : BONUS_TIER_FLOOR) if (residents >= floor) return floor;
        return 0;
    }

    /** Projects the next bonus drop. EarthMC bases the bonus on the ACTIVE resident count (inactive members
     *  stay in the nation but don't earn bonus and aren't removed for a long time), so the bonus falls when
     *  enough currently-active residents go inactive — NOT when residents are purged. We therefore count
     *  down from the active count using only FUTURE go-inactive dates (lastOnline + 42d). {@code currentBonus}
     *  is EarthMC's authoritative value (or -1 to derive the tier from the active count). */
    private NationBonusProjection computeProjectionFrom(int currentBonus, int activeCount,
                                                        java.util.List<Long> dates, long now) {
        int bonus = currentBonus >= 0 ? currentBonus : nationBonusFor(activeCount);
        if (bonus <= 0) return NationBonusProjection.NONE;       // already at the lowest tier
        int tier = tierIndexForBonus(bonus);
        if (tier < 0) return NationBonusProjection.NONE;         // unrecognised bonus value
        int currentFloor = BONUS_TIER_FLOOR[tier];
        int nextBonus = tier + 1 < BONUS_TIER_VALUE.length ? BONUS_TIER_VALUE[tier + 1] : 0;
        int drops = Math.max(1, activeCount - currentFloor + 1); // active residents that must go inactive to drop

        // Only currently-active residents (a FUTURE go-inactive date) can lower the active count from here;
        // already-inactive residents are excluded from the bonus, opted-out ones are unknown (far future).
        java.util.List<Long> future = new java.util.ArrayList<>();
        for (long d : dates) if (d > now) future.add(d);
        while (future.size() < activeCount) future.add(Long.MAX_VALUE);
        future.sort(null);
        if (drops > future.size()) return NationBonusProjection.NONE;
        long rawDrop = future.get(drops - 1);
        if (rawDrop == Long.MAX_VALUE) return NationBonusProjection.NONE;         // drop driven by unknown residents

        Countdown c = countdownTo(rawDrop, now);
        return new NationBonusProjection(nextBonus, c.days(), c.hours(), c.minutes(), c.date());
    }

    private record Countdown(int days, int hours, int minutes, String date) {}

    /** Anchors a raw 42-day mark to EarthMC's ~12:00 Europe/Berlin daily purge and expresses the wait as
     *  local calendar days plus absolute-instant hours/minutes — identical treatment to the nation bonus
     *  projection, so every countdown in the mod agrees. Days come from the viewer's local calendar (so the
     *  day count and shown date match); hours/minutes come from the absolute instant (already offset-correct
     *  vs German time). */
    private static Countdown countdownTo(long rawDrop, long now) {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        ZonedDateTime raw = Instant.ofEpochMilli(rawDrop).atZone(berlin);
        ZonedDateTime purge = raw.toLocalDate().atTime(12, 0).atZone(berlin);
        if (raw.isAfter(purge)) purge = purge.plusDays(1);
        long dropMs = purge.toInstant().toEpochMilli();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        LocalDate dropDate = Instant.ofEpochMilli(dropMs).atZone(zone).toLocalDate();
        int days = (int) Math.max(0, dropDate.toEpochDay() - today.toEpochDay());
        int hours = (int) Math.max(0, Math.ceil((dropMs - now) / 3_600_000.0));
        int minutes = (int) Math.max(0, Math.ceil((dropMs - now) / 60_000.0));
        return new Countdown(days, hours, minutes, dropDate.format(DATE_FMT));
    }

    private static int tierIndexForBonus(int bonus) {
        for (int i = 0; i < BONUS_TIER_VALUE.length; i++) if (BONUS_TIER_VALUE[i] == bonus) return i;
        return -1;
    }

    /** Each resident's removal date = (lastOnline, or now if online) + 42 days. Returns the dates for the
     *  residents the API returned (opted-out ones are absent), or null if any batch failed. Residents whose
     *  activity is already cached (within TTL) skip the API entirely — so a re-viewed or overlapping nation
     *  costs nothing. */
    private java.util.List<Long> collectRemovalDates(java.util.List<String> ids, long now) {
        java.util.List<Long> all = new java.util.ArrayList<>();
        java.util.List<String> toFetch = new java.util.ArrayList<>();
        for (String id : ids) {
            long[] c = residentActivityCache.get(id);
            if (c != null && now - c[1] < RESIDENT_ACTIVITY_TTL_MS) all.add(c[0]);
            else toFetch.add(id);
        }
        if (toFetch.isEmpty()) return all;      // whole nation served from cache → zero API calls

        java.util.List<CompletableFuture<java.util.List<Long>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < toFetch.size(); i += RESIDENT_QUERY_BATCH) {
            java.util.List<String> batch =
                    new java.util.ArrayList<>(toFetch.subList(i, Math.min(toFetch.size(), i + RESIDENT_QUERY_BATCH)));
            futures.add(CompletableFuture.supplyAsync(() -> removalDatesBatch(batch, now), executor));
        }
        for (CompletableFuture<java.util.List<Long>> f : futures) {
            java.util.List<Long> part;
            try { part = f.join(); } catch (RuntimeException e) { return null; }
            if (part == null) return null;
            all.addAll(part);
        }
        return all;
    }

    private java.util.List<Long> removalDatesBatch(java.util.List<String> batch, long now) {
        JsonObject body = new JsonObject();
        JsonArray q = new JsonArray();
        batch.forEach(q::add);
        body.add("query", q);
        // Include name+uuid so each returned player's activity can be cached by id for reuse.
        JsonObject template = new JsonObject();
        template.addProperty("name", true);
        template.addProperty("uuid", true);
        template.addProperty("timestamps", true);
        template.addProperty("status", true);
        body.add("template", template);

        String json = null;
        for (int attempt = 0; attempt < 3 && json == null; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(120L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
            }
            json = postGated(BASE + "/players", body.toString());
        }
        if (json == null) return null;
        JsonArray arr;
        try {
            arr = JsonParser.parseString(json).getAsJsonArray();
        } catch (RuntimeException e) {
            return null;
        }
        java.util.List<Long> out = new java.util.ArrayList<>();
        for (com.google.gson.JsonElement pe : arr) {
            if (!pe.isJsonObject()) continue;
            JsonObject p = pe.getAsJsonObject();
            boolean online = false;
            if (p.has("status") && p.get("status").isJsonObject()) {
                JsonObject st = p.getAsJsonObject("status");
                if (st.has("isOnline")) online = st.get("isOnline").getAsBoolean();
            }
            long lastOnline = 0;
            if (p.has("timestamps") && p.get("timestamps").isJsonObject()) {
                JsonObject ts = p.getAsJsonObject("timestamps");
                if (ts.has("lastOnline") && !ts.get("lastOnline").isJsonNull()) {
                    lastOnline = ts.get("lastOnline").getAsLong();
                }
            }
            long base = online ? now : (lastOnline > 0 ? lastOnline : now);
            long removal = base + INACTIVE_THRESHOLD_MS;
            out.add(removal);
            // Cache by both uuid and name so a later lookup keyed by either form hits.
            long[] entry = { removal, now };
            String uuid = str(p, "uuid", null);
            String pname = str(p, "name", null);
            if (uuid != null && !uuid.isBlank()) residentActivityCache.put(uuid, entry);
            if (pname != null && !pname.isBlank()) residentActivityCache.put(pname, entry);
        }
        return out;
    }

    /** post() throttled by activeBatchGate, for the parallel activity-batch lookups only. */
    private String postGated(String url, String body) {
        try {
            activeBatchGate.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            return post(url, body);
        } finally {
            activeBatchGate.release();
        }
    }

    private String post(String url, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "TownyMapAddon/1.0 (Fabric Mod)")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            LOGGER.warn("[TownyMap] EarthMC API {} → HTTP {}", url, resp.statusCode());
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] EarthMC API request failed: {}", e.getMessage());
        }
        return null;
    }

    private String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(20))
                    .header("User-Agent", "TownyMapAddon/1.0 (Fabric Mod)")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) return resp.body();
            LOGGER.warn("[TownyMap] EarthMC API {} -> HTTP {}", url, resp.statusCode());
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] EarthMC API request failed: {}", e.getMessage());
        }
        return null;
    }

    private EarthMcPlayerData parsePlayer(JsonObject p) {
        String name = str(p, "name", "?");
        String uuid = str(p, "uuid", "");
        String formatted = str(p, "formattedName", "");
        String town = objectName(p, "town");
        String nation = objectName(p, "nation");

        boolean online = false;
        boolean npc = false;
        boolean mayor = false;
        boolean king = false;
        if (p.has("status") && p.get("status").isJsonObject()) {
            JsonObject status = p.getAsJsonObject("status");
            online = bool(status, "isOnline");
            npc = bool(status, "isNPC");
            mayor = bool(status, "isMayor");
            king = bool(status, "isKing");
        }

        double balance = 0;
        int friends = 0;
        if (p.has("stats") && p.get("stats").isJsonObject()) {
            JsonObject stats = p.getAsJsonObject("stats");
            if (stats.has("balance")) balance = stats.get("balance").getAsDouble();
            if (stats.has("numFriends")) friends = stats.get("numFriends").getAsInt();
        }

        String lastOnline = "";
        long lastOnlineMs = 0L;
        long registeredMs = 0L;
        String registered = "";
        if (p.has("timestamps") && p.get("timestamps").isJsonObject()) {
            JsonObject ts = p.getAsJsonObject("timestamps");
            if (ts.has("lastOnline") && !ts.get("lastOnline").isJsonNull()) {
                lastOnlineMs = ts.get("lastOnline").getAsLong();
                lastOnline = Instant.ofEpochMilli(lastOnlineMs)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .format(DATE_FMT);
            }
            if (ts.has("registered") && !ts.get("registered").isJsonNull()) {
                long regMs = ts.get("registered").getAsLong();
                if (regMs > 0) {
                    registeredMs = regMs;
                    registered = Instant.ofEpochMilli(regMs)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(DATE_FMT);
                }
            }
        }

        return new EarthMcPlayerData(name, uuid, town, nation, formatted,
                online, npc, mayor, king, balance, friends, lastOnline, lastOnlineMs, registeredMs, registered);
    }

    private EarthMcNationData parseNation(JsonObject n) {
        String name = str(n, "name", "?");
        String uuid = str(n, "uuid", "");
        String discord = str(n, "discord", "");
        String board = str(n, "board", "");
        String king = objectName(n, "king");
        String capital = objectName(n, "capital");
        String founded = "";
        if (n.has("timestamps") && n.get("timestamps").isJsonObject()) {
            JsonObject ts = n.getAsJsonObject("timestamps");
            if (ts.has("registered")) {
                long ms = ts.get("registered").getAsLong();
                founded = Instant.ofEpochMilli(ms)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .format(DATE_FMT);
            }
        }

        int towns = 0;
        int residents = 0;
        int chunks = 0;
        int outlaws = 0;
        int allies = 0;
        int enemies = 0;
        double balance = 0;
        int nationBonusVal = -1;
        if (n.has("stats") && n.get("stats").isJsonObject()) {
            JsonObject stats = n.getAsJsonObject("stats");
            if (stats.has("numTowns")) towns = stats.get("numTowns").getAsInt();
            if (stats.has("numResidents")) residents = stats.get("numResidents").getAsInt();
            if (stats.has("numTownBlocks")) chunks = stats.get("numTownBlocks").getAsInt();
            if (stats.has("numOutlaws")) outlaws = stats.get("numOutlaws").getAsInt();
            if (stats.has("numAllies")) allies = stats.get("numAllies").getAsInt();
            if (stats.has("numEnemies")) enemies = stats.get("numEnemies").getAsInt();
            if (stats.has("balance")) balance = stats.get("balance").getAsDouble();
            if (stats.has("nationBonus")) nationBonusVal = stats.get("nationBonus").getAsInt();
        }

        boolean isPublic = false;
        boolean isOpen = false;
        boolean isNeutral = false;
        if (n.has("status") && n.get("status").isJsonObject()) {
            JsonObject status = n.getAsJsonObject("status");
            isPublic = bool(status, "isPublic");
            isOpen = bool(status, "isOpen");
            isNeutral = bool(status, "isNeutral");
        }

        boolean hasSpawn = false;
        int spawnX = 0;
        int spawnZ = 0;
        if (n.has("coordinates") && n.get("coordinates").isJsonObject()) {
            JsonObject coords = n.getAsJsonObject("coordinates");
            if (coords.has("spawn") && coords.get("spawn").isJsonObject()) {
                JsonObject spawn = coords.getAsJsonObject("spawn");
                if (spawn.has("x") && spawn.has("z")) {
                    spawnX = (int) Math.round(spawn.get("x").getAsDouble());
                    spawnZ = (int) Math.round(spawn.get("z").getAsDouble());
                    hasSpawn = true;
                }
            }
        }

        // Active count looked up on demand for the focused nation only (fetchNationResidentStats),
        // not here — parseNation runs from the nation index/search en masse. -1 = "not looked up".
        return new EarthMcNationData(name, uuid, discord, board, king, capital, founded, towns, residents, chunks,
                outlaws, allies, enemies, balance, isPublic, isOpen, isNeutral, hasSpawn, spawnX, spawnZ,
                nationBonusVal, -1);
    }

    private static String objectName(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonObject()) {
            return str(obj.getAsJsonObject(key), "name", "");
        }
        return "";
    }

    private static boolean bool(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsBoolean();
    }

    private static String str(JsonObject obj, String key, String fallback) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return fallback;
    }
}

package net.townymap.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches the access blocklist: UUIDs and nations barred from using the mod.
 *
 * <p><b>Fails open, always.</b> A network error, a malformed file or an unreachable host leaves the mod
 * fully working. The cost of wrongly locking out a legitimate user is far higher than the cost of a
 * blocked one getting through until their next launch — and this is a courtesy check, not security:
 * it runs on the user's own machine and anyone determined can edit it out of the jar.
 *
 * <p>The list is public by necessity, since the client must read it.
 */
public final class BlocklistClient {

    private static final Logger LOGGER = LogManager.getLogger("TownyMap");

    /** Raw file on the default branch, so it can be edited from the GitHub web UI with no release. */
    private static final String URL =
            "https://raw.githubusercontent.com/JR1258/EarthMC-Map-Addon/1.21.11/docs/blocklist.json";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Lower-cased UUIDs and nation names, plus the message shown to anyone matched. */
    public record Blocklist(Set<String> uuids, Set<String> nations, String message) {
        public boolean isEmpty() { return uuids.isEmpty() && nations.isEmpty(); }
    }

    public static final Blocklist EMPTY = new Blocklist(Set.of(), Set.of(), "");

    public CompletableFuture<Blocklist> fetch() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<String> r = http.send(
                        HttpRequest.newBuilder(URI.create(URL))
                                .timeout(Duration.ofSeconds(10))
                                .header("User-Agent", "JR1258/EarthMC-Map-Addon blocklist")
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return EMPTY;   // includes 404: no list published yet
                JsonElement el = JsonParser.parseString(r.body());
                if (!el.isJsonObject()) return EMPTY;
                JsonObject o = el.getAsJsonObject();
                Set<String> uuids = lower(o, "uuids");
                Set<String> nations = lower(o, "nations");
                String msg = o.has("message") && o.get("message").isJsonPrimitive()
                        ? o.get("message").getAsString() : "";
                if (!uuids.isEmpty() || !nations.isEmpty()) {
                    LOGGER.info("[TownyMap] Blocklist loaded: {} uuid(s), {} nation(s)",
                            uuids.size(), nations.size());
                }
                return new Blocklist(uuids, nations, msg);
            } catch (Exception e) {
                // Fail open. Never let a fetch problem lock anyone out.
                return EMPTY;
            }
        });
    }

    private static Set<String> lower(JsonObject o, String key) {
        Set<String> out = new HashSet<>();
        if (!o.has(key) || !o.get(key).isJsonArray()) return out;
        for (JsonElement e : o.getAsJsonArray(key)) {
            if (!e.isJsonPrimitive()) continue;
            String v = e.getAsString().trim().toLowerCase(Locale.ROOT).replace("-", "");
            if (!v.isBlank()) out.add(v);
        }
        return out;
    }
}

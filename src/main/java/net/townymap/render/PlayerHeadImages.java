package net.townymap.render;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Real player heads for players who are NOT in the client's own player list (i.e. offline / off-server). Their
 * skin can't come from the game, so the composited head PNG is fetched from mc-heads.net (the same service the
 * reference extension uses) and cached as a GUI texture. One head is fetched per player panel opened, on demand.
 */
public final class PlayerHeadImages {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    public static final int HEAD_PX = 32;
    private static final String URL = "https://mc-heads.net/avatar/%s/" + HEAD_PX + ".png";
    private static final long RETRY_MS = 60_000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private static final ConcurrentHashMap<String, Identifier> LOADED = new ConcurrentHashMap<>();
    private static final Set<String> INFLIGHT = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();

    private PlayerHeadImages() {}

    /** The cached head texture for {@code name}, or null while it loads (a fetch is kicked off on the first call). */
    public static Identifier get(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name.toLowerCase(Locale.ROOT);
        Identifier id = LOADED.get(key);
        if (id != null) return id;
        Long failed = FAILED_AT.get(key);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null;   // back off
        fetch(name, key);
        return null;
    }

    private static void fetch(String name, String key) {
        if (!INFLIGHT.add(key)) return;
        CompletableFuture.runAsync(() -> {
            byte[] bytes = null;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(URL, name)))
                        .timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "JR1258/EarthMC-Map-Addon heads")
                        .GET().build();
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() / 100 == 2 && resp.body().length > 0) bytes = resp.body();
            } catch (Exception e) {
                LOGGER.debug("[TownyMap] head fetch failed for {}: {}", name, e.toString());
            }
            final byte[] data = bytes;
            Minecraft mc = Minecraft.getInstance();
            Runnable apply = () -> {
                INFLIGHT.remove(key);
                if (data == null) { FAILED_AT.put(key, System.currentTimeMillis()); return; }
                try {
                    NativeImage img = NativeImage.read(data);   // texture upload must run on the render thread
                    Identifier id = Identifier.fromNamespaceAndPath("townymapaddon", "head/" + key.replaceAll("[^a-z0-9_]", "_"));
                    mc.getTextureManager().register(id, new DynamicTexture(() -> "head " + key, img));
                    LOADED.put(key, id);
                    FAILED_AT.remove(key);
                } catch (Exception e) {
                    FAILED_AT.put(key, System.currentTimeMillis());
                }
            };
            if (mc != null) mc.execute(apply); else INFLIGHT.remove(key);
        });
    }
}

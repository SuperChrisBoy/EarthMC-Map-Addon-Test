package net.townymap.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Optional integration with the EarthMC Ice Highways Map
 * (https://xilef2211.github.io/ice-highways-map/).
 *
 * Users drop the project's {@code highways.json} files (any number of them) into
 *   {@code <config>/townymapaddon/ice-highways/}
 * and the routes are drawn on Xaero's WorldMap as coloured polylines, with the
 * stations as labelled dots when zoomed in.
 *
 * JSON shape (only the parts we render):
 * <pre>{
 *   "stations": [ { "name": "...", "x": 0, "z": 0 }, ... ],
 *   "lines": { "Company": { "Line": {
 *       "color": "a020f0",
 *       "branches": { "BranchName": { "vertices": [[x,z],[x,z],...] } }
 *   } } }
 * }</pre>
 */
public final class IceHighwayManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final int DEFAULT_RGB = 0x55D6FF;          // icy cyan fallback
    private static final double STATION_MIN_SCALE = 0.04;     // dots appear when zoomed in
    private static final double STATION_LABEL_MIN_SCALE = 0.14;
    private static final int STATION_COLOR = 0xFFFFFFFF;
    private static final int STATION_OUTLINE = 0xFF101418;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TownyMap-IceHighways");
        t.setDaemon(true);
        return t;
    });

    private static volatile Data data = Data.EMPTY;
    private static volatile boolean loadScheduled = false;

    private IceHighwayManager() {}

    // ── Data model ────────────────────────────────────────────────────────────

    /** One polyline: parallel world-coordinate arrays + an ARGB colour. */
    private record Line(int[] x, int[] z, int argb, int minX, int maxX, int minZ, int maxZ) {}
    private record Station(String name, int x, int z) {}
    private record Data(List<Line> lines, List<Station> stations) {
        static final Data EMPTY = new Data(List.of(), List.of());
    }

    // ── Public API ──────────────────────────────────────────────────────────--

    public static Path folder() {
        return FabricLoader.getInstance().getConfigDir().resolve("townymapaddon").resolve("ice-highways");
    }

    public static boolean hasData() {
        Data d = data;
        return !d.lines().isEmpty() || !d.stations().isEmpty();
    }

    /** Creates the folder (with a README) if needed and opens it in the file browser. */
    public static void openFolder() {
        try {
            ensureFolder();
        } catch (IOException e) {
            LOGGER.warn("[TownyMap] Could not create ice-highways folder", e);
        }
        Util.getPlatform().openUri(folder().toUri());
    }

    /** Re-reads every {@code *.json} in the folder on a background thread. */
    public static void reload() {
        if (loadScheduled) return;
        loadScheduled = true;
        EXEC.execute(() -> {
            try {
                data = loadFromDisk();
                LOGGER.info("[TownyMap] Ice highways: {} lines, {} stations",
                        data.lines().size(), data.stations().size());
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Failed to load ice highways", e);
            } finally {
                loadScheduled = false;
            }
        });
    }

    // ── Loading / parsing ─────────────────────────────────────────────────────

    private static void ensureFolder() throws IOException {
        Path dir = folder();
        Files.createDirectories(dir);
        Path readme = dir.resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(readme,
                    "Drop Ice Highways Map JSON files here.\n\n"
                    + "Get them from https://github.com/xilef2211/ice-highways-map "
                    + "(e.g. aurora/highways.json) and place any number of *.json files in this folder, "
                    + "then use \"Reload Ice Highways\" in the mod settings.\n");
        }
    }

    private static Data loadFromDisk() throws IOException {
        Path dir = folder();
        if (!Files.isDirectory(dir)) {
            ensureFolder();
            return Data.EMPTY;
        }
        ArrayList<Line> lines = new ArrayList<>();
        ArrayList<Station> stations = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (!file.toString().toLowerCase().endsWith(".json")) continue;
                try {
                    parseFile(file, lines, stations);
                } catch (Exception e) {
                    LOGGER.warn("[TownyMap] Skipping malformed ice-highways file {}: {}",
                            file.getFileName(), e.getMessage());
                }
            }
        }
        return new Data(List.copyOf(lines), List.copyOf(stations));
    }

    private static void parseFile(Path file, List<Line> lines, List<Station> stations) throws IOException {
        JsonElement root = JsonParser.parseString(Files.readString(file));
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();

        // lines: { company: { line: { color, branches: { branch: { vertices } } } } }
        if (obj.has("lines") && obj.get("lines").isJsonObject()) {
            for (var company : obj.getAsJsonObject("lines").entrySet()) {
                if (!company.getValue().isJsonObject()) continue;
                for (var line : company.getValue().getAsJsonObject().entrySet()) {
                    if (!line.getValue().isJsonObject()) continue;
                    JsonObject ld = line.getValue().getAsJsonObject();
                    int argb = 0xFF000000 | parseColor(optString(ld, "color"));
                    if (!ld.has("branches") || !ld.get("branches").isJsonObject()) continue;
                    for (var branch : ld.getAsJsonObject("branches").entrySet()) {
                        if (!branch.getValue().isJsonObject()) continue;
                        JsonObject bd = branch.getValue().getAsJsonObject();
                        if (!bd.has("vertices") || !bd.get("vertices").isJsonArray()) continue;
                        Line parsed = parseVertices(bd.getAsJsonArray("vertices"), argb);
                        if (parsed != null) lines.add(parsed);
                    }
                }
            }
        }

        // stations: [ { name, x, z }, ... ]
        if (obj.has("stations") && obj.get("stations").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("stations")) {
                if (!el.isJsonObject()) continue;
                JsonObject st = el.getAsJsonObject();
                if (!st.has("x") || !st.has("z")) continue;
                stations.add(new Station(optString(st, "name"),
                        st.get("x").getAsInt(), st.get("z").getAsInt()));
            }
        }
    }

    private static Line parseVertices(JsonArray verts, int argb) {
        int n = verts.size();
        if (n < 2) return null;
        int[] x = new int[n], z = new int[n];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int m = 0;
        for (JsonElement el : verts) {
            if (!el.isJsonArray()) continue;
            JsonArray pair = el.getAsJsonArray();
            if (pair.size() < 2) continue;
            int vx = pair.get(0).getAsInt();
            int vz = pair.get(1).getAsInt();
            x[m] = vx; z[m] = vz; m++;
            if (vx < minX) minX = vx;
            if (vx > maxX) maxX = vx;
            if (vz < minZ) minZ = vz;
            if (vz > maxZ) maxZ = vz;
        }
        if (m < 2) return null;
        if (m < n) { x = java.util.Arrays.copyOf(x, m); z = java.util.Arrays.copyOf(z, m); }
        return new Line(x, z, argb, minX, maxX, minZ, maxZ);
    }

    private static int parseColor(String hex) {
        if (hex == null || hex.isBlank()) return DEFAULT_RGB;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        try {
            int rgb = (int) Long.parseLong(h, 16);
            return rgb & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return DEFAULT_RGB;
        }
    }

    private static String optString(JsonObject obj, String key) {
        return obj.has(key) && obj.get(key).isJsonPrimitive() ? obj.get(key).getAsString() : "";
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    public static void render(GuiGraphicsExtractor ctx, double cameraX, double cameraZ,
                              double blockScale, int sw, int sh) {
        Data d = data;
        if (blockScale <= 0 || d.lines().isEmpty() && d.stations().isEmpty()) return;

        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        for (Line line : d.lines()) {
            if (line.maxX() < worldLeft || line.minX() > worldRight
                    || line.maxZ() < worldTop || line.minZ() > worldBottom) continue;
            drawLine(ctx, line, cameraX, cameraZ, blockScale, sw, sh);
        }

        if (blockScale >= STATION_MIN_SCALE && !d.stations().isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            Font tr = mc == null ? null : mc.font;
            boolean labels = blockScale >= STATION_LABEL_MIN_SCALE && tr != null;
            for (Station s : d.stations()) {
                if (s.x() < worldLeft || s.x() > worldRight || s.z() < worldTop || s.z() > worldBottom) continue;
                int sx = sw / 2 + (int) Math.round((s.x() - cameraX) * blockScale);
                int sy = sh / 2 + (int) Math.round((s.z() - cameraZ) * blockScale);
                ctx.fill(sx - 2, sy - 2, sx + 2, sy + 2, STATION_OUTLINE);
                ctx.fill(sx - 1, sy - 1, sx + 1, sy + 1, STATION_COLOR);
                if (labels && !s.name().isBlank()) {
                    ctx.text(tr, s.name(), sx + 4, sy - 4, 0xFFBFE9FF, true);
                }
            }
        }
    }

    private static void drawLine(GuiGraphicsExtractor ctx, Line line, double cameraX, double cameraZ,
                                 double blockScale, int sw, int sh) {
        int[] wx = line.x(), wz = line.z();
        int prevX = sw / 2 + (int) Math.round((wx[0] - cameraX) * blockScale);
        int prevY = sh / 2 + (int) Math.round((wz[0] - cameraZ) * blockScale);
        int argb = line.argb();
        for (int i = 1; i < wx.length; i++) {
            int cx = sw / 2 + (int) Math.round((wx[i] - cameraX) * blockScale);
            int cy = sh / 2 + (int) Math.round((wz[i] - cameraZ) * blockScale);
            boolean offscreen = (prevX < 0 && cx < 0) || (prevX > sw && cx > sw)
                             || (prevY < 0 && cy < 0) || (prevY > sh && cy > sh);
            if (!offscreen) drawSegment(ctx, prevX, prevY, cx, cy, argb);
            prevX = cx;
            prevY = cy;
        }
    }

    private static void drawSegment(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2, int argb) {
        if (y1 == y2) {
            if (x1 != x2) ctx.fill(Math.min(x1, x2), y1, Math.max(x1, x2) + 1, y1 + 1, argb);
            return;
        }
        if (x1 == x2) {
            ctx.fill(x1, Math.min(y1, y2), x1 + 1, Math.max(y1, y2) + 1, argb);
            return;
        }
        double dx = x2 - x1, dy = y2 - y1;
        int length = (int) Math.ceil(Math.hypot(dx, dy));
        Matrix3x2fStack m = ctx.pose();
        m.pushMatrix();
        try {
            m.translate(x1 + 0.5f, y1 + 0.5f);
            m.rotate((float) Math.atan2(dy, dx));
            ctx.fill(0, 0, length + 1, 1, argb);
        } finally {
            m.popMatrix();
        }
    }
}

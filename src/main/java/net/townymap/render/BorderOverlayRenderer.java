package net.townymap.render;

import net.minecraft.client.gui.DrawContext;
import net.townymap.TownyMapConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the real-world country and state borders over the map, as vector polylines.
 *
 * <p>This used to bake the borders into raster tiles — a 26 MB prebuilt PNG atlas plus a runtime rasteriser,
 * texture cache and disk cache — and cap them two zoom levels below the map's maximum. Zooming past that cap
 * stretched a 0.25 px/block texture across the screen, which is what made borders look blurry up close.
 *
 * <p>Lines are geometry, so drawing them directly is sharper at every zoom (there is no fixed resolution to
 * outgrow, and no seams between tiles) and drops all of that machinery. The geometry itself is a compact
 * binary blob built by tools/EncodeBorders — see {@link #decode} for the format.
 */
final class BorderOverlayRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    /** Compact border geometry, produced from the source JSONs by tools/EncodeBorders at build time. */
    private static final String BORDERS_BIN = "/assets/townymapaddon/borders/borders.bin";
    private static final byte LAYER_COUNTRY = 1;
    private static final byte LAYER_STATE_ONLY = 2;
    /** Points closer together than this on screen add no visible detail, so they are folded away. */
    private static final double MIN_SEGMENT_PX = 1.0;

    private final TownyMapConfig config;
    // Populated off-thread at startup: decoding ~244k points takes a moment and must not stall the first frame.
    private volatile List<BorderLine> countryLines = List.of();
    private volatile List<BorderLine> stateOnlyLines = List.of();
    private volatile boolean loading = true;

    BorderOverlayRenderer(TownyMapConfig config) {
        this.config = config;
        Thread loader = new Thread(this::load, "TownyMap-BorderLoad");
        loader.setDaemon(true);
        loader.start();
    }

    /** True while the border geometry is still being decoded, so the toggle can show a loading hint. */
    boolean isLoading() {
        return loading;
    }

    void render(DrawContext ctx, double cameraX, double cameraZ, double blockScale,
                int sw, int sh, double worldLeft, double worldRight,
                double worldTop, double worldBottom) {
        int mode = config.borderOverlayMode;
        if (mode == 0 || blockScale <= 0) return;

        float thickness = Math.max(0.1f, Math.min(3.0f, config.borderThicknessMultiplier));
        // Widths are screen pixels now (they used to be tile pixels at a fixed tile resolution), so borders
        // keep the same visual weight at every zoom instead of thickening as a tile is stretched.
        float countryW = Math.max(1.0f, 2.4f * thickness);
        float stateW = Math.max(1.0f, 1.8f * thickness);

        // States first, so country outlines draw over them where the two run together — the old layer order.
        if (mode == 2) {
            drawLines(ctx, stateOnlyLines, cameraX, cameraZ, blockScale, sw, sh,
                    worldLeft, worldRight, worldTop, worldBottom, 0xEBFFFFFF, stateW);
        }
        drawLines(ctx, countryLines, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, 0xFFFFFFFF, countryW);
    }

    /**
     * Projects and draws one layer.
     *
     * <p>Two cheap filters carry the cost at world zoom, where every line is technically on screen: a
     * bounding-box reject per line, then screen-space decimation that folds away points landing on the pixel
     * just drawn. Detail therefore costs nothing until you are zoomed in far enough to actually see it.
     */
    private void drawLines(DrawContext ctx, List<BorderLine> lines,
                           double cameraX, double cameraZ, double blockScale, int sw, int sh,
                           double worldLeft, double worldRight, double worldTop, double worldBottom,
                           int color, float width) {
        final double minStepSq = MIN_SEGMENT_PX * MIN_SEGMENT_PX;
        final double halfW = sw / 2.0;
        final double halfH = sh / 2.0;

        for (BorderLine line : lines) {
            if (!line.intersects(worldLeft, worldRight, worldTop, worldBottom)) continue;

            double[] xs = line.x();
            double[] zs = line.z();
            double px = (xs[0] - cameraX) * blockScale + halfW;
            double py = (zs[0] - cameraZ) * blockScale + halfH;
            double tailX = px;
            double tailY = py;
            boolean pendingTail = false;   // last point was folded away; keep it if the line ends there

            for (int i = 1; i < xs.length; i++) {
                double cx = (xs[i] - cameraX) * blockScale + halfW;
                double cy = (zs[i] - cameraZ) * blockScale + halfH;
                double dx = cx - px;
                double dy = cy - py;
                if (dx * dx + dy * dy < minStepSq && i < xs.length - 1) {
                    tailX = cx;
                    tailY = cy;
                    pendingTail = true;
                    continue;
                }
                if (onScreen(px, py, cx, cy, sw, sh)) {
                    drawSegment(ctx, px, py, cx, cy, width, color);
                }
                px = cx;
                py = cy;
                pendingTail = false;
            }
            if (pendingTail && onScreen(px, py, tailX, tailY, sw, sh)) {
                drawSegment(ctx, px, py, tailX, tailY, width, color);
            }
        }
    }

    /** Cheap reject for a segment that cannot cross the viewport (both ends off the same edge). */
    private static boolean onScreen(double x1, double y1, double x2, double y2, int sw, int sh) {
        return !((x1 < 0 && x2 < 0) || (x1 > sw && x2 > sw)
                || (y1 < 0 && y2 < 0) || (y1 > sh && y2 > sh));
    }

    /** One segment as a rotated, centred quad — smooth at any angle, unlike an axis-aligned fill. */
    private static void drawSegment(DrawContext ctx, double x1, double y1, double x2, double y2,
                                    float width, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double length = Math.hypot(dx, dy);
        if (length < 0.01) return;
        org.joml.Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        try {
            m.translate((float) x1, (float) y1);
            m.rotate((float) Math.atan2(dy, dx));
            m.translate(0f, -width / 2f);          // centre the quad on the line
            m.scale((float) length, width);
            ctx.fill(0, 0, 1, 1, color);           // unit quad stretched to the exact sub-pixel size
        } finally {
            m.popMatrix();
        }
    }

    // ── Geometry loading ─────────────────────────────────────────────────────

    private void load() {
        deleteStaleTileCache();

        List<BorderLine> countries = new ArrayList<>();
        List<BorderLine> stateOnly = new ArrayList<>();
        try (InputStream stream = BorderOverlayRenderer.class.getResourceAsStream(BORDERS_BIN)) {
            if (stream == null) {
                LOGGER.warn("[TownyMap] Missing border resource {}", BORDERS_BIN);
            } else {
                decode(stream.readAllBytes(), countries, stateOnly);
                LOGGER.info("[TownyMap] Loaded {} country and {} state-only border lines",
                        countries.size(), stateOnly.size());
            }
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Failed to load border resource {}: {}", BORDERS_BIN, e.toString());
        }
        countryLines = List.copyOf(countries);
        stateOnlyLines = List.copyOf(stateOnly);
        loading = false;
    }

    /**
     * Reads the packed blob written by tools/EncodeBorders:
     * <pre>
     *   "TMBL", version, scale, lineCount,
     *   then per line: pointCount, layer byte, and pointCount (dx, dz) zigzag varints
     *   delta-encoded against the previous point.
     * </pre>
     * Each line carries its own layer, so the country/state split is already decided — the old JSON path
     * had to re-derive it on every startup by fingerprinting all 21k polylines.
     */
    private static void decode(byte[] data, List<BorderLine> countries, List<BorderLine> stateOnly) {
        if (data.length < 6 || data[0] != 'T' || data[1] != 'M' || data[2] != 'B' || data[3] != 'L') {
            throw new IllegalStateException("bad border blob header");
        }
        int version = data[4] & 0xFF;
        if (version != 1) throw new IllegalStateException("unsupported border blob version " + version);
        double scale = data[5] & 0xFF;

        int[] pos = {6};
        int lineCount = (int) readVarInt(data, pos);
        for (int l = 0; l < lineCount; l++) {
            int n = (int) readVarInt(data, pos);
            int layer = data[pos[0]++] & 0xFF;
            double[] x = new double[n];
            double[] z = new double[n];
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
            long px = 0, pz = 0;
            for (int i = 0; i < n; i++) {
                px += readVarInt(data, pos);
                pz += readVarInt(data, pos);
                x[i] = px / scale;
                z[i] = pz / scale;
                if (x[i] < minX) minX = x[i];
                if (x[i] > maxX) maxX = x[i];
                if (z[i] < minZ) minZ = z[i];
                if (z[i] > maxZ) maxZ = z[i];
            }
            BorderLine line = new BorderLine(x, z, minX, maxX, minZ, maxZ);
            if (layer == LAYER_COUNTRY) countries.add(line);
            else if (layer == LAYER_STATE_ONLY) stateOnly.add(line);
        }
    }

    /**
     * Removes the border tile cache the old raster renderer wrote into the config folder.
     *
     * <p>That renderer cached every rasterised tile to disk, which grew to ~100 MB for an active player. The
     * vector path never reads or writes it, so on first run after updating it is pure dead weight — this is
     * the mod's own generated cache, safe to drop and never regenerated.
     */
    private static void deleteStaleTileCache() {
        try {
            java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("townymapaddon").resolve("border-cache");
            if (!java.nio.file.Files.isDirectory(dir)) return;

            long[] stats = new long[2];   // {files, bytes}
            try (var walk = java.nio.file.Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        if (java.nio.file.Files.isRegularFile(p)) {
                            stats[0]++;
                            stats[1] += java.nio.file.Files.size(p);
                        }
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (Exception ignored) { /* leave anything we can't remove */ }
                });
            }
            if (stats[0] > 0) {
                LOGGER.info("[TownyMap] Removed {} stale border tile cache files ({} MB) — "
                        + "borders are drawn as vectors now", stats[0], stats[1] / (1024 * 1024));
            }
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Could not clean the old border cache: {}", e.toString());
        }
    }

    private static long readVarInt(byte[] data, int[] pos) {
        long raw = 0;
        int shift = 0;
        while (true) {
            int b = data[pos[0]++] & 0xFF;
            raw |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return (raw >>> 1) ^ -(raw & 1);   // undo zigzag
    }

    private record BorderLine(double[] x, double[] z,
                              double minX, double maxX, double minZ, double maxZ) {
        boolean intersects(double left, double right, double top, double bottom) {
            return maxX >= left && minX <= right && maxZ >= top && minZ <= bottom;
        }
    }
}

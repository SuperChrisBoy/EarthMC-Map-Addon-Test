package net.townymap.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.townymap.TownyMapConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.townymap.TownyMapMod;
import net.townymap.api.SquaremapApiClient;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
import net.townymap.model.OptimisticClaimChunk;
import net.townymap.model.PlayerMarker;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;
import java.util.Arrays;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

/**
 * Draws Towny town borders and online-player dots over Xaero's WorldMap.
 *
 * screenX = sw/2 + (worldX - cameraX) * scale
 * screenY = sh/2 + (worldZ - cameraZ) * scale
 * where scale = GUI pixels per world block.
 */
public class WorldMapRenderer {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("TownyMapAddon");
    private static final int DOT_HALF = 2;
    private static final double TOWN_FILL_MIN_SCALE = 0.035;
    private static final double MIN_TOWN_SCREEN_PIXELS = 0.0;
    private static final int CHUNK_SIZE = 16;
    private static final int HOVER_CHUNK_FILL = 0x22FFFFFF;
    private static final int HOVER_CHUNK_BORDER = 0xD8FFFFFF;
    private static final double MIN_CHUNK_GRID_SPACING = 4.0;
    private static final int TOWN_INDEX_CELL_SIZE = 2048;
    private static final long STATUS_RGB_CYCLE_MS = 5000L;
    private static final int TINY_TOWN_SCREEN_PIXELS = 2;
    // Interior fills (renderCachedFill draws EVERY merged chunk-rect of EVERY town) are by far the most
    // expensive part — at the zoom where fills switch on but many towns are visible it's ~150ms/frame.
    // So only fill when few enough towns are on screen that it's cheap, i.e. when actually zoomed in.
    private static final int FILL_MAX_TOWNS = 120;

    // ── Outline level-of-detail ───────────────────────────────────────────────
    // Each ring stores its outline at several resolutions, built by snapping the
    // (chunk-aligned) vertices to a coarser grid and merging the result.  Coarser
    // grids drop small features → fewer connected line segments when zoomed out.
    //
    // LOD_GRID[k] = snap grid in blocks for level k.  Level 0 is the raw outline
    // (Towny data is already on the 16-block chunk grid, so grid 16 == raw).
    // LOD_MIN_SCALE[k] = use level k while blockScale (px/block) ≥ this value.
    //   Chosen so the snap grid maps to roughly 2–4 px on screen at each level,
    //   i.e. features smaller than a few pixels are removed.
    // 384 is the coarsest level — the old 768 step was dropped because at full zoom-out it read worse
    // (coarse snap + heaviest tile downscale) than the level before it. Full zoom-out now caps at 384.
    private static final int[]    LOD_GRID      = { 16,   32,    80,    192,   384  };
    private static final double[] LOD_MIN_SCALE = { 0.125, 0.05, 0.022, 0.012, 0.0  };

    private final TownyMapConfig     config;
    private final SquaremapApiClient api;
    private final SquaremapTileRenderer squaremapTiles;
    private final BorderOverlayRenderer borderOverlay;
    private final ExecutorService townCacheExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TownyMap-TownRenderCache");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean townCacheBuildRunning = new AtomicBoolean(false);
    private final List<RenderTown> visibleTownScratch = new ArrayList<>(256);
    private final Set<RenderTown> visibleTownSeen = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RenderTown> detailQuerySeen = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile TownRenderCache townRenderCache = TownRenderCache.empty();
    private volatile List<TownData> townCacheRequestedSource = List.of();
    private final Set<String> favoriteTownKeys = new HashSet<>();
    private int favoriteTownCount = -1;
    private int favoritesVersion = 0;   // bumped when the favourite set changes (tiles bake favourites in)

    // ── Cached town-outline tiles ─────────────────────────────────────────────
    // Outlines rasterise once (off-thread) into world-anchored tile textures, then just blit — the same
    // technique BorderOverlayRenderer uses.
    private static final int OUTLINE_TILE_PIXELS = 256;
    // The DP+AA tile path owns the outlines whenever one BLOCK is at most this many PHYSICAL pixels on
    // screen (a chunk ≤ 32 phys px) — i.e. all of mid-zoom out to the full overview, with NO town-count
    // gate. That matches Leaflet exactly: the website simplify+AA-renders every polygon at every zoom,
    // and its shapes only look rectilinear close-in because the chunk steps exceed the simplify tolerance
    // there. Zoomed in past this, the crisp 1:1 direct renderer takes over (the website is fully
    // rectilinear at those zooms as well).
    private static final double OUTLINE_TILE_MAX_PPB = 2.0;
    private static final int MAX_OUTLINE_TILES = 320;
    private static final int MAX_OUTLINE_TILE_LOADS = 48;              // bake more in parallel → sharp fast
    private static final int MAX_OUTLINE_TILE_UPLOADS_PER_FRAME = 24;  // and upload them fast on settle
    // The browser renders its canvas at devicePixelRatio (PHYSICAL pixels), so we rasterise at up to this
    // multiple of the GUI scale (the window scale factor, capped — 2× already matches the site's sharpness
    // at 4× the tile count; uncapped retina scales would explode tile counts for invisible gains).
    private static final double OUTLINE_TILE_MAX_DENSITY = 2.0;
    // Settle time before a new zoom bucket rasterises (Leaflet redraws its canvas on zoom END, scaling
    // the old picture during the gesture).
    private static final long OUTLINE_ZOOM_SETTLE_MS = 55;   // bake the new bucket almost immediately once
                                                             // the zoom stops → sharp tiles appear near-instantly
    // Whole-map overview level: coarse enough that a couple dozen tiles cover ALL of EarthMC (at this
    // ppb one tile spans ~12.8k blocks), baked once and never evicted. It is drawn ONLY as a last-resort
    // floor — when neither this zoom nor the previous one has a single cached tile for the view — so the
    // outlines can never fully disappear mid-zoom, yet it never double-draws under the finer levels
    // (which would thicken/halo the lines you just approved).
    private static final double OUTLINE_OVERVIEW_PPB = 0.02;
    private int pendingOutlineZoom = Integer.MIN_VALUE;
    private long pendingOutlineZoomSinceMs;
    // Leaflet's smoothFactor (1.0), in GUI px — scaled by density into tile px at use. Verified against
    // Leaflet 1.9.4 source: simplify = radial vertex reduction THEN Douglas-Peucker, both with sqTolerance.
    // The tolerance is the same world-space distance for every tile of a zoom level, so neighbouring tiles
    // — and adjacent towns sharing a border edge — simplify identically and stay flush.
    private static final double OUTLINE_SIMPLIFY_TOLERANCE_PX = 1.0;
    // The site's exact town-polygon paint (read from map.earthmc.net markers.json — every one of its
    // ~5100 polygons ships weight:2, opacity:0.3, and Leaflet's default fillOpacity 0.2): a WIDE
    // translucent round-joined stroke over a soft fill. That style — not the geometry — is most of the
    // "smooth, flowing, never-intersecting" look: the fat 30%-alpha stroke visually rounds the chunk
    // staircase and adjacent towns' strokes overlap into one soft shared edge.
    private static final double SITE_STROKE_WEIGHT_GUI = 2.0;   // CSS px on the site == GUI px here
    private static final int SITE_STROKE_ALPHA = 165;          // ~0.65: crisper/more defined than the
                                                               // site's faint 0.3, which read fuzzy in-game
    private static final int SITE_FILL_ALPHA = 64;              // ~0.25 — a touch over the site's 0.2 so
                                                                // the nation fill colour actually reads
    // Overview tiles are few but expensive (thousands of towns each), so parallelise their rasterisation
    // to shorten the load when zooming into a new coarse bucket — capped so it never starves the render
    // thread / game tick.
    private static final int OUTLINE_TILE_THREADS =
            Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
    private final ExecutorService outlineTileExecutor = Executors.newFixedThreadPool(OUTLINE_TILE_THREADS, r -> {
        Thread t = new Thread(r, "TownyMap-OutlineTiles");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);   // yield to the render thread
        return t;
    });
    private final LinkedHashMap<Long, Identifier> outlineTiles = new LinkedHashMap<>(64, 0.75f, true);
    private final Set<Long> outlineTilesLoading = ConcurrentHashMap.newKeySet();
    private final Set<Long> outlineTilesEmpty = ConcurrentHashMap.newKeySet();
    private final Queue<LoadedOutlineTile> outlineTilesCompleted = new ConcurrentLinkedQueue<>();
    private TownRenderCache outlineTilesSnapshot;   // which snapshot the current tiles were built for
    private boolean outlineTilesSmooth = true;     // outline STYLE baked into the current tiles
    private int outlineTilesFavVersion = -1;        // favourites baked into the current tiles
    private int outlineTilesStatusVersion = -1;     // status-highlight exclusion baked into the current tiles

    // Towns the active status mode highlights. They are EXCLUDED from the tile bake and drawn live in the
    // animated status colour instead, so the highlight is a single flush line (no baked line under it).
    // statusVersion bumps when this set changes → tiles rebuild, exactly like favouritesVersion.
    private Set<String> statusHighlightKeys = Set.of();
    private int statusVersion = 0;
    private int lastStatusMode = -1;
    private int lastStatusDetailsSize = -1;
    private int lastStatusFavVersion = -1;
    private int lastReadyOutlineZoom = Integer.MIN_VALUE;   // last zoom whose tiles fully covered the view

    public WorldMapRenderer(TownyMapConfig config, SquaremapApiClient api) {
        this.config = config;
        this.api    = api;
        this.squaremapTiles = new SquaremapTileRenderer(config);
        this.borderOverlay = new BorderOverlayRenderer(config);
    }

    public void invalidateTownCaches() {
        visibleTownScratch.clear();
        visibleTownSeen.clear();
        townRenderCache = TownRenderCache.empty();
        townCacheRequestedSource = List.of();
    }

    public void render(GuiGraphicsExtractor ctx,
                       double cameraX, double cameraZ, double blockScale,
                       int sw, int sh,
                       Map<String, TownPopupData> townDetails,
                       Map<String, EarthMcPlayerData> playerDetails,
                       Map<String, EarthMcNationData> nationDetails) {
        if (blockScale <= 0) return;
        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;
        List<RenderTown> visibleTowns = visibleTowns(blockScale, worldLeft, worldRight, worldTop, worldBottom);
        refreshFavoriteTownKeys();

        borderOverlay.render(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom);

        renderChunkGrid(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom);
        if (config.townsEnabled) {
            renderTowns(ctx, cameraX, cameraZ, blockScale, sw, sh, visibleTowns, townDetails);
        }
        // Optimistic claim chunks are NOT drawn here - see renderPlayersLayer. They are ctx.fill quads,
        // which the textured outline tiles paint over inside one batch, so drawing them in this pass made
        // freshly claimed chunks invisible behind the tiles.
        renderNationCapitalStars(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, nationDetails);
        // Player dots are NOT drawn here: the town outline tiles are textured quads and, within a single
        // GuiGraphicsExtractor batch, textured draws paint over the colored ctx.fill dots — so tiles would hide (and,
        // as they async-rebuild, "blink") the dots at zoom-out. They're drawn in renderPreDropdown instead
        // (see MixinGuiMap#renderWorldMapPlayers), after the tile batch is flushed, so they always sit on top.
    }

    /** Player dots, drawn in the renderPreDropdown pass (after the tile batch is flushed) so they render
     *  above the textured town-outline tiles instead of being covered by them. */
    public void renderPlayersLayer(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale,
                                   int sw, int sh, Map<String, EarthMcPlayerData> playerDetails) {
        if (config == null || blockScale <= 0) return;

        // Freshly claimed chunks are drawn in THIS pass, not in render(), for the same reason as the
        // player dots: they are ctx.fill quads and the town outline tiles are textured draws, which paint
        // over ctx.fill within a single DrawContext batch. Drawn in render() they ended up hidden behind
        // the tiles. By here the tile batch has been flushed, so they sit on top as they used to.
        if (config.townsEnabled) {
            renderOptimisticClaimChunks(ctx, cameraX, cameraZ, blockScale, sw, sh,
                    cameraX - sw / 2.0 / blockScale, cameraX + sw / 2.0 / blockScale,
                    cameraZ - sh / 2.0 / blockScale, cameraZ + sh / 2.0 / blockScale);
        }

        if (!config.playersEnabled) return;
        renderPlayers(ctx, cameraX, cameraZ, blockScale, sw, sh, playerDetails);
    }

    public void renderSquaremapBackground(GuiGraphicsExtractor ctx,
                                          double cameraX, double cameraZ, double blockScale,
                                          int sw, int sh, boolean moving) {
        if (!config.squaremapBackgroundEnabled || blockScale <= 0) return;
        ctx.fill(0, 0, sw, sh, 0xFF101418);

        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        squaremapTiles.render(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, moving);
        // Baseline dim (0x38) plus the optional user "Darken Map" level (0-3), so the squaremap can be
        // pushed darker to make the town overlay stand out.
        int darkAlpha = Math.min(0xFF, 0x38 + config.squaremapDarken * 0x2A);
        ctx.fill(0, 0, sw, sh, darkAlpha << 24);
    }

    public void renderSquaremapViewport(GuiGraphicsExtractor ctx,
                                        double cameraX, double cameraZ, double blockScale,
                                        int sw, int sh, boolean moving) {
        if (!config.squaremapBackgroundEnabled || blockScale <= 0 || sw <= 0 || sh <= 0) return;

        double worldLeft = cameraX - sw / 2.0 / blockScale;
        double worldRight = cameraX + sw / 2.0 / blockScale;
        double worldTop = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        squaremapTiles.render(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, moving);
    }

    public void renderSquaremapMinimapViewport(GuiGraphicsExtractor ctx,
                                               double cameraX, double cameraZ, double blockScale,
                                               int sw, int sh, boolean moving) {
        renderSquaremapMinimapViewport(ctx, cameraX, cameraZ, blockScale, sw, sh, moving, 0.0);
    }

    public void renderSquaremapMinimapViewport(GuiGraphicsExtractor ctx,
                                               double cameraX, double cameraZ, double blockScale,
                                               int sw, int sh, boolean moving, double circularClipRadius) {
        if (!config.squaremapBackgroundEnabled || blockScale <= 0 || sw <= 0 || sh <= 0) return;

        double worldLeft = cameraX - sw / 2.0 / blockScale;
        double worldRight = cameraX + sw / 2.0 / blockScale;
        double worldTop = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        squaremapTiles.renderMinimap(ctx, cameraX, cameraZ, blockScale, sw, sh,
                worldLeft, worldRight, worldTop, worldBottom, moving, circularClipRadius);
    }

    public boolean isSquaremapLoading() {
        return squaremapTiles.isLoading();
    }

    public boolean isBorderLoading() {
        return borderOverlay.isLoading();
    }

    public void renderHoveredChunk(GuiGraphicsExtractor ctx,
                                   double cameraX, double cameraZ, double blockScale,
                                   int sw, int sh,
                                   double mouseWorldX, double mouseWorldZ) {
        if (!config.squaremapBackgroundEnabled || blockScale <= 0) return;
        double spacing = CHUNK_SIZE * blockScale;
        if (spacing < 3.0) return;

        int chunkX = floorToChunk(mouseWorldX);
        int chunkZ = floorToChunk(mouseWorldZ);
        int x1 = toScreenX(chunkX * CHUNK_SIZE, cameraX, blockScale, sw);
        int y1 = toScreenY(chunkZ * CHUNK_SIZE, cameraZ, blockScale, sh);
        int x2 = toScreenX((chunkX + 1) * CHUNK_SIZE, cameraX, blockScale, sw);
        int y2 = toScreenY((chunkZ + 1) * CHUNK_SIZE, cameraZ, blockScale, sh);

        int left = Math.min(x1, x2);
        int right = Math.max(x1, x2);
        int top = Math.min(y1, y2);
        int bottom = Math.max(y1, y2);
        if (right < 0 || left > sw || bottom < 0 || top > sh) return;

        left = Math.max(0, left);
        right = Math.min(sw, right);
        top = Math.max(0, top);
        bottom = Math.min(sh, bottom);
        if (right - left < 2 || bottom - top < 2) return;

        ctx.fill(left, top, right, bottom, HOVER_CHUNK_FILL);
        ctx.fill(left, top, right, top + 1, HOVER_CHUNK_BORDER);
        ctx.fill(left, bottom - 1, right, bottom, HOVER_CHUNK_BORDER);
        ctx.fill(left, top, left + 1, bottom, HOVER_CHUNK_BORDER);
        ctx.fill(right - 1, top, right, bottom, HOVER_CHUNK_BORDER);
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    private static int toScreenX(double worldX, double camX, double scale, int sw) {
        return sw / 2 + (int) Math.round((worldX - camX) * scale);
    }

    private static int toScreenY(double worldZ, double camZ, double scale, int sh) {
        return sh / 2 + (int) Math.round((worldZ - camZ) * scale);
    }

    // ── Town rendering ───────────────────────────────────────────────────────

    private void renderChunkGrid(GuiGraphicsExtractor ctx,
                                 double cameraX, double cameraZ, double blockScale,
                                 int sw, int sh,
                                 double worldLeft, double worldRight,
                                 double worldTop, double worldBottom) {
        if (!config.chunkGridEnabled) return;
        double spacing = CHUNK_SIZE * blockScale;
        if (spacing < MIN_CHUNK_GRID_SPACING) return;

        int minChunkX = floorToChunk(worldLeft) - 1;
        int maxChunkX = floorToChunk(worldRight) + 1;
        int minChunkZ = floorToChunk(worldTop) - 1;
        int maxChunkZ = floorToChunk(worldBottom) + 1;
        int color = 0xCC000000;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            int x = toScreenX(chunkX * CHUNK_SIZE, cameraX, blockScale, sw);
            if (x >= 0 && x < sw) ctx.fill(x, 0, x + 1, sh, color);
        }
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            int y = toScreenY(chunkZ * CHUNK_SIZE, cameraZ, blockScale, sh);
            if (y >= 0 && y < sh) ctx.fill(0, y, sw, y + 1, color);
        }
    }

    private static int floorToChunk(double worldCoord) {
        return (int) Math.floor(worldCoord / CHUNK_SIZE);
    }

    private void renderOptimisticClaimChunks(GuiGraphicsExtractor ctx,
                                             double cameraX, double cameraZ, double blockScale,
                                             int sw, int sh,
                                             double worldLeft, double worldRight,
                                             double worldTop, double worldBottom) {
        for (OptimisticClaimChunk chunk : TownyMapMod.optimisticClaimChunks()) {
            int blockX = chunk.blockX();
            int blockZ = chunk.blockZ();
            if (blockX + CHUNK_SIZE < worldLeft || blockX > worldRight
                    || blockZ + CHUNK_SIZE < worldTop || blockZ > worldBottom) continue;

            int x1 = toScreenX(blockX, cameraX, blockScale, sw);
            int y1 = toScreenY(blockZ, cameraZ, blockScale, sh);
            int x2 = toScreenX(blockX + CHUNK_SIZE, cameraX, blockScale, sw);
            int y2 = toScreenY(blockZ + CHUNK_SIZE, cameraZ, blockScale, sh);

            ctx.fill(Math.min(x1, x2), Math.min(y1, y2),
                    Math.max(x1, x2), Math.max(y1, y2), chunk.fillColor());
            int left = Math.min(x1, x2);
            int right = Math.max(x1, x2);
            int top = Math.min(y1, y2);
            int bottom = Math.max(y1, y2);
            ctx.fill(left, top, right, top + 1, chunk.outlineColor());
            ctx.fill(left, bottom - 1, right, bottom, chunk.outlineColor());
            ctx.fill(left, top, left + 1, bottom, chunk.outlineColor());
            ctx.fill(right - 1, top, right, bottom, chunk.outlineColor());
        }
    }

    private void renderTowns(GuiGraphicsExtractor ctx,
                             double cameraX, double cameraZ, double blockScale,
                             int sw, int sh,
                             List<RenderTown> visibleTowns,
                             Map<String, TownPopupData> townDetails) {
        double worldLeft   = cameraX - sw / 2.0 / blockScale;
        double worldRight  = cameraX + sw / 2.0 / blockScale;
        double worldTop    = cameraZ - sh / 2.0 / blockScale;
        double worldBottom = cameraZ + sh / 2.0 / blockScale;

        refreshStatusHighlightKeys(townDetails);

        // ── Outline strategy: exactly squaremap's ────────────────────────────────
        // The smooth (DP + anti-aliased) tile path owns the outlines at every zoom where a chunk is small
        // on screen — gated by ZOOM, not town count, because Leaflet canvas-renders every polygon at every
        // zoom. The tiles bake every town's own border colour EXCEPT the status-highlighted subset, which
        // is excluded from the bake and drawn live in the animated status colour (it IS the border, with
        // no baked line under it to drift against). renderTownOutlineTiles returns false until every
        // visible tile is ready, in which case we fall through to direct rendering so the map is never
        // left blank.
        double density = tileRenderDensity();
        // "Smooth Town Outlines" off routes everything through the direct path below, which draws the
        // raw chunk-aligned rings - the original blocky border style, kept for people who prefer it.
        // BOTH styles go through the tiles: measured on live EarthMC data the direct path peaks at
        // ~13.9k draw calls per frame around blockScale 0.05, while a tiled screen is ~20 blits whatever
        // the zoom. The tile bake rasterises rectilinear rings instead of simplified ones when the blocky
        // style is selected, so the look is preserved and the per-frame cost is not.
        boolean useTiles = blockScale * density <= OUTLINE_TILE_MAX_PPB;
        boolean fillsEligible = visibleTowns.size() <= FILL_MAX_TOWNS && blockScale >= TOWN_FILL_MIN_SCALE;

        // Tiles bake fill AND stroke on the same simplified path (Leaflet's _fillStroke), so nothing
        // else needs to draw when they're ready.
        if (useTiles && renderTownOutlineTiles(ctx, cameraX, cameraZ, blockScale, sw, sh,
                                               worldLeft, worldRight, worldTop, worldBottom)) {
            if (config.townStatusOverlayMode > 0) {
                renderStatusHighlightsLive(ctx, visibleTowns, cameraX, cameraZ, blockScale, sw, sh);
            }
            return;
        }

        int statusRgb = statusHighlightRgb();
        int fillColor0 = fillsEligible ? 0xFF : 0;
        boolean overlayOn = config.townStatusOverlayMode > 0;
        boolean haveFavorites = !favoriteTownKeys.isEmpty();

        // Opacity depends on which outline style is active, because the two modes have different
        // constraints:
        //  - Smooth: this path draws only at close zoom and the baked tiles take over further out, so it
        //    must use the tiles' own alphas or towns visibly change brightness across that boundary.
        //  - Blocky: there are no tiles at all, so nothing to match. Use the user's Border/Fill Opacity
        //    settings, which is what 1.3.1 did - forcing the tile alphas here (stroke 220 -> 165,
        //    fill 35 -> 64) is what made "blocky" look washed out rather than like the old style.
        int strokeAlpha = config.smoothTownOutlines ? SITE_STROKE_ALPHA : (config.borderAlpha & 0xFF);
        int interiorAlpha = config.smoothTownOutlines ? SITE_FILL_ALPHA : (config.fillAlpha & 0xFF);
        for (RenderTown town : visibleTowns) {
            // town.key() is already lower-cased, so no per-town String allocation here.
            boolean favorite = haveFavorites && favoriteTownKeys.contains(town.key());
            boolean statusHighlighted = overlayOn && isStatusHighlighted(town, townDetails);
            // One colour encodes everything → one outline pass (favourite > status > the town's own colour).
            // Use the SAME opacities as the baked tiles (SITE_*), not borderAlpha/fillAlpha. They used to
            // differ — stroke 220 vs 165, fill 35 vs 64 — so crossing the tile/direct zoom boundary made
            // towns visibly change brightness, most obvious on the bright gold favourites.
            // Favourites differ from other towns only in COLOUR, never in opacity.
            int outlineColor = favorite ? ((strokeAlpha << 24) | 0xFFE066)
                    : statusHighlighted ? (0xFF000000 | statusRgb)
                    : ((strokeAlpha << 24) | (town.data().rgbColor() & 0xFFFFFF));
            // Interior uses the town's FILL colour (nation fill), not the outline colour — except
            // favourites, which stay gold inside as well as out.
            int fillColor = fillColor0 == 0 ? 0
                    : favorite ? ((interiorAlpha << 24) | 0xFFE066)
                    : ((interiorAlpha << 24) | (town.data().fillRgbColor() & 0xFFFFFF));

            for (RingGeometry ring : town.rings()) {
                renderRing(ctx, ring, 0, outlineColor, fillColor, cameraX, cameraZ, blockScale, sw, sh);
            }
        }
    }

    private boolean isStatusHighlighted(RenderTown town, Map<String, TownPopupData> townDetails) {
        TownPopupData details = townDetails.get(town.key());
        if (details == null) return false;
        return switch (config.townStatusOverlayMode) {
            case 1 -> details.canOutsidersSpawn();
            case 2 -> details.isOverClaimed();
            case 3 -> details.isOpen();
            case 4 -> details.isForSale();
            case 5 -> !details.hasNation();
            default -> false;
        };
    }

    // Recomputes the set of towns the status mode highlights, which the tile bake EXCLUDES (they're drawn
    // live instead). Cheap: only re-scans when the mode, the loaded-detail count, or the favourite set
    // actually changes, and only bumps statusVersion (forcing a tile rebuild) when the set really differs.
    private void refreshStatusHighlightKeys(Map<String, TownPopupData> townDetails) {
        int mode = config.townStatusOverlayMode;
        int detailsSize = townDetails.size();
        if (mode == lastStatusMode && detailsSize == lastStatusDetailsSize
                && favoritesVersion == lastStatusFavVersion) {
            return;
        }
        lastStatusMode = mode;
        lastStatusDetailsSize = detailsSize;
        lastStatusFavVersion = favoritesVersion;

        Set<String> next;
        if (mode == 0) {
            next = Set.of();
        } else {
            next = new HashSet<>();
            for (RenderTown town : townRenderCache.allTowns()) {
                if (favoriteTownKeys.contains(town.key())) continue;   // favourites stay baked as gold
                if (isStatusHighlighted(town, townDetails)) next.add(town.key());
            }
        }
        if (!next.equals(statusHighlightKeys)) {
            statusHighlightKeys = next.isEmpty() ? Set.of() : Set.copyOf(next);
            statusVersion++;
        }
    }

    // The status-highlighted subset is excluded from the tile bake, so draw it here live in the animated
    // status colour — one outline, flush on the border, with no baked line beneath it to drift against.
    private void renderStatusHighlightsLive(GuiGraphicsExtractor ctx, List<RenderTown> visibleTowns,
                                            double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        if (statusHighlightKeys.isEmpty()) return;
        int statusColor = 0xFF000000 | statusHighlightRgb();
        for (RenderTown town : visibleTowns) {
            if (!statusHighlightKeys.contains(town.key())) continue;
            for (RingGeometry ring : town.rings()) {
                renderRing(ctx, ring, 0, statusColor, 0, cameraX, cameraZ, blockScale, sw, sh);
            }
        }
    }

    // ── Cached town-outline tiles ─────────────────────────────────────────────

    private boolean renderTownOutlineTiles(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale,
                                           int sw, int sh, double worldLeft, double worldRight,
                                           double worldTop, double worldBottom) {
        TownRenderCache snapshot = townRenderCache;
        if (snapshot != outlineTilesSnapshot || favoritesVersion != outlineTilesFavVersion
                || statusVersion != outlineTilesStatusVersion
                || config.smoothTownOutlines != outlineTilesSmooth) {
            clearOutlineTiles();              // town data, favourites, or status-exclusion changed → rebuild
            outlineTilesSmooth = config.smoothTownOutlines;
            outlineTilesSnapshot = snapshot;
            outlineTilesFavVersion = favoritesVersion;
            outlineTilesStatusVersion = statusVersion;
        }
        if (snapshot.allTowns().isEmpty()) return true;   // nothing to draw, but still "handled" (no direct)
        processOutlineTileUploads();
        Set<String> favSnapshot = favoriteTownKeys.isEmpty() ? Set.of() : Set.copyOf(favoriteTownKeys);
        Set<String> statusSnapshot = statusHighlightKeys;   // already immutable (Set.of() / Set.copyOf)

        // Rasterise at PHYSICAL pixel density (like the browser's devicePixelRatio canvas): the zoom
        // bucket is chosen from blockScale × GUI scale, so ppb below is physical px per block and the
        // blit lands 1:1 with the framebuffer instead of being magnified (the old fuzz).
        double density = tileRenderDensity();
        int zoom = chooseOutlineTileZoom(blockScale * density);
        double ppb = outlineTilePixelsPerBlock(zoom);
        double tileWorldSize = OUTLINE_TILE_PIXELS / ppb;

        int minTx = (int) Math.floor(worldLeft / tileWorldSize);
        int maxTx = (int) Math.floor(worldRight / tileWorldSize);
        int minTy = (int) Math.floor(worldTop / tileWorldSize);
        int maxTy = (int) Math.floor(worldBottom / tileWorldSize);

        // Leaflet redraws its canvas on zoom END, scaling the old picture during the gesture. While the
        // zoom bucket is still settling, show a coarser cached level and DON'T bake the new bucket yet
        // (avoids the rebuild flood).
        if (zoom != lastReadyOutlineZoom) {
            long now = System.currentTimeMillis();
            if (zoom != pendingOutlineZoom) {
                pendingOutlineZoom = zoom;
                pendingOutlineZoomSinceMs = now;
            }
            if (now - pendingOutlineZoomSinceMs < OUTLINE_ZOOM_SETTLE_MS) {
                drawCoarserFallback(ctx, zoom, cameraX, cameraZ, blockScale, sw, sh,
                                    worldLeft, worldRight, worldTop, worldBottom,
                                    density, snapshot, favSnapshot, statusSnapshot);
                return true;
            }
        }

        // Pass 1 — take stock of THIS zoom without drawing yet, and request whatever's missing. Deciding
        // first is what removes the flicker: a coarser fallback must never be painted *underneath* this
        // zoom's tiles, or every line that exists in both layers double-draws (thicker + darker) and then
        // visibly thins the moment the fallback stops.
        boolean allReady = true;
        int readyCount = 0;
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                long key = outlineTileKey(zoom, tx, ty);
                if (outlineTiles.containsKey(key)) {
                    readyCount++;
                } else if (!outlineTilesEmpty.contains(key)) {
                    requestOutlineTile(key, zoom, tx, ty, tileWorldSize, ppb, density,
                                       snapshot, favSnapshot, statusSnapshot);
                    allReady = false;
                }
            }
        }

        // Pass 2 — draw exactly ONE layer. Either this zoom's tiles (never mixed with a coarser level), or,
        // if this zoom has nothing cached yet, the best coarser level so the outlines never vanish.
        if (readyCount > 0) {
            for (int ty = minTy; ty <= maxTy; ty++) {
                for (int tx = minTx; tx <= maxTx; tx++) {
                    Identifier tex = outlineTiles.get(outlineTileKey(zoom, tx, ty));
                    if (tex != null) {
                        blitOutlineTile(ctx, tex, tx, ty, tileWorldSize, cameraX, cameraZ, blockScale, sw, sh);
                    }
                }
            }
        } else {
            drawCoarserFallback(ctx, zoom, cameraX, cameraZ, blockScale, sw, sh,
                                worldLeft, worldRight, worldTop, worldBottom,
                                density, snapshot, favSnapshot, statusSnapshot);
        }
        if (allReady) lastReadyOutlineZoom = zoom;

        // ALWAYS handled in the tile range — the caller must never fall to the old rectilinear renderer.
        return true;
    }

    /** Single coarser layer for when this zoom has nothing cached: the last fully-ready level if it has
     *  anything, else the pinned whole-map overview floor. Only ever drawn INSTEAD of the current zoom's
     *  tiles, never under them. */
    private void drawCoarserFallback(GuiGraphicsExtractor ctx, int zoom, double cameraX, double cameraZ,
                                     double blockScale, int sw, int sh, double worldLeft, double worldRight,
                                     double worldTop, double worldBottom, double density,
                                     TownRenderCache snapshot, Set<String> favSnapshot,
                                     Set<String> statusSnapshot) {
        // Stand in with the last fully-ready level. (Picking the "nearest cached" level instead was tried
        // and reverted: which level is nearest changes as tiles bake and evict, so the stand-in hopped
        // between levels frame to frame and made every town flicker.)
        if (lastReadyOutlineZoom != Integer.MIN_VALUE && lastReadyOutlineZoom != zoom
                && blitOutlineTilesAtZoom(ctx, lastReadyOutlineZoom, cameraX, cameraZ, blockScale,
                                          sw, sh, worldLeft, worldRight, worldTop, worldBottom)) {
            return;
        }
        drewOverviewFloor(ctx, cameraX, cameraZ, blockScale, sw, sh,
                          worldLeft, worldRight, worldTop, worldBottom,
                          density, snapshot, favSnapshot, statusSnapshot);
    }

    /** Blits every visible tile at {@code zoom} (scaled to the current view) — but only if it has full
     *  coverage (every visible tile is loaded or known-empty), so we never show gaps. Returns false if
     *  any tile is still missing, so the caller can fall back. */
    private boolean blitOutlineTilesAtZoom(GuiGraphicsExtractor ctx, int zoom, double cameraX, double cameraZ,
                                           double blockScale, int sw, int sh, double worldLeft,
                                           double worldRight, double worldTop, double worldBottom) {
        double ppb = outlineTilePixelsPerBlock(zoom);
        double tileWorldSize = OUTLINE_TILE_PIXELS / ppb;
        int minTx = (int) Math.floor(worldLeft / tileWorldSize);
        int maxTx = (int) Math.floor(worldRight / tileWorldSize);
        int minTy = (int) Math.floor(worldTop / tileWorldSize);
        int maxTy = (int) Math.floor(worldBottom / tileWorldSize);
        // Draw whatever of this zoom IS cached — partial coverage beats nothing. (It used to bail unless
        // every visible tile was present, which zooming OUT can never satisfy: the new view exposes world
        // the old, more-zoomed-in level never covered — so the outlines vanished mid-zoom.)
        boolean drewAny = false;
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                Identifier tex = outlineTiles.get(outlineTileKey(zoom, tx, ty));
                if (tex != null) {
                    blitOutlineTile(ctx, tex, tx, ty, tileWorldSize, cameraX, cameraZ, blockScale, sw, sh);
                    drewAny = true;
                }
            }
        }
        return drewAny;
    }

    /** Zoom bucket of the pinned whole-map overview level. */
    private static int overviewOutlineZoom() {
        return (int) Math.round(Math.log(OUTLINE_OVERVIEW_PPB) / Math.log(OUTLINE_ZOOM_STEP));
    }

    /** Requests (once) and blits the pinned overview level for the visible area. Used only as the floor
     *  when no finer level had anything cached, so it never stacks under the fine tiles. */
    private void drewOverviewFloor(GuiGraphicsExtractor ctx, double cameraX, double cameraZ, double blockScale,
                                   int sw, int sh, double worldLeft, double worldRight,
                                   double worldTop, double worldBottom, double density,
                                   TownRenderCache snapshot, Set<String> favSnapshot,
                                   Set<String> statusSnapshot) {
        int ovZoom = overviewOutlineZoom();
        double ovPpb = outlineTilePixelsPerBlock(ovZoom);
        double ovTileWorld = OUTLINE_TILE_PIXELS / ovPpb;
        int minTx = (int) Math.floor(worldLeft / ovTileWorld);
        int maxTx = (int) Math.floor(worldRight / ovTileWorld);
        int minTy = (int) Math.floor(worldTop / ovTileWorld);
        int maxTy = (int) Math.floor(worldBottom / ovTileWorld);
        for (int ty = minTy; ty <= maxTy; ty++) {
            for (int tx = minTx; tx <= maxTx; tx++) {
                long key = outlineTileKey(ovZoom, tx, ty);
                Identifier tex = outlineTiles.get(key);
                if (tex != null) {
                    blitOutlineTile(ctx, tex, tx, ty, ovTileWorld, cameraX, cameraZ, blockScale, sw, sh);
                } else if (!outlineTilesEmpty.contains(key)) {
                    requestOutlineTile(key, ovZoom, tx, ty, ovTileWorld, ovPpb, density,
                                       snapshot, favSnapshot, statusSnapshot);
                }
            }
        }
    }

    /** Physical-px-per-GUI-px multiple the tiles rasterise at (capped window scale factor). */
    private static double tileRenderDensity() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getWindow() == null) return 1.0;
        return Math.min(OUTLINE_TILE_MAX_DENSITY, Math.max(1.0, client.getWindow().getGuiScale()));
    }

    private void requestOutlineTile(long key, int zoom, int tx, int ty, double tileWorldSize, double ppb,
                                    double density, TownRenderCache snapshot, Set<String> favSnapshot,
                                    Set<String> statusSnapshot) {
        if (outlineTilesLoading.size() >= MAX_OUTLINE_TILE_LOADS) return;
        if (!outlineTilesLoading.add(key)) return;
        // Stamp the versions this tile is being baked with, so a tile that finishes after the favourite or
        // status-exclusion set changes is rejected on upload instead of showing a stale exclusion.
        int favVer = outlineTilesFavVersion;
        int statVer = outlineTilesStatusVersion;
        outlineTileExecutor.execute(() -> {
            try {
                NativeImage img = rasterizeOutlineTile(zoom, tx, ty, tileWorldSize, ppb, density, snapshot, favSnapshot, statusSnapshot);
                if (img == null) outlineTilesEmpty.add(key);
                else outlineTilesCompleted.add(new LoadedOutlineTile(key, snapshot, img, favVer, statVer));
            } catch (Exception e) {
                LOGGER.warn("[TownyMap] Failed to rasterise town outline tile: {}", e.getMessage());
            } finally {
                outlineTilesLoading.remove(key);
            }
        });
    }

    private NativeImage rasterizeOutlineTile(int zoom, int tx, int ty, double tileWorldSize, double ppb,
                                             double density, TownRenderCache snapshot, Set<String> favSnapshot,
                                             Set<String> statusSnapshot) {
        double tileWorldX = tx * tileWorldSize;
        double tileWorldZ = ty * tileWorldSize;
        double pad = tileWorldSize * 0.02;
        double left = tileWorldX - pad, right = tileWorldX + tileWorldSize + pad;
        double top = tileWorldZ - pad, bottom = tileWorldZ + tileWorldSize + pad;

        HashSet<RenderTown> towns = new HashSet<>();
        int minCellX = floorToIndexCell(left), maxCellX = floorToIndexCell(right);
        int minCellZ = floorToIndexCell(top), maxCellZ = floorToIndexCell(bottom);
        Map<Long, List<RenderTown>> index = snapshot.spatialIndex();
        for (int cz = minCellZ; cz <= maxCellZ; cz++) {
            for (int cx = minCellX; cx <= maxCellX; cx++) {
                List<RenderTown> cell = index.get(indexCellKey(cx, cz));
                if (cell != null) towns.addAll(cell);
            }
        }
        if (towns.isEmpty()) return null;

        // Input geometry: ALWAYS the raw rings (lod 0), simplified in pixel space — Leaflet's way, at
        // every zoom out to the full overview. Pre-snapped LOD grids change the character of the
        // simplified output at each LOD band (and can collapse mid-size towns into degenerate rings that
        // vanish), which is exactly the "looks different past this zoom" the raw path avoids. The
        // settle-then-redraw debounce makes raw affordable: one off-thread bake per settled zoom, and the
        // radial reduction stage eats most of the raw points in a single O(n) pass anyway.
        double simplifyTol = OUTLINE_SIMPLIFY_TOLERANCE_PX * density;
        // Style-dependent bake. Blocky reproduces the direct renderer exactly: the LOD tier that zoom
        // would have picked, no pixel-space simplification, no anti-aliasing, and the user's own
        // Border/Fill Opacity - so a baked tile is what the direct path would have drawn, minus the
        // thousands of draw calls.
        boolean smooth = config.smoothTownOutlines;
        double guiScale = ppb / density;                       // GUI-space blockScale this tile represents
        int lod = smooth ? 0 : Math.min(LOD_GRID.length - 1, selectLod(guiScale));
        int strokeAlphaT = smooth ? SITE_STROKE_ALPHA : (config.borderAlpha & 0xFF);
        int fillAlphaT   = smooth ? SITE_FILL_ALPHA   : (config.fillAlpha & 0xFF);
        // The direct path also suppresses fills past FILL_MAX_TOWNS, but that is a per-frame cost guard
        // which a baked tile does not need; the scale gate is the part that is actually about looks.
        boolean bakeFill = smooth || guiScale >= TOWN_FILL_MIN_SCALE;
        // Anti-aliased strokes with round joins over DP-simplified rings — the squaremap/Leaflet look.
        // The fine 1.1× zoom buckets keep the blit within ~5% of 1:1, and the LINEAR sampler smooths the
        // residual resample uniformly (sub-half-pixel), the same way the browser composites its canvas.
        BufferedImage img = new BufferedImage(OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        boolean drew = false;
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    smooth ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            // Zoom-adaptive on-screen stroke width: fine when zoomed in (a chunk spans many tile px),
            // a touch bolder when zoomed out where the diagonal simplification reads best. Fixes the
            // "too fat when zoomed in close" of the old constant weight. 16*ppb = a chunk's width in tile
            // px; guiW is the resulting on-screen width in GUI px (tiles blit at ~1/density, so ×density
            // to convert to the tile-space stroke).
            // Upper clamp only bites when zoomed OUT. Keeping it under a pixel there is what stops the
            // far-zoom mush: a ~2px stroke on a town that's only a few px wide swallows the shape.
            if (smooth) {
                double guiW = Math.max(0.65, Math.min(0.85, 20.0 / (16.0 * ppb)));
                g.setStroke(new BasicStroke((float) (guiW * density),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            } else {
                // Exactly one GUI pixel, square ends and mitred corners: the direct path's
                // drawHorizontalLine/drawVerticalLine produce hard 1px lines with square joins.
                g.setStroke(new BasicStroke((float) density, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            }
            for (RenderTown town : towns) {
                if (!town.intersectsWorld(left, right, top, bottom)) continue;
                if (statusSnapshot.contains(town.key())) continue;   // status-highlighted → drawn live, not baked
                // Favourites are baked exactly like every other town — identical thin stroke, identical
                // translucent fill — only the COLOUR differs (gold). Previously they also got a fully
                // opaque line, which made them shout next to the ~65%-alpha neighbours.
                boolean favorite = favSnapshot.contains(town.key());
                // Outline uses squaremap's stroke colour, interior uses its fillColor — on EarthMC those
                // are the nation's two colours, so towns read as their nation instead of one flat tint.
                int rgb = favorite ? 0xFFE066 : (town.data().rgbColor() & 0xFFFFFF);
                int fillRgb = favorite ? 0xFFE066 : (town.data().fillRgbColor() & 0xFFFFFF);

                // With "Far Zoom Town Dots" ON, a town only a few px across collapses to a crisp dot
                // (an anti-aliased outline that small can read as a blob). With it OFF we keep drawing
                // the real outline right down to 1px — below that there is literally no shape left to
                // resolve, so the dot is the only thing that can be drawn at all.
                double tw = (town.maxX() - town.minX()) * ppb;
                double th = (town.maxZ() - town.minZ()) * ppb;
                double dotBelow = config.farZoomTownDots ? 2.5 * density : 1.0;
                if (Math.max(tw, th) <= dotBelow) {
                    int cx = (int) Math.round(((town.minX() + town.maxX()) / 2.0 - tileWorldX) * ppb);
                    int cy = (int) Math.round(((town.minZ() + town.maxZ()) / 2.0 - tileWorldZ) * ppb);
                    g.setColor(awtColor(0xC8000000 | rgb));   // dot needs more alpha than a stroke to read
                    g.fillRect(cx, cy, 1, 1);
                    drew = true;
                    continue;
                }
                for (RingGeometry ring : town.rings()) {
                    int[] xs = ring.lodX(lod), zs = ring.lodZ(lod);
                    if (xs.length < 2) continue;
                    double[] px = new double[xs.length];
                    double[] pz = new double[xs.length];
                    for (int i = 0; i < xs.length; i++) {
                        px[i] = (xs[i] - tileWorldX) * ppb;
                        pz[i] = (zs[i] - tileWorldZ) * ppb;
                    }
                    int n = smooth ? simplifyRing(px, pz, simplifyTol) : xs.length;
                    Path2D.Double path = new Path2D.Double();
                    path.moveTo(px[0], pz[0]);
                    for (int i = 1; i < n; i++) {
                        path.lineTo(px[i], pz[i]);
                    }
                    path.closePath();
                    // Leaflet's _fillStroke on the SAME simplified path: soft fill first, wide translucent
                    // stroke over it. Fill and stroke can never disagree about the shape — the reason the
                    // site's borders always sit flush on their fills.
                    if (bakeFill) {
                        g.setColor(awtColor((fillAlphaT << 24) | fillRgb));
                        g.fill(path);
                    }
                    g.setColor(awtColor((strokeAlphaT << 24) | rgb));
                    g.draw(path);
                    drew = true;
                }
            }
        } finally {
            g.dispose();
        }
        if (!drew) return null;

        NativeImage ni = new NativeImage(OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS, false);
        for (int y = 0; y < OUTLINE_TILE_PIXELS; y++) {
            for (int x = 0; x < OUTLINE_TILE_PIXELS; x++) {
                ni.setPixel(x, y, img.getRGB(x, y));   // BufferedImage.getRGB is ARGB; NativeImage.setPixel takes ARGB
            }
        }
        return ni;
    }

    /**
     * Leaflet 1.9.4's LineUtil.simplify, in-place: STAGE 1 radial vertex reduction (drop every point
     * within {@code tolerance} of the previously kept point — the aggressive pass that collapses chunk
     * staircases the moment their steps shrink under the tolerance), then STAGE 2 Douglas-Peucker.
     * Compacts survivors to the front of the arrays and returns their count. Endpoints always kept (the
     * ring closes back to index 0 via closePath). Iterative DP so a huge ring can't overflow the stack.
     */
    public static int simplifyRing(double[] px, double[] pz, double tolerance) {
        int n = px.length;
        if (n < 8) return n;                       // tiny ring: nothing worth removing
        double sqTol = tolerance * tolerance;

        // Stage 1 — Leaflet _reducePoints: radial distance to the last KEPT point.
        int m = 1;                                 // px[0]/pz[0] kept in place
        int prev = 0;
        for (int i = 1; i < n; i++) {
            double dx = px[i] - px[prev], dz = pz[i] - pz[prev];
            if (dx * dx + dz * dz > sqTol) {
                px[m] = px[i];
                pz[m] = pz[i];
                prev = i;
                m++;
            }
        }
        if (prev < n - 1) {
            px[m] = px[n - 1];
            pz[m] = pz[n - 1];
            m++;
        }
        n = m;
        if (n < 3) return n;

        // Stage 2 — Douglas-Peucker over the reduced points.
        boolean[] keep = new boolean[n];
        keep[0] = keep[n - 1] = true;
        double tolSq = tolerance * tolerance;
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] seg = stack.pop();
            int a = seg[0], b = seg[1];
            if (b - a < 2) continue;
            double ax = px[a], az = pz[a];
            double dx = px[b] - ax, dz = pz[b] - az;
            double lenSq = dx * dx + dz * dz;
            double maxDistSq = -1.0;
            int maxIdx = -1;
            for (int i = a + 1; i < b; i++) {
                double distSq;
                if (lenSq <= 1e-9) {               // degenerate segment → plain point distance
                    double ex = px[i] - ax, ez = pz[i] - az;
                    distSq = ex * ex + ez * ez;
                } else {
                    double t = ((px[i] - ax) * dx + (pz[i] - az) * dz) / lenSq;
                    t = Math.max(0.0, Math.min(1.0, t));
                    double ex = px[i] - (ax + t * dx), ez = pz[i] - (az + t * dz);
                    distSq = ex * ex + ez * ez;
                }
                if (distSq > maxDistSq) {
                    maxDistSq = distSq;
                    maxIdx = i;
                }
            }
            if (maxDistSq > tolSq) {
                keep[maxIdx] = true;
                stack.push(new int[]{a, maxIdx});
                stack.push(new int[]{maxIdx, b});
            }
        }
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                px[kept] = px[i];
                pz[kept] = pz[i];
                kept++;
            }
        }
        return kept;
    }

    private void processOutlineTileUploads() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        for (int i = 0; i < MAX_OUTLINE_TILE_UPLOADS_PER_FRAME; i++) {
            LoadedOutlineTile loaded = outlineTilesCompleted.poll();
            if (loaded == null) return;
            if (loaded.snapshot() != outlineTilesSnapshot
                    || loaded.favVersion() != outlineTilesFavVersion
                    || loaded.statusVersion() != outlineTilesStatusVersion
                    || outlineTiles.containsKey(loaded.key())) {
                loaded.image().close();   // stale (data/favourites/status changed) or duplicate
                continue;
            }
            try {
                Identifier id = Identifier.fromNamespaceAndPath("townymapaddon", "town_outline_tile/" + loaded.key());
                OutlineTileTexture tex = new OutlineTileTexture(
                        () -> "TownyMap outline tile " + loaded.key(), loaded.image());
                client.getTextureManager().register(id, tex);
                outlineTiles.put(loaded.key(), id);
                evictOldOutlineTiles(client);
            } catch (Exception e) {
                loaded.image().close();
                LOGGER.warn("[TownyMap] Failed to upload town outline tile: {}", e.getMessage());
            }
        }
    }

    private void blitOutlineTile(GuiGraphicsExtractor ctx, Identifier texture, int tx, int ty, double tileWorldSize,
                                 double cameraX, double cameraZ, double blockScale, int sw, int sh) {
        double tileWorldX = tx * tileWorldSize;
        double tileWorldZ = ty * tileWorldSize;
        int x1 = toScreenX(tileWorldX, cameraX, blockScale, sw);
        int y1 = toScreenY(tileWorldZ, cameraZ, blockScale, sh);
        int x2 = toScreenX(tileWorldX + tileWorldSize, cameraX, blockScale, sw);
        int y2 = toScreenY(tileWorldZ + tileWorldSize, cameraZ, blockScale, sh);
        if (x2 <= 0 || x1 >= sw || y2 <= 0 || y1 >= sh) return;
        int drawW = Math.max(1, x2 - x1);
        int drawH = Math.max(1, y2 - y1);
        // Exact-rect blit, no bleed: the old ±1px bleed stretched every tile by +2px, so it was never 1:1
        // and NEAREST duplicated random rows of the anti-aliased lines (visible as smudge). Adjacent tiles
        // share their rounded screen edge by construction (x2 of one IS x1 of the next), so there are no
        // gaps to hide, and overlapping translucent AA strokes would double-blend into dark seams anyway.
        ctx.blit(RenderPipelines.GUI_TEXTURED, texture, x1, y1, 0.0F, 0.0F,
                drawW, drawH, OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS, OUTLINE_TILE_PIXELS);
    }

    private void clearOutlineTiles() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            for (Identifier id : outlineTiles.values()) client.getTextureManager().release(id);
        }
        outlineTiles.clear();
        outlineTilesEmpty.clear();
        outlineTilesLoading.clear();
        lastReadyOutlineZoom = Integer.MIN_VALUE;   // cached tiles are gone → no stale zoom to fall back to
        LoadedOutlineTile loaded;
        while ((loaded = outlineTilesCompleted.poll()) != null) loaded.image().close();
    }

    private void evictOldOutlineTiles(Minecraft client) {
        // The pinned overview level is never evicted — it's the floor that keeps outlines on screen, and
        // it's only a couple dozen tiles.
        int pinnedZoom = overviewOutlineZoom();
        while (outlineTiles.size() > MAX_OUTLINE_TILES) {
            Long victim = null;
            for (Long k : outlineTiles.keySet()) {
                if (outlineTileKeyZoom(k) != pinnedZoom) { victim = k; break; }
            }
            if (victim == null) break;   // nothing left but pinned overview tiles
            client.getTextureManager().release(outlineTiles.get(victim));
            outlineTiles.remove(victim);
        }
    }

    // Zoom needs 16 bits, not 8: with the fine 1.03 step the bucket index spans roughly -141..+115, so an
    // 8-bit field aliased the whole-map overview (-141) onto a very-close zoom (+115). A collision handed
    // back a massively-coarse tile for a fine slot — it rendered hugely magnified (fat, blocky) until the
    // correct tile baked and overwrote the key. 16+24+24 fills the long exactly.
    private static long outlineTileKey(int zoom, int tx, int ty) {
        return ((long) (zoom & 0xFFFF) << 48) | ((long) (tx & 0xFFFFFF) << 24) | (long) (ty & 0xFFFFFF);
    }

    /** Zoom bucket packed into a tile key (sign-extended back from its 16-bit field). */
    private static int outlineTileKeyZoom(long key) {
        return (short) ((key >>> 48) & 0xFFFF);
    }

    // Fine zoom buckets (10% steps) instead of power-of-2. With power-of-2, a tile was drawn anywhere
    // from 1:1 down to 0.5× within a bucket — the GPU then filters that scaling, which is the blur AND
    // the "sharp→soft→sharp as you zoom" oscillation. With 10% buckets the tile is always blitted within
    // ~5% of 1:1, so there's essentially nothing to filter and the lines stay crisp at every zoom.
    // Fine 3% zoom buckets: the tile then always blits within ~1.5% of exactly 1:1 with the framebuffer,
    // so NEAREST sampling passes the baked pixels through pixel-perfect. The old 10% buckets left up to a
    // 5% scale mismatch → resample blur (the "fuzzy lines"). Panning holds one bucket (reuse); only a
    // settled zoom change rebakes.
    private static final double OUTLINE_ZOOM_STEP = 1.03;
    // Coarse power-of-2-ish snap fallback for scales below anything actually reachable. This USED to be
    // 0.008 — but the full overview floor is 0.0625/8 ≈ 0.0078, so the LAST zoom-out notch fell into the
    // coarse snap and blitted a tile from a bucket up to ~2× away: blocky right when the map should look
    // its smoothest. DP-simplified tiles are cheap to re-rasterise, so fine 1.1× buckets now cover the
    // entire reachable range; the coarse path remains only as a safety net for absurd scales.
    private static final double OUTLINE_FINE_MIN_SCALE = 0.004;
    private static final int OUTLINE_COARSE_SNAP = 23;  // ~one power-of-2 step (log2/log1.03 ≈ 23.4)

    private static int chooseOutlineTileZoom(double blockScale) {
        int z = (int) Math.round(Math.log(blockScale) / Math.log(OUTLINE_ZOOM_STEP));
        if (blockScale < OUTLINE_FINE_MIN_SCALE) {
            z = Math.round((float) z / OUTLINE_COARSE_SNAP) * OUTLINE_COARSE_SNAP;
        }
        return z;
    }

    private static double outlineTilePixelsPerBlock(int zoom) {
        return Math.pow(OUTLINE_ZOOM_STEP, zoom);
    }

    private static Color awtColor(int argb) {
        return new Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF);
    }

    private record LoadedOutlineTile(long key, TownRenderCache snapshot, NativeImage image,
                                     int favVersion, int statusVersion) {}

    private static final class OutlineTileTexture extends DynamicTexture {
        private OutlineTileTexture(java.util.function.Supplier<String> name, NativeImage image) {
            super(name, image);
            // NEAREST, not LINEAR: the tile is drawn at a non-1:1 scale every frame, and linear filtering
            // re-blurs it on the GPU no matter how crisp we rasterised it. Nearest keeps the edges hard.
            // (The 2× supersample above gives the tile enough line width to survive the nearest downscale.)
            this.sampler = RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, false);
        }
    }

    // ── Shared ring renderer ─────────────────────────────────────────────────
    //
    // Iterates the pre-merged H/V segment arrays directly — no allocation, no
    // matrix ops.  Segments whose endpoints map to the same screen pixel are
    // skipped (truly sub-pixel and invisible).
    //
    // Safety net: if every single merged segment was sub-pixel (happens only for
    // very small or highly irregular towns at extreme zoom-out), we fall back to
    // a 4-call bounding-box outline so the town is always visible.  This fallback
    // fires rarely and costs at most 4 draw calls when it does.

    private void renderRing(GuiGraphicsExtractor ctx, RingGeometry ring, int lodBoost,
                            int borderColor, int fillColor,
                            double cameraX, double cameraZ, double blockScale,
                            int sw, int sh) {
        // Town-level bounding-box cull
        int screenMinX = toScreenX(ring.minX(), cameraX, blockScale, sw);
        int screenMaxX = toScreenX(ring.maxX(), cameraX, blockScale, sw);
        int screenMinY = toScreenY(ring.minZ(), cameraZ, blockScale, sh);
        int screenMaxY = toScreenY(ring.maxZ(), cameraZ, blockScale, sh);
        if (screenMaxX < 0 || screenMinX > sw || screenMaxY < 0 || screenMinY > sh) return;

        int bbW = Math.abs(screenMaxX - screenMinX);
        int bbH = Math.abs(screenMaxY - screenMinY);

        // Tiny town: bounding box ≤ TINY pixels — single dot
        if (bbW <= TINY_TOWN_SCREEN_PIXELS && bbH <= TINY_TOWN_SCREEN_PIXELS) {
            int dotColor = (borderColor >>> 24) > 0 ? borderColor : fillColor;
            renderTinyTown(ctx, screenMinX, screenMinY, screenMaxX, screenMaxY, dotColor, sw, sh);
            return;
        }

        // Fill (pre-computed rects, gated by TOWN_FILL_MIN_SCALE in caller)
        if ((fillColor >>> 24) > 0 && bbW > 2 && bbH > 2) {
            renderCachedFill(ctx, ring, fillColor, cameraX, cameraZ, blockScale, sw, sh);
        }

        if ((borderColor >>> 24) == 0) return;

        // Pick the LOD whose snap grid maps to a few pixels at this zoom (higher zoom → finer detail).
        // lodBoost coarsens it further when the map is busy — still the real shape, just fewer segments.
        // BUT when the interior is filled, trace the outline at the finest LOD (level 0 = the raw chunk
        // outline) so it actually bounds the EXACT fill rects — a coarser snap lets the fill spill outside
        // the border. Fills are gated to ≤120 towns at zoomed-in scale, so the finer outline is cheap here.
        int lod = (fillColor >>> 24) > 0 ? 0
                : Math.min(LOD_GRID.length - 1, selectLod(blockScale) + lodBoost);
        int[] xs = ring.lodX(lod), zs = ring.lodZ(lod);
        int n = xs.length;
        if (n < 2) {
            renderBoundingBoxBorder(ctx, screenMinX, screenMinY, screenMaxX, screenMaxY,
                                    borderColor, sw, sh);
            return;
        }

        // Draw the outline as ONE connected closed loop.  We walk vertices in
        // order and draw every on-screen edge, so visible corners always join.
        //
        // Per-edge trivial reject: if both endpoints are off the SAME side of the
        // screen the whole edge is invisible, so we skip its draw call but still
        // advance prevX/prevY — connectivity is preserved for everything visible.
        // (Without this, panning near a large town submits draw calls for all of
        // its off-screen edges every frame, which is what caused the pan stutter.)
        int prevX = toScreenX(xs[0], cameraX, blockScale, sw);
        int prevY = toScreenY(zs[0], cameraZ, blockScale, sh);
        for (int i = 1; i <= n; i++) {
            int idx = i == n ? 0 : i;   // close the loop on the final iteration
            int cx = toScreenX(xs[idx], cameraX, blockScale, sw);
            int cy = toScreenY(zs[idx], cameraZ, blockScale, sh);

            boolean offscreen = (prevX < 0 && cx < 0) || (prevX > sw && cx > sw)
                             || (prevY < 0 && cy < 0) || (prevY > sh && cy > sh);
            if (!offscreen) {
                if (prevY == cy) {
                    if (prevX != cx) ctx.horizontalLine(Math.min(prevX, cx), Math.max(prevX, cx), cy, borderColor);
                    else ctx.fill(cx, cy, cx + 1, cy + 1, borderColor);   // 1px corner dot
                } else if (prevX == cx) {
                    ctx.verticalLine(cx, Math.min(prevY, cy), Math.max(prevY, cy), borderColor);
                } else {
                    // Defensive (Towny data is axis-aligned, so this is unreachable):
                    // draw an L-bend to keep the loop connected without a diagonal.
                    ctx.horizontalLine(Math.min(prevX, cx), Math.max(prevX, cx), prevY, borderColor);
                    ctx.verticalLine(cx, Math.min(prevY, cy), Math.max(prevY, cy), borderColor);
                }
            }
            prevX = cx;
            prevY = cy;
        }
    }

    /** Largest LOD level (coarsest) whose min-scale threshold is satisfied. */
    private static int selectLod(double blockScale) {
        for (int k = 0; k < LOD_MIN_SCALE.length; k++) {
            if (blockScale >= LOD_MIN_SCALE[k]) return k;
        }
        return LOD_MIN_SCALE.length - 1;
    }

    /** Draws the four sides of the screen bounding box — always visible, 4 calls. */
    private static void renderBoundingBoxBorder(GuiGraphicsExtractor ctx,
                                                int x1, int y1, int x2, int y2,
                                                int color, int sw, int sh) {
        int bx1 = Math.max(-1, Math.min(x1, x2));
        int bx2 = Math.min(sw,  Math.max(x1, x2));
        int by1 = Math.max(-1, Math.min(y1, y2));
        int by2 = Math.min(sh,  Math.max(y1, y2));
        if (bx1 >= bx2 || by1 >= by2) return;
        ctx.horizontalLine(bx1, bx2, by1, color);
        ctx.horizontalLine(bx1, bx2, by2, color);
        ctx.verticalLine(bx1, by1, by2, color);
        ctx.verticalLine(bx2, by1, by2, color);
    }

    private static void renderTinyTown(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2,
                                       int color, int sw, int sh) {
        if ((color >>> 24) == 0) return;
        int x = Math.max(0, Math.min(sw - 1, (x1 + x2) / 2));
        int y = Math.max(0, Math.min(sh - 1, (y1 + y2) / 2));
        ctx.fill(x, y, x + 1, y + 1, color);
    }

    private static void renderCachedFill(GuiGraphicsExtractor ctx, RingGeometry ring, int color,
                                         double cameraX, double cameraZ, double blockScale,
                                         int sw, int sh) {
        // fillData is a flat int[] with 4 values per rect: [minX, minZ, maxX, maxZ, ...]
        int[] fd = ring.fillData();
        for (int i = 0, len = fd.length; i < len; i += 4) {
            int x1 = toScreenX(fd[i],     cameraX, blockScale, sw);
            int y1 = toScreenY(fd[i + 1], cameraZ, blockScale, sh);
            int x2 = toScreenX(fd[i + 2], cameraX, blockScale, sw);
            int y2 = toScreenY(fd[i + 3], cameraZ, blockScale, sh);
            int left   = Math.min(x1, x2), right  = Math.max(x1, x2);
            int top    = Math.min(y1, y2), bottom = Math.max(y1, y2);
            if (right <= 0 || left >= sw || bottom <= 0 || top >= sh) continue;
            if (right <= left || bottom <= top) continue;
            ctx.fill(left, top, right, bottom, color);
        }
    }

    private boolean isFavorite(String townName) {
        return favoriteTownKeys.contains(townName == null ? "" : townName.toLowerCase(Locale.ROOT));
    }

    private int statusHighlightRgb() {
        if (!config.statusHighlightRainbow) return config.statusHighlightColor & 0x00FFFFFF;
        double hue = (System.currentTimeMillis() % STATUS_RGB_CYCLE_MS) / (double) STATUS_RGB_CYCLE_MS;
        return hsvToRgb(hue, 0.78, 1.0);
    }

    private static int hsvToRgb(double hue, double saturation, double value) {
        double h = (hue - Math.floor(hue)) * 6.0;
        int sector = (int) Math.floor(h);
        double fraction = h - sector;
        double p = value * (1.0 - saturation);
        double q = value * (1.0 - fraction * saturation);
        double t = value * (1.0 - (1.0 - fraction) * saturation);
        double r, g, b;
        switch (sector) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return ((int) Math.round(r * 255.0) << 16)
                | ((int) Math.round(g * 255.0) << 8)
                | (int) Math.round(b * 255.0);
    }

    private void refreshFavoriteTownKeys() {
        if (favoriteTownCount == config.favoriteTowns.size()) return;
        favoriteTownKeys.clear();
        for (String favorite : config.favoriteTowns) {
            if (favorite != null && !favorite.isBlank()) {
                favoriteTownKeys.add(favorite.toLowerCase(Locale.ROOT));
            }
        }
        favoriteTownCount = config.favoriteTowns.size();
        favoritesVersion++;   // tiles bake favourites in → rebuild them when the set changes
    }

    private List<RenderTown> visibleTowns(double blockScale,
                                          double worldLeft, double worldRight,
                                          double worldTop, double worldBottom) {
        TownRenderCache cache = townRenderCache();
        visibleTownScratch.clear();
        visibleTownSeen.clear();

        int minCellX = floorToIndexCell(worldLeft);
        int maxCellX = floorToIndexCell(worldRight);
        int minCellZ = floorToIndexCell(worldTop);
        int maxCellZ = floorToIndexCell(worldBottom);
        long cellCount = (long) (maxCellX - minCellX + 1) * (long) (maxCellZ - minCellZ + 1);
        if (cellCount > Math.max(1, cache.spatialIndex().size())) {
            for (RenderTown town : cache.allTowns()) {
                addVisibleTown(town, blockScale, worldLeft, worldRight, worldTop, worldBottom);
            }
            return visibleTownScratch;
        }

        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                List<RenderTown> cellTowns = cache.spatialIndex().get(indexCellKey(cellX, cellZ));
                if (cellTowns == null) continue;
                for (RenderTown town : cellTowns) {
                    addVisibleTown(town, blockScale, worldLeft, worldRight, worldTop, worldBottom);
                }
            }
        }
        return visibleTownScratch;
    }

    private void addVisibleTown(RenderTown town, double blockScale,
                                double worldLeft, double worldRight,
                                double worldTop, double worldBottom) {
        if (!visibleTownSeen.add(town)) return;
        if (!town.intersectsWorld(worldLeft, worldRight, worldTop, worldBottom)) return;
        if (!largeEnoughOnScreen(town, blockScale, MIN_TOWN_SCREEN_PIXELS)) return;
        visibleTownScratch.add(town);
    }

    /**
     * Visits towns whose bounding box intersects the viewport, using the spatial
     * index instead of a full scan.  {@code action} receives each town's display
     * name and its precomputed lowercase key (no per-call string allocation) and
     * returns true if it issued a request; iteration stops after {@code limit}
     * such requests.  Called off the render path (every ~500 ms), on the client
     * thread, using its own dedup scratch so it never disturbs render state.
     */
    public void forEachVisibleTownDetail(double worldLeft, double worldRight,
                                         double worldTop, double worldBottom,
                                         int limit, BiPredicate<String, String> action) {
        TownRenderCache cache = townRenderCache();
        int issued = 0;

        int minCellX = floorToIndexCell(worldLeft);
        int maxCellX = floorToIndexCell(worldRight);
        int minCellZ = floorToIndexCell(worldTop);
        int maxCellZ = floorToIndexCell(worldBottom);
        long cellCount = (long) (maxCellX - minCellX + 1) * (long) (maxCellZ - minCellZ + 1);

        // Same heuristic as visibleTowns(): if the viewport spans more index cells
        // than the index has entries, a flat scan is cheaper than cell lookups.
        if (cellCount > Math.max(1, cache.spatialIndex().size())) {
            for (RenderTown town : cache.allTowns()) {
                if (!town.intersectsWorld(worldLeft, worldRight, worldTop, worldBottom)) continue;
                if (action.test(town.name(), town.key()) && ++issued >= limit) return;
            }
            return;
        }

        detailQuerySeen.clear();
        for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                List<RenderTown> cellTowns = cache.spatialIndex().get(indexCellKey(cellX, cellZ));
                if (cellTowns == null) continue;
                for (RenderTown town : cellTowns) {
                    if (!detailQuerySeen.add(town)) continue;   // town spans several cells
                    if (!town.intersectsWorld(worldLeft, worldRight, worldTop, worldBottom)) continue;
                    if (action.test(town.name(), town.key()) && ++issued >= limit) return;
                }
            }
        }
    }

    private TownRenderCache townRenderCache() {
        List<TownData> towns = api.getTowns();
        TownRenderCache cache = townRenderCache;
        if (cache.matches(towns)) return cache;
        requestTownRenderCacheBuild(towns);
        return cache;
    }

    private void requestTownRenderCacheBuild(List<TownData> towns) {
        if (towns.isEmpty()) return;
        if (towns == townCacheRequestedSource && townCacheBuildRunning.get()) return;
        townCacheRequestedSource = towns;
        if (!townCacheBuildRunning.compareAndSet(false, true)) return;
        townCacheExecutor.execute(() -> {
            List<TownData> source = townCacheRequestedSource;
            try {
                TownRenderCache built = buildTownRenderCache(source, townRenderCache);
                if (api.getTowns() == source) {
                    townRenderCache = built;
                }
            } finally {
                townCacheBuildRunning.set(false);
                if (api.getTowns() != townRenderCache.source()) {
                    requestTownRenderCacheBuild(api.getTowns());
                }
            }
        });
    }

    private static TownRenderCache buildTownRenderCache(List<TownData> towns, TownRenderCache previous) {
        Map<String, RenderTown> byName = new HashMap<>(Math.max(16, towns.size() * 2));
        Map<Long, List<RenderTown>> mutableSpatialIndex = new HashMap<>();
        ArrayList<RenderTown> allTowns = new ArrayList<>(towns.size());
        for (TownData town : towns) {
            RenderTown renderTown = reusableRenderTown(town, previous);
            allTowns.add(renderTown);
            byName.put(renderTown.key(), renderTown);

            int minCellX = floorToIndexCell(town.minX());
            int maxCellX = floorToIndexCell(town.maxX());
            int minCellZ = floorToIndexCell(town.minZ());
            int maxCellZ = floorToIndexCell(town.maxZ());
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                    mutableSpatialIndex.computeIfAbsent(indexCellKey(cellX, cellZ), ignored -> new ArrayList<>())
                            .add(renderTown);
                }
            }
        }
        Map<Long, List<RenderTown>> spatialIndex = new HashMap<>(mutableSpatialIndex.size());
        for (Map.Entry<Long, List<RenderTown>> entry : mutableSpatialIndex.entrySet()) {
            spatialIndex.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new TownRenderCache(towns, towns.size(), Map.copyOf(byName),
                Map.copyOf(spatialIndex), List.copyOf(allTowns));
    }

    private static RenderTown reusableRenderTown(TownData town, TownRenderCache previous) {
        RenderTown cached = previous.byName().get(town.key());
        if (cached != null && cached.signature() == town.renderSignature()) {
            return cached;
        }
        return RenderTown.from(town);
    }

    private static int floorToIndexCell(double worldCoord) {
        return (int) Math.floor(worldCoord / TOWN_INDEX_CELL_SIZE);
    }

    private static long indexCellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    private static boolean largeEnoughOnScreen(RenderTown town, double blockScale, double minPixels) {
        double width = (town.maxX() - town.minX()) * blockScale;
        double height = (town.maxZ() - town.minZ()) * blockScale;
        return width >= minPixels || height >= minPixels;
    }

    private record TownRenderCache(List<TownData> source, int sourceSize,
                                   Map<String, RenderTown> byName,
                                   Map<Long, List<RenderTown>> spatialIndex,
                                   List<RenderTown> allTowns) {
        private static TownRenderCache empty() {
            return new TownRenderCache(List.of(), 0, Map.of(), Map.of(), List.of());
        }

        private boolean matches(List<TownData> towns) {
            return towns == source && towns.size() == sourceSize;
        }
    }

    private record RenderTown(TownData data, String name, String key, long signature, List<RingGeometry> rings,
                              int minX, int maxX, int minZ, int maxZ) {
        private static RenderTown from(TownData town) {
            ArrayList<RingGeometry> rings = new ArrayList<>(town.polygonRings().size());
            for (int[][] ring : town.polygonRings()) {
                rings.add(RingGeometry.from(ring));
            }
            return new RenderTown(town, town.name(), town.key(), town.renderSignature(),
                    List.copyOf(rings), town.minX(), town.maxX(), town.minZ(), town.maxZ());
        }

        private boolean intersectsWorld(double left, double right, double top, double bottom) {
            return maxX >= left && minX <= right && maxZ >= top && minZ <= bottom;
        }
    }

    // ── RingGeometry ─────────────────────────────────────────────────────────
    //
    // Stores pre-merged outline segments and pre-computed fill rects.
    // Both are built once on the background cache thread; the render thread
    // only iterates flat int[] arrays — zero allocations on the hot path.
    // ── RingGeometry ─────────────────────────────────────────────────────────
    //
    // Research finding: squaremap polygon data already contains only corner
    // vertices — every polygon edge is already a maximal axis-aligned segment.
    // No collinear-vertex merging is needed or useful.
    //
    // Build strategy:
    //   The raw outline (level 0) is the ordered corner vertices straight from
    //   squaremap.  Coarser levels are produced by snapping every vertex to a
    //   larger grid (LOD_GRID) and then dropping duplicate / collinear vertices.
    //   Because snapping is a pure function of the world coordinate, axis-aligned
    //   edges stay axis-aligned and shared town borders snap identically, so the
    //   loop stays closed and connected at every level.
    //
    // Render strategy:
    //   Walk the chosen level's ordered vertices and draw every edge in sequence
    //   as a connected closed loop — no edge is ever skipped, so there are no
    //   gaps at corners.  Coarser levels simply contain fewer vertices.
    //
    // Fill data: flat int[] [minX, minZ, maxX, maxZ, ...], 4 ints per rect.

    private record RingGeometry(
            int[][] lodX,     // lodX[level] = ordered X coords for that LOD level
            int[][] lodZ,     // lodZ[level] = ordered Z coords for that LOD level
            int[] fillData,
            int minX, int maxX, int minZ, int maxZ) {

        private int[] lodX(int level) { return lodX[level]; }
        private int[] lodZ(int level) { return lodZ[level]; }

        private static RingGeometry from(int[][] ring) {
            int n = ring.length;
            int[] px = new int[n], pz = new int[n];
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                px[i] = ring[i].length > 0 ? ring[i][0] : 0;
                pz[i] = ring[i].length > 1 ? ring[i][1] : 0;
                if (px[i] < minX) minX = px[i];
                if (px[i] > maxX) maxX = px[i];
                if (pz[i] < minZ) minZ = pz[i];
                if (pz[i] > maxZ) maxZ = pz[i];
            }
            if (n == 0) { minX = maxX = minZ = maxZ = 0; }

            int levels = LOD_GRID.length;
            int[][] lodX = new int[levels][];
            int[][] lodZ = new int[levels][];

            // Level 0: raw outline (clean up any incidental dup/collinear points)
            int[][] base = cleanRectilinear(px, pz, px.length);
            lodX[0] = base[0];
            lodZ[0] = base[1];

            // Coarser levels: snap to grid, then clean.  If a level collapses to a
            // degenerate shape (< 4 pts), reuse the previous (finer) level so the
            // town never disappears.
            for (int k = 1; k < levels; k++) {
                int grid = LOD_GRID[k];
                int[] sx = new int[n], sz = new int[n];
                for (int i = 0; i < n; i++) {
                    sx[i] = snap(px[i], grid);
                    sz[i] = snap(pz[i], grid);
                }
                int[][] simplified = cleanRectilinear(sx, sz, n);
                if (simplified[0].length >= 4 && simplified[0].length < lodX[k - 1].length) {
                    lodX[k] = simplified[0];
                    lodZ[k] = simplified[1];
                } else {
                    // No further reduction (or degenerate) — share the finer level's arrays
                    lodX[k] = lodX[k - 1];
                    lodZ[k] = lodZ[k - 1];
                }
            }

            return new RingGeometry(lodX, lodZ,
                                    buildFillData(px, pz, minX, maxX, minZ, maxZ),
                                    minX, maxX, minZ, maxZ);
        }
    }

    /** Rounds a coordinate to the nearest multiple of {@code grid} (symmetric). */
    private static int snap(int v, int grid) {
        return Math.floorDiv(v + (grid >> 1), grid) * grid;
    }

    /**
     * Cleans an ordered rectilinear closed-loop vertex set:
     *   1. drops consecutive duplicate points (incl. the wrap-around)
     *   2. drops collinear vertices (where prev, cur, next share an X or a Z),
     *      repeating until stable so flattened staircases fully collapse
     * Returns {outX, outZ}.  Axis-alignment and closure are preserved.
     */
    private static int[][] cleanRectilinear(int[] x, int[] z, int n) {
        // Pass 1: remove consecutive duplicates
        int[] ax = new int[n], az = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (m > 0 && ax[m - 1] == x[i] && az[m - 1] == z[i]) continue;
            ax[m] = x[i]; az[m] = z[i]; m++;
        }
        while (m > 1 && ax[m - 1] == ax[0] && az[m - 1] == az[0]) m--;   // wrap dup

        // Pass 2: iteratively drop collinear vertices on the closed loop
        boolean changed = true;
        while (changed && m > 3) {
            changed = false;
            int w = 0;
            int[] bx = new int[m], bz = new int[m];
            for (int i = 0; i < m; i++) {
                int prev = (i - 1 + m) % m, next = (i + 1) % m;
                boolean colX = ax[prev] == ax[i] && ax[i] == ax[next];
                boolean colZ = az[prev] == az[i] && az[i] == az[next];
                if (colX || colZ) { changed = true; continue; }   // drop this vertex
                bx[w] = ax[i]; bz[w] = az[i]; w++;
            }
            if (changed) { ax = bx; az = bz; m = w; }
        }

        return new int[][]{ Arrays.copyOf(ax, m), Arrays.copyOf(az, m) };
    }

    /**
     * Builds fill data as a flat int[] with 4 ints per rect: [minX, minZ, maxX, maxZ, ...].
     * Uses Arrays.sort for O(n log n) band deduplication instead of the previous O(n²) scan.
     */
    private static int[] buildFillData(int[] x, int[] z, int minX, int maxX, int minZ, int maxZ) {
        int n = x.length;
        if (n < 3 || minX == maxX || minZ == maxZ) return new int[0];

        // Deduplicate Z band boundaries: sort a copy, then take unique values — O(n log n)
        int[] sortedZ = Arrays.copyOf(z, n);
        Arrays.sort(sortedZ);
        int bandCount = 0;
        int[] bands = new int[n + 1];
        for (int val : sortedZ) {
            if (bandCount == 0 || bands[bandCount - 1] != val) bands[bandCount++] = val;
        }
        if (bands[bandCount - 1] != maxZ) bands[bandCount++] = maxZ;
        // bands[] already sorted by Arrays.sort — no second pass needed

        int[] intersections = new int[n];
        int[] temp = new int[Math.max(16, n * 2)];  // generous initial cap, no FillRect objects
        int tempLen = 0;

        for (int bi = 1; bi < bandCount; bi++) {
            int zTop    = bands[bi - 1];
            int zBottom = bands[bi];
            if (zTop >= zBottom) continue;

            int xCount = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                int z1 = z[i], z2 = z[j];
                if (z1 == z2) continue;
                int lo = Math.min(z1, z2), hi = Math.max(z1, z2);
                if (zTop >= lo && zTop < hi) intersections[xCount++] = x[i];
            }

            // Sort intersections (insertion sort — xCount is usually very small)
            for (int i = 1; i < xCount; i++) {
                int key = intersections[i], j = i - 1;
                while (j >= 0 && intersections[j] > key) { intersections[j + 1] = intersections[j--]; }
                intersections[j + 1] = key;
            }

            for (int i = 0; i + 1 < xCount; i += 2) {
                int xLeft = intersections[i], xRight = intersections[i + 1];
                if (xLeft == xRight) continue;
                if (tempLen + 4 > temp.length) temp = Arrays.copyOf(temp, temp.length * 2);
                temp[tempLen++] = Math.max(minX, xLeft);
                temp[tempLen++] = zTop;
                temp[tempLen++] = Math.min(maxX, xRight);
                temp[tempLen++] = zBottom;
            }
        }
        if (tempLen == 0) return new int[0];

        // Merge vertically adjacent rects that share the same X extent.
        // Sort by (minX, maxX) so identical-width columns are grouped together;
        // within each group the scanline order is already Z-ascending, so adjacent
        // bands that have the same left/right edge collapse into one tall rect.
        // For a rectangular town this turns N band-rects into 1 draw call.
        int numRects = tempLen / 4;
        if (numRects > 1) {
            // Pack (minX + 50000, maxX + 50000, rectIndex) as a sort key.
            // Coords ≤ 100 k blocks → +50000 fits in 17 bits (2^17 = 131072 > 90000).
            // 17 + 17 + 17 = 51 bits — no overflow.
            long[] rsk = new long[numRects];
            for (int i = 0; i < numRects; i++) {
                rsk[i] = ((long)(temp[i * 4] + 50000) << 34)
                        | ((long)(temp[i * 4 + 2] + 50000) << 17)
                        | i;
            }
            Arrays.sort(rsk, 0, numRects);

            int[] merged = new int[tempLen];
            int mLen = 0;
            int ox1 = Integer.MIN_VALUE, oz1 = 0, ox2 = 0, oz2 = 0;
            for (long rk : rsk) {
                int ri  = (int)(rk & 0x1FFFF) * 4;
                int rx1 = temp[ri], rz1 = temp[ri + 1], rx2 = temp[ri + 2], rz2 = temp[ri + 3];
                if (rx1 == ox1 && rx2 == ox2 && rz1 == oz2) {
                    oz2 = rz2;  // extend current rect downward
                } else {
                    if (ox1 != Integer.MIN_VALUE) {
                        merged[mLen++] = ox1; merged[mLen++] = oz1;
                        merged[mLen++] = ox2; merged[mLen++] = oz2;
                    }
                    ox1 = rx1; oz1 = rz1; ox2 = rx2; oz2 = rz2;
                }
            }
            merged[mLen++] = ox1; merged[mLen++] = oz1;
            merged[mLen++] = ox2; merged[mLen++] = oz2;
            return Arrays.copyOf(merged, mLen);
        }

        return Arrays.copyOf(temp, tempLen);
    }

    // ── Nation capital markers ───────────────────────────────────────────────

    private void renderNationCapitalStars(GuiGraphicsExtractor ctx,
                                          double cameraX, double cameraZ, double blockScale,
                                          int sw, int sh,
                                          double worldLeft, double worldRight,
                                          double worldTop, double worldBottom,
                                          Map<String, EarthMcNationData> nationDetails) {
        if (!config.townsEnabled || !config.nationStarsEnabled || nationDetails.isEmpty()) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;

        for (EarthMcNationData nation : nationDetails.values()) {
            double markerX, markerZ;

            // Prefer spawn coordinates from the EarthMC API — they are always accurate
            // and don't depend on squaremap polygon names matching the API's capital name.
            if (nation.hasSpawn()) {
                markerX = nation.spawnX();
                markerZ = nation.spawnZ();
            } else if (!nation.capitalName().isBlank()) {
                // Fall back to locating the capital town in the squaremap polygon list.
                TownData capital = townByName(nation.capitalName());
                if (capital == null) continue;
                markerX = capital.centerX();
                markerZ = capital.centerZ();
            } else {
                continue;
            }

            if (markerX < worldLeft || markerX > worldRight
                    || markerZ < worldTop || markerZ > worldBottom) continue;

            int x = toScreenX(markerX, cameraX, blockScale, sw);
            int y = toScreenY(markerZ, cameraZ, blockScale, sh);
            if (x < -10 || x > sw + 10 || y < -10 || y > sh + 10) continue;

            ctx.text(client.font, "★", x - 3, y - 5, 0xFFFFD84D, true);
        }
    }

    private TownData townByName(String name) {
        RenderTown town = townRenderCache().byName().get(name.toLowerCase(Locale.ROOT));
        return town == null ? null : town.data();
    }

    // ── Player rendering ─────────────────────────────────────────────────────

    private void renderPlayers(GuiGraphicsExtractor ctx,
                               double cameraX, double cameraZ, double blockScale,
                               int sw, int sh, Map<String, EarthMcPlayerData> playerDetails) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        String selfName = client.getUser().getName();
        for (PlayerMarker p : api.getPlayers()) {
            if (p.name().equalsIgnoreCase(selfName)) continue;

            int dotX = toScreenX(p.x(), cameraX, blockScale, sw);
            int dotY = toScreenY(p.z(), cameraZ, blockScale, sh);

            if (dotX < -10 || dotX > sw + 10 || dotY < -10 || dotY > sh + 10) continue;

            int color = TownyMapMod.playerDotColor(p.name(), p.key());
            if ((color >>> 24) == 0) continue;
            ctx.fill(dotX - DOT_HALF, dotY - DOT_HALF,
                     dotX + DOT_HALF, dotY + DOT_HALF,
                     color);

            if (config.showPlayerNames && blockScale >= config.playerNameMinScale) {
                EarthMcPlayerData details = playerDetails.get(p.name().toLowerCase(Locale.ROOT));
                String affiliation = affiliation(details);
                boolean showAffiliation = !affiliation.isBlank() && blockScale >= config.playerAffiliationMinScale;
                int nameY = showAffiliation ? dotY + 7 : dotY - 4;
                if (showAffiliation) {
                    ctx.text(
                            client.font,
                            affiliation,
                            dotX + DOT_HALF + 2,
                            dotY - 5,
                            0xFFB8D7FF,
                            true);
                }
                ctx.text(
                        client.font,
                        p.name(),
                        dotX + DOT_HALF + 2,
                        nameY,
                        config.playerLabelColor,
                        true);
            }
        }
    }

    private static String affiliation(EarthMcPlayerData details) {
        if (details == null) return "";
        if (!details.townName().isBlank() && !details.nationName().isBlank()) {
            return details.townName() + " / " + details.nationName();
        }
        if (!details.townName().isBlank()) return details.townName();
        if (!details.nationName().isBlank()) return details.nationName();
        return "";
    }
}

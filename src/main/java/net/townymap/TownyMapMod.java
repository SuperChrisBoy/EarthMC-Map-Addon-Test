package net.townymap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.Level;
import net.townymap.api.EarthMcApiClient;
import net.townymap.api.SquaremapApiClient;
import net.townymap.command.TownyMapCommand;
import net.townymap.gui.ChunkCounterOverlay;
import net.townymap.gui.MapToggleOverlay;
import net.townymap.gui.TownHoverOverlay;
import net.townymap.gui.TownInfoOverlay;
import net.townymap.gui.TownSearchOverlay;
import net.townymap.input.TownyMapKeybinds;
import net.townymap.integration.XaeroWaypointBridge;
import net.townymap.model.EarthMcNationData;
import net.townymap.model.EarthMcPlayerData;
import net.townymap.model.MapJumpTarget;
import net.townymap.model.NationBonusProjection;
import net.townymap.model.NationResidentStats;
import net.townymap.model.OptimisticClaimChunk;
import net.townymap.model.TownData;
import net.townymap.model.TownPopupData;
import net.townymap.render.TownyMinimapOverlay;
import net.townymap.render.WorldMapRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.hud.minimap.common.config.MinimapConfigConstants;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Environment(EnvType.CLIENT)
public class TownyMapMod implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TOWN_CACHE_TYPE = new TypeToken<Map<String, TownPopupData>>() {}.getType();
    private static final long TOWN_DETAILS_SAVE_DELAY_MS = 2_000L;
    private static final long TOWN_DETAILS_MAX_AGE_MS = 60_000L;
    private static final long DETAIL_REQUEST_DEFER_MS = 2_000L;
    private static final long OPTIMISTIC_CLAIM_TTL_MS = 20_000L;
    private static final long PENDING_CLAIM_TTL_MS = 12_000L;
    // Max visible-town details fetched per 500ms cycle. One bulk /towns request covers up to 100 names,
    // so this is ~2 parallel requests — enough to fill a normal screen at once instead of dribbling.
    private static final int MAX_TOWN_DETAIL_BATCH = 150;
    // Max player details collected per bulk /players request (search rows / visible players) — one batch.
    private static final int PLAYER_BULK_PER_CYCLE = 100;
    private static final ScheduledExecutorService CACHE_SAVE_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "TownyMap-CacheSave");
                t.setDaemon(true);
                return t;
            });

    private static TownyMapConfig     config;
    private static SquaremapApiClient apiClient;
    private static EarthMcApiClient   earthMcApi;
    private static WorldMapRenderer   renderer;
    private static final AtomicLong townLookupId = new AtomicLong();
    private static double lastWorldMapCameraX = Double.NaN;
    private static double lastWorldMapCameraZ = Double.NaN;
    private static double lastWorldMapScale = Double.NaN;
    private static long worldMapMovingUntilMs = 0;
    private static final Map<String, TownPopupData> townDetailsCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> townDetailsFetchedAt = new ConcurrentHashMap<>();
    private static final Set<String> townDetailsLoading = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> townDetailsDeferredAt = new ConcurrentHashMap<>();
    // On-demand active-resident counts (the bulk last-login lookup), kept OFF the mass town/nation
    // fetch so the map's bulk fetches stay cheap. Only the focused/searched town & nation are looked
    // up here. Value -1 = looked up but unavailable (e.g. over the resident cap) — cached to avoid re-firing.
    private static final Map<String, Integer> townActiveCache = new ConcurrentHashMap<>();
    private static final Set<String> townActiveLoading = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> nationActiveCache = new ConcurrentHashMap<>();
    private static final Map<String, NationBonusProjection> nationBonusProjCache = new ConcurrentHashMap<>();
    private static final Set<String> nationBonusProjLoading = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean townDetailsSaveScheduled = new AtomicBoolean(false);
    private static volatile MapJumpTarget townInfoRouteTarget;
    private static volatile List<EarthMcPlayerData> apiPlayers = List.of();
    private static final Map<String, EarthMcPlayerData> playerDetailsCache = new ConcurrentHashMap<>();
    private static final Set<String> playerDetailsLoading = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> playerDetailsFailedAt = new ConcurrentHashMap<>();
    private static final Map<String, Long> playerDetailsDeferredAt = new ConcurrentHashMap<>();
    private static final List<OptimisticClaimChunk> optimisticClaimChunks = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile PendingClaim pendingClaim;
    private static final Set<String> minimapOutsideNationPlayers = ConcurrentHashMap.newKeySet();
    private static volatile List<EarthMcNationData> apiNations = List.of();
    private static final Map<String, EarthMcNationData> nationDetailsCache = new ConcurrentHashMap<>();
    private static final Set<String> nationDetailsLoading = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> nationDetailsDeferredAt = new ConcurrentHashMap<>();
    private static final int NATION_BULK_PER_CYCLE = 100;   // nations warmed per bulk /nations cycle
    private static volatile long lastPlayerIndexAttemptMs = 0;
    private static volatile Set<String> cachedFavoriteTownKeys = Set.of();
    private static volatile int cachedFavoriteTownCount = -1;
    private static volatile long minimapNationAlertFlashUntilMs = 0;
    private static volatile long minimapFrameColorReadAtMs = 0;
    private static volatile int minimapFrameColor = 0xFFFFFFFF;
    private static final ThreadLocal<Boolean> suppressNativeMinimapCompass =
            ThreadLocal.withInitial(() -> false);
    private static final AtomicBoolean nativeCompassSuppressionLogged = new AtomicBoolean(false);
    private static volatile long minimapPlayerDetailWindowMs = 0;
    private static volatile int minimapPlayerDetailRequests = 0;
    private static volatile long lastVisibleTownDetailsRequestMs = 0;
    private static volatile long lastVisiblePlayerDetailsRequestMs = 0;
    private static volatile long lastNationCapitalDetailsRequestMs = 0;
    private static volatile long lastSearchDetailsRequestMs = 0;
    private static volatile String lastSearchDetailsQuery = "";
    private static volatile long lastMinimapNationAlertUpdateMs = 0;
    /** Prevents hammering EarthMC API when nations index fails to load. */
    private static volatile long lastNationIndexAttemptMs = 0;
    // Caches for hot-path string work (recomputed only when the input changes).
    private static String activeServerAddress = null;
    private static boolean activeServerResult = false;
    private static String cachedSelfName = null;
    private static String cachedSelfKey = null;

    @Override
    public void onInitializeClient() {
        LOGGER.info("TownyMap Addon initialising...");

        config     = TownyMapConfig.load();
        apiClient  = new SquaremapApiClient(config);
        earthMcApi = new EarthMcApiClient();
        renderer   = new WorldMapRenderer(config, apiClient);
        ChunkCounterOverlay.loadSelection(config.chunkCounterSelection,
                config.chunkCounterGroups, config.activeChunkCounterGroup);
        loadTownDetailsCache();
        net.townymap.integration.CustomOverlayManager.reload();

        apiClient.start();
        TownyMapCommand.register();
        TownyMapKeybinds.register();
        ClientSendMessageEvents.COMMAND.register(TownyMapMod::onCommandSent);
        ClientReceiveMessageEvents.GAME.register(TownyMapMod::onGameMessage);

        LOGGER.info("TownyMap Addon ready; map refreshes are deferred until map/minimap rendering is active ({})", config.squaremapBaseUrl);
    }

    private static void onCommandSent(String command) {
        if (apiClient == null || !isActiveOnCurrentServer()) return;
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (isTownClaimCommand(normalized)) {
            rememberPendingClaim();
        } else if (isTownUnclaimCommand(normalized)) {
            refreshTownClaimsAfterCommand();
        }
    }

    private static void onGameMessage(Component message, boolean overlay) {
        if (apiClient == null || !isActiveOnCurrentServer() || message == null) return;
        PendingClaim pending = pendingClaim;
        if (pending == null) return;

        long now = System.currentTimeMillis();
        if (pending.expired(now)) {
            pendingClaim = null;
            return;
        }

        String normalized = normalizeChatMessage(message.getString());
        if (isTownClaimSuccessMessage(normalized)) {
            pendingClaim = null;
            if (pending.townName().isBlank()) {
                resolveAndAddOptimisticClaimChunk(pending.chunkX(), pending.chunkZ());
            } else {
                addOptimisticClaimChunk(pending.chunkX(), pending.chunkZ(), pending.townName());
            }
            refreshTownClaimsAfterCommand();
        } else if (isTownClaimFailureMessage(normalized)) {
            pendingClaim = null;
        }
    }

    private static void refreshTownClaimsAfterCommand() {
        if (apiClient == null) return;
        apiClient.forceTownMarkerRefreshDelayed(150);
        apiClient.forceTownMarkerRefreshDelayed(750);
        apiClient.forceTownMarkerRefreshDelayed(2500);
        apiClient.forceTownMarkerRefreshDelayed(6500);
        apiClient.forceTownMarkerRefreshDelayed(12000);
    }

    private static boolean isTownClaimCommand(String normalized) {
        return normalized.equals("t claim") || normalized.startsWith("t claim ")
                || normalized.equals("town claim") || normalized.startsWith("town claim ");
    }

    private static boolean isTownUnclaimCommand(String normalized) {
        return normalized.equals("t unclaim") || normalized.startsWith("t unclaim ")
                || normalized.equals("town unclaim") || normalized.startsWith("town unclaim ");
    }

    private static void rememberPendingClaim() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getUser() == null) return;
        String selfName = client.getUser().getName();
        int chunkX = floorToChunk(client.player.getX());
        int chunkZ = floorToChunk(client.player.getZ());

        EarthMcPlayerData cached = playerDetailsCache.get(townKey(selfName));
        pendingClaim = new PendingClaim(chunkX, chunkZ,
                cached != null ? cached.townName() : "",
                System.currentTimeMillis() + PENDING_CLAIM_TTL_MS);
        if (cached != null && !cached.townName().isBlank()) {
            return;
        }

        if (earthMcApi == null) return;
        earthMcApi.fetchPlayer(selfName).thenAccept(data -> {
            if (data == null || data.townName().isBlank()) return;
            playerDetailsCache.put(townKey(selfName), data);
            playerDetailsCache.put(townKey(data.name()), data);
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    PendingClaim pending = pendingClaim;
                    if (pending != null && pending.chunkX() == chunkX && pending.chunkZ() == chunkZ
                            && !pending.expired(System.currentTimeMillis())) {
                        pendingClaim = new PendingClaim(chunkX, chunkZ, data.townName(), pending.expiresAtMs());
                    }
                });
            }
        });
    }

    private static void resolveAndAddOptimisticClaimChunk(int chunkX, int chunkZ) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null || earthMcApi == null) return;
        String selfName = client.getUser().getName();
        EarthMcPlayerData cached = playerDetailsCache.get(townKey(selfName));
        if (cached != null && !cached.townName().isBlank()) {
            addOptimisticClaimChunk(chunkX, chunkZ, cached.townName());
            return;
        }
        earthMcApi.fetchPlayer(selfName).thenAccept(data -> {
            if (data == null || data.townName().isBlank()) return;
            playerDetailsCache.put(townKey(selfName), data);
            playerDetailsCache.put(townKey(data.name()), data);
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> addOptimisticClaimChunk(chunkX, chunkZ, data.townName()));
            }
        });
    }

    private static String normalizeChatMessage(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean isTownClaimSuccessMessage(String normalized) {
        return normalized.contains("successfully claimed");
    }

    private static boolean isTownClaimFailureMessage(String normalized) {
        return normalized.contains("already been claimed")
                || normalized.contains("already belongs")
                || normalized.contains("not claimable")
                || normalized.contains("not enough available town blocks")
                || normalized.contains("cannot afford")
                || normalized.contains("can't afford")
                || normalized.contains("too close")
                || normalized.contains("too far")
                || normalized.contains("not attached")
                || normalized.contains("contains no valid plots")
                || normalized.contains("cannot claim")
                || normalized.contains("can't claim")
                || normalized.contains("unable to claim")
                || normalized.contains("not allowed to claim")
                || normalized.contains("must belong to a town")
                || normalized.contains("you don't belong to a town")
                || normalized.contains("you do not have permission to expand your town")
                || normalized.contains("another plugin stopped the claim")
                || normalized.contains("another plugin stopped your claim");
    }

    private static void addOptimisticClaimChunk(int chunkX, int chunkZ, String townName) {
        if (config == null || apiClient == null || townName == null || townName.isBlank()) return;
        TownData town = townByName(townName);
        int fillColor;
        int outlineColor;
        if (isFavorite(townName)) {
            fillColor = 0x44FFE066;
            outlineColor = 0xFFFFE066;
        } else if (town != null) {
            fillColor = town.argbFillColor(Math.max(config.fillAlpha, 90));
            outlineColor = town.argbColor(config.borderAlpha);
        } else {
            int rgb = config.statusHighlightColor & 0x00FFFFFF;
            fillColor = (Math.max(config.fillAlpha, 90) << 24) | rgb;
            outlineColor = 0xFF000000 | rgb;
        }

        long now = System.currentTimeMillis();
        optimisticClaimChunks.removeIf(chunk -> chunk.chunkX() == chunkX && chunk.chunkZ() == chunkZ);
        optimisticClaimChunks.add(new OptimisticClaimChunk(chunkX, chunkZ, townName, fillColor, outlineColor,
                now + OPTIMISTIC_CLAIM_TTL_MS));
    }

    private static TownData townByName(String townName) {
        if (apiClient == null || townName == null) return null;
        for (TownData town : apiClient.getTowns()) {
            if (town.name().equalsIgnoreCase(townName)) return town;
        }
        return null;
    }

    private static int floorToChunk(double blockCoord) {
        return Math.floorDiv((int) Math.floor(blockCoord), 16);
    }

    public static void forceRefreshTownClaims() {
        if (apiClient == null) return;
        invalidateTownRenderCaches();
        apiClient.forceTownMarkerRefresh();
    }

    public static void toggleSquaremapBackground() {
        if (!canUseKeybindAction()) return;
        config.squaremapBackgroundEnabled = !config.squaremapBackgroundEnabled;
        config.save();
        sendFeedback("Squaremap overlay: " + onOff(config.squaremapBackgroundEnabled),
                config.squaremapBackgroundEnabled ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    public static void cycleBorderOverlayMode() {
        if (!canUseKeybindAction()) return;
        config.borderOverlayMode = (config.borderOverlayMode + 1) % 3;
        config.save();
        sendFeedback("Borders: " + borderModeLabel(config.borderOverlayMode),
                config.borderOverlayMode == 0 ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    // Selectable map modes. "Overclaim" (2) is omitted until EarthMC's API exposes active-resident
    // counts — its claim max is wrong without them, so over-claim detection misfires. The case is kept
    // (commented) in the highlight switch and labels so it can be re-enabled later.
    private static final int[] STATUS_MODES = {0, 1, 2, 3, 4, 5};

    /** Advance the map-mode value to the next/previous selectable mode, skipping disabled ones. */
    public static int nextStatusMode(int current, boolean backward) {
        int idx = 0;
        for (int i = 0; i < STATUS_MODES.length; i++) {
            if (STATUS_MODES[i] == current) { idx = i; break; }
        }
        idx = Math.floorMod(idx + (backward ? -1 : 1), STATUS_MODES.length);
        return STATUS_MODES[idx];
    }

    public static void cycleTownStatusOverlayMode() {
        if (!canUseKeybindAction()) return;
        config.townStatusOverlayMode = nextStatusMode(config.townStatusOverlayMode, false);
        config.save();
        sendFeedback("Map mode: " + townStatusModeLabel(config.townStatusOverlayMode),
                config.townStatusOverlayMode == 0 ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    public static void toggleChunkCounter() {
        if (!canUseKeybindAction()) return;
        if (config.chunkCounterEnabled) ChunkCounterOverlay.flushSelection();
        if (!config.chunkCounterEnabled) {
            config.chunkCounterEnabled = true;
            config.chunkCounterMode = 2;
            ChunkCounterOverlay.prepareMultiMode(config);
        } else {
            config.chunkCounterEnabled = false;
            config.chunkCounterMode = 2;
        }
        config.save();
        sendFeedback("Chunk counter: " + ChunkCounterOverlay.toolbarLabel(config),
                config.chunkCounterEnabled ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    public static void refreshTownClaimsFromKeybind() {
        if (!canUseKeybindAction()) return;
        forceRefreshTownClaims();
        sendFeedback("Refreshing towns and claims from squaremap...", ChatFormatting.WHITE);
    }

    private static boolean canUseKeybindAction() {
        return config != null && isActiveOnCurrentServer();
    }

    private static String onOff(boolean enabled) {
        return enabled ? "ON" : "OFF";
    }

    private static String borderModeLabel(int mode) {
        return switch (mode) {
            case 1 -> "Countries";
            case 2 -> "States";
            default -> "OFF";
        };
    }

    private static String townStatusModeLabel(int mode) {
        return switch (mode) {
            case 1 -> "Public";
            case 2 -> "Overclaim";
            case 3 -> "Open";
            case 4 -> "For Sale";
            case 5 -> "No Nation";
            default -> "None";
        };
    }

    public static void onTownMarkersUpdated() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            pruneOptimisticClaimChunks(true);
            invalidateTownRenderCaches();
            return;
        }
        client.execute(() -> {
            pruneOptimisticClaimChunks(false);
            invalidateTownRenderCaches();
        });
    }

    private static void invalidateTownRenderCaches() {
        if (renderer != null) renderer.invalidateTownCaches();
        TownyMinimapOverlay.invalidateTownCache();
        requestMinimapTownHighlightRefresh();
    }

    public static void saveChunkCounterSelection(List<Long> selectedChunks) {
        if (config == null) return;
        config.chunkCounterSelection = new ArrayList<>(selectedChunks);
        config.save();
    }

    public static void saveChunkCounterState(List<Long> selectedChunks, List<List<Long>> selectedGroups,
                                             int activeGroup) {
        if (config == null) return;
        config.chunkCounterSelection = new ArrayList<>(selectedChunks);
        ArrayList<List<Long>> groups = new ArrayList<>();
        if (selectedGroups != null) {
            for (List<Long> group : selectedGroups) {
                groups.add(group == null ? new ArrayList<>() : new ArrayList<>(group));
            }
        }
        config.chunkCounterGroups = groups;
        config.activeChunkCounterGroup = Math.max(0, Math.min(6, activeGroup));
        config.save();
    }

    public static List<OptimisticClaimChunk> optimisticClaimChunks() {
        pruneOptimisticClaimChunks(false);
        return List.copyOf(optimisticClaimChunks);
    }

    private static void pruneOptimisticClaimChunks(boolean clearAll) {
        long now = System.currentTimeMillis();
        if (clearAll || apiClient == null) {
            optimisticClaimChunks.clear();
            return;
        }
        List<TownData> towns = apiClient.getTowns();
        optimisticClaimChunks.removeIf(chunk -> chunk.expired(now) || confirmedClaimChunk(chunk, towns));
    }

    private static boolean confirmedClaimChunk(OptimisticClaimChunk chunk, List<TownData> towns) {
        double centerX = chunk.chunkX() * 16 + 8.0;
        double centerZ = chunk.chunkZ() * 16 + 8.0;
        TownData town = TownHoverOverlay.townAt(centerX, centerZ, towns);
        return town != null && town.name().equalsIgnoreCase(chunk.townName());
    }

    private record PendingClaim(int chunkX, int chunkZ, String townName, long expiresAtMs) {
        private boolean expired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    /**
     * Called by MixinGuiMap every frame while Xaero's WorldMap is open.
     */
    public static void renderSquaremapBackground(GuiGraphicsExtractor ctx,
                                                 double cameraX, double cameraZ,
                                                 double scale, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            boolean moving = updateWorldMapMovement(cameraX, cameraZ, scale);
            renderer.renderSquaremapBackground(ctx, cameraX, cameraZ, scale, screenW, screenH, moving);
        }
    }

    // ── Search dismiss ─────────────────────────────────────────────────────────
    // The search bar / result persists across panning; it clears on a fresh map (re)open or when the user
    // clicks AWAY — a left-click on the map that isn't a pan-drag, or a right-click on a new town.
    private static Object lastSearchMapInstance = null;
    private static boolean suppressNextPanClear = false;   // retained: jumpTo() still calls it (now a no-op)

    // A left-click on the map arms a pending dismiss; the next frames decide. If the camera PANS (drag) we
    // keep the result up; otherwise (button released, or a short grace window elapses with no pan) it was a
    // genuine click-away and we dismiss. Camera movement is the drag signal — reliable across MC versions;
    // GLFW is only a fast release hint, with a time fallback so the dismiss always fires.
    private static boolean armedMapDismiss = false;
    private static double armCamX, armCamZ;
    private static long armTimeMs = 0L;
    private static final long DISMISS_DELAY_MS = 200L;   // fallback if the GLFW release read is unavailable

    /** Called once per frame by the GuiMap mixin with the raw camera. Clears the search on a fresh map
     *  (re)open, and resolves an armed click-away dismiss (pan = keep, click = dismiss). */
    public static void onWorldMapFrame(Object mapInstance, double cameraX, double cameraZ) {
        if (mapInstance != lastSearchMapInstance) {       // map (re)opened → start with a fresh bar
            lastSearchMapInstance = mapInstance;
            armedMapDismiss = false;
            TownSearchOverlay.reset();
            return;
        }
        if (armedMapDismiss) {
            if (Math.abs(cameraX - armCamX) > 0.5 || Math.abs(cameraZ - armCamZ) > 0.5) {
                armedMapDismiss = false;                  // camera moved → it was a pan-drag → keep the result
            } else {
                long win = GLFW.glfwGetCurrentContext();
                boolean released = win != 0L
                        && GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_RELEASE;
                if (released || System.currentTimeMillis() - armTimeMs >= DISMISS_DELAY_MS) {
                    armedMapDismiss = false;
                    dismissOnMapClick();                  // released (or timed out) without panning → click-away
                }
            }
        }
    }

    /** A left-click landed on the map (not on our UI). Arm a dismiss that fires unless the camera pans first,
     *  so dragging to pan leaves the search result / town popup up. */
    public static void armMapClickDismiss(double cameraX, double cameraZ) {
        armCamX = cameraX;
        armCamZ = cameraZ;
        armTimeMs = System.currentTimeMillis();
        armedMapDismiss = true;
    }

    /** The click-away dismiss: clears the search bar/result AND the town popup. */
    public static void dismissOnMapClick() {
        armedMapDismiss = false;
        dismissTownInfo();
        TownSearchOverlay.reset();
    }

    /** Retained no-op: jumpTo() still calls this, but panning no longer clears the search, so there's
     *  nothing to suppress. */
    public static void suppressNextPanClear() {
        suppressNextPanClear = true;
    }

    public static void renderOnWorldMap(GuiGraphicsExtractor ctx,
                                        double cameraX, double cameraZ,
                                        double scale, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            apiClient.tickWhileMapOpen();
            refreshPlayerIndexIfNeeded();
            refreshNationIndexIfNeeded();
            requestNationCapitalDetails();
            requestVisibleTownDetails(cameraX, cameraZ, scale, screenW, screenH);
            requestVisiblePlayerDetails(cameraX, cameraZ, scale, screenW, screenH);
            renderer.render(ctx, cameraX, cameraZ, scale, screenW, screenH,
                    townDetailsCache, playerDetailsCache, nationDetailsCache);
            if (config.customOverlaysEnabled) {
                net.townymap.integration.CustomOverlayManager.render(ctx, cameraX, cameraZ, scale, screenW, screenH);
            }
        }
    }

    /** Draws the world-map player dots in the renderPreDropdown pass (after the town-tile batch is flushed),
     *  so they sit above the textured outline tiles instead of being painted over — the cause of the dots
     *  "blinking" at zoom-out. Positions/details are the same live squaremap data used everywhere. */
    public static void renderWorldMapLatePass(GuiGraphicsExtractor ctx, double cameraX, double cameraZ,
                                             double scale, int screenW, int screenH) {
        if (!isActiveOnCurrentServer() || renderer == null || config == null || !config.playersEnabled) return;
        renderer.renderPlayersLayer(ctx, cameraX, cameraZ, scale, screenW, screenH, playerDetailsCache);
    }

    public static boolean shouldRenderWorldMapIndicatorOverlay() {
        return isActiveOnCurrentServer() && config != null && config.squaremapBackgroundEnabled;
    }

    private static boolean updateWorldMapMovement(double cameraX, double cameraZ, double scale) {
        long now = System.currentTimeMillis();
        if (Double.isNaN(lastWorldMapCameraX)) {
            lastWorldMapCameraX = cameraX;
            lastWorldMapCameraZ = cameraZ;
            lastWorldMapScale = scale;
            return false;
        }

        double movedBlocks = Math.hypot(cameraX - lastWorldMapCameraX, cameraZ - lastWorldMapCameraZ);
        double scaleDelta = Math.abs(scale - lastWorldMapScale) / Math.max(0.000001, Math.abs(lastWorldMapScale));
        if (movedBlocks > 0.75 || scaleDelta > 0.003) {
            worldMapMovingUntilMs = now + 175L;
        }
        lastWorldMapCameraX = cameraX;
        lastWorldMapCameraZ = cameraZ;
        lastWorldMapScale = scale;
        return now < worldMapMovingUntilMs;
    }

    private static boolean isWorldMapMoving() {
        return System.currentTimeMillis() < worldMapMovingUntilMs;
    }

    public static void renderHoveredWorldMapChunk(GuiGraphicsExtractor ctx,
                                                  double cameraX, double cameraZ,
                                                  double scale, int screenW, int screenH,
                                                  double worldX, double worldZ) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            renderer.renderHoveredChunk(ctx, cameraX, cameraZ, scale, screenW, screenH, worldX, worldZ);
        }
    }

    public static void renderSquaremapMinimapViewport(GuiGraphicsExtractor ctx,
                                                      double cameraX, double cameraZ,
                                                      double scale, int width, int height) {
        renderSquaremapMinimapViewport(ctx, cameraX, cameraZ, scale, width, height, false, 0.0);
    }

    public static void renderSquaremapMinimapViewport(GuiGraphicsExtractor ctx,
                                                      double cameraX, double cameraZ,
                                                      double scale, int width, int height,
                                                      boolean circularClip, double circularClipRadius) {
        if (!isActiveOnCurrentServer()) return;
        if (renderer != null) {
            renderer.renderSquaremapMinimapViewport(ctx, cameraX, cameraZ, scale, width, height,
                    true, circularClip ? circularClipRadius : 0.0);
        }
    }

    public static void renderChunkCounter(GuiGraphicsExtractor ctx,
                                          double cameraX, double cameraZ,
                                          double scale, int screenW, int screenH,
                                          double worldX, double worldZ) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null) return;
        if (!config.chunkCounterEnabled) return;
        ChunkCounterOverlay.tickDrag(worldX, worldZ);
        ChunkCounterOverlay.render(ctx, cameraX, cameraZ, scale, screenW, screenH, worldX, worldZ,
                config.chunkCounterEnabled);
    }

    public static void renderMapToggles(GuiGraphicsExtractor ctx, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (config != null) {
            MapToggleOverlay.render(ctx, screenH, config,
                    renderer != null && renderer.isSquaremapLoading(),
                    renderer != null && renderer.isBorderLoading());
        }
    }

    public static void updateMinimapNationAlert(double playerX, double playerZ, double visibleBlocks) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null || !config.minimapNationAlertEnabled) {
            minimapOutsideNationPlayers.clear();
            minimapNationAlertFlashUntilMs = 0;
            return;
        }
        if (apiClient == null) return;
        long now = System.currentTimeMillis();
        if (now - lastMinimapNationAlertUpdateMs < 500L) return;
        lastMinimapNationAlertUpdateMs = now;
        List<TownData> towns = apiClient.getTowns();
        if (towns.isEmpty()) {
            minimapOutsideNationPlayers.clear();
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null) return;
        String selfName = client.getUser().getName();

        Set<String> currentlyVisibleWilderness = new HashSet<>();
        for (var marker : apiClient.getPlayers()) {
            if (marker.name() == null || marker.name().equalsIgnoreCase(selfName)) continue;
            if (Math.abs(marker.x() - playerX) > visibleBlocks
                    || Math.abs(marker.z() - playerZ) > visibleBlocks) continue;
            if (TownHoverOverlay.townAt(marker.x(), marker.z(), towns) != null) continue;

            String key = townKey(marker.name());
            currentlyVisibleWilderness.add(key);
            if (!minimapOutsideNationPlayers.contains(key)) {
                minimapNationAlertFlashUntilMs = System.currentTimeMillis() + 4_000L;
            }
        }

        minimapOutsideNationPlayers.clear();
        minimapOutsideNationPlayers.addAll(currentlyVisibleWilderness);
    }

    /**
     * Draws a high-contrast player-position indicator at the minimap centre.
     * Only renders when the squaremap background is active and the minimap is enlarged.
     * Must be called AFTER {@code renderOutsidePip} so it composites on top.
     */
    public static void renderMinimapPlayerIndicator(GuiGraphicsExtractor ctx, Object session, int mapX, int mapY, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.renderPlayerIndicator(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session, mapX, mapY, size);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to render minimap player indicator: {}", e.getMessage());
        }
    }

    public static void setSuppressNativeMinimapCompass(Object session) {
        suppressNativeMinimapCompass.set(shouldUseCustomEnlargedMinimapCompass(session));
    }

    public static void clearSuppressNativeMinimapCompass() {
        suppressNativeMinimapCompass.remove();
    }

    public static boolean shouldSuppressNativeMinimapCompass() {
        boolean suppress = suppressNativeMinimapCompass.get();
        if (suppress && nativeCompassSuppressionLogged.compareAndSet(false, true)) {
            LOGGER.info("[TownyMap] Suppressing Xaero native minimap compass while squaremap overlay is active");
        }
        return suppress;
    }

    private static boolean shouldUseCustomEnlargedMinimapCompass(Object session) {
        if (!isActiveOnCurrentServer()) return false;
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapBackgroundEnabled) return false;
        try {
            xaero.hud.minimap.module.MinimapSession minimapSession =
                    (xaero.hud.minimap.module.MinimapSession) session;
            return minimapSession.getProcessor() != null
                    && !minimapSession.getProcessor().isCaveModeDisplayed();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void renderMinimapCompassDirections(GuiGraphicsExtractor ctx, Object session, int mapX, int mapY, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.renderCompassDirections(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session, mapX, mapY, size);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to render minimap compass directions: {}", e.getMessage());
        }
    }

    // Name → {lastX, lastZ, lastSeenMs} for players who were recently nearby (info-panel red tracking).
    private static final java.util.Map<String, double[]> nearbyLastKnown = new java.util.HashMap<>();

    // Scale for our minimap text so it matches Xaero's own info/coordinate text. Xaero draws that
    // text inside its 1/xaeroScale matrix, i.e. at minimapScale/screenScale of the base font; ours
    // was at 1.0 (too big). The minimap mixin sets this each frame from the live scales.
    private static volatile float minimapTextScale = 1.0f;

    public static void setMinimapTextScale(float scale) {
        minimapTextScale = (scale > 0.05f && scale <= 4.0f) ? scale : 1.0f;
    }

    public static float minimapTextScale() {
        return minimapTextScale;
    }

    /**
     * Renders our info lines centred under the minimap: current town + nation, nearby players
     * (within 100 blocks, with distance), and nearest town when in the wilderness. A player who was
     * nearby but is no longer visible stays listed in red with their last-known distance until you
     * are 100 blocks from that spot or one minute passes. Per-line config toggles.
     */
    public static int renderMinimapInfoLines(GuiGraphicsExtractor ctx, int mapCenterX, int mapTop, int mapBottom) {
        if (!isActiveOnCurrentServer() || config == null || apiClient == null) return 0;
        if (!config.infoDisplayTownEnabled && !config.infoDisplayNearbyPlayersEnabled
                && !config.infoDisplayNearestTownEnabled) return 0;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.getUser() == null) return 0;

        double px = client.player.getX();
        double pz = client.player.getZ();
        long now = System.currentTimeMillis();
        java.util.List<TownData> towns = apiClient.getTowns();
        TownData here = TownHoverOverlay.townAt(px, pz, towns);
        java.util.List<String> lines = new java.util.ArrayList<>();

        if (config.infoDisplayTownEnabled) {
            if (here != null) {
                String key = townKey(here.name());
                requestTownDetails(here.name(), key);
                TownPopupData details = townDetailsCache.get(key);
                String nation = details != null ? details.nationName() : null;
                lines.add(nation != null && !nation.isBlank()
                        ? "§fTown: " + here.name() + " §7(" + nation + ")"
                        : "§fTown: " + here.name());
            } else {
                lines.add("§7Wilderness");
            }
        }

        if (config.infoDisplayNearbyPlayersEnabled) {
            // Keep the nearby list's squaremap positions fresh (~1s) whenever it's shown — independent of
            // the minimap-extensions overlay or the full map being open. tickPlayers is interval-throttled
            // and de-duped, so calling it here is cheap and won't double-fetch.
            apiClient.tickPlayers();
            String self = client.getUser().getName();
            java.util.Map<String, Double> current = new java.util.HashMap<>();
            for (var m : apiClient.getPlayers()) {
                if (m.name() == null || m.name().equalsIgnoreCase(self)) continue;
                double d = Math.hypot(m.x() - px, m.z() - pz);
                if (d <= 100.0) {
                    current.put(m.name(), d);
                    nearbyLastKnown.put(m.name(), new double[]{m.x(), m.z(), now});
                }
            }
            java.util.List<String> entries = new java.util.ArrayList<>();
            current.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByValue())
                    .forEach(e -> entries.add("§e" + e.getKey() + " §7(" + (int) Math.round(e.getValue()) + "m)"));
            java.util.Iterator<java.util.Map.Entry<String, double[]>> it = nearbyLastKnown.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<String, double[]> e = it.next();
                if (current.containsKey(e.getKey())) continue;
                double[] t = e.getValue();
                double d = Math.hypot(t[0] - px, t[1] - pz);
                if (d > 100.0 || now - (long) t[2] > 60_000L) {
                    it.remove();
                    continue;
                }
                entries.add("§c" + e.getKey() + " §7(~" + (int) Math.round(d) + "m)");
            }
            if (!entries.isEmpty()) {
                // One player per line (a column); cap the list and summarise the rest.
                int cap = 5;
                int shown = Math.min(cap, entries.size());
                for (int i = 0; i < shown; i++) {
                    lines.add(i == 0 ? "§fNearby: " + entries.get(i) : entries.get(i));
                }
                if (entries.size() > cap) {
                    lines.add("§7+" + (entries.size() - cap) + " more");
                }
            }
        }

        if (config.infoDisplayNearestTownEnabled && here == null && !towns.isEmpty()) {
            TownData nearest = null;
            double best = Double.MAX_VALUE;
            for (TownData t : towns) {
                double d = Math.hypot(t.centerX() - px, t.centerZ() - pz);
                if (d < best) { best = d; nearest = t; }
            }
            if (nearest != null) {
                lines.add("§fNearest: " + nearest.name() + " §7(" + (int) Math.round(best) + "m)");
            }
        }

        if (lines.isEmpty()) return 0;
        // Scale our text to match Xaero's own info/coordinate text; all layout below is in scaled
        // (on-screen) units so the block stays correctly placed and centred under the minimap.
        float s = minimapTextScale();
        float lineH = (client.font.lineHeight + 1) * s;
        float totalH = lines.size() * lineH;

        float maxW = 0;
        for (String line : lines) maxW = Math.max(maxW, client.font.width(line) * s);

        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();
        int pad = 2;

        // Place the block just below the minimap (clearing Xaero's coordinate line); flip above near
        // the screen bottom. (The live Xaero-info-block measurement used on the 1.21.11 branch, which
        // prevents overlap with multi-line Xaero info, is not ported here yet.)
        float top = mapBottom + lineH + 12;
        if (top + totalH > screenH - pad) {
            top = mapTop - (lineH + 12) - totalH;
        }
        top = Math.max(pad, Math.min(top, screenH - pad - totalH));

        // Center the column on the minimap, then clamp the whole block onto the screen.
        float blockCenterX = Math.max(pad + maxW / 2f,
                Math.min((float) mapCenterX, screenW - pad - maxW / 2f));

        float cy = top + lineH / 2f;
        for (String line : lines) {
            TownyMinimapOverlay.drawScaledLabelCentered(ctx, client.font, line, blockCenterX, cy,
                    0xFFFFFFFF, 0, true);
            cy += lineH;
        }
        return (int) Math.ceil(totalH);
    }

    public static void renderMinimapNationAlert(GuiGraphicsExtractor ctx, Object session, int x, int y, int size) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null || !config.minimapNationAlertEnabled) return;
        long remaining = minimapNationAlertFlashUntilMs - System.currentTimeMillis();
        if (remaining <= 0 || size <= 8) return;

        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 95.0);
        int alpha = 120 + (int) Math.round(110.0 * pulse);
        int color = ((alpha & 0xFF) << 24) | (minimapFrameColor() & 0x00FFFFFF);
        int thickness = 3;
        try {
            xaero.hud.minimap.module.MinimapSession minimapSession =
                    (xaero.hud.minimap.module.MinimapSession) session;
            if (TownyMinimapOverlay.isCircularMinimap(minimapSession)) {
                TownyMinimapOverlay.renderCircularOutline(ctx, x, y, size, color, 0, thickness);
                return;
            }
        } catch (Exception ignored) {
        }
        ctx.fill(x, y, x + size, y + thickness, color);
        ctx.fill(x, y + size - thickness, x + size, y + size, color);
        ctx.fill(x, y, x + thickness, y + size, color);
        ctx.fill(x + size - thickness, y, x + size, y + size, color);
    }

    public static void renderMinimapWaypointsOnTop(GuiGraphicsExtractor ctx, Object session, int mapX, int mapY, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.renderWaypointsOnTop(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session, mapX, mapY, size);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to redraw minimap waypoints: {}", e.getMessage());
        }
    }

    public static void renderMinimapFrame(GuiGraphicsExtractor ctx, Object session, int x, int y, int size) {
        if (!isActiveOnCurrentServer()) return;
        if (config == null || !config.minimapExtensionsEnabled || !config.squaremapBackgroundEnabled) return;
        if (size <= 8) return;
        try {
            xaero.hud.minimap.module.MinimapSession minimapSession =
                    (xaero.hud.minimap.module.MinimapSession) session;
            if (minimapSession.getProcessor() == null) return;
            if (minimapSession.getProcessor().isCaveModeDisplayed()) return;
            int color = 0xFF000000 | (minimapFrameColor() & 0x00FFFFFF);
            int shadow = 0xAA000000;
            int thickness = 1;
            if (TownyMinimapOverlay.isCircularMinimap(minimapSession)) {
                // Thicker smooth ring on the circular minimap: it sits on top of the squaremap and
                // covers its stepped clip edge, so the visible boundary is this clean circle.
                TownyMinimapOverlay.renderCircularOutline(ctx, x, y, size, color, shadow, 2);
                return;
            }
            ctx.fill(x - 1, y - 1, x + size + 1, y, shadow);
            ctx.fill(x - 1, y + size, x + size + 1, y + size + 1, shadow);
            ctx.fill(x - 1, y, x, y + size, shadow);
            ctx.fill(x + size, y, x + size + 1, y + size, shadow);
            ctx.fill(x, y, x + size, y + thickness, color);
            ctx.fill(x, y + size - thickness, x + size, y + size, color);
            ctx.fill(x, y, x + thickness, y + size, color);
            ctx.fill(x + size - thickness, y, x + size, y + size, color);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to render minimap frame overlay: {}", e.getMessage());
        }
    }

    public static int minimapFrameColor() {
        long now = System.currentTimeMillis();
        if (now - minimapFrameColorReadAtMs < 1_000L) return minimapFrameColor;
        minimapFrameColorReadAtMs = now;

        int colorIndex = 15;
        Path path = FabricLoader.getInstance().getConfigDir()
                .resolve("xaero/minimap/profiles/default.cfg");
        try {
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path)) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("minimap_frame_color")) continue;
                    int equals = trimmed.indexOf('=');
                    if (equals >= 0) {
                        colorIndex = Integer.parseInt(trimmed.substring(equals + 1).trim());
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        int[] colors = MinimapConfigConstants.COLORS;
        if (colorIndex < 0 || colorIndex >= colors.length) colorIndex = 15;
        minimapFrameColor = colors[colorIndex];
        return minimapFrameColor;
    }

    public static boolean shouldHideMinimap() {
        if (!isActiveOnCurrentServer()) return false;
        if (config == null || !config.hideMinimapInNether) return false;
        Minecraft client = Minecraft.getInstance();
        return client != null
                && client.level != null
                && client.level.dimension() == Level.NETHER;
    }

    /**
     * Coordinate multiplier for our overlay on the world map given the dimension and "EarthMC Map In
     * Nether" setting: 1.0 = render normally, 8.0 = convert Nether coords to overworld (the caller
     * also divides its block-scale by this so the overworld overlay lines up over the real tiles),
     * 0.0 = hide.
     */
    public static double worldMapOverlayScale() {
        if (!isActiveOnCurrentServer() || config == null) return 1.0;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) return 1.0;
        var dim = client.level.dimension();
        if (dim == Level.OVERWORLD) return 1.0;
        if (config.netherMode == 2 && dim == Level.NETHER) return 8.0;   // Overworld Coords
        // The overworld-only hide exists because EarthMC's map covers only its overworld, so raw X/Z
        // from another dimension would put the overlay in the wrong place relative to the player.
        // Off EarthMC there is no such correspondence to protect, and hiding would blank the whole
        // overlay on every server whose world isn't registered as minecraft:overworld (hubs,
        // skyblock, minigames) - leaving only the buttons. Render anyway there.
        if (!isOnEarthMcServer()) return 1.0;
        return 0.0;                                                       // Hidden
    }

    public static int playerDotColor(String playerName) {
        return playerDotColor(playerName, townKey(playerName));
    }

    /**
     * @param playerKey the lowercase player name.  Callers in the per-frame render
     *                  loop pass {@link net.townymap.model.PlayerMarker#key()} so no
     *                  lowercase string is allocated per player per frame.
     */
    public static int playerDotColor(String playerName, String playerKey) {
        if (!isActiveOnCurrentServer() || config == null || !config.playersEnabled) return 0;
        if (earthMcApi == null || playerName == null || playerName.isBlank()) return 0xFFFFFFFF;

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getUser() == null) return 0xFFFFFFFF;
        String selfName = client.getUser().getName();
        if (playerName.equalsIgnoreCase(selfName)) return 0;

        EarthMcPlayerData self = playerDetailsCache.get(sessionSelfKey(selfName));
        if (self == null) {
            requestMinimapPlayerDetails(selfName);
            return 0xFFFFFFFF;
        }

        EarthMcPlayerData other = playerDetailsCache.get(playerKey);
        if (other == null) {
            requestMinimapPlayerDetails(playerName);
            return 0xFFFFFFFF;
        }

        if (!self.townName().isBlank()
                && !other.townName().isBlank()
                && self.townName().equalsIgnoreCase(other.townName())) {
            return 0xFF35F2FF;
        }
        if (!self.nationName().isBlank()
                && !other.nationName().isBlank()
                && self.nationName().equalsIgnoreCase(other.nationName())) {
            return 0xFFFFE066;
        }
        return 0xFFFFFFFF;
    }

    /** Lowercase key of the local player's name, cached for the session. */
    private static String sessionSelfKey(String selfName) {
        if (!selfName.equals(cachedSelfName)) {
            cachedSelfName = selfName;
            cachedSelfKey = townKey(selfName);
        }
        return cachedSelfKey;
    }

    public static int minimapPlayerDotColor(String playerName) {
        return playerDotColor(playerName);
    }

    private static void requestMinimapPlayerDetails(String name) {
        long now = System.currentTimeMillis();
        if (now - minimapPlayerDetailWindowMs >= 1_000L) {
            minimapPlayerDetailWindowMs = now;
            minimapPlayerDetailRequests = 0;
        }
        if (minimapPlayerDetailRequests >= 4) return;
        if (requestPlayerDetails(name)) minimapPlayerDetailRequests++;
    }

    public static void renderOnMinimap(GuiGraphicsExtractor ctx, Object session, int x, int y, int size) {
        if (!isActiveOnCurrentServer()) return;
        try {
            TownyMinimapOverlay.render(ctx,
                    (xaero.hud.minimap.module.MinimapSession) session,
                    x, y, size);
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to render minimap town outlines: {}", e.getMessage());
        }
    }

    public static void renderTownSearch(GuiGraphicsExtractor ctx, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (apiClient != null) {
            apiClient.tickWhileMapOpen();
            refreshPlayerIndexIfNeeded();
            refreshNationIndexIfNeeded();
            requestSearchDetailsIfNeeded();
            TownSearchOverlay.render(ctx, screenW, screenH, apiClient.getTowns(), apiClient.getPlayers(),
                    townDetailsCache, apiPlayers, playerDetailsCache, apiClient.getPlayerHistory(),
                    apiNations, nationDetailsCache, config.favoriteTowns);
        }
    }

    public static void renderTownInfo(GuiGraphicsExtractor ctx, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        TownPopupData data = TownInfoOverlay.currentData();
        if (data != null && data != TownPopupData.WILDERNESS) {
            requestTownActiveResidents(data.townName(), townKey(data.townName()));
            if (data.nationName() != null && !data.nationName().isBlank()) {
                requestNationDetails(data.nationName());
            }
        }
        TownInfoOverlay.render(ctx, screenW, screenH,
                data != null && isFavorite(data.townName()), nationDetailsCache);
    }

    public static void renderTownHover(GuiGraphicsExtractor ctx, int mouseX, int mouseY,
                                       double worldX, double worldZ, int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return;
        if (config != null && config.chunkCounterEnabled) return;
        if (apiClient != null && config != null && config.townsEnabled) {
            TownData town = TownHoverOverlay.townAt(worldX, worldZ, apiClient.getTowns());
            if (town == null) return;

            String key = townKey(town.name());
            TownPopupData details = townDetailsCache.get(key);
            requestTownDetails(town.name(), key);
            TownHoverOverlay.render(ctx, mouseX, mouseY, screenW, screenH, town, details,
                    apiClient.getTownMayor(key), apiClient.getTownNation(key));
        }
    }

    public static boolean onMapToggleClick(double mouseX, double mouseY, int screenH) {
        return onMapToggleClick(mouseX, mouseY, screenH, false);
    }

    public static boolean onMapToggleClick(double mouseX, double mouseY, int screenH, boolean backward) {
        if (!isActiveOnCurrentServer()) return false;
        return config != null && MapToggleOverlay.handleClick(mouseX, mouseY, screenH, config, backward);
    }

    public static boolean onSettingsButtonClick(double mouseX, double mouseY, int screenH) {
        return MapToggleOverlay.handleSettingsClick(mouseX, mouseY, screenH);
    }

    public static boolean onChunkCounterClick(double worldX, double worldZ) {
        if (!isActiveOnCurrentServer()) return false;
        if (config == null || !config.chunkCounterEnabled) return false;
        return ChunkCounterOverlay.handleRightClick(worldX, worldZ);
    }

    public static boolean isChunkCounterActive() {
        return isActiveOnCurrentServer() && config != null && config.chunkCounterEnabled;
    }

    public static void openConfigScreen() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        // Pass the current screen (GuiMap) as parent so closing config returns to the map.
        client.setScreen(new net.townymap.gui.TownyMapConfigScreen(client.screen));
    }

    public static TownSearchOverlay.ClickResult onTownSearchClick(double mouseX, double mouseY,
                                                                  int screenW, int screenH) {
        if (!isActiveOnCurrentServer()) return TownSearchOverlay.ClickResult.none();
        if (apiClient == null) return TownSearchOverlay.ClickResult.none();
        return TownSearchOverlay.click(mouseX, mouseY, screenW, apiClient.getTowns(), apiClient.getPlayers(),
                townDetailsCache, apiPlayers, playerDetailsCache, apiClient.getPlayerHistory(), apiNations, nationDetailsCache,
                config != null ? config.favoriteTowns : List.of());
    }

    public static TownSearchOverlay.ClickResult onTownSearchKeyPressed(int keyCode) {
        if (!isActiveOnCurrentServer()) return TownSearchOverlay.ClickResult.none();
        if (apiClient == null) return TownSearchOverlay.ClickResult.none();
        return TownSearchOverlay.keyPressed(keyCode, apiClient.getTowns(), apiClient.getPlayers(),
                townDetailsCache, apiPlayers, playerDetailsCache, apiClient.getPlayerHistory(), apiNations, nationDetailsCache);
    }

    public static boolean onTownSearchCharTyped(char chr) {
        if (!isActiveOnCurrentServer()) return false;
        return TownSearchOverlay.charTyped(chr);
    }

    public static TownInfoOverlay.ActionResult onTownInfoClick(double mouseX, double mouseY) {
        if (!isActiveOnCurrentServer()) return TownInfoOverlay.ActionResult.none();
        TownInfoOverlay.ActionResult result = TownInfoOverlay.handleClick(mouseX, mouseY);
        if (result.action() == TownInfoOverlay.Action.FAVORITE) {
            toggleFavorite(result.townName());
        } else if (result.action() == TownInfoOverlay.Action.DISCORD) {
            TownInfoOverlay.openDiscord(result.url());
        } else if (result.action() == TownInfoOverlay.Action.ROUTE) {
            createXaeroRoute(townInfoRouteTarget);
        } else if (result.action() == TownInfoOverlay.Action.SEARCH) {
            // Clicking a name in the popup hands off to the search info panel.
            TownInfoOverlay.dismiss();
            TownSearchOverlay.openSearch(result.searchType(), result.searchName());
        }
        return result;
    }

    /**
     * Called by MixinGuiMap when the player right-clicks the map.
     * Shows a loading indicator immediately, then fills in data asynchronously.
     */
    public static void onMapRightClick(double worldX, double worldZ, int screenX, int screenY) {
        if (!isActiveOnCurrentServer()) return;
        if (earthMcApi == null) return;
        armedMapDismiss = false;
        TownSearchOverlay.reset();   // right-clicking a new town clears any active search bar/result
        TownData clickedTown = null;
        TownPopupData fallback = null;
        MapJumpTarget fallbackTarget = null;
        String fallbackKey = "";
        if (apiClient != null) {
            TownData town = TownHoverOverlay.townAt(worldX, worldZ, apiClient.getTowns());
            if (town != null) {
                clickedTown = town;
                fallbackKey = townKey(town.name());
                fallback = townDetailsCache.get(fallbackKey);
                fallbackTarget = new MapJumpTarget(town.name(), town.centerX(), town.centerZ());
            }
        }
        long lookupId = townLookupId.incrementAndGet();
        if (fallback != null) {
            showLookupResult(fallback, screenX, screenY, worldX, worldZ, fallbackTarget);
            if (isTownDetailsFresh(fallbackKey)) return;
        } else {
            TownInfoOverlay.showLoading(screenX, screenY);
        }
        TownPopupData cachedFallback = fallback;
        MapJumpTarget cachedFallbackTarget = fallbackTarget;
        if (clickedTown != null) {
            earthMcApi.fetchTown(clickedTown.name()).thenAccept(data -> {
                Minecraft client = Minecraft.getInstance();
                if (client == null) return;
                client.execute(() -> {
                    if (lookupId != townLookupId.get()) return;
                    showLookupResult(data != null ? data : cachedFallback, screenX, screenY,
                            worldX, worldZ, cachedFallbackTarget);
                });
            });
            return;
        }
        earthMcApi.fetchTownAt(worldX, worldZ).thenAccept(data -> {
            Minecraft client = Minecraft.getInstance();
            if (client == null) return;
            client.execute(() -> {
                if (lookupId != townLookupId.get()) return;
                showLookupResult(data != null ? data : cachedFallback, screenX, screenY,
                        worldX, worldZ, cachedFallbackTarget);
            });
        });
    }

    /**
     * Opens the rich town popup (TownInfoOverlay, the one with the Route button) for a town chosen
     * from the search bar or an in-panel town link, so the search bar and map right-click share one GUI.
     * The popup is anchored at screen centre — a search selection recentres the camera on the town, so
     * that's where it ends up — and the town's details are fetched exactly as a right-click would.
     */
    public static void openTownPopupFromSearch(String townName) {
        if (!isActiveOnCurrentServer() || earthMcApi == null || apiClient == null) return;
        if (townName == null || townName.isBlank()) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        int sx = client.getWindow().getGuiScaledWidth() / 2;
        int sy = client.getWindow().getGuiScaledHeight() / 2;

        TownData clickedTown = null;
        for (TownData town : apiClient.getTowns()) {
            if (town.name().equalsIgnoreCase(townName)) { clickedTown = town; break; }
        }
        double worldX = 0, worldZ = 0;
        MapJumpTarget fallbackTarget = null;
        TownPopupData fallback = null;
        String fallbackKey = "";
        if (clickedTown != null) {
            worldX = clickedTown.centerX();
            worldZ = clickedTown.centerZ();
            fallbackKey = townKey(clickedTown.name());
            fallback = townDetailsCache.get(fallbackKey);
            fallbackTarget = new MapJumpTarget(clickedTown.name(), clickedTown.centerX(), clickedTown.centerZ());
        }

        long lookupId = townLookupId.incrementAndGet();
        if (fallback != null) {
            showLookupResult(fallback, sx, sy, worldX, worldZ, fallbackTarget);
            if (isTownDetailsFresh(fallbackKey)) return;
        } else {
            TownInfoOverlay.showLoading(sx, sy);
        }
        final TownPopupData cachedFallback = fallback;
        final MapJumpTarget cachedFallbackTarget = fallbackTarget;
        final double fWorldX = worldX, fWorldZ = worldZ;
        earthMcApi.fetchTown(townName).thenAccept(data -> {
            Minecraft c = Minecraft.getInstance();
            if (c == null) return;
            c.execute(() -> {
                if (lookupId != townLookupId.get()) return;
                showLookupResult(data != null ? data : cachedFallback, sx, sy,
                        fWorldX, fWorldZ, cachedFallbackTarget);
            });
        });
    }

    public static void dismissTownInfo() {
        townLookupId.incrementAndGet();
        townInfoRouteTarget = null;
        TownInfoOverlay.dismiss();
    }

    public static boolean isActiveOnCurrentServer() {
        if (config == null) return false;
        if (!config.earthmcOnly) return true;
        return isOnEarthMcServer();
    }

    /**
     * Whether the current server actually is EarthMC, independent of the "EarthMC Only" toggle.
     * Used for behaviour that only makes sense against EarthMC's own world (see
     * {@link #worldMapOverlayScale()}), as opposed to whether the mod is switched on at all.
     */
    public static boolean isOnEarthMcServer() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return false;
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null) return false;
        // Cache the toLowerCase().contains() result per server address.  This is
        // called once per online player per frame (playerDotColor), so the string
        // allocation is hot; recompute only when the address actually changes.
        String address = server.ip;
        if (!address.equals(activeServerAddress)) {
            activeServerAddress = address;
            activeServerResult = address.toLowerCase(Locale.ROOT).contains("earthmc.net");
        }
        return activeServerResult;
    }

    private static void showLookupResult(TownPopupData data, int screenX, int screenY,
                                         double clickedWorldX, double clickedWorldZ,
                                         MapJumpTarget fallbackTarget) {
        if (data != null) {
            if (data != TownPopupData.WILDERNESS) {
                cacheTownDetails(townKey(data.townName()), data);
                scheduleTownDetailsCacheSave();
            }
            townInfoRouteTarget = routeTarget(data, clickedWorldX, clickedWorldZ, fallbackTarget);
            TownInfoOverlay.show(data, screenX, screenY);
        } else {
            townInfoRouteTarget = null;
            TownInfoOverlay.dismiss();
        }
    }

    private static MapJumpTarget routeTarget(TownPopupData data, double clickedWorldX, double clickedWorldZ,
                                             MapJumpTarget fallbackTarget) {
        if (data == null || data == TownPopupData.WILDERNESS) return null;
        String townName = data.townName();
        if (apiClient != null) {
            for (TownData town : apiClient.getTowns()) {
                if (town.name().equalsIgnoreCase(townName)) {
                    return new MapJumpTarget(town.name(), town.centerX(), town.centerZ());
                }
            }
        }
        if (fallbackTarget != null) return fallbackTarget;
        return new MapJumpTarget(townName,
                (int) Math.round(clickedWorldX),
                (int) Math.round(clickedWorldZ));
    }

    public static boolean createXaeroRoute(MapJumpTarget target) {
        if (!isActiveOnCurrentServer() || target == null) return false;
        try {
            boolean created = XaeroWaypointBridge.createRouteWaypoint(target);
            if (created) {
                sendFeedback("Xaero route set to " + target.label() + ".", ChatFormatting.GREEN);
            } else {
                sendFeedback("Could not create a Xaero route here.", ChatFormatting.RED);
            }
            return created;
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[TownyMap] Failed to create Xaero route waypoint", e);
            sendFeedback("Xaero route creation failed.", ChatFormatting.RED);
            return false;
        }
    }

    private static void sendFeedback(String message, ChatFormatting color) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        client.player.sendSystemMessage(Component.literal("[TownyMap] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal(message).withStyle(color)));
    }

    // Single-town fetch for the hovered/clicked town. Visible-map and search details go through the bulk
    // path (requestTownDetailsBulk); this is only ever called for one town at a time, so it needs no
    // concurrency cap of its own — the loading set just dedupes an in-flight request.
    private static void requestTownDetails(String townName, String key) {
        long now = System.currentTimeMillis();
        if (earthMcApi == null || isTownDetailsFresh(key) || requestDeferred(townDetailsDeferredAt, key, now)) return;
        if (!townDetailsLoading.add(key)) return;
        earthMcApi.fetchTown(townName).whenComplete((data, error) -> {
            if (data != null && data != TownPopupData.WILDERNESS) {
                cacheTownDetails(key, data);
                scheduleTownDetailsCacheSave();
                townDetailsDeferredAt.remove(key);
            } else if (error != null) {
                deferRequest(townDetailsDeferredAt, key, System.currentTimeMillis());
            }
            townDetailsLoading.remove(key);
        });
    }

    private static boolean requestDeferred(Map<String, Long> deferredAt, String key, long now) {
        Long deferred = deferredAt.get(key);
        if (deferred == null) return false;
        if (now - deferred < DETAIL_REQUEST_DEFER_MS) return true;
        deferredAt.remove(key);
        return false;
    }

    private static void deferRequest(Map<String, Long> deferredAt, String key, long now) {
        if (key == null || key.isBlank()) {
            return;
        }
        deferredAt.put(key, now);
    }

    private static boolean isTownDetailsFresh(String key) {
        if (!townDetailsCache.containsKey(key)) return false;
        Long fetchedAt = townDetailsFetchedAt.get(key);
        return fetchedAt != null && System.currentTimeMillis() - fetchedAt < TOWN_DETAILS_MAX_AGE_MS;
    }

    private static void cacheTownDetails(String requestedKey, TownPopupData data) {
        if (data == null || data == TownPopupData.WILDERNESS) return;
        long now = System.currentTimeMillis();
        String canonicalKey = townKey(data.townName());
        if (requestedKey != null && !requestedKey.isBlank()) {
            townDetailsCache.put(requestedKey, withCachedActive(requestedKey, data));
            townDetailsFetchedAt.put(requestedKey, now);
            townDetailsDeferredAt.remove(requestedKey);
        }
        townDetailsCache.put(canonicalKey, withCachedActive(canonicalKey, data));
        townDetailsFetchedAt.put(canonicalKey, now);
        townDetailsDeferredAt.remove(canonicalKey);
    }

    /** Re-applies a previously looked-up active count when base town data is (re)cached, so the
     *  inactive suffix survives a base refresh and the active-before-base ordering both work. */
    private static TownPopupData withCachedActive(String key, TownPopupData data) {
        Integer a = townActiveCache.get(key);
        return (a != null && a >= 0) ? data.withActiveResidentCount(a) : data;
    }

    /** Looks up the active-resident count for ONE focused town (off the mass-fetch path) and folds
     *  it into the cached detail + the open info panel. Cheap: at most one town's residents. */
    /** Triggers the on-demand active-resident lookup for a focused town (search panel), so it's fetched
     *  only for the town actually opened rather than every visible search row. */
    public static void requestTownActive(String townName) {
        requestTownActiveResidents(townName, townKey(townName));
    }

    private static void requestTownActiveResidents(String townName, String key) {
        if (earthMcApi == null || townName == null || townName.isBlank() || key == null || key.isBlank()) return;
        if (townActiveCache.containsKey(key) || !townActiveLoading.add(key)) return;
        earthMcApi.fetchTownActiveResidents(townName).whenComplete((count, error) -> {
            int c = count != null ? count : -1;
            townActiveCache.put(key, c);   // cache even -1 so this doesn't re-fire every frame
            if (c >= 0) {
                townDetailsCache.computeIfPresent(key, (k, old) -> old.withActiveResidentCount(c));
                TownInfoOverlay.setActiveResidentCount(townName, c);
            }
            townActiveLoading.remove(key);
        });
    }

    /** Looks up the active-resident count for ONE focused nation (off the mass-fetch path). */
    /** ONE fetch for the focused nation yields BOTH the active-resident count and the bonus-drop
     *  projection (was two separate /nations + /players passes — the cause of the 429 storm that hid the
     *  inactive/bonus rows). The projection cache doubles as the loaded-marker. */
    private static void requestNationResidentStats(String nationName) {
        if (earthMcApi == null || nationName == null || nationName.isBlank()) return;
        String key = townKey(nationName);
        if (nationBonusProjCache.containsKey(key) || !nationBonusProjLoading.add(key)) return;
        earthMcApi.fetchNationResidentStats(nationName).whenComplete((stats, error) -> {
            NationResidentStats s = stats != null ? stats : NationResidentStats.NONE;
            nationBonusProjCache.put(key, s.projection());
            if (s.activeCount() >= 0) {
                nationActiveCache.put(key, s.activeCount());
                nationDetailsCache.computeIfPresent(key, (k, old) -> old.withActiveResidentCount(s.activeCount()));
            }
            nationBonusProjLoading.remove(key);
        });
    }

    /** Triggers the combined active+projection lookup on first view (a per-resident timestamp lookup, so
     *  only nations actually opened are looked up) and returns the projection (null until loaded). The
     *  active count lands in the cached nation details, driving the inactive row. */
    public static NationBonusProjection nationBonusProjection(String nationName) {
        if (nationName == null) return null;
        NationBonusProjection cached = nationBonusProjCache.get(townKey(nationName));
        if (cached == null) requestNationResidentStats(nationName);
        return cached;
    }

    private static void refreshPlayerIndex() {
        if (earthMcApi == null) return;
        lastPlayerIndexAttemptMs = System.currentTimeMillis();
        earthMcApi.fetchPlayerIndex().thenAccept(players -> {
            if (players != null && !players.isEmpty()) {
                apiPlayers = players;
                LOGGER.info("[TownyMap] Loaded {} EarthMC player names", players.size());
            }
        });
    }

    private static void refreshPlayerIndexIfNeeded() {
        if (!apiPlayers.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastPlayerIndexAttemptMs < 30_000) return;
        refreshPlayerIndex();
    }

    private static void refreshNationIndex() {
        if (earthMcApi == null) return;
        lastNationIndexAttemptMs = System.currentTimeMillis();
        earthMcApi.fetchNationIndex().thenAccept(nations -> {
            if (nations != null && !nations.isEmpty()) {
                apiNations = nations;
                LOGGER.info("[TownyMap] Loaded {} EarthMC nation names", nations.size());
            }
        });
    }

    /**
     * Retries the nation index fetch if it never populated (e.g. API was down at startup).
     * Limits retries to once every 30 seconds so we don't spam EarthMC.
     */
    private static void refreshNationIndexIfNeeded() {
        if (!apiNations.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastNationIndexAttemptMs < 30_000) return;
        refreshNationIndex();
    }

    private static void requestVisibleTownDetails(double cameraX, double cameraZ, double scale,
                                                  int screenW, int screenH) {
        if (earthMcApi == null || apiClient == null || renderer == null || config == null) return;
        if (config.townStatusOverlayMode == 0) return;
        if (scale <= 0) return;
        long now = System.currentTimeMillis();
        if (now - lastVisibleTownDetailsRequestMs < 500L) return;
        lastVisibleTownDetailsRequestMs = now;

        double worldLeft = cameraX - screenW / 2.0 / scale;
        double worldRight = cameraX + screenW / 2.0 / scale;
        double worldTop = cameraZ - screenH / 2.0 / scale;
        double worldBottom = cameraZ + screenH / 2.0 / scale;

        // Collect every visible town still missing details (using the renderer's spatial index and
        // precomputed keys), then fetch them in ONE bulk /towns request instead of dribbling out a POST
        // per town. Bounded so a continental zoom-out can't queue thousands of names in a cycle.
        List<String> names = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        renderer.forEachVisibleTownDetail(worldLeft, worldRight, worldTop, worldBottom, MAX_TOWN_DETAIL_BATCH,
                (name, key) -> {
                    if (townDetailsCache.containsKey(key) || townDetailsLoading.contains(key)
                            || requestDeferred(townDetailsDeferredAt, key, now)) return false;
                    names.add(name);
                    keys.add(key);
                    return true;
                });
        requestTownDetailsBulk(names, keys);
    }

    /**
     * Fetches details for many towns in a single bulk /towns request (≤100 names per query) instead of
     * one POST per town. {@code names} and {@code keys} are parallel lists (keys = townKey(name)). Caches
     * each town the API returns; towns it didn't return are deferred so they don't re-fire every cycle.
     */
    private static void requestTownDetailsBulk(List<String> names, List<String> keys) {
        if (earthMcApi == null || names.isEmpty()) return;
        // Mark all in-flight up front so the next render cycle skips them.
        townDetailsLoading.addAll(keys);
        earthMcApi.fetchTowns(names).whenComplete((result, error) -> {
            try {
                long doneAt = System.currentTimeMillis();
                boolean cachedAny = false;
                for (String key : keys) {
                    // fetchTowns keys results by lowercase town name, which is exactly townKey(name).
                    TownPopupData data = result == null ? null : result.get(key);
                    if (data != null) {
                        cacheTownDetails(key, data);   // caches under the requested key AND the canonical name
                        cachedAny = true;
                    } else {
                        // Not returned (deleted/renamed, or a failed batch) → defer so it doesn't re-fire every cycle.
                        deferRequest(townDetailsDeferredAt, key, doneAt);
                    }
                }
                if (cachedAny) scheduleTownDetailsCacheSave();
            } finally {
                townDetailsLoading.removeAll(keys);
            }
        });
    }

    private static void requestVisiblePlayerDetails(double cameraX, double cameraZ, double scale,
                                                    int screenW, int screenH) {
        if (earthMcApi == null || apiClient == null || config == null || !config.showPlayerNames) return;
        if (scale < config.playerNameMinScale) return;
        long now = System.currentTimeMillis();
        if (now - lastVisiblePlayerDetailsRequestMs < 500L) return;
        lastVisiblePlayerDetailsRequestMs = now;

        double worldLeft = cameraX - screenW / 2.0 / scale;
        double worldRight = cameraX + screenW / 2.0 / scale;
        double worldTop = cameraZ - screenH / 2.0 / scale;
        double worldBottom = cameraZ + screenH / 2.0 / scale;

        List<String> names = new ArrayList<>();
        for (var marker : apiClient.getPlayers()) {
            if (marker.x() < worldLeft || marker.x() > worldRight
                    || marker.z() < worldTop || marker.z() > worldBottom) continue;
            if (!playerDetailNeeded(townKey(marker.name()), now)) continue;
            names.add(marker.name());
            if (names.size() >= PLAYER_BULK_PER_CYCLE) break;
        }
        requestPlayerDetailsBulk(names);
    }

    private static void requestPlayerDetailsForSearch() {
        if (earthMcApi == null) return;
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (String name : TownSearchOverlay.visibleApiPlayerMatches(apiPlayers)) {
            if (playerDetailNeeded(townKey(name), now)) names.add(name);
        }
        String exact = TownSearchOverlay.exactPlayerQuery();
        if (!exact.isBlank() && playerDetailNeeded(townKey(exact), now)) names.add(exact);
        requestPlayerDetailsBulk(names);
    }

    private static void requestSearchDetailsIfNeeded() {
        String currentQuery = TownSearchOverlay.query().trim().toLowerCase(Locale.ROOT);
        if (currentQuery.isEmpty()) {
            lastSearchDetailsQuery = "";
            return;
        }
        long now = System.currentTimeMillis();
        if (currentQuery.equals(lastSearchDetailsQuery) && now - lastSearchDetailsRequestMs < 500L) {
            return;
        }
        lastSearchDetailsQuery = currentQuery;
        lastSearchDetailsRequestMs = now;
        requestTownDetailsForSearch();
        requestPlayerDetailsForSearch();
        requestNationDetailsForSearch();
    }

    /** True if this player key still needs a detail fetch (not cached, in-flight, recently-failed, or deferred). */
    private static boolean playerDetailNeeded(String key, long now) {
        if (playerDetailsCache.containsKey(key) || playerDetailsLoading.contains(key)) return false;
        Long failedAt = playerDetailsFailedAt.get(key);
        if (failedAt != null && now - failedAt < 30_000) return false;
        return !requestDeferred(playerDetailsDeferredAt, key, now);
    }

    /** Fetches details for many players in one bulk /players request (≤100 names/query) instead of a POST
     *  per player — used by the search rows and visible-player loops. Players the API didn't return
     *  (opted out) are backed off like the single path. */
    private static void requestPlayerDetailsBulk(List<String> names) {
        if (earthMcApi == null || names.isEmpty()) return;
        List<String> keys = new ArrayList<>(names.size());
        for (String name : names) {
            String key = townKey(name);
            keys.add(key);
            playerDetailsLoading.add(key);
        }
        earthMcApi.fetchPlayers(names).whenComplete((result, error) -> {
            try {
                long doneAt = System.currentTimeMillis();
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    EarthMcPlayerData data = result == null ? null : result.get(key);
                    if (data != null) {
                        playerDetailsCache.put(key, data);
                        playerDetailsCache.put(townKey(data.name()), data);
                        playerDetailsFailedAt.remove(key);
                        playerDetailsDeferredAt.remove(key);
                    } else {
                        playerDetailsFailedAt.put(key, doneAt);
                    }
                }
            } finally {
                for (String key : keys) playerDetailsLoading.remove(key);
            }
        });
    }

    // Single-player fetch for the minimap trickle (rate-limited to 4/sec) and self lookups. The search and
    // visible-player paths use requestPlayerDetailsBulk, so this needs no concurrency cap of its own.
    private static boolean requestPlayerDetails(String name) {
        if (earthMcApi == null || name == null || name.isBlank()) return false;
        String key = townKey(name);
        long now = System.currentTimeMillis();
        if (!playerDetailNeeded(key, now)) return false;
        if (!playerDetailsLoading.add(key)) return false;
        earthMcApi.fetchPlayer(name).whenComplete((data, error) -> {
            if (data != null) {
                playerDetailsCache.put(key, data);
                playerDetailsCache.put(townKey(data.name()), data);
                playerDetailsFailedAt.remove(key);
                playerDetailsDeferredAt.remove(key);
            } else {
                playerDetailsFailedAt.put(key, System.currentTimeMillis());
            }
            playerDetailsLoading.remove(key);
        });
        return true;
    }

    private static void requestTownDetailsForSearch() {
        if (earthMcApi == null || apiClient == null) return;
        // Town details for the visible search rows go out as one bulk /towns request. The active-resident
        // count is NOT fetched per row — it's on-demand for the selected town only (requestTownActive),
        // matching nations, so a search with many rows doesn't fire a /players lookup for each.
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (String name : TownSearchOverlay.visibleTownMatches(apiClient.getTowns())) {
            String key = townKey(name);
            if (townDetailsCache.containsKey(key) || townDetailsLoading.contains(key)
                    || requestDeferred(townDetailsDeferredAt, key, now)) continue;
            names.add(name);
            keys.add(key);
        }
        requestTownDetailsBulk(names, keys);
    }

    private static void requestNationDetailsForSearch() {
        if (earthMcApi == null) return;
        // Bulk-fetch details for the visible search nations in one /nations request. The active/projection
        // lookup is NOT fired here — it's on-demand for the selected nation only (nationBonusProjection).
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (String name : TownSearchOverlay.visibleNationMatches(apiNations)) {
            String key = townKey(name);
            if (nationDetailsCache.containsKey(key) || nationDetailsLoading.contains(key)
                    || requestDeferred(nationDetailsDeferredAt, key, now)) continue;
            names.add(name);
        }
        requestNationDetailsBulk(names);
    }

    private static void requestNationCapitalDetails() {
        if (earthMcApi == null || apiNations.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastNationCapitalDetailsRequestMs < 1_000L) return;
        lastNationCapitalDetailsRequestMs = now;
        // Warm capital-star details a whole batch per cycle (one bulk /nations) instead of two singles.
        List<String> names = new ArrayList<>();
        for (EarthMcNationData nation : apiNations) {
            String key = townKey(nation.name());
            if (nationDetailsCache.containsKey(key) || nationDetailsLoading.contains(key)) continue;
            names.add(nation.name());
            if (names.size() >= NATION_BULK_PER_CYCLE) break;
        }
        requestNationDetailsBulk(names);
    }

    /** Fetches details for many nations in one bulk /nations request (≤100 names/query) instead of one
     *  POST per nation. Applies any cached active count; nations the API didn't return are deferred. */
    private static void requestNationDetailsBulk(List<String> names) {
        if (earthMcApi == null || names.isEmpty()) return;
        List<String> keys = new ArrayList<>(names.size());
        for (String name : names) {
            String key = townKey(name);
            keys.add(key);
            nationDetailsLoading.add(key);
        }
        earthMcApi.fetchNations(names).whenComplete((result, error) -> {
            try {
                long doneAt = System.currentTimeMillis();
                for (int i = 0; i < keys.size(); i++) {
                    String key = keys.get(i);
                    EarthMcNationData data = result == null ? null : result.get(key);
                    if (data != null) {
                        Integer a = nationActiveCache.get(key);
                        EarthMcNationData stored = (a != null && a >= 0) ? data.withActiveResidentCount(a) : data;
                        nationDetailsCache.put(key, stored);
                        nationDetailsCache.put(townKey(data.name()), stored);
                        nationDetailsDeferredAt.remove(key);
                    } else {
                        deferRequest(nationDetailsDeferredAt, key, doneAt);
                    }
                }
            } finally {
                for (String key : keys) nationDetailsLoading.remove(key);
            }
        });
    }

    // Single-nation fetch for one town's nation (hover/selected town). The bulk path handles the stars and
    // search rows, so this only ever runs for one nation at a time and needs no concurrency cap of its own.
    private static boolean requestNationDetails(String name) {
        if (earthMcApi == null || name == null || name.isBlank()) return false;
        String key = townKey(name);
        long now = System.currentTimeMillis();
        if (requestDeferred(nationDetailsDeferredAt, key, now)) return false;
        if (nationDetailsCache.containsKey(key) || !nationDetailsLoading.add(key)) return false;
        earthMcApi.fetchNation(name).whenComplete((data, error) -> {
            if (data != null) {
                Integer a = nationActiveCache.get(key);
                EarthMcNationData stored = (a != null && a >= 0) ? data.withActiveResidentCount(a) : data;
                nationDetailsCache.put(key, stored);
                nationDetailsCache.put(townKey(data.name()), stored);
                nationDetailsDeferredAt.remove(key);
            } else if (error != null) {
                deferRequest(nationDetailsDeferredAt, key, System.currentTimeMillis());
            }
            nationDetailsLoading.remove(key);
        });
        return true;
    }

    private static String townKey(String townName) {
        return townName == null ? "" : townName.toLowerCase(Locale.ROOT);
    }

    public static boolean isFavorite(String townName) {
        if (config == null || townName == null) return false;
        return favoriteTownKeys().contains(townKey(townName));
    }

    private static void toggleFavorite(String townName) {
        if (config == null || townName == null || townName.isBlank()) return;
        String key = townKey(townName);
        boolean removed = config.favoriteTowns.removeIf(name -> townKey(name).equals(key));
        if (!removed) {
            config.favoriteTowns.add(townName);
        }
        cachedFavoriteTownCount = -1;
        config.save();
    }

    public static Set<String> favoriteTownKeys() {
        if (config == null) return Set.of();
        if (cachedFavoriteTownCount == config.favoriteTowns.size()) return cachedFavoriteTownKeys;
        Set<String> keys = ConcurrentHashMap.newKeySet();
        for (String favorite : config.favoriteTowns) {
            keys.add(townKey(favorite));
        }
        cachedFavoriteTownKeys = Set.copyOf(keys);
        cachedFavoriteTownCount = config.favoriteTowns.size();
        return cachedFavoriteTownKeys;
    }

    private static void loadTownDetailsCache() {
        Path path = townDetailsCachePath();
        if (!Files.isRegularFile(path)) return;
        try {
            String json = Files.readString(path);
            if (!json.contains("canOutsidersSpawn")
                    || !json.contains("isOverClaimed")
                    || !json.contains("isOpen")
                    || !json.contains("isForSale")
                    || !json.contains("hasNation")
                    || !json.contains("maxChunks")) {
                LOGGER.info("[TownyMap] Ignoring old town detail cache without status overlay fields");
                return;
            }
            Map<String, TownPopupData> loaded = GSON.fromJson(json, TOWN_CACHE_TYPE);
            if (loaded != null) {
                townDetailsCache.putAll(loaded);
                for (String key : loaded.keySet()) {
                    townDetailsFetchedAt.put(key, 0L);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[TownyMap] Failed to load town detail cache: {}", e.getMessage());
        }
    }

    private static void scheduleTownDetailsCacheSave() {
        if (!townDetailsSaveScheduled.compareAndSet(false, true)) return;
        CACHE_SAVE_EXECUTOR.schedule(() -> {
            try {
                saveTownDetailsCacheNow();
            } finally {
                townDetailsSaveScheduled.set(false);
            }
        }, TOWN_DETAILS_SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void saveTownDetailsCacheNow() {
        Path path = townDetailsCachePath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(townDetailsCache));
        } catch (Exception e) {
            LOGGER.debug("[TownyMap] Failed to save town detail cache: {}", e.getMessage());
        }
    }

    private static Path townDetailsCachePath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("townymapaddon")
                .resolve("town-details-cache.json");
    }

    public static void requestMinimapTownHighlightRefresh() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> {
            try {
                Object session = Class.forName("xaero.common.XaeroMinimapSession")
                        .getMethod("getCurrentSession")
                        .invoke(null);
                if (session == null) return;
                Object processor = session.getClass()
                        .getMethod("getMinimapProcessor")
                        .invoke(session);
                if (processor == null) return;
                Object writer = processor.getClass()
                        .getMethod("getMinimapWriter")
                        .invoke(processor);
                if (writer == null) return;
                Object handler = writer.getClass()
                        .getMethod("getDimensionHighlightHandler")
                        .invoke(writer);
                if (handler == null) return;
                handler.getClass().getMethod("requestRefresh").invoke(handler);
                LOGGER.debug("[TownyMap] Requested Xaero minimap town highlight refresh");
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.debug("[TownyMap] Xaero minimap highlight refresh unavailable: {}", e.getMessage());
            }
        });
    }

    public static RecruitmentPlayerProfile recruitmentPlayerProfile(String name) {
        if (name == null || name.isBlank()) return null;
        EarthMcPlayerData data = playerDetailsCache.get(townKey(name));
        if (data == null || data.registeredMs() <= 0L) return null;
        return new RecruitmentPlayerProfile(data.name(), data.townName(), data.nationName(), data.registeredMs());
    }

    public static boolean requestRecruitmentPlayerProfile(String name) {
        if (name == null || name.isBlank()) return false;
        String key = townKey(name);
        return playerDetailsCache.containsKey(key) || playerDetailsLoading.contains(key);
    }

    public static TownyMapConfig     getConfig()    { return config;    }
    public static SquaremapApiClient getApiClient() { return apiClient; }

    public record RecruitmentPlayerProfile(String name, String town, String nation, long registeredMs) {}
}

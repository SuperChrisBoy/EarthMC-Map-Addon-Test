package net.townymap.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2fStack;
import net.townymap.TownyMapMod;
import net.townymap.gui.TownInfoOverlay;
import net.townymap.gui.TownSearchOverlay;
import net.townymap.model.MapJumpTarget;
import net.townymap.model.TownData;
import org.objectweb.asm.Opcodes;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Injects into Xaero's GuiMap.
 *
 * Rendering order inside method_25394 (Screen.render):
 *   1. renderPreDropdown — waypoints, labels, town overlays (HEAD/RETURN inject here)
 *   2. Squaremap tile compositing (DrawContext flush in onBeforePlayerArrow)
 *   3. Xaero's player arrow (drawArrowOnMap via vertex buffers)
 *   4. method_25394 RETURN — our arrow re-draw fires here, guaranteed on top of tiles
 *
 * Town overlays and info UI render at renderPreDropdown HEAD (clean matrix state).
 * The player arrow MUST render at method_25394 RETURN so it lands after the
 * squaremap DrawContext flush, not before it.
 *
 * method_25402 = mouseClicked(Click, boolean) in MC 1.21.11.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class MixinGuiMap {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final AtomicBoolean MAP_SURFACE_ERROR_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean RENDER_ERROR_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean CLICK_ERROR_LOGGED = new AtomicBoolean(false);
    @org.spongepowered.asm.mixin.Unique
    private boolean townymap$widgetsHidden = false;
    /** Widgets wider or taller than this share of the screen are never hidden — that's map surface, not UI. */
    @org.spongepowered.asm.mixin.Unique
    private static final double TOWNYMAP_MAX_HIDEABLE_FRACTION = 0.4;
    // TEST: how much further "World Map Overview" lets you zoom out (Xaero's min destScale / this).
    private static final double WORLD_MAP_OVERVIEW_FACTOR = 8.0;

    @Shadow(remap = false) private double cameraX;
    @Shadow(remap = false) private double cameraZ;
    @Shadow(remap = false) private double scale;
    @Shadow(remap = false) private double screenScale;

    // Extend Xaero's zoom-out floor when "World Map Overview" is on: Xaero clamps the world-map zoom
    // at 0.0625; we lower that floor so the user can zoom out far enough to see the whole EarthMC map.
    // Because we only change Xaero's own zoom, the tiles, player arrow and our overlay all share one
    // scale and stay aligned. Xaero <=1.41.x clamps inline in changeZoom; 1.42.0 moved the clamp into
    // a new applyZoomLimits() (which silently broke the old single hook on 26.2). Patch the 0.0625
    // floor in BOTH, each require = 0, so whichever method the installed Xaero has gets patched and
    // the other no-ops instead of failing.
    @ModifyConstant(method = "changeZoom", constant = @Constant(doubleValue = 0.0625), require = 0, remap = false)
    private double townymap$extendWorldMapZoomOut(double original) {
        return (TownyMapMod.getConfig() != null && TownyMapMod.getConfig().worldMapOverview)
                ? original / WORLD_MAP_OVERVIEW_FACTOR
                : original;
    }

    @ModifyConstant(method = "applyZoomLimits", constant = @Constant(doubleValue = 0.0625), require = 0, remap = false)
    private double townymap$extendWorldMapZoomOutModern(double original) {
        return (TownyMapMod.getConfig() != null && TownyMapMod.getConfig().worldMapOverview)
                ? original / WORLD_MAP_OVERVIEW_FACTOR
                : original;
    }

    // ── All overlay rendering at HEAD (clean GL/matrix state) ─────────────────

    @Inject(require = 0, 
            method = "method_25394",
            at = @At(
                    value = "FIELD",
                    target = "Lxaero/map/common/config/option/WorldMapProfiledConfigOptions;ARROW:Lxaero/lib/common/config/option/BooleanConfigOption;",
                    opcode = Opcodes.GETSTATIC,
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void onBeforePlayerArrow(DrawContext ctx, int mouseX, int mouseY,
                                     float delta, CallbackInfo ci) {
        // Clear the search bar when the map is reopened (new GuiMap instance) or panned. Tracked on the
        // raw camera every frame, regardless of dimension. jumpTo() suppresses the next pan-clear so that
        // centre-on-select doesn't wipe the bar.
        TownyMapMod.onWorldMapFrame(this, cameraX, cameraZ);
        if (TownyMapMod.isAccessBlocked()) return;
        // The EarthMC map is overworld-only. Outside the overworld our overlay is hidden, except in
        // "Overworld Coords" mode in the Nether, where we scale the overlay's camera x8 and its
        // block-scale /8 so the overworld map/towns line up exactly over Xaero's real Nether tiles.
        double dimMul = TownyMapMod.worldMapOverlayScale();
        if (dimMul <= 0.0) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            int w = mc.getWindow().getScaledWidth();
            int h = mc.getWindow().getScaledHeight();
            double guiScale = (screenScale > 0) ? scale / screenScale : scale;
            double camX = cameraX * dimMul;
            double camZ = cameraZ * dimMul;
            double mapScale = guiScale / dimMul;
            TownyMapMod.renderSquaremapBackground(ctx, camX, camZ, mapScale, w, h);
            TownyMapMod.renderOnWorldMap(ctx, camX, camZ, mapScale, w, h);
            if (mapScale > 0) {
                double worldX = (mouseX - w / 2.0) / mapScale + camX;
                double worldZ = (mouseY - h / 2.0) / mapScale + camZ;
                TownyMapMod.renderHoveredWorldMapChunk(ctx, camX, camZ, mapScale, w, h, worldX, worldZ);
                TownyMapMod.renderChunkCounter(ctx, camX, camZ, mapScale, w, h, worldX, worldZ);
            }
            ctx.drawDeferredElements();
            clearDepthForXaeroArrowIfAvailable();
            disableDepthTestIfAvailable();
        } catch (Exception e) {
            logOnce(MAP_SURFACE_ERROR_LOGGED, "Failed to render world-map surface overlay", e);
        }
    }

    @Inject(require = 0, method = "renderPreDropdown", at = @At("HEAD"), remap = false)
    private void onRenderPreDropdown(DrawContext ctx, int mouseX, int mouseY,
                                     float delta, CallbackInfo ci) {
        if (TownyMapMod.isAccessBlocked()) return;
        // Freshness line goes HERE, not in the overlay inject: the squaremap tiles are drawn after that
        // one and painted straight over it, so the line vanished whenever the layer was switched on.
        // renderPreDropdown runs late enough to sit on top of everything. Screen-space at a fixed Y, so
        // it lands under Xaero's coordinates from here too, and it still reports outside the overworld.
        try {
            TownyMapMod.renderMapDataStatus(ctx);
        } catch (Throwable ignored) {
            // A broken status line must never take the map down with it -- that has happened twice.
        }
        try {
            // Hide Xaero's own buttons for the capture too. Only SMALL widgets are touched: hiding every
            // widget last time took Xaero's map surface with it, so anything occupying a large share of the
            // screen is left alone no matter what it is. Restored the frame after, and the armed state times
            // out on its own, so this can't get stuck the way it did before.
            boolean composing = TownyMapMod.hideChromeForScreenshot();
            if (composing != townymap$widgetsHidden) {
                townymap$setSmallWidgetsVisible(!composing);
                townymap$widgetsHidden = composing;
            }
            // Arrow first, so the UI panels below queue on top of it in the batch.
            // It's a "you are here" marker, so it has no place in a shared picture of the map.
            if (!composing) renderPlayerArrow(ctx);

            MinecraftClient mc = MinecraftClient.getInstance();
            int w = mc.getWindow().getScaledWidth();
            int h = mc.getWindow().getScaledHeight();
            // Player dots here (not in the tile batch of onBeforePlayerArrow): the tiles are already flushed,
            // so the dots render on top of them and stop "blinking" behind async tile rebuilds at zoom-out.
            double dimMul = TownyMapMod.worldMapOverlayScale();
            if (dimMul > 0.0) {
                double guiScale = (screenScale > 0) ? scale / screenScale : scale;
                double mapScale = guiScale / dimMul;
                TownyMapMod.renderWorldMapLatePass(ctx, cameraX * dimMul, cameraZ * dimMul, mapScale, w, h);
            }
            // A clean map screenshot skips our own chrome for the frame, so the capture is just the map.
            if (!TownyMapMod.hideChromeForScreenshot()) {
                double[] world = overlayWorldFromScreen(mouseX, mouseY, w, h);
                if (world != null) {
                    TownyMapMod.renderTownHover(ctx, mouseX, mouseY, world[0], world[1], w, h);
                }
                TownyMapMod.renderTownInfo(ctx, w, h);
                TownyMapMod.renderMapToggles(ctx, h);
                TownyMapMod.renderPlanningCounter(ctx, w, h);
                TownyMapMod.renderTownSearch(ctx, w, h);
                TownyMapMod.renderArchiveBanner(ctx, w);
            }
            TownyMapMod.captureMapScreenshotIfArmed();
        } catch (Exception e) {
            logOnce(RENDER_ERROR_LOGGED, "Failed to render Xaero world-map overlay", e);
        }
    }

    // Player arrow, drawn via DrawContext at the START of renderPreDropdown.
    //
    // Layering requirement: the arrow must sit ABOVE the squaremap tiles but BELOW
    // our UI panels (search bar, town info, toggles).  The chronology in
    // method_25394 is: onBeforePlayerArrow (tiles drawn + drawDeferredElements
    // flush) → renderPreDropdown (our overlays) → RETURN.  Drawing the arrow here,
    // before the UI panels, queues it into the deferred batch ahead of them, so the
    // arrow renders under the UI; and since the tiles were already flushed in
    // onBeforePlayerArrow, the arrow still renders on top of the tiles.
    //
    // (Previously this drew at method_25394 RETURN, which queued the arrow AFTER the
    // UI panels — making the arrow draw over the search box.)
    private void renderPlayerArrow(DrawContext ctx) {
        try {
            if (!TownyMapMod.shouldRenderWorldMapIndicatorOverlay()) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            ClientPlayerEntity player = mc.player;
            if (player == null || mc.world == null) return;

            int w = mc.getWindow().getScaledWidth();
            int h = mc.getWindow().getScaledHeight();
            double guiScale = (screenScale > 0) ? scale / screenScale : scale;
            if (guiScale <= 0) return;

            double dx = player.getX() - cameraX;
            double dz = player.getZ() - cameraZ;
            float sx = (float) (w / 2.0 + dx * guiScale);
            float sy = (float) (h / 2.0 + dz * guiScale);
            if (sx < -32 || sx > w + 32 || sy < -32 || sy > h + 32) return;

            float yawRad = (float) Math.toRadians(player.getYaw());
            Matrix3x2fStack m = ctx.getMatrices();

            // Replicate Xaero's own arrow sizing.
            // Xaero passes sc = scaleMultiplier / scale to drawObjectOnMap, which then
            // calls matrixStack.scale(sc, sc, 1) in a coordinate space where 1 unit =
            // 1 world block (their map matrix already encodes guiScale).  So the arrow
            // appears 26 * sc * guiScale = 26 * smult / screenScale GUI pixels wide —
            // constant regardless of zoom, only growing on HiDPI screens > 1080 px tall.
            int fwMin = Math.min(mc.getWindow().getFramebufferWidth(),
                                 mc.getWindow().getFramebufferHeight());
            double scaleMultiplier = fwMin <= 1080 ? 1.0 : fwMin / 1080.0;
            float arrowScale = (float) Math.max(0.2, Math.min(2.0,
                    scaleMultiplier / Math.max(1, screenScale)));
            float shadowOffset = 2f * arrowScale;

            // Shadow — offset south in pre-rotation space, then scale
            m.pushMatrix();
            m.translate(sx, sy + shadowOffset);
            m.rotate(yawRad);
            m.scale(arrowScale, arrowScale);
            drawXaeroArrowSprite(ctx, 0xE5000000);
            m.popMatrix();

            // Main arrow — Xaero colour: r=1, g=0.08, b=0.08, a=1
            m.pushMatrix();
            m.translate(sx, sy);
            m.rotate(yawRad);
            m.scale(arrowScale, arrowScale);
            drawXaeroArrowSprite(ctx, 0xFFFF1414);
            m.popMatrix();
        } catch (Exception e) {
            logOnce(RENDER_ERROR_LOGGED, "Failed to render world-map arrow overlay", e);
        }
    }

    /**
     * Draws Xaero's own arrow sprite (from assets/xaeroworldmap/gui/gui.png) via
     * DrawContext so it composites on top of the squaremap deferred tile batch.
     *
     * UV in the 256×256 sheet: origin (13, 5), size 26×28.
     * Xaero centers it by drawing at screen position (−13, −5) in local space.
     * The caller must have already applied the player-yaw rotation to the matrix.
     *
     * @param color ARGB tint — 0xFFFF1414 for the red arrow, 0xE5000000 for shadow.
     */
    private static final Identifier XAERO_GUI = Identifier.of("xaeroworldmap", "gui/gui.png");

    private static void drawXaeroArrowSprite(DrawContext ctx, int color) {
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, XAERO_GUI,
                -13, -5,   // screen position (centers the 26-wide sprite at x=0)
                0f, 0f,    // UV start in the 256×256 sheet (sprite is at top-left)
                26, 28,    // sprite size
                256, 256,  // full texture size
                color);
    }

    /** Converts a screen position to the EarthMC overlay's WORLD coords, applying the same dimension scale
     *  (dimMul) the overlay renders with. In the Nether's "Overworld Coords" mode the overlay is drawn at
     *  overworld scale (dimMul=8), so without this a hover/click would look up towns at raw Nether
     *  coordinates and always land on wilderness. Returns null when the overlay isn't shown. */
    private double[] overlayWorldFromScreen(double screenX, double screenY, int sw, int sh) {
        double dimMul = TownyMapMod.worldMapOverlayScale();
        if (dimMul <= 0.0) return null;
        double guiScale = (screenScale > 0) ? scale / screenScale : scale;
        if (guiScale <= 0) return null;
        double mapScale = guiScale / dimMul;
        return new double[] {
                (screenX - sw / 2.0) / mapScale + cameraX * dimMul,
                (screenY - sh / 2.0) / mapScale + cameraZ * dimMul
        };
    }

    // ── Mouse click ───────────────────────────────────────────────────────────

    @Inject(require = 0, method = "method_25402", at = @At("HEAD"), remap = false, cancellable = true)
    private void onMouseClicked(Click click, boolean bl,
                                CallbackInfoReturnable<Boolean> cir) {
        if (TownyMapMod.isAccessBlocked()) return;   // let Xaero handle it as if we were not installed
        try {
            int button = click.buttonInfo().button();
            MinecraftClient mc = MinecraftClient.getInstance();
            int sw = mc.getWindow().getScaledWidth();
            int sh = mc.getWindow().getScaledHeight();

            if (button == 0 && net.townymap.gui.TownSearchOverlay.isExpandClick(click.x(), click.y())) {
                TownyMapMod.openStatsPanel();
                cir.setReturnValue(true);
                return;
            }
            if (button == 0) {
                // The freshness line's [R] button, checked before the search bar so it wins the click.
                if (TownyMapMod.clickMapDataStatus(click.x(), click.y())) {
                    cir.setReturnValue(true);
                    return;
                }
                TownSearchOverlay.ClickResult result =
                        TownyMapMod.onTownSearchClick(click.x(), click.y(), sw, sh);
                if (result.consumed()) {
                    handleJumpOrRoute(result.target());
                    cir.setReturnValue(true);
                    return;
                }
            }

            if (button == 0) {
                TownInfoOverlay.ActionResult action = TownyMapMod.onTownInfoClick(click.x(), click.y());
                if (action.action() != TownInfoOverlay.Action.NONE) {
                    cir.setReturnValue(true);
                    return;
                }
            }

            if (button == 0 && TownyMapMod.onSettingsButtonClick(click.x(), click.y(), sh)) {
                TownyMapMod.openConfigScreen();
                cir.setReturnValue(true);
                return;
            }

            if (button == 0 && TownyMapMod.onMapToggleClick(click.x(), click.y(), sh)) {
                cir.setReturnValue(true);
                return;
            }

            // Planning counter chips ("+" arms placement, T# removes that planned town).
            if (button == 0 && TownyMapMod.onPlanningCounterClick(click.x(), click.y())) {
                cir.setReturnValue(true);
                return;
            }

            // With "+" armed, the next map click drops a planned town instead of selecting anything.
            if (button == 0) {
                double dimMul = TownyMapMod.worldMapOverlayScale();
                double guiScale = (screenScale > 0) ? scale / screenScale : scale;
                if (dimMul > 0.0 && guiScale > 0.0
                        && TownyMapMod.onPlanningMapClick(click.x(), click.y(),
                                cameraX * dimMul, cameraZ * dimMul, guiScale / dimMul, sw, sh)) {
                    cir.setReturnValue(true);
                    return;
                }
            }

            // Left-click a date-step arrow under the archive banner (±1 / ±10 days).
            if (button == 0 && TownyMapMod.onArchiveNavClick(click.x(), click.y())) {
                cir.setReturnValue(true);
                return;
            }

            // Left-click the archive banner exits archive mode.
            if (button == 0 && TownyMapMod.onArchiveBannerClick(click.x(), click.y())) {
                cir.setReturnValue(true);
                return;
            }

            // Left-click a player dot/head → open the small player info panel (which has Expand). Uses the
            // overlay's own camera + scale so the hit-test matches exactly where the markers were drawn.
            if (button == 0) {
                double dimMul = TownyMapMod.worldMapOverlayScale();
                double guiScale = (screenScale > 0) ? scale / screenScale : scale;
                if (dimMul > 0.0 && guiScale > 0.0
                        && TownyMapMod.onMapPlayerClick(click.x(), click.y(),
                                cameraX * dimMul, cameraZ * dimMul, guiScale / dimMul, sw, sh)) {
                    cir.setReturnValue(true);
                    return;
                }
            }

            // Left-click a nation capital star → open the small nation info panel (which has Expand). The
            // star screen positions are recorded as drawn, so this is a direct screen-space hit-test.
            if (button == 0 && TownyMapMod.onMapNationStarClick(click.x(), click.y())) {
                cir.setReturnValue(true);
                return;
            }

            // Right-click on our buttons: consume so it never falls through to the map (no
            // town/wilderness selection behind the button), and cycle mode toggles backward.
            if (button == 1 && TownyMapMod.onSettingsButtonClick(click.x(), click.y(), sh)) {
                cir.setReturnValue(true);
                return;
            }
            if (button == 1 && TownyMapMod.onMapToggleClick(click.x(), click.y(), sh, true)) {
                cir.setReturnValue(true);
                return;
            }

            if (button == 1 && TownyMapMod.isChunkCounterActive()) {
                double[] world = overlayWorldFromScreen(click.x(), click.y(), sw, sh);
                if (world != null) {
                    TownyMapMod.onChunkCounterClick(world[0], world[1]);
                }
                cir.setReturnValue(true);
                return;
            }

            if (button == 0) {
                TownyMapMod.armMapClickDismiss(cameraX, cameraZ);   // dismiss the search/popup unless this
                return;                                             // click turns into a pan-drag (keeps it)
            }
            if (button != 1) return;

            double[] world = overlayWorldFromScreen(click.x(), click.y(), sw, sh);
            if (world == null) return;

            TownyMapMod.onMapRightClick(world[0], world[1], (int) click.x(), (int) click.y());
            cir.setReturnValue(true);
        } catch (Exception e) {
            logOnce(CLICK_ERROR_LOGGED, "Failed to handle Xaero world-map click", e);
        }
    }

    @Inject(require = 0, method = "method_25404", at = @At("HEAD"), remap = false, cancellable = true)
    private void onKeyPressed(KeyInput input,
                              CallbackInfoReturnable<Boolean> cir) {
        if (TownyMapMod.isAccessBlocked()) return;   // let Xaero handle it as if we were not installed
        try {
            // The clean-screenshot key is a normal rebindable keybind (Options -> Controls). Screens
            // swallow key presses, so match it here too — but never while the search bar has focus.
            if (!TownSearchOverlay.isFocused()
                    && net.townymap.input.TownyMapKeybinds.isMapScreenshotKey(input)) {
                TownyMapMod.armMapScreenshot();
                cir.setReturnValue(true);
                return;
            }
            // Same story for refresh and the info panel: the map screen eats key presses, so the binds
            // have to be matched here as well to work with the map open. Never while the search bar has
            // focus, or typing "r" into it would reload the claims.
            if (!TownSearchOverlay.isFocused()) {
                if (net.townymap.input.TownyMapKeybinds.isRefreshKey(input)) {
                    TownyMapMod.refreshTownClaimsFromSettings();
                    cir.setReturnValue(true);
                    return;
                }
                if (net.townymap.input.TownyMapKeybinds.isOpenStatsKey(input)) {
                    TownyMapMod.openStatsPanel();
                    cir.setReturnValue(true);
                    return;
                }
            }
            TownSearchOverlay.ClickResult result = TownyMapMod.onTownSearchKeyPressed(input.key());
            if (result.consumed()) {
                jumpTo(result.target());
                cir.setReturnValue(true);
            }
        } catch (Exception e) {
            logOnce(CLICK_ERROR_LOGGED, "Failed to handle Xaero world-map key press", e);
        }
    }

    @Inject(require = 0, method = "method_25400", at = @At("HEAD"), remap = false, cancellable = true)
    private void onCharTyped(CharInput input, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!input.isValidChar()) return;
            boolean consumed = false;
            String text = input.asString();
            for (int i = 0; i < text.length(); i++) {
                consumed |= TownyMapMod.onTownSearchCharTyped(text.charAt(i));
            }
            if (consumed) {
                cir.setReturnValue(true);
            }
        } catch (Exception e) {
            logOnce(CLICK_ERROR_LOGGED, "Failed to handle Xaero world-map text input", e);
        }
    }

    // Search results carry EarthMC (overworld) coordinates, but Xaero's camera is in the player's
    // current dimension. Without the divide, jumping to a town from the Nether centred the map 8x
    // too far out and the target wasn't on screen at all.
    private void jumpTo(TownData town) {
        if (town == null) return;
        TownyMapMod.suppressNextPanClear();   // centring on a selected result isn't a user pan
        double dimScale = TownyMapMod.worldMapCoordinateScale();
        cameraX = town.centerX() / dimScale;
        cameraZ = town.centerZ() / dimScale;
    }

    private void jumpTo(MapJumpTarget target) {
        if (target == null) return;
        TownyMapMod.suppressNextPanClear();   // centring on a selected result isn't a user pan
        double dimScale = TownyMapMod.worldMapCoordinateScale();
        cameraX = target.x() / dimScale;
        cameraZ = target.z() / dimScale;
    }

    private void handleJumpOrRoute(MapJumpTarget target) {
        if (target == null) return;
        if (isShiftDown()) {
            TownyMapMod.createXaeroRoute(target);
            return;
        }
        jumpTo(target);
    }

    private static boolean isShiftDown() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        long handle = mc.getWindow().getHandle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }


    @org.spongepowered.asm.mixin.Unique
    private void townymap$setSmallWidgetsVisible(boolean visible) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.getWindow() == null) return;
            int sw = mc.getWindow().getScaledWidth();
            int sh = mc.getWindow().getScaledHeight();
            for (net.minecraft.client.gui.Element e
                    : ((net.minecraft.client.gui.screen.Screen) (Object) this).children()) {
                if (!(e instanceof net.minecraft.client.gui.widget.ClickableWidget w)) continue;
                if (w.getWidth() > sw * TOWNYMAP_MAX_HIDEABLE_FRACTION
                        || w.getHeight() > sh * TOWNYMAP_MAX_HIDEABLE_FRACTION) {
                    continue;   // too big to be a button; leave it alone
                }
                w.visible = visible;
            }
        } catch (Exception ignored) {
            // Best-effort: Xaero draws most of its map UI itself rather than as widgets.
        }
    }

    private static void logOnce(AtomicBoolean flag, String message, Exception e) {
        if (flag.compareAndSet(false, true)) {
            LOGGER.warn("[TownyMap] {}", message, e);
        }
    }

    private static void disableDepthTestIfAvailable() {
        for (String className : new String[]{
                "com.mojang.blaze3d.opengl.GlStateManager",
                "com.mojang.blaze3d.platform.GlStateManager"
        }) {
            try {
                Class<?> stateManager = Class.forName(className);
                stateManager.getMethod("_disableDepthTest").invoke(null);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
    }

    /**
     * No-op on current Xaero: {@code xaero.lib.client.graphics.util.TextureUtils} is absent from both
     * the 26.4.2 minimap and 1.44.2 world map jars, so the loop below never finds a match. Kept because
     * it costs nothing and would start working again if the class returns; the arrow no longer depends
     * on it either way, since we draw our own unconditionally.
     */
    private static void clearDepthForXaeroArrowIfAvailable() {
        try {
            Object framebuffer = MinecraftClient.getInstance().getFramebuffer();
            Class<?> textureUtils = Class.forName("xaero.lib.client.graphics.util.TextureUtils");
            for (Method method : textureUtils.getMethods()) {
                if (!"clearRenderTargetDepth".equals(method.getName()) || method.getParameterCount() != 2) continue;
                Class<?>[] params = method.getParameterTypes();
                if (!params[0].isInstance(framebuffer) || params[1] != float.class) continue;
                method.invoke(null, framebuffer, 1.0F);
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        }
    }
}

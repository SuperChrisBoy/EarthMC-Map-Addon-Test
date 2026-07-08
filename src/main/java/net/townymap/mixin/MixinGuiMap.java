package net.townymap.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
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
 * Rendering order inside extractRenderState (Screen.render):
 *   1. renderPreDropdown — waypoints, labels, town overlays (HEAD/RETURN inject here)
 *   2. Squaremap tile compositing (GuiGraphicsExtractor flush in onBeforePlayerArrow)
 *   3. Xaero's player arrow (drawArrowOnMap via vertex buffers)
 *   4. extractRenderState RETURN — our arrow re-draw fires here, guaranteed on top of tiles
 *
 * Town overlays and info UI render at renderPreDropdown HEAD (clean matrix state).
 * The player arrow MUST render at extractRenderState RETURN so it lands after the
 * squaremap GuiGraphicsExtractor flush, not before it.
 *
 * mouseClicked = mouseClicked(MouseButtonEvent, boolean) in MC 1.21.11.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class MixinGuiMap {

    private static final Logger LOGGER = LoggerFactory.getLogger("TownyMapAddon");
    private static final AtomicBoolean MAP_SURFACE_ERROR_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean RENDER_ERROR_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean CLICK_ERROR_LOGGED = new AtomicBoolean(false);

    // MC 26.2 fires a phantom left-click immediately after every right-click; we swallow one left-click
    // within this window of a right-click so it can't dismiss the town popup the right-click just opened.
    private static final long SPURIOUS_LEFT_CLICK_NANOS = 70_000_000L;   // 70ms
    private long lastRightClickNanos = 0L;

    @Shadow(remap = false) private double cameraX;
    @Shadow(remap = false) private double cameraZ;
    @Shadow(remap = false) private double scale;
    @Shadow(remap = false) private double screenScale;

    // ── All overlay rendering at HEAD (clean GL/matrix state) ─────────────────

    @Inject(
            method = "extractRenderState",
            at = @At(
                    value = "FIELD",
                    target = "Lxaero/map/common/config/option/WorldMapProfiledConfigOptions;ARROW:Lxaero/lib/common/config/option/BooleanConfigOption;",
                    opcode = Opcodes.GETSTATIC,
                    shift = At.Shift.BEFORE
            ),
            remap = false
    )
    private void onBeforePlayerArrow(GuiGraphicsExtractor ctx, int mouseX, int mouseY,
                                     float delta, CallbackInfo ci) {
        // Clear the search bar when the map is reopened (new GuiMap instance) or panned. Tracked on the
        // raw camera every frame, regardless of dimension. jumpTo() suppresses the next pan-clear so that
        // centre-on-select doesn't wipe the bar.
        TownyMapMod.onWorldMapFrame(this, cameraX, cameraZ);
        // The EarthMC map is overworld-only. Outside the overworld our overlay is hidden, except in
        // "Overworld Coords" mode in the Nether, where we scale the overlay's camera x8 and its
        // block-scale /8 so the overworld map/towns line up exactly over Xaero's real Nether tiles.
        double dimMul = TownyMapMod.worldMapOverlayScale();
        if (dimMul <= 0.0) return;
        try {
            Minecraft mc = Minecraft.getInstance();
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
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
            ctx.extractDeferredElements(mouseX, mouseY, delta);
            clearDepthForXaeroArrowIfAvailable();
            disableDepthTestIfAvailable();
        } catch (Exception e) {
            logOnce(MAP_SURFACE_ERROR_LOGGED, "Failed to render world-map surface overlay", e);
        }
    }

    // "World Map Overview": lower Xaero's world-map zoom-out floor (the 0.0625 clamp) so you can zoom
    // out to the whole EarthMC map. Xaero <=1.41.0 clamped inline in changeZoom; 1.42.0 moved the clamp
    // into a new applyZoomLimits() method (which silently broke this hook). We patch the 0.0625 floor in
    // BOTH, each with require = 0, so whichever the installed Xaero version has gets patched and the
    // other no-ops instead of failing — future-proof across the update.
    @ModifyConstant(method = "changeZoom", constant = @Constant(doubleValue = 0.0625), require = 0, remap = false)
    private double townymap$extendWorldMapZoomOut(double original) {
        return (TownyMapMod.getConfig() != null && TownyMapMod.getConfig().worldMapOverview)
                ? original / 8.0
                : original;
    }

    @ModifyConstant(method = "applyZoomLimits", constant = @Constant(doubleValue = 0.0625), require = 0, remap = false)
    private double townymap$extendWorldMapZoomOutModern(double original) {
        return (TownyMapMod.getConfig() != null && TownyMapMod.getConfig().worldMapOverview)
                ? original / 8.0
                : original;
    }

    @Inject(method = "renderPreDropdown", at = @At("HEAD"), remap = false)
    private void onRenderPreDropdown(GuiGraphicsExtractor ctx, int mouseX, int mouseY,
                                     float delta, CallbackInfo ci) {
        try {
            // Arrow first, so the UI panels below queue on top of it in the batch.
            renderPlayerArrow(ctx);

            Minecraft mc = Minecraft.getInstance();
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            // Player dots here (not in the tile batch of onBeforePlayerArrow): the tiles are already flushed,
            // so the dots render on top of them and stop "blinking" behind async tile rebuilds at zoom-out.
            double dimMul = TownyMapMod.worldMapOverlayScale();
            if (dimMul > 0.0) {
                double guiScale = (screenScale > 0) ? scale / screenScale : scale;
                double mapScale = guiScale / dimMul;
                TownyMapMod.renderWorldMapPlayers(ctx, cameraX * dimMul, cameraZ * dimMul, mapScale, w, h);
            }
            double[] world = overlayWorldFromScreen(mouseX, mouseY, w, h);
            if (world != null) {
                TownyMapMod.renderTownHover(ctx, mouseX, mouseY, world[0], world[1], w, h);
            }
            TownyMapMod.renderTownInfo(ctx, w, h);
            TownyMapMod.renderMapToggles(ctx, h);
            TownyMapMod.renderTownSearch(ctx, w, h);
        } catch (Exception e) {
            logOnce(RENDER_ERROR_LOGGED, "Failed to render Xaero world-map overlay", e);
        }
    }

    // Player arrow, drawn via GuiGraphicsExtractor at the START of renderPreDropdown.
    //
    // Layering requirement: the arrow must sit ABOVE the squaremap tiles but BELOW
    // our UI panels (search bar, town info, toggles).  The chronology in
    // extractRenderState is: onBeforePlayerArrow (tiles drawn + deferred elements
    // flush) → renderPreDropdown (our overlays) → RETURN.  Drawing the arrow here,
    // before the UI panels, queues it into the deferred batch ahead of them, so the
    // arrow renders under the UI; and since the tiles were already flushed in
    // onBeforePlayerArrow, the arrow still renders on top of the tiles.
    //
    // (Previously this drew at extractRenderState RETURN, which queued the arrow AFTER the
    // UI panels — making the arrow draw over the search box.)
    private void renderPlayerArrow(GuiGraphicsExtractor ctx) {
        try {
            if (!TownyMapMod.shouldRenderWorldMapIndicatorOverlay()) return;
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null) return;

            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            double guiScale = (screenScale > 0) ? scale / screenScale : scale;
            if (guiScale <= 0) return;

            double dx = player.getX() - cameraX;
            double dz = player.getZ() - cameraZ;
            float sx = (float) (w / 2.0 + dx * guiScale);
            float sy = (float) (h / 2.0 + dz * guiScale);
            if (sx < -32 || sx > w + 32 || sy < -32 || sy > h + 32) return;

            float yawRad = (float) Math.toRadians(player.getYRot());
            Matrix3x2fStack m = ctx.pose();

            // Replicate Xaero's own arrow sizing.
            // Xaero passes sc = scaleMultiplier / scale to drawObjectOnMap, which then
            // calls matrixStack.scale(sc, sc, 1) in a coordinate space where 1 unit =
            // 1 world block (their map matrix already encodes guiScale).  So the arrow
            // appears 26 * sc * guiScale = 26 * smult / screenScale GUI pixels wide —
            // constant regardless of zoom, only growing on HiDPI screens > 1080 px tall.
            int fwMin = Math.min(mc.getWindow().getWidth(),
                                 mc.getWindow().getHeight());
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
     * GuiGraphicsExtractor so it composites on top of the squaremap deferred tile batch.
     *
     * UV in the 256×256 sheet: origin (13, 5), size 26×28.
     * Xaero centers it by drawing at screen position (−13, −5) in local space.
     * The caller must have already applied the player-yaw rotation to the matrix.
     *
     * @param color ARGB tint — 0xFFFF1414 for the red arrow, 0xE5000000 for shadow.
     */
    private static final Identifier XAERO_GUI = Identifier.fromNamespaceAndPath("xaeroworldmap", "gui/gui.png");

    private static void drawXaeroArrowSprite(GuiGraphicsExtractor ctx, int color) {
        ctx.blit(RenderPipelines.GUI_TEXTURED, XAERO_GUI,
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

    @Inject(method = "mouseClicked", at = @At("HEAD"), remap = false, cancellable = true)
    private void onMouseClicked(MouseButtonEvent click, boolean bl,
                                CallbackInfoReturnable<Boolean> cir) {
        try {
            int button = click.buttonInfo().button();
            // Swallow 26.2's phantom left-click that trails every right-click (else it dismisses the popup).
            if (button == 1) {
                lastRightClickNanos = System.nanoTime();
            } else if (button == 0 && System.nanoTime() - lastRightClickNanos < SPURIOUS_LEFT_CLICK_NANOS) {
                lastRightClickNanos = 0L;   // consume exactly one phantom left-click per right-click
                cir.setReturnValue(true);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            int sw = mc.getWindow().getGuiScaledWidth();
            int sh = mc.getWindow().getGuiScaledHeight();

            if (button == 0) {
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

            // Right-click on our buttons: consume so it never falls through to the map (no town/wilderness
            // selection behind the button), and cycle the mode toggles BACKWARD.
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

    @Inject(method = "keyPressed", at = @At("HEAD"), remap = false, cancellable = true)
    private void onKeyPressed(KeyEvent input,
                              CallbackInfoReturnable<Boolean> cir) {
        try {
            TownSearchOverlay.ClickResult result = TownyMapMod.onTownSearchKeyPressed(input.key());
            if (result.consumed()) {
                jumpTo(result.target());
                cir.setReturnValue(true);
            }
        } catch (Exception e) {
            logOnce(CLICK_ERROR_LOGGED, "Failed to handle Xaero world-map key press", e);
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), remap = false, cancellable = true)
    private void onCharTyped(CharacterEvent input, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!input.isAllowedChatCharacter()) return;
            boolean consumed = false;
            String text = input.codepointAsString();
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

    private void jumpTo(TownData town) {
        if (town == null) return;
        TownyMapMod.suppressNextPanClear();   // centring on a selected result isn't a user pan
        cameraX = town.centerX();
        cameraZ = town.centerZ();
    }

    private void jumpTo(MapJumpTarget target) {
        if (target == null) return;
        TownyMapMod.suppressNextPanClear();   // centring on a selected result isn't a user pan
        cameraX = target.x();
        cameraZ = target.z();
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
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null) return false;
        long handle = mc.getWindow().handle();
        return GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
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

    private static void clearDepthForXaeroArrowIfAvailable() {
        try {
            Object framebuffer = Minecraft.getInstance().gameRenderer.mainRenderTarget();
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

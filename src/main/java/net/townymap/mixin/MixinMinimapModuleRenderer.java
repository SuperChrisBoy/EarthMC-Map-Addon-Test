package net.townymap.mixin;

import net.minecraft.client.gui.DrawContext;
import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.module.MinimapRenderer;
import xaero.hud.render.module.ModuleRenderContext;

@Mixin(value = MinimapRenderer.class, remap = false)
public class MixinMinimapModuleRenderer {

    @Inject(require = 0, 
            method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/render/module/ModuleRenderContext;Lnet/minecraft/class_332;F)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void townymapaddon$hideMinimapInNether(MinimapSession session,
                                                   ModuleRenderContext renderContext,
                                                   DrawContext drawContext,
                                                   float tickDelta,
                                                   CallbackInfo ci) {
        if (TownyMapMod.shouldHideMinimap()) {
            TownyMapMod.clearSuppressNativeMinimapCompass();
            ci.cancel();
            return;
        }
        TownyMapMod.setSuppressNativeMinimapCompass(session);
    }

    @Inject(require = 0, 
            method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/render/module/ModuleRenderContext;Lnet/minecraft/class_332;F)V",
            at = @At("RETURN"),
            remap = false
    )
    private void townymapaddon$clearNativeCompassSuppression(MinimapSession session,
                                                            ModuleRenderContext renderContext,
                                                            DrawContext drawContext,
                                                            float tickDelta,
                                                            CallbackInfo ci) {
        TownyMapMod.clearSuppressNativeMinimapCompass();
    }

    @Redirect(require = 0, 
            method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/render/module/ModuleRenderContext;Lnet/minecraft/class_332;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/common/minimap/render/MinimapRenderer;renderOutsidePip(Lxaero/hud/minimap/module/MinimapSession;IIIIDFIFLnet/minecraft/class_332;)V"
            ),
            remap = false
    )
    private void townymapaddon$renderTownOutlines(xaero.common.minimap.render.MinimapRenderer renderer,
                                                  MinimapSession session,
                                                  int x, int y, int screenW, int screenH,
                                                  double screenScale, float minimapScale,
                                                  int configuredWidth, float tickDelta,
                                                  DrawContext drawContext,
                                                  MinimapSession originalSession,
                                                  ModuleRenderContext renderContext,
                                                  DrawContext originalDrawContext,
                                                  float originalTickDelta) {
        MinimapBounds bounds = actualMinimapBounds(session, x, y, screenScale, minimapScale,
                renderContext, configuredWidth);
        TownyMapMod.renderOnMinimap(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        // Reset before Xaero draws its info block; our InfoDisplayRenderer mixin records its on-screen
        // bounds during this call so renderMinimapInfoLines (below) can stack under it without overlap.
        TownyMapMod.beginXaeroInfoCapture();
        renderer.renderOutsidePip(session, x, y, screenW, screenH, screenScale, minimapScale,
                configuredWidth, tickDelta, drawContext);
        TownyMapMod.renderMinimapFrame(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        TownyMapMod.renderMinimapNationAlert(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        TownyMapMod.renderMinimapWaypointsOnTop(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        // Draw after renderOutsidePip so our indicator composites on top of Xaero's arrow
        TownyMapMod.renderMinimapPlayerIndicator(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        TownyMapMod.renderMinimapCompassDirections(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        // Anchor our info to the minimap itself (so it moves with it). The method places the block
        // below Xaero's coordinate line, or flips it above and clamps to the screen near the edges.
        TownyMapMod.renderMinimapInfoLines(drawContext,
                bounds.x() + bounds.size() / 2, bounds.y(), bounds.y() + bounds.size());
    }

    private static MinimapBounds actualMinimapBounds(MinimapSession session, int x, int y,
                                                     double screenScale, float minimapScale,
                                                     ModuleRenderContext renderContext,
                                                     int configuredWidth) {
        double xaeroScale = screenScale / Math.max(0.0001F, minimapScale);
        try {
            int minimapSize = session.getProcessor().getMinimapSize();
            int scaledX = (int) (x * xaeroScale);
            int scaledY = (int) (y * xaeroScale);
            int scaledLeft = scaledX + 6;
            int scaledTop = scaledY + 6;
            int scaledSize = minimapSize / 2 + 6;
            int mapX = (int) Math.round(scaledLeft / xaeroScale);
            int mapY = (int) Math.round(scaledTop / xaeroScale);
            int size = Math.max(1, (int) Math.round(scaledSize / xaeroScale));
            int maxSize = Math.min(renderContext.w - Math.max(0, mapX - x),
                    renderContext.h - Math.max(0, mapY - y));
            if (maxSize > 0) {
                size = Math.min(size, maxSize);
            }
            return new MinimapBounds(mapX, mapY, size);
        } catch (RuntimeException | LinkageError ignored) {
            int moduleSize = Math.min(renderContext.w, renderContext.h);
            int size = configuredWidth > 0 ? Math.min(configuredWidth, moduleSize) : moduleSize;
            int mapX = x + Math.max(0, (renderContext.w - size) / 2);
            int mapY = y + Math.max(0, (renderContext.h - size) / 2);
            return new MinimapBounds(mapX, mapY, size);
        }
    }

    private record MinimapBounds(int x, int y, int size) {}
}

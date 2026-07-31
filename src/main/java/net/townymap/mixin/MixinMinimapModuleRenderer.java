package net.townymap.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
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
            method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/render/module/ModuleRenderContext;Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void townymapaddon$hideMinimapInNether(MinimapSession session,
                                                   ModuleRenderContext renderContext,
                                                   GuiGraphicsExtractor drawContext,
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
            method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/render/module/ModuleRenderContext;Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V",
            at = @At("RETURN"),
            remap = false
    )
    private void townymapaddon$clearNativeCompassSuppression(MinimapSession session,
                                                            ModuleRenderContext renderContext,
                                                            GuiGraphicsExtractor drawContext,
                                                            float tickDelta,
                                                            CallbackInfo ci) {
        TownyMapMod.clearSuppressNativeMinimapCompass();
    }

    @Redirect(require = 0, 
            method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/render/module/ModuleRenderContext;Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/common/minimap/render/MinimapRenderer;renderOutsidePip(Lxaero/hud/minimap/module/MinimapSession;IIIIDFIFLnet/minecraft/client/gui/GuiGraphicsExtractor;)V"
            ),
            remap = false
    )
    private void townymapaddon$renderTownOutlines(xaero.common.minimap.render.MinimapRenderer renderer,
                                                  MinimapSession session,
                                                  int x, int y, int screenW, int screenH,
                                                  double screenScale, float minimapScale,
                                                  int configuredWidth, float tickDelta,
                                                  GuiGraphicsExtractor drawContext,
                                                  MinimapSession originalSession,
                                                  ModuleRenderContext renderContext,
                                                  GuiGraphicsExtractor originalDrawContext,
                                                  float originalTickDelta) {
        MinimapBounds bounds = actualMinimapBounds(session, x, y, screenScale, minimapScale,
                renderContext, configuredWidth);
        // Match Xaero's info-text size: it renders inside a 1/xaeroScale matrix, i.e. at
        // minimapScale/screenScale of the base font. Our text draws at 1.0, so scale it to match.
        TownyMapMod.setMinimapTextScale((float) (minimapScale / Math.max(1.0e-4, screenScale)));
        TownyMapMod.renderOnMinimap(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        renderer.renderOutsidePip(session, x, y, screenW, screenH, screenScale, minimapScale,
                configuredWidth, tickDelta, drawContext);
        TownyMapMod.renderMinimapFrame(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        TownyMapMod.renderMinimapNationAlert(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        TownyMapMod.renderMinimapWaypointsOnTop(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        // Draw after renderOutsidePip so our indicator composites on top of Xaero's arrow
        TownyMapMod.renderMinimapPlayerIndicator(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        TownyMapMod.renderMinimapCompassDirections(drawContext, session, bounds.x(), bounds.y(), bounds.size());
        // Our info lines (town/nation, nearby players, nearest town), anchored under the minimap.
        TownyMapMod.renderMinimapInfoLines(drawContext,
                bounds.x() + bounds.size() / 2, bounds.y(), bounds.y() + bounds.size());
    }

    private static MinimapBounds actualMinimapBounds(MinimapSession session, int x, int y,
                                                     double screenScale, float minimapScale,
                                                     ModuleRenderContext renderContext,
                                                     int configuredWidth) {
        int boxW = renderContext.w;
        int boxH = renderContext.h;
        int minimapSize = session.getProcessor().getMinimapSize();
        // Xaero's VISIBLE circle is its frame's OUTER edge. Decoded from
        // MinimapRenderer.renderMinimap + MinimapRendererHelper.drawTexturedElipseInsideRectangleFrame:
        //   inner radius (local) = (minimapSize/2)/2 = minimapSize/4
        //   outer radius (local) = inner + frame thickness (4) = minimapSize/4 + 4
        // both drawn in the matrix scaled by 1/xaeroScale (xaeroScale = screenScale/minimapScale), so
        //   GUI outer diameter = 2*(minimapSize/4 + 4)/xaeroScale = (minimapSize/2 + 8)/xaeroScale.
        // (terrain alone is (minimapSize/2)/xaeroScale; the frame adds 8/xaeroScale of diameter.)
        // The circle is concentric with the box's boxSize content square, so we centre it there.
        double xaeroScale = screenScale / Math.max(1.0e-4, minimapScale);
        int diameter = (int) Math.round((minimapSize / 2.0 + 8.0) / xaeroScale);
        if (diameter <= 0) {
            diameter = Math.min(boxW, boxH);
        }
        diameter = Math.max(1, diameter);
        int mapX = x + (boxW - diameter) / 2;
        int mapY = y + (boxW - diameter) / 2;
        return new MinimapBounds(mapX, mapY, diameter);
    }

    private record MinimapBounds(int x, int y, int size) {}
}

package net.townymap.mixin;

import net.minecraft.client.util.math.MatrixStack;
import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.BlockPos;
import xaero.hud.minimap.Minimap;
import xaero.hud.minimap.info.render.InfoDisplayRenderer;
import xaero.hud.minimap.module.MinimapSession;

@Mixin(value = xaero.common.minimap.render.MinimapRenderer.class, remap = false)
public abstract class MixinCommonMinimapRenderer {

    @Inject(
            method = "renderMinimap(Lxaero/hud/minimap/module/MinimapSession;Lxaero/common/minimap/MinimapProcessor;IIIIDFIFLxaero/lib/client/graphics/XaeroBufferProvider;)V",
            at = @At("HEAD"),
            remap = false
    )
    private void townymapaddon$setNativeCompassSuppression(MinimapSession session,
                                                           @Coerce Object processor,
                                                           int x,
                                                           int y,
                                                           int screenW,
                                                           int screenH,
                                                           double screenScale,
                                                           float minimapScale,
                                                           int configuredWidth,
                                                           float tickDelta,
                                                           @Coerce Object bufferProvider,
                                                           CallbackInfo ci) {
        TownyMapMod.setSuppressNativeMinimapCompass(session);
        TownyMapMod.setSuppressNativeMinimapWaypoints();
    }

    @Inject(
            method = "renderMinimap(Lxaero/hud/minimap/module/MinimapSession;Lxaero/common/minimap/MinimapProcessor;IIIIDFIFLxaero/lib/client/graphics/XaeroBufferProvider;)V",
            at = @At("RETURN"),
            remap = false
    )
    private void townymapaddon$clearNativeCompassSuppression(MinimapSession session,
                                                             @Coerce Object processor,
                                                             int x,
                                                             int y,
                                                             int screenW,
                                                             int screenH,
                                                             double screenScale,
                                                             float minimapScale,
                                                             int configuredWidth,
                                                             float tickDelta,
                                                             @Coerce Object bufferProvider,
                                                             CallbackInfo ci) {
        TownyMapMod.clearSuppressNativeMinimapCompass();
        TownyMapMod.clearSuppressNativeMinimapWaypoints();
    }

    @Inject(
            method = "renderCompass(Lnet/minecraft/class_4587;Lxaero/common/settings/ModSettings;Lxaero/lib/client/config/ClientConfigManager;Lxaero/lib/client/graphics/XaeroBufferProvider;IIIDDZF)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void townymapaddon$suppressNativeEnlargedCompass(MatrixStack matrixStack,
                                                            @Coerce Object settings,
                                                            @Coerce Object configManager,
                                                            @Coerce Object bufferProvider,
                                                            int x,
                                                            int y,
                                                            int size,
                                                            double ps,
                                                            double pc,
                                                            boolean lockedNorth,
                                                            float scale,
                                                            CallbackInfo ci) {
        if (TownyMapMod.shouldSuppressNativeMinimapCompass()) {
            ci.cancel();
        }
    }

    // Stack our info lines with Xaero's own info area: render ours at the info origin, then shift
    // Xaero's info (coords/biome/etc.) down by our height so the two never overlap.
    @Redirect(
            method = "renderOutsidePip(Lxaero/hud/minimap/module/MinimapSession;IIIIDFIFLnet/minecraft/class_332;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/hud/minimap/info/render/InfoDisplayRenderer;render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/minimap/Minimap;IILnet/minecraft/class_2338;IIFLnet/minecraft/class_332;)V"
            ),
            remap = false
    )
    private void townymapaddon$infoLines(InfoDisplayRenderer infoRenderer, MinimapSession session,
                                         Minimap minimap, int x, int y, BlockPos pos,
                                         int infoWidth, int infoExtra, float partial, DrawContext ctx) {
        int myHeight = 0;
        try {
            myHeight = TownyMapMod.renderMinimapInfoLines(ctx, x, y);
        } catch (Throwable ignored) {
        }
        infoRenderer.render(session, minimap, x, y + myHeight, pos, infoWidth, infoExtra, partial, ctx);
    }
}

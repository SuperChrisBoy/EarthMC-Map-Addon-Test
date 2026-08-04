package net.townymap.mixin;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Observes where Xaero draws each of its info-display lines (coords, biome, etc.) so our own info
 * block can stack just below/above the whole thing without overlapping — no matter how many lines
 * Xaero shows or what info-display scale is set.
 */
@Mixin(value = xaero.hud.minimap.info.render.InfoDisplayRenderer.class, remap = false)
public class MixinInfoDisplayRenderer {

    @Redirect(require = 0,
            method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/minimap/Minimap;IILnet/minecraft/core/BlockPos;IIFLnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"
            ),
            remap = false
    )
    private void townymap$captureInfoLine(GuiGraphicsExtractor ctx, Font font, Component text,
                                          int x, int y, int color, boolean shadow) {
        TownyMapMod.captureXaeroInfoLine(ctx, x, y, font.lineHeight);
        ctx.text(font, text, x, y, color, shadow);
    }
}

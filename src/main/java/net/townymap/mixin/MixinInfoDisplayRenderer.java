package net.townymap.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
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

    @Redirect(
            method = "render(Lxaero/hud/minimap/module/MinimapSession;Lxaero/hud/minimap/Minimap;IILnet/minecraft/class_2338;IIFLnet/minecraft/class_332;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/class_332;method_51439(Lnet/minecraft/class_327;Lnet/minecraft/class_2561;IIIZ)V"
            ),
            remap = false
    )
    private void townymap$captureInfoLine(DrawContext ctx, TextRenderer font, Text text,
                                          int x, int y, int color, boolean shadow) {
        TownyMapMod.captureXaeroInfoLine(ctx, x, y, font.fontHeight);
        ctx.drawText(font, text, x, y, color, shadow);
    }
}

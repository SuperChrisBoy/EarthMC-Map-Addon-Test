package net.townymap.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Renders the exposed-wilderness warning above vanilla's bottom-center HUD rows. */
@Mixin(InGameHud.class)
public abstract class MixinHud{
    @Inject(method="render",at=@At("RETURN"),require=0)
    private void townymap$renderWildernessRisk(DrawContext graphics,RenderTickCounter delta,CallbackInfo ci){
        TownyMapMod.renderHunterWarningHud(graphics);
        TownyMapMod.renderWildernessRiskHud(graphics,false);
        TownyMapMod.renderHunterActivityHud(graphics);
    }
}

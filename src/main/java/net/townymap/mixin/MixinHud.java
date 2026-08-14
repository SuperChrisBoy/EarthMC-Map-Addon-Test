package net.townymap.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Renders the exposed-wilderness warning above vanilla's bottom-center HUD rows. */
@Mixin(Hud.class)
public abstract class MixinHud{
    @Shadow private int overlayMessageTime;

    @Inject(method="extractRenderState",at=@At("RETURN"),require=0)
    private void townymap$renderWildernessRisk(GuiGraphicsExtractor graphics,DeltaTracker delta,CallbackInfo ci){
        TownyMapMod.renderHunterWarningHud(graphics);
        TownyMapMod.renderWildernessRiskHud(graphics,overlayMessageTime>0);
    }
}

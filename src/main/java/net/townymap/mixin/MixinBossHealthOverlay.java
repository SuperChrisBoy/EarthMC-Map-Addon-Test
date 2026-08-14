package net.townymap.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Keeps server boss-bar notices below the top-center wilderness warning. */
@Mixin(BossHealthOverlay.class)
public abstract class MixinBossHealthOverlay{
    @ModifyConstant(method="extractRenderState",constant=@Constant(intValue=12),require=0)
    private int townymap$moveBossBarsBelowVoteParty(int original){
        return original+TownyMapMod.topWarningBossBarOffset();
    }
}

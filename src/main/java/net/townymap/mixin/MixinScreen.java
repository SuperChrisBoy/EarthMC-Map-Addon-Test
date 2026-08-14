package net.townymap.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Global vote-party display, including the title screen and non-world menus. */
@Mixin(Screen.class)
public abstract class MixinScreen{
    @Inject(method="extractRenderState",at=@At("RETURN"),require=0)
    private void townymap$renderVoteParty(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta,CallbackInfo ci){if(TownyMapMod.isWorldMapOpen())return;Minecraft mc=Minecraft.getInstance();Screen self=(Screen)(Object)this;if(mc.gui.screen()!=self)return;TownyMapMod.renderVotePartyGlobal(g,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight(),self);}
}

package net.townymap.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Global vote-party display, including the title screen and non-world menus. */
@Mixin(Screen.class)
public abstract class MixinScreen{
    @Inject(method="removed",at=@At("HEAD"),require=0)
    private void townymap$cancelHunterDragOnScreenClose(CallbackInfo ci){TownyMapMod.cancelHunterActivityDrag();}
    @Inject(method="extractRenderState",at=@At("RETURN"),require=0)
    private void townymap$renderVoteParty(GuiGraphicsExtractor g,int mouseX,int mouseY,float delta,CallbackInfo ci){if(TownyMapMod.isWorldMapOpen())return;Minecraft mc=Minecraft.getInstance();Screen self=(Screen)(Object)this;if(mc.gui.screen()!=self)return;TownyMapMod.renderVotePartyGlobal(g,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight(),self);}

    @Inject(method="mouseClicked",at=@At("HEAD"),cancellable=true,require=0)
    private void townymap$clickHunterActivity(MouseButtonEvent click,boolean doubled,CallbackInfoReturnable<Boolean> cir){if(!((Object)this instanceof ChatScreen)||click.buttonInfo().button()!=0)return;Minecraft mc=Minecraft.getInstance();if(TownyMapMod.clickHunterActivity(click.x(),click.y(),mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight()))cir.setReturnValue(true);}

    @Inject(method="mouseReleased",at=@At("HEAD"),cancellable=true,require=0)
    private void townymap$releaseHunterActivity(MouseButtonEvent click,CallbackInfoReturnable<Boolean> cir){if((Object)this instanceof ChatScreen&&click.buttonInfo().button()==0&&TownyMapMod.releaseHunterActivity())cir.setReturnValue(true);}

    @Inject(method="mouseScrolled",at=@At("HEAD"),cancellable=true,require=0)
    private void townymap$scrollHunterActivity(double mouseX,double mouseY,double horizontal,double vertical,CallbackInfoReturnable<Boolean> cir){if(!((Object)this instanceof ChatScreen))return;Minecraft mc=Minecraft.getInstance();if(TownyMapMod.scrollHunterActivity(mouseX,mouseY,vertical,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight()))cir.setReturnValue(true);}
}

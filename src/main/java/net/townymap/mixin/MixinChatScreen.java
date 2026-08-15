package net.townymap.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.townymap.TownyMapMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** ChatScreen overrides Screen's click path, so activity-window input must be hooked here. */
@Mixin(ChatScreen.class)
public abstract class MixinChatScreen {
    @Inject(method="removed",at=@At("HEAD"),require=1)
    private void townymap$cancelHunterDrag(CallbackInfo ci){TownyMapMod.cancelHunterActivityDrag();}

    @Inject(method="extractRenderState",at=@At("RETURN"),require=1)
    private void townymap$renderHunterActivity(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float delta,CallbackInfo ci){
        TownyMapMod.renderHunterActivityChat(graphics);
    }

    @Inject(method="mouseClicked",at=@At("HEAD"),cancellable=true,require=1)
    private void townymap$clickHunterActivity(MouseButtonEvent click,boolean doubled,CallbackInfoReturnable<Boolean> cir){
        if(click.buttonInfo().button()!=0)return;
        Minecraft mc=Minecraft.getInstance();
        if(TownyMapMod.clickHunterActivity(click.x(),click.y(),mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight()))cir.setReturnValue(true);
    }
}

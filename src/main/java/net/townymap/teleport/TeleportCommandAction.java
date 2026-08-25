package net.townymap.teleport;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;

/** Applies the persisted Standard-mode command behavior. Advanced mode is always clipboard-only. */
public final class TeleportCommandAction {
    public static final int CLIPBOARD=0,CHAT=1,EXECUTE=2;
    private static final TeleportCommandCooldown COOLDOWN=new TeleportCommandCooldown();
    private TeleportCommandAction(){}
    public static Result perform(String command,int configuredMode,boolean advanced,boolean verified){
        if(command==null||command.isBlank())return Result.UNAVAILABLE;
        long now=System.currentTimeMillis();if(!COOLDOWN.tryAcquire(now))return Result.COOLDOWN;
        MinecraftClient mc=MinecraftClient.getInstance();if(mc==null)return Result.UNAVAILABLE;
        int mode=resolveMode(configuredMode,advanced,verified);
        if(mode==CLIPBOARD){mc.keyboard.setClipboard(command);return Result.COPIED;}
        if(mode==CHAT){mc.setScreen(new ChatScreen(command, false));return Result.CHAT_READY;}
        if(mc.getNetworkHandler()==null)return Result.UNAVAILABLE;
        mc.getNetworkHandler().sendChatCommand(command.startsWith("/")?command.substring(1):command);return Result.EXECUTED;
    }
    public static int resolveMode(int configuredMode,boolean advanced,boolean verified){int mode=advanced?CLIPBOARD:Math.clamp(configuredMode,CLIPBOARD,EXECUTE);return mode==EXECUTE&&!verified?CLIPBOARD:mode;}
    public enum Result{COPIED,CHAT_READY,EXECUTED,COOLDOWN,UNAVAILABLE}
}

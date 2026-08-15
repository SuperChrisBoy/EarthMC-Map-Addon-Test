package net.townymap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.hunter.alert.HunterEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Draggable, retractable bounded-history panel rendered over Xaero's World Map. */
public final class HunterActivityOverlay {
    private static final int W=260,H=190,HEADER=22,PAD=7,LINE=11;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static int scroll;
    private static boolean dragging;
    private static double dragOffsetX,dragOffsetY;

    private HunterActivityOverlay(){}

    private static int x(int sw,TownyMapConfig cfg){return HunterActivityWindowState.x(sw,W,cfg.hunterActivityWindowX);}
    private static int y(int sh,TownyMapConfig cfg){return HunterActivityWindowState.y(sh,HEADER,cfg.hunterActivityWindowY);}

    public static void render(GuiGraphicsExtractor c,int sw,int sh,TownyMapConfig cfg,boolean interactive){
        if(!cfg.hunterActivityWindowShown){cancelDrag();return;}
        Minecraft mc=Minecraft.getInstance();
        if(dragging&&(!interactive||mc==null||!mc.isWindowActive()||org.lwjgl.glfw.GLFW.glfwGetMouseButton(mc.getWindow().handle(),org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)!=org.lwjgl.glfw.GLFW.GLFW_PRESS)){cancelDrag();}
        if(dragging){double mx=mc.mouseHandler.getScaledXPos(mc.getWindow()),my=mc.mouseHandler.getScaledYPos(mc.getWindow());cfg.hunterActivityWindowX=HunterActivityWindowState.draggedX(mx,dragOffsetX,sw,W);cfg.hunterActivityWindowY=HunterActivityWindowState.draggedY(my,dragOffsetY,sh,HEADER);}
        int x=x(sw,cfg),y=y(sh,cfg),h=cfg.hunterActivityWindowMinimized?HEADER:Math.min(H,sh-y-16);
        c.fill(x-1,y-1,x+W+1,y+h+1,0xFF41464E);c.fill(x,y,x+W,y+h,0xE815181C);c.fill(x,y,x+W,y+HEADER,0xEE252A30);
        c.text(Minecraft.getInstance().font,Component.translatable("townymapaddon.hunter.activity.title"),x+PAD,y+7,0xFFFFFFFF,false);
        c.text(Minecraft.getInstance().font,cfg.hunterActivityWindowMinimized?"[+] [X]":"[–] [X]",x+W-46,y+7,0xFFBFC5CC,false);
        if(cfg.hunterActivityWindowMinimized)return;
        List<HunterEvent> events=TownyMapMod.hunterActivityHistory();int cy=y+HEADER+5;int skipped=0;
        for(HunterEvent e:events){if(skipped++<scroll)continue;int color=switch(e.severity()){case CRITICAL->0xFFFF5555;case WARNING->0xFFFFAA55;case NOTICE->0xFFFFFF55;case INFO->0xFFBFC5CC;};String head=TIME.format(Instant.ofEpochMilli(e.atMs()))+"  "+e.title().getString();c.text(Minecraft.getInstance().font,trim(head,35),x+PAD,cy,color,false);cy+=LINE;for(var line:e.lines()){if(cy+LINE>y+h-3)break;c.text(Minecraft.getInstance().font,trim(line.getString(),38),x+PAD+8,cy,0xFF9FA6AE,false);cy+=LINE;}cy+=4;if(cy+LINE>y+h)break;}
    }

    public static boolean click(double mx,double my,int sw,int sh,TownyMapConfig cfg){if(!cfg.hunterActivityWindowShown)return false;int x=x(sw,cfg),y=y(sh,cfg);return switch(HunterActivityWindowState.headerAction(mx,my,x,y,W,HEADER)){case NONE->false;case CLOSE->{dragging=false;cfg.hunterActivityWindowShown=false;cfg.save();yield true;}case MINIMIZE->{cfg.hunterActivityWindowMinimized=!cfg.hunterActivityWindowMinimized;cfg.save();yield true;}case DRAG->{dragging=true;dragOffsetX=mx-x;dragOffsetY=my-y;yield true;}};}
    public static boolean release(TownyMapConfig cfg){if(!dragging)return false;cancelDrag();cfg.save();return true;}
    public static boolean scroll(double mx,double my,double amount,int sw,int sh,TownyMapConfig cfg){if(!cfg.hunterActivityWindowShown||cfg.hunterActivityWindowMinimized)return false;int x=x(sw,cfg),y=y(sh,cfg),h=Math.min(H,sh-y-16);if(mx<x||mx>x+W||my<y||my>y+h)return false;int max=Math.max(0,TownyMapMod.hunterActivityHistory().size()-1);scroll=Math.clamp(scroll-(int)Math.signum(amount),0,max);return true;}
    public static void toggle(TownyMapConfig cfg){cancelDrag();cfg.hunterActivityWindowShown=!cfg.hunterActivityWindowShown;cfg.save();}
    public static void cancelDrag(){dragging=false;dragOffsetX=0;dragOffsetY=0;}
    static boolean draggingForTest(){return dragging;}
    private static String trim(String s,int n){return s.length()<=n?s:s.substring(0,Math.max(1,n-1))+"…";}
}

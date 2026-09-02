package net.townymap.gui;

/** Pure window geometry used by the renderer and regression tests. */
public final class HunterActivityWindowState {
    public enum HeaderAction { NONE, DRAG, MINIMIZE, CLOSE }
    private HunterActivityWindowState() {}

    public static int x(int screenWidth,int windowWidth,int configuredX){
        int preferred=configuredX<0?screenWidth-windowWidth-12:configuredX;
        return Math.clamp(preferred,4,Math.max(4,screenWidth-windowWidth-4));
    }
    public static int y(int screenHeight,int headerHeight,int configuredY){
        return Math.clamp(configuredY,4,Math.max(4,screenHeight-headerHeight-4));
    }
    public static int draggedX(double mouseX,double offsetX,int screenWidth,int windowWidth){
        return Math.clamp((int)Math.round(mouseX-offsetX),4,Math.max(4,screenWidth-windowWidth-4));
    }
    public static int draggedY(double mouseY,double offsetY,int screenHeight,int headerHeight){
        return Math.clamp((int)Math.round(mouseY-offsetY),4,Math.max(4,screenHeight-headerHeight-4));
    }
    public static HeaderAction headerAction(double mouseX,double mouseY,int x,int y,int width,int headerHeight){
        if(mouseX<x||mouseX>x+width||mouseY<y||mouseY>y+headerHeight)return HeaderAction.NONE;
        if(mouseX>=x+width-24)return HeaderAction.CLOSE;
        if(mouseX>=x+width-50)return HeaderAction.MINIMIZE;
        return HeaderAction.DRAG;
    }
}

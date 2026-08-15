package net.townymap.gui;

/** Pure scaled-GUI layout math shared by the runtime renderer and tests. */
public final class HunterAlertLayout {
    public static final int TOP_MARGIN=12;
    private HunterAlertLayout(){}
    public static Bounds centered(int screenWidth,int panelWidth,int y,int height){
        int width=Math.max(0,Math.min(panelWidth,screenWidth));
        return new Bounds((screenWidth-width)/2,y,width,height);
    }
    public record Bounds(int x,int y,int width,int height){public int centerX(){return x+width/2;}}
}

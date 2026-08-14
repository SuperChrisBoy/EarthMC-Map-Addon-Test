package net.townymap.teleport;

/** Hard caps map-overlay work so distant/off-screen routes cannot create unbounded draw calls. */
public final class TeleportRenderBudget {
    public static final int MAX_LINE_STEPS=256;
    private TeleportRenderBudget(){}
    public static int lineSteps(double pixelDistance){return Math.clamp((int)Math.ceil(Math.max(0,pixelDistance)/7.0),1,MAX_LINE_STEPS);}
}

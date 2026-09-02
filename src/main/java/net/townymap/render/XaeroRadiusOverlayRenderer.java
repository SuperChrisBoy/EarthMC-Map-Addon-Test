package net.townymap.render;

import net.minecraft.client.gui.DrawContext;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.integration.XaeroRadiusOverlayProvider;
import java.util.List;

/** Stateless visualization of authoritative threat-front snapshots. */
public final class XaeroRadiusOverlayRenderer {
    private XaeroRadiusOverlayRenderer(){}
    public static void worldMap(DrawContext ctx,List<XaeroRadiusOverlayProvider.Overlay> overlays,double cameraX,double cameraZ,double scale,int sw,int sh){if(scale<=0)return;for(var o:overlays)draw(ctx,sw/2.0+(o.x()-cameraX)*scale,sh/2.0+(o.z()-cameraZ)*scale,o,scale,0,0,sw-1,sh-1);}
    public static void minimap(DrawContext ctx,List<XaeroRadiusOverlayProvider.Overlay> overlays,double centerX,double centerY,double playerX,double playerZ,double scale,double sin,double cos,int left,int top,int right,int bottom){if(scale<=0)return;ctx.enableScissor(left,top,right+1,bottom+1);try{for(var o:overlays){double dx=o.x()-playerX,dz=o.z()-playerZ;draw(ctx,centerX+(dx*cos-dz*sin)*scale,centerY+(dx*sin+dz*cos)*scale,o,scale,left,top,right,bottom);}}finally{ctx.disableScissor();}}
    private static void draw(DrawContext ctx,double cx,double cy,XaeroRadiusOverlayProvider.Overlay o,double scale,int left,int top,int right,int bottom){TownyMapConfig cfg=TownyMapMod.getConfig();long now=System.currentTimeMillis();double plausible=o.plausible(now,cfg.hunterFrontVisualInterpolation)*scale,warning=o.warning(now,cfg.hunterFrontVisualInterpolation)*scale,outer=Math.max(plausible,warning);if(cx+outer<left||cx-outer>right||cy+outer<top||cy-outer>bottom)return;int alpha=Math.clamp((int)Math.round(cfg.hunterFrontOpacity*2.55),8,255),thickness=Math.clamp(cfg.hunterFrontLineThickness,1,4);Palette colors=palette(o.type());if(cfg.hunterShowWarningFront)ring(ctx,cx,cy,warning,(alpha<<24)|colors.warningRgb,thickness);if(cfg.hunterShowPlausibleFront)ring(ctx,cx,cy,plausible,(alpha<<24)|colors.plausibleRgb,thickness);/* no center marker: the base addon owns it */}
    static Palette palette(net.townymap.hunter.front.HiddenThreatOrigin.Type type){return switch(type){case TOWN_SPAWN->new Palette(0x43DDEB,0x2988FF);case NATION_SPAWN->new Palette(0xE76BFF,0x9A55FF);case OTHER_TELEPORT->new Palette(0x58E890,0x20B968);case KILL_EVENT_ORIGIN->new Palette(0xFFF06A,0xFF9C38);case LAST_KNOWN_POSITION->new Palette(0xFFB13B,0xFF4A4A);};}
    record Palette(int warningRgb,int plausibleRgb){}
    private static void ring(DrawContext ctx,double cx,double cy,double radius,int color,int thickness){if(radius<2)return;int segments=Math.clamp((int)Math.ceil(radius*.75),24,128);double px=cx+radius,py=cy;for(int i=1;i<=segments;i++){double a=Math.PI*2*i/segments,nx=cx+Math.cos(a)*radius,ny=cy+Math.sin(a)*radius;segment(ctx,px,py,nx,ny,color,thickness);px=nx;py=ny;}}
    private static void segment(DrawContext ctx,double x1,double y1,double x2,double y2,int color,int thickness){double dx=x2-x1,dy=y2-y1;int length=(int)Math.ceil(Math.hypot(dx,dy));if(length<=0)return;var m=ctx.getMatrices();m.pushMatrix();try{m.translate((float)x1,(float)y1);m.rotate((float)Math.atan2(dy,dx));ctx.fill(0,-thickness/2,length+1,(thickness+1)/2,color);}finally{m.popMatrix();}}
}

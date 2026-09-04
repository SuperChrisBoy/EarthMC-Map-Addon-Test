package net.townymap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.townymap.TownyMapConfig;
import net.townymap.ice.IceRoadNetwork;

import java.util.*;

/** Colored ice-highway lines, station symbols and a clickable station card. */
public final class IceRoadOverlay {
    private static final double FAR_ZOOM_SCALE=.04;
    private static IceRoadNetwork.Station selected;private static List<Hit>hits=List.of(),routeHits=List.of();private record Hit(IceRoadNetwork.Station station,double x,double y){}
    private IceRoadOverlay(){}
    public static void render(GuiGraphicsExtractor g,double camX,double camZ,double scale,int sw,int sh,TownyMapConfig cfg){if(!cfg.iceRoadOverlayEnabled||scale<=0)return;IceRoadNetwork n=IceRoadNetwork.get();double left=camX-sw/2.0/scale,right=camX+sw/2.0/scale,top=camZ-sh/2.0/scale,bottom=camZ+sh/2.0/scale;boolean far=scale<FAR_ZOOM_SCALE;Set<Long>tinyPixels=far?new HashSet<>():Set.of();for(var s:n.segments()){if(Math.max(s.x1(),s.x2())<left||Math.min(s.x1(),s.x2())>right||Math.max(s.z1(),s.z2())<top||Math.min(s.z1(),s.z2())>bottom)continue;int x1=sx(s.x1(),camX,scale,sw),y1=sy(s.z1(),camZ,scale,sh),x2=sx(s.x2(),camX,scale,sw),y2=sy(s.z2(),camZ,scale,sh);long dx=(long)x2-x1,dy=(long)y2-y1;if(far&&dx*dx+dy*dy<4){int x=(x1+x2)>>1,y=(y1+y2)>>1;if(tinyPixels.add(pixelKey(x,y)))g.fill(x,y,x+1,y+1,s.color());continue;}line(g,x1,y1,x2,y2,s.color(),cfg.iceRoadLineWidth);}List<Hit>visible=new ArrayList<>();Set<Long>markerCells=far?new HashSet<>():Set.of();int cell=Math.max(4,cfg.iceRoadMarkerSize);if(far&&selected!=null&&selected.x()>=left&&selected.x()<=right&&selected.z()>=top&&selected.z()<=bottom&&visibleByFilter(cfg,selected)){int x=sx(selected.x(),camX,scale,sw),y=sy(selected.z(),camZ,scale,sh);markerCells.add(cellKey(x,y,cell));symbol(g,x,y,selected.type(),access(cfg,selected),true,cfg.iceRoadMarkerSize);visible.add(new Hit(selected,x,y));}for(var s:n.stations()){if(s==null||s==selected&&far||s.x()<left||s.x()>right||s.z()<top||s.z()>bottom||!visibleByFilter(cfg,s))continue;int x=sx(s.x(),camX,scale,sw),y=sy(s.z(),camZ,scale,sh);if(far&&!markerCells.add(cellKey(x,y,cell)))continue;symbol(g,x,y,s.type(),access(cfg,s),s==selected,cfg.iceRoadMarkerSize);visible.add(new Hit(s,x,y));}hits=List.copyOf(visible);if(selected!=null)card(g,sw,sh,cfg,selected);}
    private static long pixelKey(int x,int y){return ((long)x<<32)^(y&0xffffffffL);}
    private static long cellKey(int x,int y,int size){return pixelKey(Math.floorDiv(x,size),Math.floorDiv(y,size));}
    private static int sx(double x,double c,double s,int w){return (int)Math.round(w/2.0+(x-c)*s);}private static int sy(double z,double c,double s,int h){return (int)Math.round(h/2.0+(z-c)*s);}
    /** Draws the same enabled ice-highway layer on Xaero's minimap, using its rotation and clipping. */
    public static void renderMinimap(GuiGraphicsExtractor g,double playerX,double playerZ,double pixelsPerBlock,double angle,int mapX,int mapY,int size,boolean circular,TownyMapConfig cfg){
        if(!cfg.iceRoadOverlayEnabled||pixelsPerBlock<=0)return;
        double cx=mapX+size/2.0,cy=mapY+size/2.0,radius=size/2.0,visible=radius/pixelsPerBlock*Math.sqrt(2.0)+32;
        double sin=Math.sin(angle),cos=Math.cos(angle),left=playerX-visible,right=playerX+visible,top=playerZ-visible,bottom=playerZ+visible;
        g.enableScissor(mapX,mapY,mapX+size,mapY+size);
        try{
            for(var s:IceRoadNetwork.get().segments()){
                if(Math.max(s.x1(),s.x2())<left||Math.min(s.x1(),s.x2())>right||Math.max(s.z1(),s.z2())<top||Math.min(s.z1(),s.z2())>bottom)continue;
                double[] a=minimapPoint(s.x1(),s.z1(),playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos),b=minimapPoint(s.x2(),s.z2(),playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos);
                if(!circular||clipCircle(a,b,cx,cy,radius-cfg.iceRoadLineWidth/2.0))line(g,(int)Math.round(a[0]),(int)Math.round(a[1]),(int)Math.round(b[0]),(int)Math.round(b[1]),s.color(),cfg.iceRoadLineWidth);
            }
            double markerRadius=cfg.iceRoadMarkerSize/2.0;
            for(var s:IceRoadNetwork.get().stations()){
                if(s==null||s.x()<left||s.x()>right||s.z()<top||s.z()>bottom||!visibleByFilter(cfg,s))continue;
                double[] p=minimapPoint(s.x(),s.z(),playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos);
                if(circular&&Math.hypot(p[0]-cx,p[1]-cy)>radius-markerRadius)continue;
                symbol(g,(int)Math.round(p[0]),(int)Math.round(p[1]),s.type(),access(cfg,s),false,cfg.iceRoadMarkerSize);
            }
        }finally{g.disableScissor();}
    }
    private static double[] minimapPoint(double x,double z,double px,double pz,double cx,double cy,double scale,double sin,double cos){double dx=x-px,dz=z-pz;return new double[]{cx+(dx*cos-dz*sin)*scale,cy+(dx*sin+dz*cos)*scale};}
    /** Clips a screen-space segment to a circular minimap. Returns false when it misses entirely. */
    private static boolean clipCircle(double[] a,double[] b,double cx,double cy,double radius){double dx=b[0]-a[0],dy=b[1]-a[1],fx=a[0]-cx,fy=a[1]-cy,q=dx*dx+dy*dy;if(q<1e-6)return fx*fx+fy*fy<=radius*radius;double disc=Math.pow(2*(fx*dx+fy*dy),2)-4*q*(fx*fx+fy*fy-radius*radius);boolean ai=fx*fx+fy*fy<=radius*radius,bi=(b[0]-cx)*(b[0]-cx)+(b[1]-cy)*(b[1]-cy)<=radius*radius;if(!ai&&!bi&&disc<0)return false;if(disc>=0){double root=Math.sqrt(disc),linear=2*(fx*dx+fy*dy),t0=(-linear-root)/(2*q),t1=(-linear+root)/(2*q),lo=Math.max(0,Math.min(t0,t1)),hi=Math.min(1,Math.max(t0,t1));if(!ai&&!bi&&lo>hi)return false;double ax=a[0],ay=a[1];if(!ai){a[0]=ax+dx*lo;a[1]=ay+dy*lo;}if(!bi){b[0]=ax+dx*hi;b[1]=ay+dy*hi;}}return ai||bi||disc>=0;}
    /** Selected Teleport Viewer result, projected and clipped to the minimap. */
    public static void renderSelectedRouteMinimap(GuiGraphicsExtractor g,TeleportViewerOverlay.MinimapRoute route,double playerX,double playerZ,double pixelsPerBlock,double angle,int mapX,int mapY,int size,boolean circular,TownyMapConfig cfg){
        if(route==null||!cfg.teleportRouteLineVisible||pixelsPerBlock<=0)return;double cx=mapX+size/2.0,cy=mapY+size/2.0,radius=size/2.0,sin=Math.sin(angle),cos=Math.cos(angle);var trip=route.ice();
        g.enableScissor(mapX,mapY,mapX+size,mapY+size);try{
            if(trip==null||trip.path().isEmpty())minimapDashed(g,route.spawnX(),route.spawnZ(),route.targetX(),route.targetZ(),0xFFFF58D0,playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos,circular,radius,3);
            else{
                minimapDashed(g,route.spawnX(),route.spawnZ(),trip.entryX(),trip.entryZ(),0xFFFFFFFF,playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos,circular,radius,3);
                for(var s:trip.path()){minimapLine(g,s.x1(),s.z1(),s.x2(),s.z2(),0xFF101010,cfg.iceRoadLineWidth+2,playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos,circular,radius);minimapLine(g,s.x1(),s.z1(),s.x2(),s.z2(),s.color(),cfg.iceRoadLineWidth,playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos,circular,radius);}
                minimapDashed(g,trip.exitX(),trip.exitZ(),route.targetX(),route.targetZ(),0xFFFFFFFF,playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos,circular,radius,3);
                IceRoadNetwork network=IceRoadNetwork.get();for(int id:trip.stationIds()){if(id<0||id>=network.stations().size())continue;var station=network.stations().get(id);if(station==null)continue;double[] p=minimapPoint(station.x(),station.z(),playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos);if(!circular||Math.hypot(p[0]-cx,p[1]-cy)<=radius-cfg.iceRoadMarkerSize/2.0)symbol(g,(int)Math.round(p[0]),(int)Math.round(p[1]),station.type(),access(cfg,station),false,cfg.iceRoadMarkerSize);}
            }
            if(cfg.teleportArrivalMarkerVisible)minimapEndpoint(g,route.spawnX(),route.spawnZ(),0xFFFFB13B,playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos,circular,radius,cfg.iceRoadMarkerSize);
            if(cfg.teleportDestinationMarkerVisible)minimapEndpoint(g,route.targetX(),route.targetZ(),0xFF23C7E8,playerX,playerZ,cx,cy,pixelsPerBlock,sin,cos,circular,radius,cfg.iceRoadMarkerSize);
        }finally{g.disableScissor();}
    }
    private static void minimapLine(GuiGraphicsExtractor g,double x1,double z1,double x2,double z2,int color,int width,double px,double pz,double cx,double cy,double scale,double sin,double cos,boolean circular,double radius){double[] a=minimapPoint(x1,z1,px,pz,cx,cy,scale,sin,cos),b=minimapPoint(x2,z2,px,pz,cx,cy,scale,sin,cos);if(!circular||clipCircle(a,b,cx,cy,radius-width/2.0))line(g,(int)Math.round(a[0]),(int)Math.round(a[1]),(int)Math.round(b[0]),(int)Math.round(b[1]),color,width);}
    private static void minimapDashed(GuiGraphicsExtractor g,double x1,double z1,double x2,double z2,int color,double px,double pz,double cx,double cy,double scale,double sin,double cos,boolean circular,double radius,int width){double dx=x2-x1,dz=z2-z1,length=Math.hypot(dx,dz)*scale;if(length<1)return;int pieces=Math.min(160,Math.max(1,(int)Math.ceil(length/10)));for(int i=0;i<pieces;i+=2){double a=i/(double)pieces,b=Math.min(1,(i+1)/(double)pieces);minimapLine(g,x1+dx*a,z1+dz*a,x1+dx*b,z1+dz*b,color,width,px,pz,cx,cy,scale,sin,cos,circular,radius);}}
    private static void minimapEndpoint(GuiGraphicsExtractor g,double x,double z,int color,double px,double pz,double cx,double cy,double scale,double sin,double cos,boolean circular,double radius,int size){double[] p=minimapPoint(x,z,px,pz,cx,cy,scale,sin,cos);if(!circular||Math.hypot(p[0]-cx,p[1]-cy)<=radius-size/2.0)endpoint(g,(int)Math.round(p[0]),(int)Math.round(p[1]),color,size);}
    private static void line(GuiGraphicsExtractor g,int x1,int y1,int x2,int y2,int color,int width){double dx=x2-x1,dy=y2-y1;int length=(int)Math.ceil(Math.hypot(dx,dy));if(length<=0)return;var m=g.pose();m.pushMatrix();try{m.translate(x1,y1);m.rotate((float)Math.atan2(dy,dx));g.fill(0,-width/2,length+1,(width+1)/2,color);}finally{m.popMatrix();}}
    /** Draws only the selected teleport result's highway subsection, with walking connectors at each end. */
    public static boolean renderSelectedRoute(GuiGraphicsExtractor g,double camX,double camZ,double scale,int sw,int sh,double spawnX,double spawnZ,double targetX,double targetZ,IceRoadNetwork.Trip trip,TownyMapConfig cfg){
        if(trip==null||trip.path().isEmpty()){routeHits=List.of();return false;}
        dashed(g,sx(spawnX,camX,scale,sw),sy(spawnZ,camZ,scale,sh),sx(trip.entryX(),camX,scale,sw),sy(trip.entryZ(),camZ,scale,sh),0xFFFFFFFF);
        for(var s:trip.path()){int x1=sx(s.x1(),camX,scale,sw),y1=sy(s.z1(),camZ,scale,sh),x2=sx(s.x2(),camX,scale,sw),y2=sy(s.z2(),camZ,scale,sh);line(g,x1,y1,x2,y2,0xFF101010,cfg.iceRoadLineWidth+2);line(g,x1,y1,x2,y2,s.color(),cfg.iceRoadLineWidth);}
        dashed(g,sx(trip.exitX(),camX,scale,sw),sy(trip.exitZ(),camZ,scale,sh),sx(targetX,camX,scale,sw),sy(targetZ,camZ,scale,sh),0xFFFFFFFF);
        IceRoadNetwork network=IceRoadNetwork.get();List<Hit> next=new ArrayList<>(trip.stationIds().size());
        for(int id:trip.stationIds()){if(id<0||id>=network.stations().size())continue;IceRoadNetwork.Station station=network.stations().get(id);if(station==null)continue;int x=sx(station.x(),camX,scale,sw),y=sy(station.z(),camZ,scale,sh);symbol(g,x,y,station.type(),access(cfg,station),station==selected,cfg.iceRoadMarkerSize);next.add(new Hit(station,x,y));}
        int entryX=sx(trip.entryX(),camX,scale,sw),entryY=sy(trip.entryZ(),camZ,scale,sh),exitX=sx(trip.exitX(),camX,scale,sw),exitY=sy(trip.exitZ(),camZ,scale,sh);endpoint(g,entryX,entryY,0xFF25C6E8,cfg.iceRoadMarkerSize);endpoint(g,exitX,exitY,0xFF25C6E8,cfg.iceRoadMarkerSize);
        routeHits=List.copyOf(next);if(selected!=null&&next.stream().anyMatch(hit->hit.station==selected))card(g,sw,sh,cfg,selected);return true;
    }
    private static void dashed(GuiGraphicsExtractor g,int x1,int y1,int x2,int y2,int color){double dx=x2-x1,dy=y2-y1,len=Math.hypot(dx,dy);if(len<1)return;int pieces=Math.min(160,Math.max(1,(int)Math.ceil(len/14)));for(int i=0;i<pieces;i+=2){double a=i/(double)pieces,b=Math.min(1,(i+1)/(double)pieces);line(g,(int)Math.round(x1+dx*a),(int)Math.round(y1+dy*a),(int)Math.round(x1+dx*b),(int)Math.round(y1+dy*b),color,3);}}
    private static void endpoint(GuiGraphicsExtractor g,int x,int y,int color,int size){int outer=Math.max(4,size/2),inner=Math.max(2,outer-2);g.fill(x-outer,y-outer,x+outer+1,y+outer+1,0xFFFFFFFF);g.fill(x-inner,y-inner,x+inner+1,y+inner+1,color);}
    /** The source site renders every typed station from a fixed 14x14 white SVG. Keep accessibility
     * in the popup instead of recoloring the glyph, so the map itself remains source-faithful. */
    private static void symbol(GuiGraphicsExtractor g,int x,int y,String type,String access,boolean active,int size){var m=g.pose();m.pushMatrix();try{m.translate(x,y);float scale=size/14f;m.scale(scale,scale);symbolAtOrigin(g,type);}finally{m.popMatrix();}}
    /** Website-faithful SVG-derived glyph shared with the in-map highway planner. */
    static void drawStationSymbol(GuiGraphicsExtractor g,int x,int y,String type,int size){symbol(g,x,y,type,"UNKNOWN",false,size);}
    private static void symbolAtOrigin(GuiGraphicsExtractor g,String type){int c=0xFFFFFFFF;if(type.startsWith("jct"))hollowSquare(g,0,0,c);else if(type.startsWith("inter"))interchange(g,0,0,c,type.endsWith("1"));else if(type.startsWith("elev"))elevator(g,0,0,c,type.endsWith("ew"));else if(type.startsWith("semi"))halfRing(g,0,0,c,Character.getNumericValue(type.charAt(type.length()-1)));else{g.fill(-3,-3,4,4,0x333388FF);ring(g,0,0,c,0,12);}}
    /** Rasterized directly from the source map's 14x14 inter1/inter2 SVG paths. */
    private static void interchange(GuiGraphicsExtractor g,int x,int y,int c,boolean inter1){
        if(inter1){rect(g,x,y,c,4,1,7,3);rect(g,x,y,c,10,1,13,3);rect(g,x,y,c,4,3,13,6);rect(g,x,y,c,10,6,13,8);rect(g,x,y,c,1,8,13,11);rect(g,x,y,c,10,11,13,13);}
        else{rect(g,x,y,c,1,1,4,3);rect(g,x,y,c,7,1,10,3);rect(g,x,y,c,1,3,10,6);rect(g,x,y,c,1,6,4,8);rect(g,x,y,c,1,8,13,11);rect(g,x,y,c,1,11,4,13);}
    }
    /** Rasterized directly from elev-ew.svg/elev-we.svg. */
    private static void elevator(GuiGraphicsExtractor g,int x,int y,int c,boolean ew){
        if(ew){rect(g,x,y,c,10,1,13,4);rect(g,x,y,c,7,4,13,7);rect(g,x,y,c,4,7,13,10);rect(g,x,y,c,1,10,7,13);}
        else{rect(g,x,y,c,1,1,4,4);rect(g,x,y,c,1,4,7,7);rect(g,x,y,c,1,7,10,10);rect(g,x,y,c,7,10,13,13);}
    }
    private static void rect(GuiGraphicsExtractor g,int x,int y,int c,int x1,int y1,int x2,int y2){g.fill(x-7+x1,y-7+y1,x-7+x2,y-7+y2,c);}
    private static void hollowSquare(GuiGraphicsExtractor g,int x,int y,int c){rect(g,x,y,c,1,1,11,4);rect(g,x,y,c,1,8,11,11);rect(g,x,y,c,1,4,4,8);rect(g,x,y,c,8,4,11,8);}
    private static void ring(GuiGraphicsExtractor g,int x,int y,int c,int start,int count){double step=Math.PI*2/12;for(int i=start;i<start+count;i++){double a=i*step,b=(i+1)*step;line(g,(int)Math.round(x+5*Math.cos(a)),(int)Math.round(y+5*Math.sin(a)),(int)Math.round(x+5*Math.cos(b)),(int)Math.round(y+5*Math.sin(b)),c,3);}}
    private static void halfRing(GuiGraphicsExtractor g,int x,int y,int c,int orientation){int start=switch(orientation){case 1->6;case 2->3;case 3->0;default->9;};ring(g,x,y,c,start,6);if(orientation==1||orientation==3)g.fill(x-6,y-1,x+7,y+2,c);else g.fill(x-1,y-6,x+2,y+7,c);}
    private static void card(GuiGraphicsExtractor g,int sw,int sh,TownyMapConfig cfg,IceRoadNetwork.Station s){int w=300,h=92,x=Math.max(8,sw-w-12),y=Math.max(8,sh-h-12);g.fill(x,y,x+w,y+h,0xF20A1419);g.text(Minecraft.getInstance().font,s.name(),x+8,y+7,0xFFFFFFFF,false);g.text(Minecraft.getInstance().font,Component.translatable("townymapaddon.ice_station.coords",Math.round(s.x()),Math.round(s.z()),typeName(s.type())),x+8,y+21,0xFFB9C5CB,false);String detail=s.notes().isBlank()?(s.lines().isEmpty()?Component.translatable("townymapaddon.ice_station.no_line_information").getString():String.join(" · ",s.lines().subList(0,Math.min(2,s.lines().size())))):s.notes();if(detail.length()>52)detail=detail.substring(0,49)+"...";g.text(Minecraft.getInstance().font,detail,x+8,y+35,0xFFDDE7ED,false);String[] keys={"unknown","accessible","blocked"};String[] values={"UNKNOWN","ACCESSIBLE","OBSTRUCTED"};for(int i=0;i<3;i++){int bx=x+8+i*95;boolean on=values[i].equals(access(cfg,s));g.fill(bx,y+57,bx+91,y+78,on?0xFF315E70:0xFF1B2931);g.centeredText(Minecraft.getInstance().font,Component.translatable("townymapaddon.ice_station.status."+keys[i]),bx+45,y+63,on?0xFFFFFFFF:0xFFB9C5CB);}}
    public static boolean click(double mx,double my,int sw,int sh,TownyMapConfig cfg){if(!cfg.iceRoadOverlayEnabled)return false;boolean insideCard=false;if(selected!=null){int x=Math.max(8,sw-312),y=Math.max(8,sh-104);insideCard=mx>=x&&mx<x+300&&my>=y&&my<y+92;if(mx>=x+8&&mx<x+293&&my>=y+57&&my<y+78){int i=(int)((mx-(x+8))/95);if(i>=0&&i<3){String[]v={"UNKNOWN","ACCESSIBLE","OBSTRUCTED"};String key=IceRoadNetwork.reportKey(selected.id());if(i==0)cfg.iceRoadStationReports.remove(key);else cfg.iceRoadStationReports.put(key,v[i]);cfg.save();return true;}}}Hit best=null;double radius=Math.max(9,cfg.iceRoadMarkerSize/2.0+3),bd=radius*radius;for(Hit h:hits){double d=(h.x-mx)*(h.x-mx)+(h.y-my)*(h.y-my);if(d<bd){bd=d;best=h;}}if(best!=null){selected=best.station;return true;}if(selected!=null&&!insideCard)selected=null;return insideCard;}
    public static boolean clickSelectedRoute(double mx,double my,int sw,int sh,TownyMapConfig cfg){if(routeHits.isEmpty())return false;if(selected!=null&&routeHits.stream().anyMatch(hit->hit.station==selected)){int x=Math.max(8,sw-312),y=Math.max(8,sh-104);if(mx>=x+8&&mx<x+293&&my>=y+57&&my<y+78){int i=(int)((mx-(x+8))/95);if(i>=0&&i<3){String[] values={"UNKNOWN","ACCESSIBLE","OBSTRUCTED"};String key=IceRoadNetwork.reportKey(selected.id());if(i==0)cfg.iceRoadStationReports.remove(key);else cfg.iceRoadStationReports.put(key,values[i]);cfg.save();return true;}}if(mx>=x&&mx<x+300&&my>=y&&my<y+92)return true;}Hit best=null;double radius=Math.max(9,cfg.iceRoadMarkerSize/2.0+3),bd=radius*radius;for(Hit hit:routeHits){double d=(hit.x-mx)*(hit.x-mx)+(hit.y-my)*(hit.y-my);if(d<bd){bd=d;best=hit;}}if(best==null)return false;selected=best.station;return true;}
    private static String access(TownyMapConfig cfg,IceRoadNetwork.Station s){return cfg.iceRoadStationReports.getOrDefault(IceRoadNetwork.reportKey(s.id()),"UNKNOWN");}private static Component typeName(String t){String key=t.startsWith("semi")?"semi_station":t.startsWith("jct")?"junction":t.startsWith("inter")?"interchange":t.startsWith("elev")?"elevator":"station";return Component.translatable("townymapaddon.ice_station.type."+key);}
    private static boolean visibleByFilter(TownyMapConfig cfg,IceRoadNetwork.Station s){String a=access(cfg,s);return cfg.iceRoadStationFilter==0||cfg.iceRoadStationFilter==1&&"ACCESSIBLE".equals(a)||cfg.iceRoadStationFilter==2&&"OBSTRUCTED".equals(a);}
}

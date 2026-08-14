package net.townymap.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.townymap.*;
import net.townymap.integration.XaeroWaypointBridge;
import net.townymap.teleport.*;
import java.util.*;

/** Compact, draggable Teleport Viewer drawn directly over Xaero's World Map. */
public final class TeleportViewerOverlay {
    private static final int W=360,HEADER=25,ROW=76,VISIBLE=4;
    private static boolean open,minimized,advanced,dragging,resultsCleared,advancedConfirmed;
    private static double targetX,targetZ,dragOffsetX,dragOffsetY;
    private static int selected,scroll;
    private static int resultLimit=10;
    private static TeleportAccessService.Plan currentPlan;
    private static List<TeleportRoute> currentRoutes=List.of();
    private static boolean cachedAdvanced,cachedTownSpawns,cachedNationSpawns,cachedUncertain;
    private static String feedback="";private static long feedbackUntil;

    private TeleportViewerOverlay(){}

    public static void open(double x,double z){targetX=x;targetZ=z;open=true;minimized=false;TownyMapConfig cfg=TownyMapMod.getConfig();advanced=cfg.teleportAdvancedEnabled&&cfg.teleportDefaultAdvanced;selected=scroll=0;resultLimit=10;resultsCleared=false;advancedConfirmed=false;feedback="";currentPlan=null;currentRoutes=List.of();TownyMapMod.refreshTeleportData(x,z);}
    public static boolean open(){return open;}
    public static int panelWidth(){return W;}
    public static void close(){open=false;minimized=false;dragging=false;currentRoutes=List.of();}

    public static void render(GuiGraphicsExtractor g,double camX,double camZ,double mapScale,int sw,int sh,TownyMapConfig cfg){
        if(!open)return;if(!cfg.teleportAdvancedEnabled)advanced=false;
        if(!minimized&&!resultsCleared)refreshRoutes(cfg);
        if(dragging){Minecraft mc=Minecraft.getInstance();double mx=mc.mouseHandler.getScaledXPos(mc.getWindow()),my=mc.mouseHandler.getScaledYPos(mc.getWindow());cfg.teleportWindowX=Math.clamp((int)Math.round(mx-dragOffsetX),4,Math.max(4,sw-W-4));cfg.teleportWindowY=Math.clamp((int)Math.round(my-dragOffsetY),4,Math.max(4,sh-HEADER-4));}
        TeleportRoute route=selectedRoute();if(route!=null)drawRoute(g,camX,camZ,mapScale,sw,sh,cfg,route);
        int x=Math.clamp(cfg.teleportWindowX,4,Math.max(4,sw-W-4)),y=Math.clamp(cfg.teleportWindowY,4,Math.max(4,sh-HEADER-4));
        int topOffset=advanced?106:94;boolean loadMoreVisible=showLoadMore();int h=minimized?HEADER:Math.min(sh-y-6,topOffset+VISIBLE*ROW+53+(loadMoreVisible?24:0));
        g.fill(x,y,x+W,y+h,0xFA071014);g.fill(x,y,x+W,y+HEADER,0xFF0D1820);
        g.text(Minecraft.getInstance().font,Component.translatable("townymapaddon.teleport.title"),x+10,y+8,0xFFFFFFFF,false);
        g.text(Minecraft.getInstance().font,minimized?"+":"–",x+W-36,y+8,0xFFDDE7ED,false);g.text(Minecraft.getInstance().font,"×",x+W-17,y+8,0xFFFF9A9A,false);if(minimized)return;
        TeleportAccessService.Plan plan=currentPlan;
        g.text(Minecraft.getInstance().font,Component.translatable("townymapaddon.teleport.destination_coords",(int)Math.round(targetX),(int)Math.round(targetZ)),x+10,y+34,0xFFE1E8ED,false);
        String membership=plan==null||plan.player()==null?Component.translatable(plan!=null&&plan.loading()?"townymapaddon.teleport.loading":"townymapaddon.teleport.eligibility_reason.api_data_missing").getString():plan.player().town()+" / "+plan.player().nation();
        g.text(Minecraft.getInstance().font,membership,x+10,y+48,0xFFB5C0C8,false);
        g.fill(x+8,y+65,x+(cfg.teleportAdvancedEnabled?W/2-2:W-8),y+85,0xFF245F50);g.centeredText(Minecraft.getInstance().font,Component.translatable("townymapaddon.teleport.mode.standard"),x+(cfg.teleportAdvancedEnabled?W/4:W/2),y+71,0xFFFFFFFF);
        if(cfg.teleportAdvancedEnabled){g.fill(x+W/2+2,y+65,x+W-8,y+85,advanced?0xFF6B4A24:0xFF26333A);g.centeredText(Minecraft.getInstance().font,Component.translatable("townymapaddon.teleport.mode.advanced"),x+3*W/4,y+71,0xFFFFFFFF);}
        boolean locked=advancedLocked();
        if(advanced)g.text(Minecraft.getInstance().font,Component.translatable(locked?"townymapaddon.teleport.advanced.locked":"townymapaddon.teleport.advanced.clipboard_only"),x+10,y+92,locked?0xFFFF9A9A:0xFFFFC06A,false);
        int top=y+topOffset;
        for(int i=scroll;i<Math.min(currentRoutes.size(),scroll+VISIBLE);i++)drawRouteRow(g,currentRoutes.get(i),i,x,top+(i-scroll)*ROW,locked);
        if(loadMoreVisible)drawLoadMoreButton(g,x+8,y+h-68,W-16,18,currentPlan!=null&&currentPlan.loading());
        if(System.currentTimeMillis()<feedbackUntil&&!feedback.isBlank())g.text(Minecraft.getInstance().font,feedback,x+10,y+h-31,0xFFFFCC66,false);
        if(plan!=null&&plan.loading()&&!resultsCleared)g.text(Minecraft.getInstance().font,Component.translatable("townymapaddon.teleport.loading"),x+10,y+h-18,0xFFFFFF77,false);
        else if(currentRoutes.isEmpty()&&!resultsCleared)g.text(Minecraft.getInstance().font,Component.translatable(advanced&&cfg.teleportPrimaryHomeTown.isBlank()?"townymapaddon.teleport.primary_required":"townymapaddon.teleport.none"),x+10,y+h-18,0xFFFFAA66,false);
        else if(!currentRoutes.isEmpty())drawFooter(g,x,y+h,locked);
        else g.text(Minecraft.getInstance().font,Component.translatable("townymapaddon.teleport.results_cleared"),x+10,y+h-18,0xFFAFBAC2,false);
    }

    private static void drawRouteRow(GuiGraphicsExtractor g,TeleportRoute route,int index,int x,int y,boolean locked){
        int bg=locked?0xEE24282B:index==selected?0xFF244F42:0xF0132420,text=locked?0xFF858B93:0xFFFFFFFF;
        g.fill(x+8,y,x+W-8,y+ROW-3,bg);
        g.text(Minecraft.getInstance().font,(route.destination().type()==TeleportDestination.Type.TOWN_SPAWN?"T · ":"N · ")+route.destination().name(),x+14,y+4,text,false);
        g.text(Minecraft.getInstance().font,Component.translatable("townymapaddon.teleport.blocks",(int)Math.round(route.walkingDistance())),x+W-100,y+4,text,false);
        int commandColor=locked?0xFF70777C:0xFF8BE5BB;
        if(advanced){String join=stepCommand(route,TeleportRoute.StepType.JOIN_TOWN);if(!join.isBlank())drawCommandButton(g,x+14,y+17,W-28,14,join,commandColor,!locked);drawCommandButton(g,x+14,y+34,W-28,14,route.destination().command(),commandColor,!locked);g.text(Minecraft.getInstance().font,quality(route),x+14,y+51,locked?0xFF777D82:qualityColor(route),false);g.text(Minecraft.getInstance().font,reason(route),x+14,y+63,locked?0xFF777D82:0xFFADB9C1,false);}
        else{drawCommandButton(g,x+14,y+20,W-28,17,route.destination().command(),commandColor,true);g.text(Minecraft.getInstance().font,reason(route),x+14,y+44,route.destination().eligibility()==TeleportDestination.Eligibility.UNCERTAIN?0xFFFFC85C:0xFFADB9C1,false);}
    }
    private static void drawCommandButton(GuiGraphicsExtractor g,int x,int y,int w,int h,String command,int color,boolean enabled){Minecraft mc=Minecraft.getInstance();double mx=mc.mouseHandler.getScaledXPos(mc.getWindow()),my=mc.mouseHandler.getScaledYPos(mc.getWindow());boolean hovered=enabled&&mx>=x&&mx<x+w&&my>=y&&my<y+h;int bg=!enabled?0xFF182126:hovered?0xFF294651:0xFF1A2A31,border=hovered?0xFF65DDB4:0xFF3C555F;g.fill(x,y,x+w,y+h,bg);g.fill(x,y,x+w,y+1,border);g.fill(x,y+h-2,x+w,y+h,border);g.fill(x,y,x+1,y+h,border);g.fill(x+w-1,y,x+w,y+h,border);g.centeredText(mc.font,command,x+w/2,y+3,color);}
    private static Component reason(TeleportRoute route){return Component.translatable("townymapaddon.teleport.eligibility_reason."+route.destination().reason().name().toLowerCase(Locale.ROOT));}
    private static Component quality(TeleportRoute route){TeleportRouteQuality.Rating rating=TeleportRouteQuality.rate(route.saving());long blocks=TeleportRouteQuality.blockDifference(route.saving());String key=rating==TeleportRouteQuality.Rating.LONGER?"townymapaddon.teleport.quality.longer":"townymapaddon.teleport.quality."+rating.name().toLowerCase(Locale.ROOT);return Component.translatable(key,blocks);}
    private static int qualityColor(TeleportRoute route){return switch(TeleportRouteQuality.rate(route.saving())){case EXCELLENT->0xFF66E6A4;case GOOD->0xFF8BE5BB;case SLIGHT_IMPROVEMENT->0xFFB7DFAE;case SAME_DISTANCE->0xFFC0C7CC;case LONGER->0xFFFFA66B;case NOT_COMPARABLE->0xFFFFC85C;};}
    private static void drawFooter(GuiGraphicsExtractor g,int x,int bottom,boolean locked){int y=bottom-44;drawFooterButton(g,x+8,y,166,18,"townymapaddon.teleport.clear_results",0xFFFF9A9A);drawFooterButton(g,x+178,y,174,18,"townymapaddon.teleport.remove_waypoints",0xFFFFC06A);y+=20;if(locked)drawFooterButton(g,x+8,y,W-16,18,"townymapaddon.teleport.advanced.confirm",0xFFFFC06A);else{drawFooterButton(g,x+8,y,170,18,"townymapaddon.teleport.target_waypoint",0xFF7EDCF2);drawFooterButton(g,x+182,y,170,18,"townymapaddon.teleport.arrival_waypoint",0xFFFFC06A);}}
    private static void drawFooterButton(GuiGraphicsExtractor g,int x,int y,int w,int h,String key,int color){g.fill(x,y,x+w,y+h,0xFF1B2931);g.fill(x,y+h-2,x+w,y+h,0xFF41515B);g.centeredText(Minecraft.getInstance().font,Component.translatable(key),x+w/2,y+5,color);}
    private static void drawLoadMoreButton(GuiGraphicsExtractor g,int x,int y,int w,int h,boolean loading){Minecraft mc=Minecraft.getInstance();double mx=mc.mouseHandler.getScaledXPos(mc.getWindow()),my=mc.mouseHandler.getScaledYPos(mc.getWindow());boolean hovered=!loading&&mx>=x&&mx<x+w&&my>=y&&my<y+h;g.fill(x,y,x+w,y+h,loading?0xFF182126:hovered?0xFF294651:0xFF1B2931);g.fill(x,y+h-2,x+w,y+h,hovered?0xFF65DDB4:0xFF41515B);g.centeredText(mc.font,Component.translatable(loading?"townymapaddon.teleport.loading":"townymapaddon.teleport.load_more"),x+w/2,y+5,loading?0xFF858B93:0xFF8BE5BB);}

    private static void drawRoute(GuiGraphicsExtractor g,double camX,double camZ,double scale,int sw,int sh,TownyMapConfig cfg,TeleportRoute route){double ax=sw/2.0+(route.destination().x()-camX)*scale,ay=sh/2.0+(route.destination().z()-camZ)*scale,tx=sw/2.0+(targetX-camX)*scale,ty=sh/2.0+(targetZ-camZ)*scale;if(cfg.teleportRouteLineVisible){int steps=TeleportRenderBudget.lineSteps(Math.hypot(tx-ax,ty-ay));for(int i=0;i<=steps;i+=2){double t=i/(double)steps;int px=(int)Math.round(ax+(tx-ax)*t),py=(int)Math.round(ay+(ty-ay)*t);if(px>=-2&&px<=sw+2&&py>=-2&&py<=sh+2)g.fill(px-1,py-1,px+2,py+2,0xFFFF58D0);}int lx=(int)((ax+tx)/2),ly=(int)((ay+ty)/2);if(lx>=-30&&lx<=sw+30&&ly>=-10&&ly<=sh+10){g.fill(lx-27,ly-8,lx+28,ly+7,0xCC171019);g.centeredText(Minecraft.getInstance().font,(int)Math.round(route.walkingDistance())+" blocks",lx,ly-4,0xFFFFFFFF);}}if(cfg.teleportArrivalMarkerVisible&&ax>=-8&&ax<=sw+8&&ay>=-8&&ay<=sh+8){g.fill((int)ax-5,(int)ay-5,(int)ax+6,(int)ay+6,0xFFFFB13B);g.fill((int)ax-2,(int)ay-2,(int)ax+3,(int)ay+3,0xFFFFFFFF);}if(cfg.teleportDestinationMarkerVisible&&tx>=-8&&tx<=sw+8&&ty>=-8&&ty<=sh+8){g.fill((int)tx-5,(int)ty-5,(int)tx+6,(int)ty+6,0xFF23C7E8);g.fill((int)tx-2,(int)ty-2,(int)tx+3,(int)ty+3,0xFFFFFFFF);}}

    public static boolean click(double mx,double my,int sw,int sh,TownyMapConfig cfg){
        if(!open)return false;int x=Math.clamp(cfg.teleportWindowX,4,Math.max(4,sw-W-4)),y=Math.clamp(cfg.teleportWindowY,4,Math.max(4,sh-HEADER-4));int topOffset=advanced?106:94;boolean loadMoreVisible=showLoadMore();int h=Math.min(sh-y-6,topOffset+VISIBLE*ROW+53+(loadMoreVisible?24:0));
        if(mx<x||mx>x+W||my<y||my>y+(minimized?HEADER:h))return false;
        if(my<y+HEADER){if(mx>x+W-25)close();else if(mx>x+W-50)minimized=!minimized;else{dragging=true;dragOffsetX=mx-x;dragOffsetY=my-y;}return true;}
        if(minimized)return true;
        if(my>=y+65&&my<=y+87&&cfg.teleportAdvancedEnabled){advanced=mx>=x+W/2;advancedConfirmed=false;selected=scroll=0;return true;}
        if(loadMoreVisible&&my>=y+h-68&&my<y+h-50){if(currentPlan!=null&&!currentPlan.loading()){resultLimit+=10;TownyMapMod.loadMoreTeleportData();}return true;}
        int footer=y+h-48;if(my>=footer&&!currentRoutes.isEmpty()){if(my<footer+20){if(mx<x+176)clearResults();else{boolean removed=XaeroWaypointBridge.removeTeleportWaypoints();feedback=Component.translatable(removed?"townymapaddon.teleport.waypoints_removed":"townymapaddon.teleport.no_waypoints").getString();feedbackUntil=System.currentTimeMillis()+1_600;}}else if(advancedLocked())advancedConfirmed=true;else if(mx<x+180){String town=TownyMapMod.teleportTownName(targetX,targetZ);XaeroWaypointBridge.createTeleportWaypoint(town==null?Component.translatable("townymapaddon.teleport.target").getString():town,(int)Math.round(targetX),0,(int)Math.round(targetZ));}else setArrivalWaypoint();return true;}
        int top=y+topOffset,index=scroll+(int)((my-top)/ROW);if(index>=scroll&&index<currentRoutes.size()&&index<scroll+VISIBLE){TeleportRoute route=currentRoutes.get(index);int rowY=top+(index-scroll)*ROW;boolean insideCommandX=mx>=x+14&&mx<x+W-14;if(!advancedLocked()&&insideCommandX){if(advanced&&my>=rowY+17&&my<rowY+31){String join=stepCommand(route,TeleportRoute.StepType.JOIN_TOWN);if(!join.isBlank()){selected=index;perform(join,route);}return true;}if(advanced&&my>=rowY+34&&my<rowY+48){selected=index;perform(route.destination().command(),route);return true;}if(!advanced&&my>=rowY+20&&my<rowY+37){selected=index;perform(route.destination().command(),route);return true;}}selected=index;return true;}return true;
    }
    private static void perform(String command,TeleportRoute route){TeleportCommandAction.Result result=TeleportCommandAction.perform(command,TownyMapMod.getConfig().teleportCommandAction,advanced,route.destination().eligibility()==TeleportDestination.Eligibility.ACCESSIBLE);feedback=Component.translatable("townymapaddon.teleport.command_result."+result.name().toLowerCase(Locale.ROOT)).getString();feedbackUntil=System.currentTimeMillis()+1_600;if(result==TeleportCommandAction.Result.EXECUTED){Minecraft mc=Minecraft.getInstance();if(mc!=null)mc.gui.setScreen(null);}}
    private static String stepCommand(TeleportRoute route,TeleportRoute.StepType type){return route.steps().stream().filter(s->s.type()==type).map(TeleportRoute.Step::command).filter(s->s!=null&&!s.isBlank()).findFirst().orElse("");}
    private static boolean advancedLocked(){return advanced&&!advancedConfirmed&&currentPlan!=null&&AdvancedCommandGuard.requiresConfirmation(currentPlan.player());}
    public static boolean release(TownyMapConfig cfg){if(!dragging)return false;dragging=false;cfg.save();return true;}
    public static boolean scroll(double mx,double my,double amount,int sw,int sh,TownyMapConfig cfg){if(!open)return false;int x=cfg.teleportWindowX,y=cfg.teleportWindowY;if(mx<x||mx>x+W||my<y||my>y+434)return false;int max=Math.max(0,currentRoutes.size()-VISIBLE);scroll=Math.clamp(scroll-(int)Math.signum(amount),0,max);selected=Math.clamp(selected,0,Math.max(0,currentRoutes.size()-1));return true;}
    private static boolean showLoadMore(){return !resultsCleared&&!currentRoutes.isEmpty()&&scroll>=Math.max(0,currentRoutes.size()-VISIBLE)&&TownyMapMod.hasMoreTeleportData();}
    private static List<TeleportRoute> routes(){return currentRoutes;}
    private static void refreshRoutes(TownyMapConfig cfg){TeleportAccessService.Plan next=TownyMapMod.teleportPlan(targetX,targetZ);boolean planChanged=next!=currentPlan;if(planChanged&&AdvancedCommandGuard.requiresConfirmation(next.player()))advancedConfirmed=false;if(!planChanged&&cachedAdvanced==advanced&&cachedTownSpawns==cfg.teleportShowTownSpawns&&cachedNationSpawns==cfg.teleportShowNationSpawns&&cachedUncertain==cfg.teleportShowUncertain)return;currentPlan=next;cachedAdvanced=advanced;cachedTownSpawns=cfg.teleportShowTownSpawns;cachedNationSpawns=cfg.teleportShowNationSpawns;cachedUncertain=cfg.teleportShowUncertain;List<TeleportRoute> base=advanced?next.advanced():next.standard();currentRoutes=base.stream().filter(r->cfg.teleportShowTownSpawns||r.destination().type()!=TeleportDestination.Type.TOWN_SPAWN).filter(r->cfg.teleportShowNationSpawns||r.destination().type()!=TeleportDestination.Type.NATION_SPAWN).filter(r->cfg.teleportShowUncertain||r.destination().eligibility()!=TeleportDestination.Eligibility.UNCERTAIN||r.destination().reason()==TeleportDestination.Reason.API_DATA_MISSING).limit(resultLimit).toList();selected=Math.clamp(selected,0,Math.max(0,currentRoutes.size()-1));scroll=Math.clamp(scroll,0,Math.max(0,currentRoutes.size()-VISIBLE));}
    private static TeleportRoute selectedRoute(){if(currentRoutes.isEmpty())return null;selected=Math.clamp(selected,0,currentRoutes.size()-1);return currentRoutes.get(selected);}
    public static void setArrivalWaypoint(){TeleportRoute r=selectedRoute();if(r!=null)XaeroWaypointBridge.createTeleportWaypoint("TP "+r.destination().name(),r.destination().x(),r.destination().y(),r.destination().z());}
    private static void clearResults(){resultsCleared=true;selected=scroll=0;currentPlan=null;currentRoutes=List.of();}
}

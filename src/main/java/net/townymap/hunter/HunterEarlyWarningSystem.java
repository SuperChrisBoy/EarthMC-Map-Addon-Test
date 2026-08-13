package net.townymap.hunter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.townymap.TownyMapConfig;
import net.townymap.api.EarthMcApiClient;
import net.townymap.gui.TownHoverOverlay;
import net.townymap.hunter.alert.HunterEvent;
import net.townymap.hunter.alert.HunterNotificationManager;
import net.townymap.hunter.cache.PositionHistoryCache;
import net.townymap.hunter.model.HunterState;
import net.townymap.hunter.route.ExposureRoutePlanner;
import net.townymap.hunter.threat.ThreatEngine;
import net.townymap.hunter.tracking.UserExposureTracker;
import net.townymap.teleport.TeleportAccessService;
import net.townymap.hunter.discovery.HunterCandidateService;
import net.townymap.model.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Client-thread coordinator. Networking remains in the existing async EarthMC/Squaremap clients. */
public final class HunterEarlyWarningSystem {
    private static final Pattern DEATH = Pattern.compile("^([A-Za-z0-9_]{1,16}) (was slain by|was shot by|was blown up by|was killed by) ([A-Za-z0-9_]{1,16})(?: using .*)?$");
    private final TownyMapConfig config; private final EarthMcApiClient earth;
    private final Map<String,HunterState> states = new LinkedHashMap<>();
    private final PositionHistoryCache positions = new PositionHistoryCache();
    private final UserExposureTracker exposure = new UserExposureTracker();
    private final ThreatEngine threats = new ThreatEngine();
    private final ExposureRoutePlanner routes = new ExposureRoutePlanner();
    private final TeleportAccessService teleports;
    private final HunterNotificationManager notifications;
    private final HunterCandidateService candidates;
    private final Map<String,NearbyCandidate> nearbyCandidates = new HashMap<>();
    private long lastTick, lastIdentityPoll;
    private volatile List<TownData> towns = List.of();
    private volatile Map<String,PlayerMarker> visible = Map.of();
    private ExposureRoutePlanner.Route route;

    public HunterEarlyWarningSystem(TownyMapConfig config, EarthMcApiClient earth, TeleportAccessService teleports, Consumer<String> alertSink) {
        this.config=config; this.earth=earth; this.teleports=teleports;this.notifications=new HunterNotificationManager(config, alertSink);this.candidates=new HunterCandidateService(config,earth);
    }
    public void tick(Minecraft mc, List<PlayerMarker> players, List<TownData> townSnapshot) {
        towns=townSnapshot;
        candidates.tick(townSnapshot);
        if (!config.hunterWarningEnabled || mc.player == null || mc.getUser() == null) return;
        long now=System.currentTimeMillis(); if (now-lastTick<250) return; lastTick=now;
        positions.record(players,now);
        Map<String,PlayerMarker> map=new HashMap<>(); for(PlayerMarker p:players) if(p.name()!=null) map.put(key(p.name()),p); visible=Map.copyOf(map);
        if(syncWatchlist()) lastIdentityPoll=0; double scale=net.townymap.TownyMapMod.dimensionCoordinateScale(); double px=mc.player.getX()*scale,pz=mc.player.getZ()*scale;
        updateNearbyCandidates(players,px,pz,scale,now,mc.getUser().getName());
        TownData userTown=TownHoverOverlay.townAt(px,pz,towns); boolean selfVisible=map.containsKey(key(mc.getUser().getName())); exposure.update(selfVisible,userTown==null,now);
        if(userTown==null && exposure.visible()) route=routes.recommend(px,pz,towns); else route=null;
        for(HunterState h:states.values()) updateHunter(h,map.get(key(h.name)),px,pz,now);
        if(now-lastIdentityPoll>=60_000){lastIdentityPoll=now; for(HunterState h:states.values()) pollIdentity(mc,h,px,pz,now);}
    }
    private void updateNearbyCandidates(List<PlayerMarker> players,double px,double pz,double scale,long now,String localName){
        if(!config.hunterCandidateWarningsEnabled){nearbyCandidates.clear();return;}
        Set<String> watched=states.keySet();
        nearbyCandidates.values().forEach(c->c.visibleNow=false);
        for(PlayerMarker p:players){
            String k=key(p.name());if(k.isEmpty()||k.equals(key(localName))||watched.contains(k))continue;
            var candidate=candidates.lookup(p.name());
            if(candidate==null||candidate.outlawTownCount()<=config.hunterCandidateOutlawThreshold)continue;
            double distance=Math.hypot(p.x()-px,p.z()-pz)/scale;
            if(distance>config.hunterCandidateWarningRadius*1.10&&!nearbyCandidates.containsKey(k))continue;
            NearbyCandidate state=nearbyCandidates.computeIfAbsent(k,ignored->new NearbyCandidate(candidate.name(),candidate.outlawTownCount()));
            state.name=candidate.name();state.outlawCount=candidate.outlawTownCount();state.x=p.x();state.z=p.z();state.distance=distance;state.lastSeenAt=now;state.visibleNow=true;state.claim=Optional.ofNullable(TownHoverOverlay.townAt(p.x(),p.z(),towns)).map(TownData::name).orElse("WILDERNESS");
            if(distance<=config.hunterCandidateWarningRadius&&!state.inside){
                state.inside=true;
                String bearing=config.hunterDirectionEnabled?" "+direction(px,pz,p.x(),p.z()):"";
                notifications.publish(HunterEvent.warning("candidate:"+k+":nearby",Component.translatable("townymapaddon.hunter.warning.candidate_nearby",state.name,(int)Math.round(distance),state.outlawCount),now,
                        Component.translatable("townymapaddon.hunter.hud.name_distance",state.name,(int)Math.round(distance),bearing),
                        Component.translatable("townymapaddon.hunter.hud.claim_visible",state.claim),Component.translatable("townymapaddon.hunter.candidate.outlaw_count",state.outlawCount)).typed(HunterEvent.Type.CANDIDATE).positioned(p.x(),p.z()));
            }else if(distance>config.hunterCandidateWarningRadius*1.10)state.inside=false;
        }
        nearbyCandidates.entrySet().removeIf(e->watched.contains(e.getKey())||now-e.getValue().lastSeenAt>30_000L);
        if(nearbyCandidates.size()>32)nearbyCandidates.entrySet().stream().sorted(Comparator.comparingLong(e->e.getValue().lastSeenAt)).limit(nearbyCandidates.size()-32).map(Map.Entry::getKey).toList().forEach(nearbyCandidates::remove);
    }
    private void pollIdentity(Minecraft mc,HunterState h,double px,double pz,long now){earth.fetchPlayer(h.name).thenAccept(data->{if(data==null)return;mc.execute(()->{String oldTown=h.residenceTown;String oldNation=h.nation;HunterState.OnlineStatus oldOnline=h.online;boolean initial=h.residenceCheckedAt==0;h.applyIdentity(data);h.residenceCheckedAt=now;if(!initial&&oldOnline!=h.online)notifications.publish(HunterEvent.normal(h.configuredName+":online:"+h.online,h.name+" is "+h.online.name().toLowerCase(Locale.ROOT),now));if(!oldTown.equalsIgnoreCase(h.residenceTown)||!oldNation.equalsIgnoreCase(h.nation)){if(!initial)notifications.publish(HunterEvent.normal(h.configuredName+":mobility",h.name+" changed residence",now,(oldTown.isBlank()?"No town":oldTown)+" → "+(h.residenceTown.isBlank()?"No town":h.residenceTown)));recomputeTeleports(mc,h,px,pz,oldTown);}});});}
    private void recomputeTeleports(Minecraft mc,HunterState h,double px,double pz,String oldTown){
        var townFuture=h.residenceTown.isBlank()?java.util.concurrent.CompletableFuture.<TownFullData>completedFuture(null):earth.fetchTownFull(h.residenceTown);
        var nationFuture=h.nation.isBlank()?java.util.concurrent.CompletableFuture.<NationFullData>completedFuture(null):earth.fetchNationFull(h.nation);
        townFuture.thenCombine(nationFuture,(town,nation)->teleports.fromOfficialDetails(town,nation,px,pz)).thenAccept(options->mc.execute(()->{h.teleportOptions=options;if(!options.isEmpty()){var tp=options.getFirst();long at=System.currentTimeMillis();String line="Possible TP: "+tp.destinationName()+" · "+(int)Math.round(tp.distanceToLocalPlayer())+"m";notifications.publish(tp.distanceToLocalPlayer()<=config.hunterTeleportThreatRadius?HunterEvent.warning(h.configuredName+":tp",h.name+" gained nearby teleport access",at,line):HunterEvent.normal(h.configuredName+":tp",h.name+" teleport options changed",at,line));}}));
    }
    private boolean syncWatchlist(){boolean added=false;Set<String>w=new LinkedHashSet<>();for(String n:config.hunterWatchlist)if(n!=null&&!n.isBlank()&&net.townymap.hunter.config.HunterWatchlist.enabled(config,n))w.add(key(n));states.keySet().retainAll(w);for(String n:config.hunterWatchlist)if(n!=null&&!n.isBlank()&&net.townymap.hunter.config.HunterWatchlist.enabled(config,n)){String k=key(n);if(!states.containsKey(k)){states.put(k,new HunterState(n.trim()));added=true;}}return added;}
    private void updateHunter(HunterState h,PlayerMarker p,double px,double pz,long now){
        HunterState.Visibility old=h.visibility;
        if(p!=null){
            TownData t=TownHoverOverlay.townAt(p.x(),p.z(),towns); String claim=t==null?"WILDERNESS":t.name();
            String nation=t==null?"":net.townymap.TownyMapMod.knownTownNation(t.name()); String oldClaim=h.direct==null?claim:h.direct.claim();
            h.direct=new HunterState.Observation(p.x(),p.z(),now,HunterState.ObservationType.DIRECT_DYNMAP,claim,nation,HunterState.Confidence.HIGH);
            h.visibility=HunterState.Visibility.VISIBLE; h.online=HunterState.OnlineStatus.ONLINE;
            double currentDistance=Math.hypot(p.x()-px,p.z()-pz);
            if(old==HunterState.Visibility.HIDDEN) notifications.publish(currentDistance<=config.hunterNearbyRadius
                    ? HunterEvent.warning(h.configuredName+":return",h.name+" reappeared nearby",now,(int)Math.round(currentDistance)+"m from you")
                    : HunterEvent.normal(h.configuredName+":return",h.name+" became visible",now,"Dynmap position restored"));
            if(!oldClaim.equalsIgnoreCase(claim)) notifications.publish(HunterEvent.normal(h.configuredName+":claim:"+claim,h.name+" changed location",now,oldClaim+" → "+claim));
        }else if(h.direct!=null){
            h.visibility=HunterState.Visibility.HIDDEN;
            if(old==HunterState.Visibility.VISIBLE){double d=Math.hypot(h.direct.x()-px,h.direct.z()-pz);String line="Last seen ~"+(int)Math.round(d)+"m away · "+h.direct.claim();notifications.publish(d<=config.hunterNearbyRadius
                    ? HunterEvent.warning(h.configuredName+":lost",h.name+" position lost nearby",now,line)
                    : HunterEvent.normal(h.configuredName+":lost",h.name+" position lost",now,line));}
        }
        HunterState.Observation o=h.bestObservation(); double d=o==null?Double.POSITIVE_INFINITY:Math.hypot(o.x()-px,o.z()-pz);
        int zone=zone(d); if(zone<h.proximityZone&&d<=threshold(h.proximityZone)*1.10)zone=h.proximityZone;
        if(zone>h.proximityZone) notifications.publish(HunterEvent.warning(h.configuredName+":zone:"+zone,h.name+" entered the "+threshold(zone)+"m warning radius",now,(int)Math.round(d)+"m"+(config.hunterDirectionEnabled?" "+direction(px,pz,o.x(),o.z()):"")));
        h.proximityZone=zone; HunterState.ThreatAssessment oldThreat=h.threat;
        h.threat=threats.assess(h,d,exposure.visible(),TownHoverOverlay.townAt(px,pz,towns)==null,now);
        if(h.threat.level().ordinal()>oldThreat.level().ordinal()) notifications.publish(h.threat.level().ordinal()>=HunterState.ThreatLevel.HIGH.ordinal()
                ? HunterEvent.warning(h.configuredName+":risk:"+h.threat.level(),"Risk became "+h.threat.level()+" for "+h.name,now,String.join(", ",h.threat.reasons()))
                : HunterEvent.normal(h.configuredName+":risk:"+h.threat.level(),"Risk became "+h.threat.level()+" for "+h.name,now,String.join(", ",h.threat.reasons())));
    }
    private int zone(double d){if(d<=config.hunterCriticalRadius)return 4;if(d<=config.hunterHighRadius)return 3;if(d<=config.hunterElevatedRadius)return 2;if(d<=config.hunterNearbyRadius)return 1;return 0;}
    private int threshold(int z){return z==4?config.hunterCriticalRadius:z==3?config.hunterHighRadius:z==2?config.hunterElevatedRadius:config.hunterNearbyRadius;}
    public List<String> exposureHudLines() {
        if(!config.hunterWarningEnabled||!config.hunterShowHud||!config.hunterExposureHud)return List.of();
        long now=System.currentTimeMillis();
        return List.of("§bDYNMAP: §f"+(exposure.visible()?"VISIBLE":"HIDDEN")+" §7· "+format(exposure.stateDurationMs(now)),
                "§bExposure: §f"+exposure.exposurePercent()+"% §7/ last 5m");
    }
    public List<String> hunterHudLines(Minecraft mc){
        if(!config.hunterWarningEnabled||!config.hunterShowHud||mc.player==null)return List.of();
        double s=net.townymap.TownyMapMod.dimensionCoordinateScale(),px=mc.player.getX()*s,pz=mc.player.getZ()*s;long now=System.currentTimeMillis();
        record Row(HunterState h,double d,int priority){} ArrayList<Row>rows=new ArrayList<>();
        if(config.hunterShowNearby)for(HunterState h:states.values()){var o=h.bestObservation();if(o!=null){double d=Math.hypot(o.x()-px,o.z()-pz)/s;if(d<=config.hunterNearbyRadius){int priority=h.threat.level().ordinal()*10+(h.inferred!=null&&now-h.inferred.atMs()<60_000?5:0);rows.add(new Row(h,d,priority));}}}
        rows.sort(Comparator.comparingInt(Row::priority).reversed().thenComparingDouble(Row::d)); ArrayList<String>out=new ArrayList<>();
        List<String> eventLines=notifications.hudLines(now); boolean warningEvent=!eventLines.isEmpty()&&!eventLines.getFirst().contains("RECENT EVENT"); if(warningEvent)out.addAll(eventLines);
        if(!rows.isEmpty()){out.add("§c§l"+Component.translatable("townymapaddon.hunter.watch.title").getString());for(int i=0;i<Math.min(config.hunterMaxHudEntries,rows.size());i++){Row r=rows.get(i);var o=r.h.bestObservation();String approx=r.h.visibility==HunterState.Visibility.VISIBLE?"":"~";out.add("§f"+r.h.name+" §c"+approx+(int)Math.round(r.d)+"m"+(config.hunterDirectionEnabled?" "+direction(px,pz,o.x(),o.z()):""));if(r.h.visibility==HunterState.Visibility.VISIBLE)out.add("§7"+o.claim()+" · VISIBLE");else{out.add("§cPOSITION LOST §7· "+format(now-o.atMs()));out.add("§7Last seen: "+o.claim());}if(config.hunterShowRisk&&r.h.threat.level().ordinal()>=HunterState.ThreatLevel.ELEVATED.ordinal())out.add("§7Risk: §f"+r.h.threat.level()+" §7("+r.h.threat.score()+")");}if(rows.size()>config.hunterMaxHudEntries)out.add("§7+"+(rows.size()-config.hunterMaxHudEntries)+" more");}
        for(Row r:rows){if(!r.h.teleportOptions.isEmpty()){var tp=r.h.teleportOptions.getFirst();if(tp.distanceToLocalPlayer()/s<=config.hunterTeleportThreatRadius){out.add("§cTP THREAT §f"+r.h.name+" §7~"+(int)Math.round(tp.distanceToLocalPlayer()/s)+"m via "+tp.destinationName());break;}}}
        if(route!=null)out.add("§aSAFE ROUTE §f"+(config.hunterDirectionEnabled?direction(px,pz,route.x(),route.z())+" ":"")+"§7"+(int)Math.round(route.distance()/s)+"m to "+route.destination());
        if(config.hunterCandidateWarningsEnabled){
            List<NearbyCandidate> automatic=nearbyCandidates.values().stream().filter(c->c.inside||(!c.visibleNow&&now-c.lastSeenAt<=30_000L)).sorted(Comparator.comparingDouble(c->c.distance)).toList();
            int available=Math.max(0,config.hunterMaxHudEntries-rows.size());
            if(!automatic.isEmpty()&&available>0)out.add("§6§l⚠ "+Component.translatable("townymapaddon.hunter.hud.possible_hunter").getString());
            for(int i=0;i<Math.min(available,automatic.size());i++){NearbyCandidate c=automatic.get(i);String bearing=config.hunterDirectionEnabled?direction(px,pz,c.x,c.z):"";out.add("§f"+Component.translatable("townymapaddon.hunter.hud.name_distance",c.name,(c.visibleNow?"":"~")+(int)Math.round(c.distance),bearing).getString());out.add(c.visibleNow?"§7"+Component.translatable("townymapaddon.hunter.hud.claim_visible",c.claim).getString():"§c"+Component.translatable("townymapaddon.hunter.hud.position_lost",format(now-c.lastSeenAt)).getString());out.add("§7"+Component.translatable("townymapaddon.hunter.candidate.outlaw_count",c.outlawCount).getString());}
        }
        if(!eventLines.isEmpty()&&!warningEvent)out.addAll(eventLines); return out;
    }
    public void onSystemMessage(String text){
        if(!config.hunterWarningEnabled||text==null)return; Matcher m=DEATH.matcher(text.replaceAll("§.","").trim()); if(!m.matches())return;
        String victim=m.group(1),killer=m.group(3); long now=System.currentTimeMillis(); HunterState hk=states.get(key(killer)),hv=states.get(key(victim));
        if(hk!=null){hk.combatHistory.addFirst(new HunterState.CombatEvent("Killed "+victim,now,true));boolean inferred=infer(hk,victim,now);if(!inferred)notifications.publish(HunterEvent.normal(hk.configuredName+":kill:"+now,hk.name+" killed "+victim,now,"No fresh mapped victim position"));}
        if(hv!=null){hv.combatHistory.addFirst(new HunterState.CombatEvent("Killed by "+killer,now,false));boolean inferred=infer(hv,killer,now);if(!inferred)notifications.publish(HunterEvent.normal(hv.configuredName+":death:"+now,hv.name+" was killed by "+killer,now));}
    }
    private boolean infer(HunterState h,String other,long now){
        var sample=positions.latestBefore(other,now);if(sample==null)return false;long age=now-sample.atMs();if(age>30_000)return false;
        HunterState.Confidence c=age<=5000?HunterState.Confidence.HIGH:age<=15000?HunterState.Confidence.MEDIUM:HunterState.Confidence.LOW;TownData t=TownHoverOverlay.townAt(sample.x(),sample.z(),towns);
        h.inferred=new HunterState.Observation(sample.x(),sample.z(),now,HunterState.ObservationType.COMBAT_INFERRED,t==null?"WILDERNESS":t.name(),"",c);
        Minecraft mc=Minecraft.getInstance();double s=net.townymap.TownyMapMod.dimensionCoordinateScale(),d=mc.player==null?Double.POSITIVE_INFINITY:Math.hypot(sample.x()-mc.player.getX()*s,sample.z()-mc.player.getZ()*s)/s;
        String distance="Combat estimate: ~"+(int)Math.round(d)+"m";String timing="Victim mapped "+format(age)+" before death · "+c;
        notifications.publish(d<=config.hunterHighRadius?HunterEvent.urgent(h.configuredName+":inferred",h.name+" may be nearby",now,distance,timing,"Recent kill while position is lost")
                :d<=config.hunterNearbyRadius?HunterEvent.warning(h.configuredName+":inferred",h.name+" combat estimate is nearby",now,distance,timing)
                :HunterEvent.normal(h.configuredName+":inferred",h.name+" killed "+other,now,distance,timing));return true;
    }
    public Collection<HunterState> states(){return List.copyOf(states.values());}
    public List<HunterEvent> activityHistory(){return notifications.history();}
    public HunterCandidateService candidateService(){return candidates;}
    private static final class NearbyCandidate{String name,claim="WILDERNESS";int outlawCount,x,z;double distance;long lastSeenAt;boolean inside,visibleNow;NearbyCandidate(String name,int count){this.name=name;this.outlawCount=count;}}
    /** Distinct world-map glyphs: direct orange, last-known red, inferred purple, possible TP cyan, route green. */
    public void renderWorldMap(GuiGraphicsExtractor ctx,double cameraX,double cameraZ,double scale,int sw,int sh){if(!config.hunterWarningEnabled||scale<=0)return;for(HunterState h:states.values()){var o=h.bestObservation();if(o!=null){int color=o.type()==HunterState.ObservationType.COMBAT_INFERRED?0xFFB05CFF:h.visibility==HunterState.Visibility.VISIBLE?0xFFFF8C32:0xFFE23B3B;cross(ctx,sx(o.x(),cameraX,scale,sw),sy(o.z(),cameraZ,scale,sh),color);}for(var tp:h.teleportOptions)cross(ctx,sx(tp.x(),cameraX,scale,sw),sy(tp.z(),cameraZ,scale,sh),0xFF4DDDEB);}if(route!=null){int x=sx(route.x(),cameraX,scale,sw),y=sy(route.z(),cameraZ,scale,sh);Minecraft mc=Minecraft.getInstance();if(mc.player!=null){double ds=net.townymap.TownyMapMod.dimensionCoordinateScale();dottedLine(ctx,sx(mc.player.getX()*ds,cameraX,scale,sw),sy(mc.player.getZ()*ds,cameraZ,scale,sh),x,y,0xFF55DD77);}cross(ctx,x,y,0xFF55DD77);}}
    private static int sx(double x,double camera,double scale,int size){return(int)Math.round((x-camera)*scale+size/2.0);}private static int sy(double z,double camera,double scale,int size){return(int)Math.round((z-camera)*scale+size/2.0);}private static void cross(GuiGraphicsExtractor c,int x,int y,int color){c.fill(x-5,y-1,x+6,y+2,color);c.fill(x-1,y-5,x+2,y+6,color);}private static void dottedLine(GuiGraphicsExtractor c,int x1,int y1,int x2,int y2,int color){int steps=Math.min(300,Math.max(Math.abs(x2-x1),Math.abs(y2-y1)));if(steps<=0)return;for(int i=0;i<=steps;i+=6){int x=x1+(x2-x1)*i/steps,y=y1+(y2-y1)*i/steps;c.fill(x-1,y-1,x+2,y+2,color);}}
    private static String key(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT);}private static String format(long ms){long sec=Math.max(0,ms/1000);return sec<60?sec+"s":(sec/60)+"m "+(sec%60)+"s";}
    public static String direction(double fromX,double fromZ,double toX,double toZ){double deg=(Math.toDegrees(Math.atan2(toX-fromX,fromZ-toZ))+360)%360;String[]d={"N","NE","E","SE","S","SW","W","NW"};return d[(int)Math.round(deg/45)%8];}
}

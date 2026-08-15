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
import net.townymap.hunter.model.ApproachRoute;
import net.townymap.hunter.route.ExposureRoutePlanner;
import net.townymap.hunter.threat.ThreatEngine;
import net.townymap.hunter.tracking.UserExposureTracker;
import net.townymap.hunter.tracking.WildernessExposureSession;
import net.townymap.hunter.teleport.HunterApproachService;
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
    private final Map<String,HunterState> candidateStates = new LinkedHashMap<>();
    private final Map<String,Integer> candidateOutlawCounts = new HashMap<>();
    private final PositionHistoryCache positions = new PositionHistoryCache();
    private final UserExposureTracker exposure = new UserExposureTracker();
    private final ThreatEngine threats;
    private final WildernessExposureSession exposureSession;
    private final net.townymap.integration.XaeroRadiusOverlayProvider radiusOverlays=new net.townymap.integration.XaeroRadiusOverlayProvider();
    private final net.townymap.integration.XaeroRadiusOverlayProvider minimapRadiusOverlays=new net.townymap.integration.XaeroRadiusOverlayProvider();
    private final net.townymap.hunter.front.HiddenThreatFrontEngine threatFronts;
    private final HunterApproachService approaches;
    private final ExposureRoutePlanner routes = new ExposureRoutePlanner();
    private final TeleportAccessService teleports;
    private final HunterNotificationManager notifications;
    private final HunterCandidateService candidates;
    private final Map<String,NearbyCandidate> nearbyCandidates = new HashMap<>();
    private final Map<String,Boolean> ruinStatus = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> ruinLoading = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private long lastTick, lastIdentityPoll, lastApproachRefresh, approachGeneration;private double lastApproachX=Double.NaN,lastApproachZ=Double.NaN;
    private long lastClaimDistanceCheck;
    private long successfulCycles,trackingErrors,lastSuccessfulCycle,lastDynmapUpdate,lastEarthmcUpdate,lastThreatEvaluation,lastHealthLog,lastAutoActorBuild,lastPipelineWarning;private int lastOnlinePlayerCount,lastQualifyingHighOutlaws,lastExposureBand;
    private Object playerIdentity;
    private double nearestClaimDistance;
    private volatile List<TownData> towns = List.of();
    private volatile Map<String,PlayerMarker> visible = Map.of();
    private ExposureRoutePlanner.Route route;

    public HunterEarlyWarningSystem(TownyMapConfig config, EarthMcApiClient earth, TeleportAccessService teleports, Consumer<String> alertSink) {
        this.config=config; this.earth=earth; this.teleports=teleports;this.threats=new ThreatEngine(config.hunterLungeBlocksPerSecond,config.hunterTeleportSetupSeconds,config.hunterArrivalSafetyFactor,config.hunterOfflineResidualMinutes);this.threatFronts=new net.townymap.hunter.front.HiddenThreatFrontEngine(config);this.exposureSession=new WildernessExposureSession(config.hunterExposureEntryRadius,config.hunterExposureEntryLimit,config.hunterExposureEntryBufferSeconds*1000L,config.hunterExposureClaimGraceSeconds*1000L,config.hunterWildernessTargetExposureSeconds*1000L);this.approaches=new HunterApproachService(earth);this.notifications=new HunterNotificationManager(config, alertSink);this.candidates=new HunterCandidateService(config,earth);
    }
    public void tick(Minecraft mc, List<PlayerMarker> players, List<TownData> townSnapshot) {
        towns=townSnapshot;
        candidates.tick(townSnapshot);
        long now=System.currentTimeMillis();
        if (!config.hunterWarningEnabled || mc.player == null || mc.getUser() == null){if(mc.player==null){exposureSession.reset();playerIdentity=null;}threatFronts.clear();radiusOverlays.clear();minimapRadiusOverlays.clear();logHealth(now);return;}
        if(playerIdentity!=null&&playerIdentity!=mc.player)exposureSession.reset();playerIdentity=mc.player;
        threats.setOfflineResidualMinutes(config.hunterOfflineResidualMinutes);
        if (now-lastTick<250) return; lastTick=now;
        positions.record(players,now);
        lastDynmapUpdate=now;lastOnlinePlayerCount=players.size();
        Map<String,PlayerMarker> map=new HashMap<>(); for(PlayerMarker p:players) if(p.name()!=null) map.put(key(p.name()),p); visible=Map.copyOf(map);
        if(syncWatchlist()) lastIdentityPoll=0; double scale=net.townymap.TownyMapMod.dimensionCoordinateScale(); double px=mc.player.getX()*scale,pz=mc.player.getZ()*scale;
        updateNearbyCandidates(players,px,pz,scale,now,mc.getUser().getName());
        TownData userTown=TownHoverOverlay.townAt(px,pz,towns); boolean selfVisible=map.containsKey(key(mc.getUser().getName())); boolean unsafe=userTown==null||Boolean.TRUE.equals(ruinStatus.get(key(userTown.name()))); exposure.update(selfVisible,unsafe,now);
        if(userTown!=null&&!ruinStatus.containsKey(key(userTown.name()))&&ruinLoading.add(key(userTown.name())))earth.fetchTownFull(userTown.name()).whenComplete((full,error)->{if(full!=null)ruinStatus.put(key(full.name()),full.isRuined());ruinLoading.remove(key(userTown.name()));});
        boolean entriesChanged=exposureSession.update(unsafe,px,pz,towns,now);
        publishExposureTransition(now);
        publishExposureMilestone(now);
        boolean approachMoved=!Double.isFinite(lastApproachX)||Math.hypot(px-lastApproachX,pz-lastApproachZ)>=WildernessExposureSession.MEANINGFUL_MOVE;
        if(approachRefreshDue(!allThreatStates().isEmpty(),entriesChanged,approachMoved,now,lastApproachRefresh,Math.max(15,config.hunterThreatRefreshSeconds)*1000L))refreshApproaches(mc,now,px,pz);
        if(userTown!=null)nearestClaimDistance=0;else if(now-lastClaimDistanceCheck>=1_000){lastClaimDistanceCheck=now;nearestClaimDistance=distanceToNearestClaim(px,pz,towns);}
        if(unsafe && exposure.visible()) route=routes.recommend(px,pz,towns); else route=null;
        for(HunterState h:states.values())safeUpdateHunter(h,map.get(key(h.name)),px,pz,now);
        syncCandidateThreatStates(players,map,now);
        for(HunterState h:candidateStates.values())safeUpdateHunter(h,map.get(key(h.name)),px,pz,now);
        threatFronts.retainActors(allThreatStates(),now,px,pz);
        if(now-lastIdentityPoll>=60_000){lastIdentityPoll=now; for(HunterState h:allThreatStates()) pollIdentity(mc,h,px,pz,now);}
        radiusOverlays.publish(threatFronts.renderFronts(config.hunterFrontWorldMapLimit,config.hunterMaxActiveTeleportThreatsPerHunterWorldMap),now,config.hunterFrontWorldMapLimit);
        minimapRadiusOverlays.publish(threatFronts.renderFronts(config.hunterMaxActiveTeleportThreatsGlobalMinimap,config.hunterMaxActiveTeleportThreatsPerHunterMinimap),now,config.hunterMaxActiveTeleportThreatsGlobalMinimap);
        successfulCycles++;lastSuccessfulCycle=now;logHealth(now);
    }
    public void tickSafely(Minecraft mc,List<PlayerMarker> players,List<TownData> townSnapshot){try{tick(mc,players==null?List.of():players,townSnapshot==null?List.of():townSnapshot);}catch(RuntimeException error){recordError("cycle",null,error);long now=System.currentTimeMillis();if(now-lastHealthLog>=10_000){lastHealthLog=now;net.townymap.TownyMapMod.LOGGER.warn("[HunterAlert/Health] tracking cycle failed; scheduler remains active cycles={} manual={} outlaw={} errors={}",successfulCycles,states.size(),candidateStates.size(),trackingErrors);}}}
    private void syncCandidateThreatStates(List<PlayerMarker> players,Map<String,PlayerMarker> visibleNow,long now){
        lastAutoActorBuild=now;
        if(!config.hunterCandidateWarningsEnabled){candidateStates.clear();candidateOutlawCounts.clear();return;}
        record Qualified(String key,String name,int count){}ArrayList<Qualified> qualified=new ArrayList<>();for(PlayerMarker p:players){String k=key(p.name());if(k.isEmpty())continue;var c=candidates.lookup(p.name());if(c!=null&&qualifiesAutomatic(config.hunterCandidateWarningsEnabled,states.containsKey(k),true,c.outlawTownCount(),config.hunterCandidateOutlawThreshold))qualified.add(new Qualified(k,c.name(),c.outlawTownCount()));}qualified.sort(Comparator.comparingInt(Qualified::count).reversed());lastQualifyingHighOutlaws=qualified.size();for(Qualified c:qualified){if(!candidateStates.containsKey(c.key())){candidateStates.put(c.key(),new HunterState(c.name(),HunterState.Source.AUTO_HIGH_OUTLAW));lastApproachRefresh=0;}candidateOutlawCounts.put(c.key(),c.count());}
        candidateStates.entrySet().removeIf(e->{var indexed=candidates.lookup(e.getValue().name);return states.containsKey(e.getKey())||e.getValue().online==HunterState.OnlineStatus.OFFLINE||indexed==null||indexed.outlawTownCount()<=config.hunterCandidateOutlawThreshold;});
        candidateOutlawCounts.keySet().retainAll(candidateStates.keySet());
    }
    private List<HunterState> allThreatStates(){ArrayList<HunterState> all=new ArrayList<>(states.values());all.addAll(candidateStates.values());return all;}
    static boolean approachRefreshDue(boolean hasActors,boolean entriesChanged,boolean moved,long now,long lastRefresh,long interval){return hasActors&&(entriesChanged||moved||now-lastRefresh>=interval);}
    static boolean candidateShouldRemove(boolean manuallyWatched,boolean hasObservation,long observationAt,boolean visibleNow,long now){return manuallyWatched||(hasObservation&&!visibleNow&&now-observationAt>30*60_000L);}
    static boolean qualifiesAutomatic(boolean enabled,boolean manual,boolean online,int outlawCount,int threshold){return enabled&&!manual&&online&&outlawCount>threshold;}
    public void onCommandSent(String command){exposureSession.recordCommand(command,System.currentTimeMillis());lastApproachRefresh=0;}
    private void refreshApproaches(Minecraft mc,long now,double px,double pz){
        lastApproachRefresh=now;lastApproachX=px;lastApproachZ=pz;long generation=++approachGeneration;
        List<String> names=allThreatStates().stream().map(h->h.name).toList();
        List<WildernessExposureSession.Entry> entries=nearbyApproachEntries(px,pz);
        approaches.refreshNetwork(towns,names,px,pz,exposureSession.recentEntry(now)).whenComplete((snapshot,error)->{
            if(error!=null){net.townymap.TownyMapMod.LOGGER.warn("[HunterAlert] Failed to refresh nearby hunter spawn access",error);return;}
            mc.execute(()->{if(generation!=approachGeneration)return;int updated=0,townRoutes=0,nationRoutes=0;for(HunterState h:allThreatStates()){var fresh=snapshot.get(key(h.name));if(fresh==null)continue;List<net.townymap.hunter.model.ApproachRoute> before=h.approachRoutes;h.approachRoutes=fresh;updated++;townRoutes+=(int)fresh.stream().filter(r->r.type()==net.townymap.hunter.model.ApproachRoute.Type.TOWN).count();nationRoutes+=(int)fresh.stream().filter(r->r.type()==net.townymap.hunter.model.ApproachRoute.Type.NATION).count();publishRouteChange(h,before,h.approachRoutes,System.currentTimeMillis());}net.townymap.TownyMapMod.LOGGER.info("[HunterAlert/ThreatFront] teleport origins refreshed actorsRequested={} actorsUpdated={} nearbyTowns={} townOrigins={} nationOrigins={}",names.size(),updated,entries.size(),townRoutes,nationRoutes);});
        });
    }
    private List<WildernessExposureSession.Entry> nearbyApproachEntries(double px,double pz){return towns.stream().map(t->new WildernessExposureSession.Entry(t.name(),WildernessExposureSession.Entry.Type.TOWN,t.centerX(),t.centerZ(),Math.hypot(t.centerX()-px,t.centerZ()-pz))).filter(e->e.distance()<=config.hunterExposureEntryRadius).sorted(Comparator.comparingDouble(WildernessExposureSession.Entry::distance).thenComparing(WildernessExposureSession.Entry::name)).limit(config.hunterExposureEntryLimit).toList();}
    private void publishRouteChange(HunterState h,List<net.townymap.hunter.model.ApproachRoute> before,List<net.townymap.hunter.model.ApproachRoute> after,long now){
        if(h.visibility==HunterState.Visibility.HIDDEN){rememberHiddenRoutes(h,before,now,true);rememberHiddenRoutes(h,after,now,false);}
        Set<String> old=new HashSet<>();before.forEach(r->old.add(r.key()));Set<String> fresh=new HashSet<>();after.forEach(r->fresh.add(r.key()));
        if(!fresh.equals(old)){String summary=after.isEmpty()?"No verified nearby spawn access":after.stream().map(r->r.name()+" "+Math.round(r.distanceToUser())+"m").limit(3).reduce((a,b)->a+", "+b).orElse("");HunterEvent event=HunterEvent.normal(h.configuredName+":routes:"+fresh.hashCode(),tr("event.tp_changed",h.name),now,Component.literal(summary)).typed(HunterEvent.Type.TELEPORT);if(h.source==HunterState.Source.AUTO_HIGH_OUTLAW&&h.threat.level().ordinal()<HunterState.ThreatLevel.HIGH.ordinal())notifications.activityOnly(event);else notifications.publish(event);}
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
                HunterEvent event=HunterEvent.warning("candidate:"+k+":nearby",Component.translatable("townymapaddon.hunter.warning.candidate_nearby",state.name,(int)Math.round(distance),state.outlawCount),now,
                        Component.translatable("townymapaddon.hunter.hud.name_distance",state.name,(int)Math.round(distance),bearing),
                        Component.translatable("townymapaddon.hunter.hud.claim_visible",state.claim),Component.translatable("townymapaddon.hunter.candidate.outlaw_count",state.outlawCount)).typed(HunterEvent.Type.CANDIDATE).positioned(p.x(),p.z());HunterState actor=candidateStates.get(k);if(actor!=null&&actor.threat.level().ordinal()>=HunterState.ThreatLevel.HIGH.ordinal())notifications.publish(event);
            }else if(distance>config.hunterCandidateWarningRadius*1.10)state.inside=false;
        }
        nearbyCandidates.entrySet().removeIf(e->watched.contains(e.getKey())||now-e.getValue().lastSeenAt>30*60_000L);
        if(nearbyCandidates.size()>32)nearbyCandidates.entrySet().stream().sorted(Comparator.comparingLong(e->e.getValue().lastSeenAt)).limit(nearbyCandidates.size()-32).map(Map.Entry::getKey).toList().forEach(nearbyCandidates::remove);
    }
    private void pollIdentity(Minecraft mc,HunterState h,double px,double pz,long now){earth.fetchPlayer(h.name).whenComplete((data,error)->{if(error!=null){recordError("identity",h,error);return;}if(data==null)return;mc.execute(()->{try{String oldTown=h.residenceTown;String oldNation=h.nation;HunterState.OnlineStatus oldOnline=h.online;boolean initial=h.residenceCheckedAt==0;h.applyIdentity(data);h.residenceCheckedAt=now;lastEarthmcUpdate=System.currentTimeMillis();if(!initial&&oldOnline!=h.online)onlineChanged(h,px,pz,now);if(!oldTown.equalsIgnoreCase(h.residenceTown)||!oldNation.equalsIgnoreCase(h.nation)){if(!initial)publishRoutine(h,HunterEvent.normal(h.configuredName+":mobility",tr("event.residence_changed",h.name),now,tr("event.from_to",townOrNone(oldTown),townOrNone(h.residenceTown))));lastApproachRefresh=0;}}catch(RuntimeException ex){recordError("identity-apply",h,ex);}});});}
    private void onlineChanged(HunterState h,double px,double pz,long now){HunterEvent event;if(h.online==HunterState.OnlineStatus.OFFLINE){h.offlineSinceMs=now;var o=h.bestObservation();double d=o==null?Double.POSITIVE_INFINITY:Math.hypot(o.x()-px,o.z()-pz);var before=h.threat.level();h.threat=threats.assess(new ThreatEngine.Input(h.visibility==HunterState.Visibility.VISIBLE,d,h.hiddenSinceMs==0?0:now-h.hiddenSinceMs,exposureSession.sessionAlive()?h.approachRoutes:List.of(),true,0),exposureSession.sessionAlive()?exposureSession.exposureModifier(now):0);event=HunterEvent.normal(h.configuredName+":offline",tr("event.offline",h.name),now,tr("event.offline_residual",risk(before),risk(h.threat.level()),config.hunterOfflineResidualMinutes));}else{h.offlineSinceMs=0;lastApproachRefresh=0;event=HunterEvent.normal(h.configuredName+":online",tr("event.online",h.name),now,tr("event.checking_location"));}if(h.source==HunterState.Source.AUTO_HIGH_OUTLAW)notifications.activityOnly(event);else notifications.publish(event);}
    private void recomputeTeleports(Minecraft mc,HunterState h,double px,double pz,String oldTown){
        var townFuture=h.residenceTown.isBlank()?java.util.concurrent.CompletableFuture.<TownFullData>completedFuture(null):earth.fetchTownFull(h.residenceTown);
        var nationFuture=h.nation.isBlank()?java.util.concurrent.CompletableFuture.<NationFullData>completedFuture(null):earth.fetchNationFull(h.nation);
        townFuture.thenCombine(nationFuture,(town,nation)->teleports.fromOfficialDetails(town,nation,px,pz)).whenComplete((options,error)->{if(error!=null){recordError("teleport",h,error);return;}mc.execute(()->{try{h.teleportOptions=options;if(!options.isEmpty()){var tp=options.getFirst();long at=System.currentTimeMillis();Component line=tr("event.possible_tp",tp.destinationName(),(int)Math.round(tp.distanceToLocalPlayer()));HunterEvent event=tp.distanceToLocalPlayer()<=config.hunterTeleportThreatRadius?HunterEvent.warning(h.configuredName+":tp",tr("event.nearby_tp",h.name),at,line):HunterEvent.normal(h.configuredName+":tp",tr("event.tp_changed",h.name),at,line);if(h.source==HunterState.Source.AUTO_HIGH_OUTLAW&&h.threat.level().ordinal()<HunterState.ThreatLevel.HIGH.ordinal())notifications.activityOnly(event);else notifications.publish(event);}}catch(RuntimeException ex){recordError("teleport-apply",h,ex);}});});
    }
    private boolean syncWatchlist(){boolean added=false;Set<String>w=new LinkedHashSet<>();for(String n:config.hunterWatchlist)if(n!=null&&!n.isBlank()&&net.townymap.hunter.config.HunterWatchlist.enabled(config,n))w.add(key(n));states.keySet().retainAll(w);for(String n:config.hunterWatchlist)if(n!=null&&!n.isBlank()&&net.townymap.hunter.config.HunterWatchlist.enabled(config,n)){String k=key(n);if(!states.containsKey(k)){states.put(k,new HunterState(n.trim()));added=true;}}return added;}
    private void updateHunter(HunterState h,PlayerMarker p,double px,double pz,long now){
        HunterState.Visibility old=h.visibility;
        long endedHiddenMs=old==HunterState.Visibility.HIDDEN&&h.hiddenSinceMs>0?now-h.hiddenSinceMs:0;
        if(p!=null){
            TownData t=TownHoverOverlay.townAt(p.x(),p.z(),towns); String claim=t==null?tr("common.wilderness").getString():t.name();
            String nation=t==null?"":net.townymap.TownyMapMod.knownTownNation(t.name()); String oldClaim=h.direct==null?claim:h.direct.claim();
            h.direct=new HunterState.Observation(p.x(),p.z(),now,HunterState.ObservationType.DIRECT_DYNMAP,claim,nation,HunterState.Confidence.HIGH);
            h.lastSeen=h.direct;h.hiddenSinceMs=0;h.teleportFrontActivatedAtMs=0;h.hiddenRouteOpportunities.clear();
            h.visibility=HunterState.Visibility.VISIBLE; h.online=HunterState.OnlineStatus.ONLINE;h.offlineSinceMs=0;
            threatFronts.remove(h.name);
            double currentDistance=Math.hypot(p.x()-px,p.z()-pz);
            if(old==HunterState.Visibility.UNKNOWN&&h.source==HunterState.Source.MANUAL_HUNTER)notifications.publish(HunterEvent.normal(h.configuredName+":tracking",tr("event.visible",h.name),now,tr("event.dynmap_restored")).typed(HunterEvent.Type.STATUS).positioned(p.x(),p.z()));
            else if(old==HunterState.Visibility.HIDDEN) publishRoutine(h,currentDistance<=config.hunterNearbyRadius
                    ? HunterEvent.warning(h.configuredName+":return",tr("event.reappeared_nearby",h.name),now,tr("event.distance_from_you",(int)Math.round(currentDistance)))
                    : HunterEvent.normal(h.configuredName+":return",tr("event.visible",h.name),now,tr("event.dynmap_restored")));
            if(!oldClaim.equalsIgnoreCase(claim)) publishRoutine(h,HunterEvent.normal(h.configuredName+":claim:"+claim,tr("event.location_changed",h.name),now,tr("event.from_to",oldClaim,claim)));
        }else if(h.direct!=null){
            h.visibility=HunterState.Visibility.HIDDEN;
            if(old==HunterState.Visibility.VISIBLE)h.hiddenSinceMs=now;
            rememberHiddenRoutes(h,h.approachRoutes,now,false);
            if(old==HunterState.Visibility.VISIBLE&&h.source==HunterState.Source.MANUAL_HUNTER){double d=Math.hypot(h.direct.x()-px,h.direct.z()-pz);Component line=tr("event.last_seen",(int)Math.round(d),h.direct.claim());notifications.publish(d<=config.hunterNearbyRadius
                    ? HunterEvent.warning(h.configuredName+":lost",tr("event.position_lost_nearby",h.name),now,line)
                    : HunterEvent.normal(h.configuredName+":lost",tr("event.position_lost",h.name),now,line));}
        }
        HunterState.Observation o=h.bestObservation(); double d=o==null?Double.POSITIVE_INFINITY:Math.hypot(o.x()-px,o.z()-pz);
        HunterState.ThreatAssessment oldThreat=h.threat;
        List<net.townymap.hunter.model.ApproachRoute> effectiveRoutes=effectiveRoutes(h,px,pz);
        boolean teleportFrontsActive=!config.hunterTeleportActivationOnExposure||(exposureSession.sessionAlive()&&exposureSession.cumulativeExposureMs(now)>=config.hunterWildernessTargetExposureSeconds*1000L);
        net.townymap.hunter.front.HiddenThreatFrontEngine.Update frontUpdate=threatFronts.update(h,now,px,pz,effectiveRoutes,route==null?null:(double)route.x(),route==null?null:(double)route.z(),route==null?null:route.destination(),teleportFrontsActive);
        publishFrontCrossings(h,frontUpdate.crossings(),now);
        double exposureRisk=exposureSession.sessionAlive()?exposureSession.exposureModifier(now):0;double targeting=exposure.targetingMultiplier(now,config.hunterPlayerHiddenSafetyEnabled,config.hunterPlayerHiddenSafetyDelaySeconds*1000L,config.hunterPlayerHiddenSafetyRampSeconds*1000L,config.hunterPlayerHiddenMaximumSafetyReductionPercent/100.0);double observableExposure=exposureRisk*targeting;
        h.threat=h.visibility==HunterState.Visibility.HIDDEN?threats.assessHidden(frontUpdate.summary(),h.source==HunterState.Source.MANUAL_HUNTER,observableExposure,h.online==HunterState.OnlineStatus.OFFLINE,h.offlineSinceMs==0?Long.MAX_VALUE:now-h.offlineSinceMs):threats.assess(new ThreatEngine.Input(true,d,0,effectiveRoutes,h.online==HunterState.OnlineStatus.OFFLINE,h.offlineSinceMs==0?Long.MAX_VALUE:now-h.offlineSinceMs),observableExposure);
        lastThreatEvaluation=now;
        if(old==HunterState.Visibility.HIDDEN&&h.visibility==HunterState.Visibility.VISIBLE)net.townymap.TownyMapMod.LOGGER.debug("[HunterAlert/Reappearance] hunter={} source={} previousVisible=false visible=true newDistance={} hiddenIntervalEndedMs={} combinedThreat={} -> {} reason={}",h.name,h.source,Math.round(d),endedHiddenMs,oldThreat.level(),h.threat.level(),d<=config.hunterHighRadius?"confirmed_near_reappearance":"confirmed_far_reappearance");
        if(h.threat.level()!=oldThreat.level()){
            net.townymap.TownyMapMod.LOGGER.debug("[HunterAlert] hunter={} source={} outlawCount={} visible={} hiddenMs={} physical={} teleport={} combined={} routes={} best={} arrivalMs={} reason={}",h.name,h.source,candidateOutlawCounts.getOrDefault(key(h.name),-1),h.visibility==HunterState.Visibility.VISIBLE,h.hiddenSinceMs==0?0:now-h.hiddenSinceMs,h.threat.physicalLevel(),h.threat.teleportLevel(),h.threat.level(),effectiveRoutes.size(),h.threat.bestRoute()==null?"none":h.threat.bestRoute().name(),h.threat.plausibleArrivalMs(),h.threat.hiddenPhase());
            if(h.visibility==HunterState.Visibility.HIDDEN)return; // front crossings are the sole hidden-threat alert source
            Component assessment=localizedAssessment(h.threat);Integer outlawCount=candidateOutlawCounts.get(key(h.name));Component source=h.source==HunterState.Source.AUTO_HIGH_OUTLAW?tr("source.auto_high_outlaw"):tr("source.manual");Component[] details=outlawCount==null?new Component[]{source,assessment}:new Component[]{source,assessment,Component.translatable("townymapaddon.hunter.candidate.outlaw_count",outlawCount)};
            HunterEvent event=h.threat.level().ordinal()>=HunterState.ThreatLevel.HIGH.ordinal()&&h.threat.level().ordinal()>oldThreat.level().ordinal()
                    ?HunterEvent.warning(h.configuredName+":risk:"+h.threat.level(),tr("event.risk_became",risk(h.threat.level()),h.name),now,details)
                    :HunterEvent.normal(h.configuredName+":risk:"+h.threat.level(),tr("event.risk_became",risk(h.threat.level()),h.name),now,details);
            if(h.source==HunterState.Source.AUTO_HIGH_OUTLAW&&h.threat.level().ordinal()<HunterState.ThreatLevel.HIGH.ordinal())notifications.activityOnly(event);else notifications.publish(event);
        }
    }
    private static void rememberHiddenRoutes(HunterState h,List<net.townymap.hunter.model.ApproachRoute> routes,long now,boolean closed){for(var route:routes){var old=h.hiddenRouteOpportunities.get(route.key());long from=old==null?now:old.usableFrom();h.hiddenRouteOpportunities.put(route.key(),new HunterState.HiddenRouteOpportunity(route,from,closed?now:0));}while(h.hiddenRouteOpportunities.size()>20)h.hiddenRouteOpportunities.remove(h.hiddenRouteOpportunities.keySet().iterator().next());}
    static List<net.townymap.hunter.model.ApproachRoute> effectiveRoutes(HunterState h,double px,double pz){Map<String,net.townymap.hunter.model.ApproachRoute> routes=new HashMap<>();for(var r:h.approachRoutes)routes.put(r.key(),new net.townymap.hunter.model.ApproachRoute(r.key(),r.name(),r.type(),r.x(),r.z(),Math.hypot(r.x()-px,r.z()-pz),r.recentEntryMatch()));if(h.visibility==HunterState.Visibility.HIDDEN)for(var opportunity:h.hiddenRouteOpportunities.values()){var r=opportunity.route();routes.putIfAbsent(r.key(),new net.townymap.hunter.model.ApproachRoute(r.key(),r.name(),r.type(),r.x(),r.z(),Math.hypot(r.x()-px,r.z()-pz),r.recentEntryMatch()));}return routes.values().stream().sorted(Comparator.comparingDouble(net.townymap.hunter.model.ApproachRoute::distanceToUser).thenComparing(net.townymap.hunter.model.ApproachRoute::key)).toList();}
    private void publishRoutine(HunterState h,HunterEvent event){if(h.source==HunterState.Source.AUTO_HIGH_OUTLAW&&h.threat.level().ordinal()<HunterState.ThreatLevel.HIGH.ordinal())notifications.activityOnly(event);else notifications.publish(event);}
    private void publishFrontCrossings(HunterState h,List<net.townymap.hunter.front.HiddenThreatFrontEngine.Crossing> crossings,long now){for(var crossing:crossings){var origin=crossing.origin();boolean teleport=origin.type()==net.townymap.hunter.front.HiddenThreatOrigin.Type.TOWN_SPAWN||origin.type()==net.townymap.hunter.front.HiddenThreatOrigin.Type.NATION_SPAWN||origin.type()==net.townymap.hunter.front.HiddenThreatOrigin.Type.OTHER_TELEPORT;String front=crossing.type()==net.townymap.hunter.front.HiddenThreatFrontEngine.FrontType.WARNING?"warning":"plausible";Component title=tr("front."+(teleport?"teleport_":"")+front+"_crossed",h.name);Component detail=tr(teleport?"front.teleport_origin_reached":"front.origin_reached",origin.label(),(int)Math.round(crossing.playerDistance()));HunterEvent event=HunterEvent.warning(h.configuredName+":front:"+origin.key()+":"+crossing.type(),title,now,detail,teleport?tr(origin.type()==net.townymap.hunter.front.HiddenThreatOrigin.Type.NATION_SPAWN?"front.nation_spawn":"front.town_spawn"):origin.knownHunter()?tr("source.manual"):tr("source.auto_high_outlaw")).typed(teleport?HunterEvent.Type.TELEPORT:HunterEvent.Type.MOVEMENT).positioned((int)origin.x(),(int)origin.z());notifications.publish(event);}}
    private void safeUpdateHunter(HunterState h,PlayerMarker p,double px,double pz,long now){try{updateHunter(h,p,px,pz,now);}catch(RuntimeException error){recordError("update",h,error);}}
    private void recordError(String phase,HunterState h,Throwable error){trackingErrors++;net.townymap.TownyMapMod.LOGGER.warn("[HunterAlert] hunter update failed phase={} hunter={}; other hunters will continue",phase,h==null?"<none>":h.name,error);}
    private void logHealth(long now){if(now-lastHealthLog<60_000)return;lastHealthLog=now;long onlineAge=age(now,lastDynmapUpdate),outlawAge=age(now,candidates.refreshedAtMs()),buildAge=age(now,lastAutoActorBuild);net.townymap.TownyMapMod.LOGGER.info("[HunterAlert/Health] enabled={} autoMonitoring={} trackerRunning={} cycles={} manualActors={} onlinePlayers={} threshold={} qualifyingHighOutlaws={} autoActors={} combinedActors={} localEntryCandidates={} lastOnlineRefreshAgeMs={} lastOutlawIndexRefreshAgeMs={} lastAutoActorBuildAgeMs={} threatAgeMs={} exposureState={} sessionId={} continuousExposureMs={} cumulativeExposureMs={} exposureModifier={} graceRemainingMs={} errors={}",config.hunterWarningEnabled,config.hunterCandidateWarningsEnabled,lastSuccessfulCycle>0&&now-lastSuccessfulCycle<5_000,successfulCycles,states.size(),lastOnlinePlayerCount,config.hunterCandidateOutlawThreshold,lastQualifyingHighOutlaws,candidateStates.size(),states.size()+candidateStates.size(),Math.min(net.townymap.hunter.teleport.HunterApproachService.LOCAL_ENTRY_CANDIDATE_LIMIT,exposureSession.entries().size()),onlineAge,outlawAge,buildAge,age(now,lastThreatEvaluation),exposureSession.state(),exposureSession.sessionId(),exposureSession.continuousExposureMs(now),exposureSession.cumulativeExposureMs(now),exposureSession.exposureModifier(now),Math.max(0,exposureSession.claimGraceMs()-exposureSession.claimGraceElapsedMs(now)),trackingErrors);for(HunterState h:candidateStates.values()){var best=h.threat.bestRoute();net.townymap.TownyMapMod.LOGGER.debug("[HunterAlert/AutoActor] player={} source={} outlawCount={} online={} dynmapVisible={} hiddenForMs={} accessibleNearbyCandidates={} bestEntry={} bestDistance={} threat={}",h.name,h.source,candidateOutlawCounts.getOrDefault(key(h.name),-1),h.online,h.visibility==HunterState.Visibility.VISIBLE,h.hiddenSinceMs==0?0:now-h.hiddenSinceMs,h.approachRoutes.size(),best==null?"none":best.name(),best==null?-1:Math.round(best.distanceToUser()),h.threat.level());}long staleLimit=Math.max(15*60_000L,config.hunterCandidateRefreshMinutes*3L*60_000L);if(config.hunterCandidateWarningsEnabled&&((outlawAge<0||outlawAge>staleLimit)||(buildAge<0||buildAge>30_000))&&now-lastPipelineWarning>=60_000){lastPipelineWarning=now;net.townymap.TownyMapMod.LOGGER.warn("[HunterAlert/Health] automatic actor discovery is stale: outlawIndexAgeMs={} autoActorBuildAgeMs={} onlineRefreshAgeMs={}",outlawAge,buildAge,onlineAge);}}
    private static long age(long now,long at){return at==0?-1:Math.max(0,now-at);}
    public TrackerHealth health(){long now=System.currentTimeMillis();return new TrackerHealth(config.hunterWarningEnabled,lastSuccessfulCycle>0&&now-lastSuccessfulCycle<5_000,lastSuccessfulCycle,successfulCycles,states.size(),candidateStates.size(),lastDynmapUpdate,lastEarthmcUpdate,lastThreatEvaluation,trackingErrors,exposureSession.active());}
    public record TrackerHealth(boolean enabled,boolean running,long lastSuccessfulCycle,long cycles,int manualHunters,int outlawHunters,long lastDynmapUpdate,long lastEarthmcUpdate,long lastThreatEvaluation,long errors,boolean exposureActive){}
    private int zone(double d){if(d<=config.hunterCriticalRadius)return 4;if(d<=config.hunterHighRadius)return 3;if(d<=config.hunterElevatedRadius)return 2;if(d<=config.hunterNearbyRadius)return 1;return 0;}
    private int threshold(int z){return z==4?config.hunterCriticalRadius:z==3?config.hunterHighRadius:z==2?config.hunterElevatedRadius:config.hunterNearbyRadius;}
    public List<String> exposureHudLines() {
        if(!config.hunterWarningEnabled||!config.hunterShowHud||!config.hunterExposureHud)return List.of();
        long now=System.currentTimeMillis();
        String dynmapColor=exposure.visible()?"§e":"§a";
        return List.of(dynmapColor+tr("hud.dynmap",tr(exposure.visible()?"common.visible":"common.hidden"),format(exposure.stateDurationMs(now))).getString(),
                "§b"+tr("hud.exposure",exposure.exposurePercent()).getString());
    }

    public String wildernessRiskHudLine(){if(!config.hunterWarningEnabled||!config.hunterShowHud||!config.hunterExposureHud||exposureSession.state()==WildernessExposureSession.State.SAFE)return "";long now=System.currentTimeMillis();return switch(exposureSession.state()){case ENTRY_BUFFER->"§6"+tr("hud.exposure_buffer",format(exposureSession.entryBufferElapsedMs(now)),format(exposureSession.entryBufferMs())).getString();case ACTIVE->(exposureSession.exposureModifier(now)>=.5?"§c":"§6")+tr("hud.exposure_active",format(exposureSession.continuousExposureMs(now)),format(exposureSession.cumulativeExposureMs(now))).getString();case CLAIM_GRACE->"§a"+tr("hud.exposure_grace",format(Math.max(0,exposureSession.claimGraceMs()-exposureSession.claimGraceElapsedMs(now))),format(exposureSession.cumulativeExposureMs(now))).getString();default->"";};}
    private void publishExposureTransition(long now){String key=switch(exposureSession.transition()){case BUFFER_STARTED->"exposure.buffer_started";case BUFFER_CANCELLED->"exposure.buffer_cancelled";case SESSION_STARTED->"exposure.session_started";case GRACE_STARTED->"exposure.grace_started";case SESSION_RESUMED->"exposure.session_resumed";case SESSION_ENDED->"exposure.session_ended";default->null;};if(key!=null)notifications.activityOnly(HunterEvent.normal("user:"+key+":"+exposureSession.sessionId(),tr(key),now));}
    private void publishExposureMilestone(long now){if(exposureSession.state()==WildernessExposureSession.State.SAFE){lastExposureBand=0;return;}double modifier=exposureSession.exposureModifier(now);int band=modifier>=1?2:modifier>=.5?1:0;if(band>lastExposureBand)notifications.activityOnly(HunterEvent.normal("user:exposure:band:"+exposureSession.sessionId()+":"+band,tr("exposure.risk_increased",format(exposureSession.cumulativeExposureMs(now))),now));lastExposureBand=band;}

    private static double distanceToNearestClaim(double x,double z,List<TownData> towns){
        double best=Double.POSITIVE_INFINITY;
        for(TownData town:towns){double boxDx=x<town.minX()?town.minX()-x:x>town.maxX()?x-town.maxX():0,boxDz=z<town.minZ()?town.minZ()-z:z>town.maxZ()?z-town.maxZ():0;if(Math.hypot(boxDx,boxDz)>=best)continue;for(int[][] ring:town.polygonRings())for(int i=0;i<ring.length;i++){int[] a=ring[i],b=ring[(i+1)%ring.length];if(a.length<2||b.length<2)continue;best=Math.min(best,pointSegmentDistance(x,z,a[0],a[1],b[0],b[1]));}}
        return Double.isFinite(best)?best:0;
    }
    private static double pointSegmentDistance(double px,double pz,double ax,double az,double bx,double bz){double dx=bx-ax,dz=bz-az,len2=dx*dx+dz*dz;if(len2==0)return Math.hypot(px-ax,pz-az);double t=Math.clamp(((px-ax)*dx+(pz-az)*dz)/len2,0,1);return Math.hypot(px-(ax+t*dx),pz-(az+t*dz));}
    public List<String> hunterHudLines(Minecraft mc){
        if(!config.hunterWarningEnabled||!config.hunterShowHud||mc.player==null)return List.of();
        double s=net.townymap.TownyMapMod.dimensionCoordinateScale(),px=mc.player.getX()*s,pz=mc.player.getZ()*s;long now=System.currentTimeMillis();
        record Row(HunterState h,double d,int priority){} ArrayList<Row>rows=new ArrayList<>();
        if(config.hunterShowNearby)for(HunterState h:states.values()){var o=h.bestObservation();if(o!=null&&(h.online!=HunterState.OnlineStatus.OFFLINE||h.offlineResidualActive(now,config.hunterOfflineResidualMinutes*60_000L))){double d=Math.hypot(o.x()-px,o.z()-pz)/s;if(d<=config.hunterNearbyRadius){int priority=(h.online==HunterState.OnlineStatus.ONLINE?10_000:0)+Math.max(0,5_000-(int)Math.round(d))+h.threat.level().ordinal()*100;rows.add(new Row(h,d,priority));}}}
        rows.sort(Comparator.comparingInt(Row::priority).reversed().thenComparingDouble(Row::d)); ArrayList<String>out=new ArrayList<>();
        if(!rows.isEmpty()){out.add("§c§l"+Component.translatable("townymapaddon.hunter.watch.title").getString());for(int i=0;i<Math.min(config.hunterMaxHudEntries,rows.size());i++){Row r=rows.get(i);var o=r.h.bestObservation();String approx=r.h.visibility==HunterState.Visibility.VISIBLE?"":"~",bearing=config.hunterDirectionEnabled?direction(px,pz,o.x(),o.z()):"";out.add("§f"+Component.translatable("townymapaddon.hunter.hud.tracked",r.h.name,approx+(int)Math.round(r.d),bearing).getString());out.add(r.h.visibility==HunterState.Visibility.VISIBLE?"§7"+Component.translatable("townymapaddon.hunter.hud.visible_claim",o.claim()).getString():"§c"+Component.translatable("townymapaddon.hunter.hud.lost_claim",format(now-o.atMs()),o.claim()).getString());String detail=config.hunterShowRisk&&r.h.threat.level().ordinal()>=HunterState.ThreatLevel.WATCH.ordinal()?Component.translatable("townymapaddon.hunter.hud.risk_score",r.h.threat.level(),r.h.threat.score()).getString():"";var tp=r.h.threat.bestRoute();if(tp!=null)detail+=(detail.isBlank()?"":" · ")+Component.translatable("townymapaddon.hunter.hud.tp_threat",(int)Math.round(tp.distanceToUser()/s),tp.name()).getString();if(!detail.isBlank())out.add("§c"+detail);}if(rows.size()>config.hunterMaxHudEntries)out.add("§7"+Component.translatable("townymapaddon.hunter.hud.more",rows.size()-config.hunterMaxHudEntries).getString());}
        for(HunterState hidden:states.values())if(hidden.visibility==HunterState.Visibility.HIDDEN){var stats=threatFronts.teleportStats(hidden.name);if(stats.latentOrigins()>0)out.add("§7"+tr("hud.teleport_summary",stats.relevantClusters(),stats.latentOrigins()).getString());}
        if(config.hunterCandidateWarningsEnabled){
            List<NearbyCandidate> automatic=nearbyCandidates.values().stream().filter(c->{HunterState actor=candidateStates.get(key(c.name));return actor!=null&&actor.threat.level().ordinal()>=HunterState.ThreatLevel.HIGH.ordinal();}).sorted(Comparator.comparingDouble(c->c.distance)).toList();
            int available=Math.max(0,config.hunterMaxHudEntries-rows.size());
            if(!automatic.isEmpty()&&available>0)out.add("§6§l⚠ "+Component.translatable("townymapaddon.hunter.hud.possible_hunter").getString());
            for(int i=0;i<Math.min(available,automatic.size());i++){NearbyCandidate c=automatic.get(i);String bearing=config.hunterDirectionEnabled?direction(px,pz,c.x,c.z):"";out.add("§f"+Component.translatable("townymapaddon.hunter.hud.name_distance",c.name,(c.visibleNow?"":"~")+(int)Math.round(c.distance),bearing).getString());out.add(c.visibleNow?"§7"+Component.translatable("townymapaddon.hunter.hud.claim_visible",c.claim).getString():"§c"+Component.translatable("townymapaddon.hunter.hud.position_lost",format(now-c.lastSeenAt)).getString());HunterState candidateThreat=candidateStates.get(key(c.name));String riskText=candidateThreat==null?"":" · "+risk(candidateThreat.threat.level()).getString();out.add("§7"+Component.translatable("townymapaddon.hunter.candidate.outlaw_count",c.outlawCount).getString()+riskText);}
        }
        return out;
    }
    public String safeRouteHudLine(Minecraft mc){if(!config.hunterWarningEnabled||!config.hunterShowHud||route==null||mc==null||mc.player==null)return "";double s=net.townymap.TownyMapMod.dimensionCoordinateScale(),px=mc.player.getX()*s,pz=mc.player.getZ()*s;int intersections=threatFronts.routeIntersectionCount(px,pz,route.x(),route.z(),System.currentTimeMillis());return intersections>0?"§c"+tr("hud.route_front_risk",intersections,route.destination()).getString():"§a"+tr("hud.safe_route",config.hunterDirectionEnabled?direction(px,pz,route.x(),route.z()):"",(int)Math.round(route.distance()/s),route.destination()).getString();}
    public List<String> warningHudLines(){if(!config.hunterWarningEnabled||!config.hunterShowHud)return List.of();List<String> lines=notifications.hudLines(System.currentTimeMillis());return lines.isEmpty()?List.of():List.copyOf(lines.subList(0,Math.min(3,lines.size())));}
    public void onSystemMessage(String text){
        if(!config.hunterWarningEnabled||text==null)return; Matcher m=DEATH.matcher(text.replaceAll("§.","").trim()); if(!m.matches())return;
        String victim=m.group(1),killer=m.group(3); long now=System.currentTimeMillis(); HunterState hk=actor(killer),hv=actor(victim);
        if(hk!=null){hk.combatHistory.addFirst(new HunterState.CombatEvent(tr("event.killed",victim).getString(),now,true));boolean inferred=infer(hk,victim,now);if(!inferred)notifications.publish(HunterEvent.normal(hk.configuredName+":kill:"+now,tr("event.hunter_killed",hk.name,victim),now,tr("event.no_victim_position")));}
        if(hv!=null){hv.combatHistory.addFirst(new HunterState.CombatEvent(tr("event.killed_by",killer).getString(),now,false));boolean inferred=infer(hv,killer,now);if(!inferred)notifications.publish(HunterEvent.normal(hv.configuredName+":death:"+now,tr("event.hunter_killed_by",hv.name,killer),now));}
    }
    private boolean infer(HunterState h,String other,long now){
        var sample=positions.latestBefore(other,now);if(sample==null)return false;long age=now-sample.atMs();if(age>30_000)return false;
        HunterState.Confidence c=age<=5000?HunterState.Confidence.HIGH:age<=15000?HunterState.Confidence.MEDIUM:HunterState.Confidence.LOW;TownData t=TownHoverOverlay.townAt(sample.x(),sample.z(),towns);
        h.inferred=new HunterState.Observation(sample.x(),sample.z(),now,HunterState.ObservationType.COMBAT_INFERRED,t==null?tr("common.wilderness").getString():t.name(),"",c);
        Component timing=tr("event.victim_timing",format(age),confidence(c));
        notifications.activityOnly(HunterEvent.normal(h.configuredName+":combat-anchor:"+now,tr("event.hunter_killed",h.name,other),now,tr("event.combat_anchor_created"),timing).typed(HunterEvent.Type.COMBAT).positioned(sample.x(),sample.z()));return true;
    }
    private HunterState actor(String name){HunterState h=states.get(key(name));return h!=null?h:candidateStates.get(key(name));}
    public Collection<HunterState> states(){return List.copyOf(states.values());}
    public List<HunterEvent> activityHistory(){return notifications.history();}
    public HunterCandidateService candidateService(){return candidates;}
    private static final class NearbyCandidate{String name,claim="WILDERNESS";int outlawCount,x,z;double distance;long lastSeenAt;boolean inside,visibleNow;NearbyCandidate(String name,int count){this.name=name;this.outlawCount=count;}}
    /** Visible exact positions only; generic hidden last-known markers remain owned by EarthMC-map-addon. */
    public void renderWorldMap(GuiGraphicsExtractor ctx,double cameraX,double cameraZ,double scale,int sw,int sh){if(!config.hunterWarningEnabled||scale<=0)return;if(config.hunterRadiusOnWorldMap)net.townymap.render.XaeroRadiusOverlayRenderer.worldMap(ctx,radiusOverlays.snapshot(),cameraX,cameraZ,scale,sw,sh);if(config.hunterShowLatentTeleportOriginsWorldMap)for(var latent:threatFronts.latentTeleports(Math.max(20,config.hunterFrontWorldMapLimit*4))){int x=sx(latent.x(),cameraX,scale,sw),y=sy(latent.z(),cameraZ,scale,sh),color=latent.type()==ApproachRoute.Type.NATION?0x886C55FF:0x8843DDEB;if(x>=0&&x<sw&&y>=0&&y<sh)ctx.fill(x-1,y-1,x+2,y+2,color);}for(HunterState h:allThreatStates())if(h.visibility==HunterState.Visibility.VISIBLE&&h.direct!=null)cross(ctx,sx(h.direct.x(),cameraX,scale,sw),sy(h.direct.z(),cameraZ,scale,sh),0xFFFF8C32);if(route!=null){int x=sx(route.x(),cameraX,scale,sw),y=sy(route.z(),cameraZ,scale,sh);Minecraft mc=Minecraft.getInstance();if(mc.player!=null){double ds=net.townymap.TownyMapMod.dimensionCoordinateScale();dottedLine(ctx,sx(mc.player.getX()*ds,cameraX,scale,sw),sy(mc.player.getZ()*ds,cameraZ,scale,sh),x,y,0xFF55DD77);}cross(ctx,x,y,0xFF55DD77);}}
    public java.util.List<net.townymap.integration.XaeroRadiusOverlayProvider.Overlay> radiusOverlaySnapshot(){return radiusOverlays.snapshot();}
    public java.util.List<net.townymap.integration.XaeroRadiusOverlayProvider.Overlay> minimapRadiusOverlaySnapshot(){return minimapRadiusOverlays.snapshot();}
    private static int sx(double x,double camera,double scale,int size){return(int)Math.round((x-camera)*scale+size/2.0);}private static int sy(double z,double camera,double scale,int size){return(int)Math.round((z-camera)*scale+size/2.0);}private static void cross(GuiGraphicsExtractor c,int x,int y,int color){c.fill(x-5,y-1,x+6,y+2,color);c.fill(x-1,y-5,x+2,y+6,color);}private static void dottedLine(GuiGraphicsExtractor c,int x1,int y1,int x2,int y2,int color){int steps=Math.min(300,Math.max(Math.abs(x2-x1),Math.abs(y2-y1)));if(steps<=0)return;for(int i=0;i<=steps;i+=6){int x=x1+(x2-x1)*i/steps,y=y1+(y2-y1)*i/steps;c.fill(x-1,y-1,x+2,y+2,color);}}
    private static Component tr(String suffix,Object...args){return Component.translatable("townymapaddon.hunter."+suffix,args);}
    private static Component townOrNone(String town){return town==null||town.isBlank()?tr("common.no_town"):Component.literal(town);}
    private static Component risk(HunterState.ThreatLevel level){return tr("risk."+level.name().toLowerCase(Locale.ROOT));}
    private static Component confidence(HunterState.Confidence value){return tr("confidence."+value.name().toLowerCase(Locale.ROOT));}
    private static Component localizedAssessment(HunterState.ThreatAssessment a){var route=a.bestRoute();Component components=tr("assessment.components",risk(a.physicalLevel()),risk(a.teleportLevel()));if(route==null)return components;return tr("assessment.route",components,route.name(),(int)Math.round(route.distanceToUser()),tr("phase."+a.hiddenPhase().replace('-','_')));}
    private static String key(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT);}private static String format(long ms){long sec=Math.max(0,ms/1000);return tr(sec<60?"common.duration_seconds":"common.duration_minutes_seconds",sec<60?sec:sec/60,sec%60).getString();}
    public static String direction(double fromX,double fromZ,double toX,double toZ){double deg=(Math.toDegrees(Math.atan2(toX-fromX,fromZ-toZ))+360)%360;String[]d={"n","ne","e","se","s","sw","w","nw"};return tr("direction."+d[(int)Math.round(deg/45)%8]).getString();}
}

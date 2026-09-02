package net.townymap.teleport;

import net.minecraft.client.MinecraftClient;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.api.EarthMcApiClient;
import net.townymap.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cached shared Towny teleport-rule engine. Geography is considered only after eligibility. */
public final class TeleportAccessService {
    /** Lightweight identity check; the expensive access snapshot itself has no time expiry. */
    static final long STATUS_CHECK_MS=60_000L;
    private static final long FAILED_RETRY_MS=15_000L;
    private static final double UNCERTAIN_PENALTY=2_500,JOIN_PENALTY=1_500,BLOCKED_PENALTY=5_000;
    private final EarthMcApiClient api;private final TownyMapConfig config;private final AtomicBoolean loading=new AtomicBoolean(),statusChecking=new AtomicBoolean();
    private final TeleportAccessEvaluator evaluator=new TeleportAccessEvaluator();
    private final net.townymap.hunter.teleport.TeleportCapabilityEngine hunterCapabilities=new net.townymap.hunter.teleport.TeleportCapabilityEngine();
    private volatile Snapshot snapshot=new Snapshot(Map.of(),Map.of(),null,0,null);
    private volatile List<TownData> mapTownCache=List.of(),lastMapTownSource;private volatile long lastAttemptAt,lastStatusCheckAt;private volatile String sessionKey="";
    private volatile long dataRevision;private volatile CachedPlan cachedPlan;
    private volatile double queryX,queryZ;
    public TeleportAccessService(EarthMcApiClient api,TownyMapConfig config){this.api=api;this.config=config;}
    public List<net.townymap.hunter.teleport.TeleportCapabilityEngine.TeleportOption> fromOfficialDetails(TownFullData town,NationFullData nation,double x,double z){return hunterCapabilities.fromOfficialDetails(town,nation,x,z);}

    public void ensure(List<TownData> mapTowns,String player){
        int previousTownCount=mapTownCache.size();boolean cacheChanged=mapTowns!=null&&!mapTowns.isEmpty()&&mapTowns!=lastMapTownSource;if(cacheChanged){lastMapTownSource=mapTowns;mapTownCache=List.copyOf(mapTowns);dataRevision++;cachedPlan=null;}boolean cacheExpanded=mapTownCache.size()>previousTownCount;long now=System.currentTimeMillis();Snapshot old=snapshot;if(old.at>0||old.error!=null&&!cacheExpanded&&now-lastAttemptAt<FAILED_RETRY_MS||!loading.compareAndSet(false,true))return;lastAttemptAt=now;
        CompletableFuture<PlayerFullData> self=api.fetchPlayerFull(player);
        List<String> nearbyTownNames=mapTownCache.stream().sorted(Comparator.comparingDouble(t->Math.hypot(t.centerX()-queryX,t.centerZ()-queryZ))).map(TownData::name).toList();
        CompletableFuture<List<String>> townNames=self.thenApply(p->{LinkedHashSet<String> names=new LinkedHashSet<>();if(p!=null&&p.town()!=null&&!p.town().isBlank())names.add(p.town());names.addAll(nearbyTownNames);return List.copyOf(names);});
        CompletableFuture<Map<String,TownFullData>> towns=townNames.thenCompose(api::fetchTownsFull);
        CompletableFuture<Map<String,NationFullData>> nations=towns.thenCombine(self,(loaded,p)->{LinkedHashSet<String>names=new LinkedHashSet<>();if(p!=null&&p.nation()!=null&&!p.nation().isBlank())names.add(p.nation());loaded.values().stream().map(TownFullData::nation).filter(n->n!=null&&!n.isBlank()).forEach(names::add);return List.copyOf(names);}).thenCompose(api::fetchNationsFull);
        towns.thenCombine(nations,Pair::new).thenCombine(self,(pair,p)->new Snapshot(pair.towns,pair.nations,p,System.currentTimeMillis(),null)).whenComplete((next,error)->{
            MinecraftClient mc=MinecraftClient.getInstance();if(mc==null){loading.set(false);return;}mc.execute(()->{boolean usable=error==null&&usable(next,nearbyTownNames.size());if(usable)snapshot=next;else{String message=error!=null&&error.getMessage()!=null?error.getMessage():"Incomplete EarthMC teleport snapshot";snapshot=new Snapshot(old.towns,old.nations,old.player,old.at,message);TownyMapMod.LOGGER.warn("[HunterAlert/Teleport] Rejected incomplete API snapshot: requestedTowns={} receivedTowns={} nations={} player={}",nearbyTownNames.size(),next==null?0:next.towns.size(),next==null?0:next.nations.size(),next!=null&&next.player!=null);}dataRevision++;cachedPlan=null;loading.set(false);});
        });
    }

    public void refresh(List<TownData> mapTowns,String player){snapshot=new Snapshot(snapshot.towns,snapshot.nations,snapshot.player,0,snapshot.error);ensure(mapTowns,player);}
    public void beginQuery(List<TownData> mapTowns,String player,double x,double z){queryX=x;queryZ=z;cachedPlan=null;ensure(mapTowns,player);}
    /** Called from the client tick: preload on join, then invalidate only when access-relevant player state changes. */
    public void tick(List<TownData> mapTowns,String player,String currentSession){
        if(player==null||player.isBlank())return;
        if(!Objects.equals(sessionKey,currentSession)){sessionKey=currentSession==null?"":currentSession;lastStatusCheckAt=0;}
        ensure(mapTowns,player);
        Snapshot current=snapshot;long now=System.currentTimeMillis();
        if(current.at==0||now-lastStatusCheckAt<STATUS_CHECK_MS||!statusChecking.compareAndSet(false,true))return;
        lastStatusCheckAt=now;
        api.fetchPlayerFull(player).thenCompose(fresh->{if(fresh==null)return CompletableFuture.completedFuture(new StatusProbe(null,null,null));CompletableFuture<TownFullData> town=fresh.town()==null||fresh.town().isBlank()?CompletableFuture.completedFuture(null):api.fetchTownFull(fresh.town());CompletableFuture<NationFullData> nation=fresh.nation()==null||fresh.nation().isBlank()?CompletableFuture.completedFuture(null):api.fetchNationFull(fresh.nation());return town.thenCombine(nation,(t,n)->new StatusProbe(fresh,t,n));}).whenComplete((probe,error)->{MinecraftClient mc=MinecraftClient.getInstance();if(mc==null){statusChecking.set(false);return;}mc.execute(()->{try{if(error!=null||probe==null||probe.player==null){TownyMapMod.LOGGER.debug("[HunterAlert/Teleport] Player access-status check failed; retaining cached snapshot");return;}Snapshot latest=snapshot;TownFullData oldTown=latest.player==null?null:latest.towns.get(key(latest.player.town()));NationFullData oldNation=latest.player==null?null:latest.nations.get(key(latest.player.nation()));boolean changed=latest.player!=null&&(!sameAccessIdentity(latest.player,probe.player)||!sameTownAccessStatus(oldTown,probe.town)||!sameNationAccessStatus(oldNation,probe.nation));if(changed){TownyMapMod.LOGGER.info("[HunterAlert/Teleport] Player town/nation access status changed; refreshing teleport cache");snapshot=new Snapshot(latest.towns,latest.nations,probe.player,0,null);cachedPlan=null;refresh(mapTownCache,player);}}finally{statusChecking.set(false);}});});
    }
    static boolean sameAccessIdentity(PlayerFullData a,PlayerFullData b){return a!=null&&b!=null&&Objects.equals(key(a.town()),key(b.town()))&&Objects.equals(key(a.nation()),key(b.nation()))&&a.isMayor()==b.isMayor()&&a.isKing()==b.isKing()&&a.hasTown()==b.hasTown()&&a.hasNation()==b.hasNation()&&a.joinedTownAtMs()==b.joinedTownAtMs()&&normalized(a.townRanks()).equals(normalized(b.townRanks()))&&normalized(a.nationRanks()).equals(normalized(b.nationRanks()));}
    static boolean sameTownAccessStatus(TownFullData a,TownFullData b){if(a==null||b==null)return a==b;return Objects.equals(key(a.name()),key(b.name()))&&Objects.equals(key(a.nation()),key(b.nation()))&&a.isPublic()==b.isPublic()&&a.isOpen()==b.isOpen()&&a.canOutsidersSpawn()==b.canOutsidersSpawn()&&a.isRuined()==b.isRuined()&&Double.compare(a.balance(),b.balance())==0&&normalized(a.trusted()).equals(normalized(b.trusted()));}
    static boolean sameNationAccessStatus(NationFullData a,NationFullData b){if(a==null||b==null)return a==b;return Objects.equals(key(a.name()),key(b.name()))&&a.isPublic()==b.isPublic()&&a.isOpen()==b.isOpen()&&normalized(a.allies()).equals(normalized(b.allies()))&&normalized(a.enemies()).equals(normalized(b.enemies()));}
    private static Set<String> normalized(List<String> values){if(values==null)return Set.of();TreeSet<String> result=new TreeSet<>();for(String value:values)if(value!=null)result.add(key(value));return Set.copyOf(result);}

    public Plan plan(double targetX,double targetZ){TeleportPlanCacheKey key=TeleportPlanCacheKey.of(targetX,targetZ,dataRevision,config.teleportPrimaryHomeTown);CachedPlan cached=cachedPlan;if(cached!=null&&cached.key.equals(key))return cached.plan;Snapshot s=snapshot;Plan result;if(s.player==null||s.towns.isEmpty()){List<TeleportRoute>fallback=TeleportFallbackRoutes.nearest(mapTownCache,targetX,targetZ);result=new Plan(fallback,List.of(),s.player,loading.get(),s.error);}else{PlayerTeleportContext current=context(s,s.player);List<TeleportRoute> standard=routesForState(s,current,targetX,targetZ,TeleportRoute.Mode.STANDARD,TeleportRoute.MembershipRisk.LOW,List.of(),0);double best=standard.isEmpty()?Double.POSITIVE_INFINITY:standard.getFirst().walkingDistance();List<TeleportRoute> advanced=advanced(s,current,targetX,targetZ,best,risk(current));result=new Plan(standard,advanced,s.player,loading.get(),s.error);}cachedPlan=new CachedPlan(key,result);return result;}

    static List<TeleportRoute> mergeKnownTowns(List<TeleportRoute> authoritative,List<TownData> mapTowns,double x,double z,Map<String,String> reports){Map<String,TeleportRoute> merged=new HashMap<>();if(authoritative!=null)for(TeleportRoute route:authoritative)add(merged,route);for(TeleportRoute route:TeleportFallbackRoutes.nearest(mapTowns,x,z)){String routeKey=route.destination().type()+":"+key(route.destination().name());TeleportDestination.PhysicalAccess access=parseReport(reports==null?null:reports.get(routeKey));TeleportDestination d=route.destination();TeleportDestination labeled=new TeleportDestination(d.type(),d.name(),d.x(),d.y(),d.z(),d.command(),d.eligibility(),access,d.reason());TeleportRoute fallback=new TeleportRoute(route.mode(),route.steps(),labeled,route.walkingDistance(),route.membershipRisk(),route.quality(),route.joinHops(),route.score(),route.saving());merged.putIfAbsent(routeKey,fallback);}return merged.values().stream().sorted(Comparator.comparingDouble(TeleportRoute::walkingDistance)).toList();}
    private static TeleportDestination.PhysicalAccess parseReport(String value){try{return TeleportDestination.PhysicalAccess.valueOf(value==null?"UNKNOWN":value);}catch(IllegalArgumentException e){return TeleportDestination.PhysicalAccess.UNKNOWN;}}
    static String townSetSignature(List<TownData> towns){if(towns==null||towns.isEmpty())return "";return towns.stream().filter(Objects::nonNull).map(TownData::key).filter(s->!s.isBlank()).distinct().sorted().collect(java.util.stream.Collectors.joining("\n"));}

    static boolean usable(Snapshot next,int requestedTowns){return next!=null&&TeleportSnapshotValidation.usable(next.player!=null,next.towns.size(),next.nations.size(),requestedTowns);}

    private List<TeleportRoute> routesForState(Snapshot s,PlayerTeleportContext state,double x,double z,TeleportRoute.Mode mode,TeleportRoute.MembershipRisk risk,List<TeleportRoute.Step> prefix,int hops){
        Map<String,TeleportRoute> out=new HashMap<>();
        for(TownFullData town:s.towns.values()){
            Access access=access(evaluator.town(state,town));debug(state,town,access);if(access.eligibility==TeleportDestination.Eligibility.UNAVAILABLE)continue;
            add(out,route(mode,town(town,access),x,z,risk,hops,prefix));
        }
        for(NationFullData nation:s.nations.values()){if(!hasSpawn(nation))continue;Access access=access(evaluator.nation(state,nation));if(access.eligibility!=TeleportDestination.Eligibility.UNAVAILABLE)add(out,route(mode,nation(nation,access),x,z,risk,hops,prefix));}
        return sort(out.values());
    }

    private List<TeleportRoute> advanced(Snapshot s,PlayerTeleportContext current,double x,double z,double best,TeleportRoute.MembershipRisk risk){
        TownFullData primary=s.towns.get(key(config.teleportPrimaryHomeTown));if(primary==null)return List.of();
        TownFullData targetTown=s.towns.values().stream().min(Comparator.comparingDouble(t->Math.hypot(t.spawnX()-x,t.spawnZ()-z))).orElse(null);if(targetTown==null)return List.of();
        List<TownFullData> joins=new ArrayList<>();if(targetTown.isOpen())joins.add(targetTown);if(!targetTown.nation().isBlank())s.towns.values().stream().filter(t->t.isOpen()&&t.nation().equalsIgnoreCase(targetTown.nation())&&!t.name().equalsIgnoreCase(targetTown.name())).sorted(Comparator.comparingDouble(t->Math.hypot(t.spawnX()-x,t.spawnZ()-z))).forEach(joins::add);if(joins.isEmpty())s.towns.values().stream().filter(TownFullData::isOpen).sorted(Comparator.comparingDouble(t->Math.hypot(t.spawnX()-x,t.spawnZ()-z))).forEach(joins::add);
        Map<String,TeleportRoute> out=new HashMap<>();for(TownFullData join:joins){if(join.name().equalsIgnoreCase(current.town()))continue;NationFullData joinedNation=s.nations.get(key(join.nation()));PlayerTeleportContext simulated=current.simulateJoin(join,joinedNation==null?Set.of():joinedNation.allies(),enemiesFor(s,join.nation()));List<TeleportRoute.Step> prefix=new ArrayList<>();if(!current.town().isBlank())prefix.add(new TeleportRoute.Step(TeleportRoute.StepType.LEAVE_TOWN,current.town(),"/t leave"));prefix.add(new TeleportRoute.Step(TeleportRoute.StepType.JOIN_TOWN,join.name(),"/t join "+join.name()));for(TeleportRoute r:routesForState(s,simulated,x,z,TeleportRoute.Mode.JOIN_ASSISTED,risk,prefix,1))add(out,withSaving(r,best));}
        return sort(out.values());
    }

    private PlayerTeleportContext context(Snapshot s,PlayerFullData p){NationFullData nation=s.nations.get(key(p.nation()));return PlayerTeleportContext.of(p,s.towns.get(key(p.town())),nation==null?Set.of():nation.allies(),enemiesFor(s,p.nation()),s.towns.get(key(config.teleportPrimaryHomeTown)));}
    private static Set<String> enemiesFor(Snapshot s,String nation){Set<String>out=new HashSet<>();NationFullData own=s.nations.get(key(nation));if(own!=null)out.addAll(own.enemies());for(NationFullData other:s.nations.values())if(other.enemies().stream().anyMatch(n->n.equalsIgnoreCase(nation)))out.add(other.name());return Set.copyOf(out);}
    private static Access access(TeleportAccessEvaluator.Result r){return new Access(r.status(),r.reason());}
    private TeleportDestination town(TownFullData t,Access a){return new TeleportDestination(TeleportDestination.Type.TOWN_SPAWN,t.name(),t.spawnX(),t.spawnY(),t.spawnZ(),"/t spawn "+t.name(),a.eligibility,report(TeleportDestination.Type.TOWN_SPAWN,t.name()),a.reason);}
    private TeleportDestination nation(NationFullData n,Access a){return new TeleportDestination(TeleportDestination.Type.NATION_SPAWN,n.name(),n.spawnX(),n.spawnY(),n.spawnZ(),"/n spawn "+n.name(),a.eligibility,report(TeleportDestination.Type.NATION_SPAWN,n.name()),a.reason);}
    public void cycleSpawnReport(TeleportDestination d){String k=reportKey(d.type(),d.name());TeleportDestination.PhysicalAccess next=switch(report(d.type(),d.name())){case UNKNOWN->TeleportDestination.PhysicalAccess.ACCESSIBLE;case ACCESSIBLE->TeleportDestination.PhysicalAccess.OBSTRUCTED;case OBSTRUCTED->TeleportDestination.PhysicalAccess.UNKNOWN;};if(next==TeleportDestination.PhysicalAccess.UNKNOWN)config.teleportSpawnReports.remove(k);else config.teleportSpawnReports.put(k,next.name());dataRevision++;cachedPlan=null;config.save();}
    private TeleportDestination.PhysicalAccess report(TeleportDestination.Type type,String name){try{return TeleportDestination.PhysicalAccess.valueOf(config.teleportSpawnReports.getOrDefault(reportKey(type,name),"UNKNOWN"));}catch(IllegalArgumentException e){return TeleportDestination.PhysicalAccess.UNKNOWN;}}
    private static String reportKey(TeleportDestination.Type type,String name){return type.name()+":"+key(name);}
    private static TeleportRoute route(TeleportRoute.Mode mode,TeleportDestination d,double x,double z,TeleportRoute.MembershipRisk risk,int hops,List<TeleportRoute.Step> prefix){double walk=d.distanceTo(x,z),penalty=hops*JOIN_PENALTY+(d.eligibility()==TeleportDestination.Eligibility.UNCERTAIN?UNCERTAIN_PENALTY:0)+(d.physicalAccess()==TeleportDestination.PhysicalAccess.OBSTRUCTED?BLOCKED_PENALTY:0)+switch(risk){case CRITICAL->100_000;case HIGH->30_000;case MEDIUM->10_000;case UNKNOWN->15_000;default->0;};List<TeleportRoute.Step>steps=new ArrayList<>(prefix);steps.add(new TeleportRoute.Step(d.type()==TeleportDestination.Type.TOWN_SPAWN?TeleportRoute.StepType.TOWN_SPAWN:TeleportRoute.StepType.NATION_SPAWN,d.name(),d.command()));steps.add(new TeleportRoute.Step(TeleportRoute.StepType.WALK,Integer.toString((int)Math.round(walk)),""));return new TeleportRoute(mode,List.copyOf(steps),d,walk,risk,d.eligibility()==TeleportDestination.Eligibility.ACCESSIBLE?TeleportRoute.Quality.GOOD:TeleportRoute.Quality.UNCERTAIN,hops,walk+penalty,0);}
    private static TeleportRoute withSaving(TeleportRoute r,double best){return new TeleportRoute(r.mode(),r.steps(),r.destination(),r.walkingDistance(),r.membershipRisk(),r.quality(),r.joinHops(),r.score(),Double.isFinite(best)?best-r.walkingDistance():Double.NaN);}
    private static boolean hasSpawn(NationFullData n){return n.spawnX()!=0||n.spawnZ()!=0;}private static TeleportRoute.MembershipRisk risk(PlayerTeleportContext p){if(p.mayor()||p.nationLeader())return TeleportRoute.MembershipRisk.CRITICAL;if(p.hasStaffRank())return p.trusted()?TeleportRoute.MembershipRisk.MEDIUM:TeleportRoute.MembershipRisk.HIGH;if(!p.primaryTownOpen())return TeleportRoute.MembershipRisk.HIGH;if(p.trusted())return TeleportRoute.MembershipRisk.LOW;return TeleportRoute.MembershipRisk.MEDIUM;}
    private static void add(Map<String,TeleportRoute>m,TeleportRoute r){String k=r.destination().type()+":"+key(r.destination().name());m.merge(k,r,(a,b)->a.score()<=b.score()?a:b);}private static List<TeleportRoute>sort(Collection<TeleportRoute>c){return TeleportRouteRanking.rank(c);}private static String key(String s){return s==null?"":s.toLowerCase(Locale.ROOT);}
    private static void debug(PlayerTeleportContext state,TownFullData town,Access access){TownyMapMod.LOGGER.debug("[HunterAlert/Teleport] Town={} playerTown={} playerNation={} targetNation={} public={} outsiderSpawn={} enemy={} sameNation={} balance={} result={} reason={}",town.name(),state.town(),state.nation(),town.nation(),town.isPublic(),town.canOutsidersSpawn(),state.enemy(town.nation()),state.sameNation(town.nation()),town.balance(),access.eligibility,access.reason);}
    public boolean loading(){return loading.get();}public record Plan(List<TeleportRoute>standard,List<TeleportRoute>advanced,PlayerFullData player,boolean loading,String error){}private record CachedPlan(TeleportPlanCacheKey key,Plan plan){}private record Access(TeleportDestination.Eligibility eligibility,TeleportDestination.Reason reason){}private record Pair(Map<String,TownFullData>towns,Map<String,NationFullData>nations){}private record StatusProbe(PlayerFullData player,TownFullData town,NationFullData nation){}private record Snapshot(Map<String,TownFullData>towns,Map<String,NationFullData>nations,PlayerFullData player,long at,String error){}
}

package net.townymap.teleport;

import net.minecraft.client.Minecraft;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.api.EarthMcApiClient;
import net.townymap.model.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cached shared Towny teleport-rule engine. Geography is considered only after eligibility. */
public final class TeleportAccessService {
    private static final long TTL_MS=10*60_000L;
    private static final double UNCERTAIN_PENALTY=2_500,JOIN_PENALTY=1_500,BLOCKED_PENALTY=5_000;
    private final EarthMcApiClient api;private final TownyMapConfig config;private final AtomicBoolean loading=new AtomicBoolean();
    private final net.townymap.hunter.teleport.TeleportCapabilityEngine hunterCapabilities=new net.townymap.hunter.teleport.TeleportCapabilityEngine();
    private volatile Snapshot snapshot=new Snapshot(Map.of(),Map.of(),null,0,null);
    public TeleportAccessService(EarthMcApiClient api,TownyMapConfig config){this.api=api;this.config=config;}
    public List<net.townymap.hunter.teleport.TeleportCapabilityEngine.TeleportOption> fromOfficialDetails(TownFullData town,NationFullData nation,double x,double z){return hunterCapabilities.fromOfficialDetails(town,nation,x,z);}

    public void ensure(List<TownData> mapTowns,String player){
        Snapshot old=snapshot;if(old.at>0&&System.currentTimeMillis()-old.at<TTL_MS||!loading.compareAndSet(false,true))return;
        List<String> townNames=mapTowns.stream().map(TownData::name).toList();
        CompletableFuture<Map<String,TownFullData>> towns=api.fetchTownsFull(townNames);
        CompletableFuture<Map<String,NationFullData>> nations=api.fetchNationIndex().thenCompose(index->api.fetchNationsFull(index.stream().map(EarthMcNationData::name).toList()));
        CompletableFuture<PlayerFullData> self=api.fetchPlayerFull(player);
        towns.thenCombine(nations,Pair::new).thenCombine(self,(pair,p)->new Snapshot(pair.towns,pair.nations,p,System.currentTimeMillis(),null)).whenComplete((next,error)->{
            Minecraft mc=Minecraft.getInstance();if(mc==null){loading.set(false);return;}mc.execute(()->{snapshot=error==null&&next!=null?next:new Snapshot(old.towns,old.nations,old.player,old.at,error==null?"Unknown error":error.getMessage());loading.set(false);});
        });
    }

    public Plan plan(double targetX,double targetZ){Snapshot s=snapshot;if(s.player==null)return new Plan(List.of(),List.of(),null,loading.get(),s.error);PlayerTownyState current=state(s,s.player);List<TeleportRoute> standard=routesForState(s,current,targetX,targetZ,TeleportRoute.Mode.STANDARD,TeleportRoute.MembershipRisk.LOW,List.of(),0);double best=standard.isEmpty()?Double.POSITIVE_INFINITY:standard.getFirst().walkingDistance();List<TeleportRoute> advanced=advanced(s,current,targetX,targetZ,best,risk(s.player));return new Plan(standard,advanced,s.player,loading.get(),s.error);}

    private List<TeleportRoute> routesForState(Snapshot s,PlayerTownyState state,double x,double z,TeleportRoute.Mode mode,TeleportRoute.MembershipRisk risk,List<TeleportRoute.Step> prefix,int hops){
        Map<String,TeleportRoute> out=new HashMap<>();
        for(TownFullData town:s.towns.values()){
            Access access=townAccess(state,town);debug(state,town,access);if(access.eligibility==TeleportDestination.Eligibility.UNAVAILABLE)continue;
            add(out,route(mode,town(town,access),x,z,risk,hops,prefix));
        }
        NationFullData own=s.nations.get(key(state.nation()));if(own!=null&&hasSpawn(own)&&!state.enemy(own.name()))add(out,route(mode,nation(own,new Access(TeleportDestination.Eligibility.ACCESSIBLE,TeleportDestination.Reason.OWN_NATION)),x,z,risk,hops,prefix));
        return sort(out.values());
    }

    private List<TeleportRoute> advanced(Snapshot s,PlayerTownyState current,double x,double z,double best,TeleportRoute.MembershipRisk risk){
        TownFullData targetTown=s.towns.values().stream().min(Comparator.comparingDouble(t->Math.hypot(t.spawnX()-x,t.spawnZ()-z))).orElse(null);if(targetTown==null)return List.of();
        List<TownFullData> joins=new ArrayList<>();if(targetTown.isOpen())joins.add(targetTown);if(!targetTown.nation().isBlank())s.towns.values().stream().filter(t->t.isOpen()&&t.nation().equalsIgnoreCase(targetTown.nation())&&!t.name().equalsIgnoreCase(targetTown.name())).sorted(Comparator.comparingDouble(t->Math.hypot(t.spawnX()-x,t.spawnZ()-z))).limit(12).forEach(joins::add);if(joins.isEmpty())s.towns.values().stream().filter(TownFullData::isOpen).sorted(Comparator.comparingDouble(t->Math.hypot(t.spawnX()-x,t.spawnZ()-z))).limit(12).forEach(joins::add);
        Map<String,TeleportRoute> out=new HashMap<>();for(TownFullData join:joins){if(join.name().equalsIgnoreCase(current.town()))continue;NationFullData joinedNation=s.nations.get(key(join.nation()));PlayerTownyState simulated=current.simulateJoin(join,joinedNation==null?Set.of():Set.copyOf(joinedNation.enemies()));List<TeleportRoute.Step> prefix=new ArrayList<>();if(!current.town().isBlank())prefix.add(new TeleportRoute.Step(TeleportRoute.StepType.LEAVE_TOWN,current.town(),"/t leave"));prefix.add(new TeleportRoute.Step(TeleportRoute.StepType.JOIN_TOWN,join.name(),"/t join "+join.name()));for(TeleportRoute r:routesForState(s,simulated,x,z,TeleportRoute.Mode.JOIN_ASSISTED,risk,prefix,1))add(out,withSaving(r,best));}
        return sort(out.values());
    }

    private Access townAccess(PlayerTownyState state,TownFullData town){
        if(state.enemy(town.nation()))return new Access(TeleportDestination.Eligibility.UNAVAILABLE,TeleportDestination.Reason.ENEMY_NATION);
        if(town.name().equalsIgnoreCase(state.town()))return new Access(TeleportDestination.Eligibility.ACCESSIBLE,TeleportDestination.Reason.OWN_TOWN);
        if(!state.nation().isBlank()&&town.nation().equalsIgnoreCase(state.nation()))return new Access(TeleportDestination.Eligibility.ACCESSIBLE,TeleportDestination.Reason.SAME_NATION_ACCESS);
        if(town.canOutsidersSpawn()||town.isPublic())return new Access(TeleportDestination.Eligibility.ACCESSIBLE,TeleportDestination.Reason.OUTSIDER_SPAWN_ENABLED);
        return new Access(TeleportDestination.Eligibility.UNAVAILABLE,TeleportDestination.Reason.OUTSIDER_SPAWN_DISABLED);
    }
    private PlayerTownyState state(Snapshot s,PlayerFullData p){NationFullData nation=s.nations.get(key(p.nation()));return PlayerTownyState.current(p,nation==null?Set.of():Set.copyOf(nation.enemies()));}
    private TeleportDestination town(TownFullData t,Access a){return new TeleportDestination(TeleportDestination.Type.TOWN_SPAWN,t.name(),t.spawnX(),t.spawnY(),t.spawnZ(),"/t spawn "+t.name(),a.eligibility,report(TeleportDestination.Type.TOWN_SPAWN,t.name()),a.reason);}
    private TeleportDestination nation(NationFullData n,Access a){return new TeleportDestination(TeleportDestination.Type.NATION_SPAWN,n.name(),n.spawnX(),n.spawnY(),n.spawnZ(),"/n spawn "+n.name(),a.eligibility,report(TeleportDestination.Type.NATION_SPAWN,n.name()),a.reason);}
    public void cycleSpawnReport(TeleportDestination d){String k=reportKey(d.type(),d.name());TeleportDestination.PhysicalAccess next=switch(report(d.type(),d.name())){case UNKNOWN->TeleportDestination.PhysicalAccess.ACCESSIBLE;case ACCESSIBLE->TeleportDestination.PhysicalAccess.OBSTRUCTED;case OBSTRUCTED->TeleportDestination.PhysicalAccess.UNCERTAIN;case UNCERTAIN->TeleportDestination.PhysicalAccess.UNKNOWN;};if(next==TeleportDestination.PhysicalAccess.UNKNOWN)config.teleportSpawnReports.remove(k);else config.teleportSpawnReports.put(k,next.name());config.save();}
    private TeleportDestination.PhysicalAccess report(TeleportDestination.Type type,String name){try{return TeleportDestination.PhysicalAccess.valueOf(config.teleportSpawnReports.getOrDefault(reportKey(type,name),"UNKNOWN"));}catch(IllegalArgumentException e){return TeleportDestination.PhysicalAccess.UNKNOWN;}}
    private static String reportKey(TeleportDestination.Type type,String name){return type.name()+":"+key(name);}
    private static TeleportRoute route(TeleportRoute.Mode mode,TeleportDestination d,double x,double z,TeleportRoute.MembershipRisk risk,int hops,List<TeleportRoute.Step> prefix){double walk=d.distanceTo(x,z),penalty=hops*JOIN_PENALTY+(d.eligibility()==TeleportDestination.Eligibility.UNCERTAIN?UNCERTAIN_PENALTY:0)+(d.physicalAccess()==TeleportDestination.PhysicalAccess.OBSTRUCTED?BLOCKED_PENALTY:0)+switch(risk){case CRITICAL->100_000;case HIGH->30_000;case MEDIUM->10_000;case UNKNOWN->15_000;default->0;};List<TeleportRoute.Step>steps=new ArrayList<>(prefix);steps.add(new TeleportRoute.Step(d.type()==TeleportDestination.Type.TOWN_SPAWN?TeleportRoute.StepType.TOWN_SPAWN:TeleportRoute.StepType.NATION_SPAWN,d.name(),d.command()));steps.add(new TeleportRoute.Step(TeleportRoute.StepType.WALK,Integer.toString((int)Math.round(walk)),""));return new TeleportRoute(mode,List.copyOf(steps),d,walk,risk,d.eligibility()==TeleportDestination.Eligibility.ACCESSIBLE?TeleportRoute.Quality.GOOD:TeleportRoute.Quality.UNCERTAIN,hops,walk+penalty,0);}
    private static TeleportRoute withSaving(TeleportRoute r,double best){return new TeleportRoute(r.mode(),r.steps(),r.destination(),r.walkingDistance(),r.membershipRisk(),r.quality(),r.joinHops(),r.score(),Double.isFinite(best)?best-r.walkingDistance():0);}
    private static boolean hasSpawn(NationFullData n){return n.spawnX()!=0||n.spawnZ()!=0;}private static TeleportRoute.MembershipRisk risk(PlayerFullData p){if(p.isMayor())return TeleportRoute.MembershipRisk.CRITICAL;if(p.isKing()||!p.townRanks().isEmpty()||!p.nationRanks().isEmpty())return TeleportRoute.MembershipRisk.HIGH;if(p.hasTown())return TeleportRoute.MembershipRisk.MEDIUM;return TeleportRoute.MembershipRisk.LOW;}
    private static void add(Map<String,TeleportRoute>m,TeleportRoute r){String k=r.destination().type()+":"+key(r.destination().name());m.merge(k,r,(a,b)->a.score()<=b.score()?a:b);}private static List<TeleportRoute>sort(Collection<TeleportRoute>c){return c.stream().sorted(Comparator.comparingDouble(TeleportRoute::score)).toList();}private static String key(String s){return s==null?"":s.toLowerCase(Locale.ROOT);}
    private static void debug(PlayerTownyState state,TownFullData town,Access access){TownyMapMod.LOGGER.debug("Teleport eligibility: Town={} PlayerTown={} PlayerNation={} TargetNation={} OutsiderSpawn={} Enemy={} SameNation={} Result={} Reason={}",town.name(),state.town(),state.nation(),town.nation(),town.canOutsidersSpawn()||town.isPublic(),state.enemy(town.nation()),!state.nation().isBlank()&&state.nation().equalsIgnoreCase(town.nation()),access.eligibility,access.reason);}
    public boolean loading(){return loading.get();}public record Plan(List<TeleportRoute>standard,List<TeleportRoute>advanced,PlayerFullData player,boolean loading,String error){}private record Access(TeleportDestination.Eligibility eligibility,TeleportDestination.Reason reason){}private record Pair(Map<String,TownFullData>towns,Map<String,NationFullData>nations){}private record Snapshot(Map<String,TownFullData>towns,Map<String,NationFullData>nations,PlayerFullData player,long at,String error){}
}

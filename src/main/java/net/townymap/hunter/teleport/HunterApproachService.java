package net.townymap.hunter.teleport;

import net.townymap.api.EarthMcApiClient;
import net.townymap.hunter.model.ApproachRoute;
import net.townymap.hunter.tracking.WildernessExposureSession;
import net.townymap.model.*;
import net.townymap.teleport.*;
import java.util.*;
import java.util.concurrent.*;

/** Builds an immutable hunter-specific reverse view of spawn access near the exposed user. */
public final class HunterApproachService {
    public static final int LOCAL_ENTRY_CANDIDATE_LIMIT=10;
    private static final long NETWORK_CACHE_MS=10*60_000L;
    private final EarthMcApiClient api; private final TeleportAccessEvaluator evaluator=new TeleportAccessEvaluator();
    private volatile NetworkDataset network=new NetworkDataset(Map.of(),Map.of(),0,0);private CompletableFuture<NetworkDataset> networkLoading;
    public HunterApproachService(EarthMcApiClient api){this.api=api;}
    public CompletableFuture<Map<String,List<ApproachRoute>>> refresh(List<WildernessExposureSession.Entry> entries,
                                                                      Collection<String> hunters,double ux,double uz,
                                                                      WildernessExposureSession.RecentEntry recent){
        List<String> townNames=entries.stream().map(WildernessExposureSession.Entry::name).distinct().toList();
        CompletableFuture<Map<String,TownFullData>> towns=api.fetchTownsFull(townNames).thenApply(loaded->{if(!townNames.isEmpty()&&(loaded==null||loaded.size()<townNames.size()))throw new IllegalStateException("Incomplete town access snapshot: "+(loaded==null?0:loaded.size())+"/"+townNames.size());return loaded;});
        Map<String,CompletableFuture<PlayerFullData>> playerFutures=new LinkedHashMap<>();
        for(String h:hunters)playerFutures.put(key(h),isolate(api.fetchPlayerFull(h)));
        CompletableFuture<Void> playersDone=CompletableFuture.allOf(playerFutures.values().toArray(CompletableFuture[]::new));
        return towns.thenCombine(playersDone,(ts,ignored)->new Seed(ts,players(playerFutures)))
                .thenCompose(seed->{Set<String> nations=new LinkedHashSet<>();seed.towns.values().forEach(t->{if(t.nation()!=null&&!t.nation().isBlank())nations.add(t.nation());});seed.players.values().forEach(p->{if(p!=null&&p.nation()!=null&&!p.nation().isBlank())nations.add(p.nation());});return api.fetchNationsFull(List.copyOf(nations)).thenApply(ns->build(seed,ns,ux,uz,recent));});
    }
    static <T> CompletableFuture<T> isolate(CompletableFuture<T> future){return future.exceptionally(error->null);}
    /** Full eligible teleport graph. The expensive EarthMC snapshot is cached and never rebuilt on a render/tick path. */
    public CompletableFuture<Map<String,List<ApproachRoute>>> refreshNetwork(List<TownData> mapTowns,Collection<String> hunters,double ux,double uz,WildernessExposureSession.RecentEntry recent){return networkDataset(mapTowns).thenCompose(data->{Map<String,CompletableFuture<PlayerFullData>> fs=new LinkedHashMap<>();for(String hunter:hunters)fs.put(key(hunter),isolate(api.fetchPlayerFull(hunter)));return CompletableFuture.allOf(fs.values().toArray(CompletableFuture[]::new)).thenApply(ignored->build(new Seed(data.towns,players(fs)),data.nations,ux,uz,recent,false));});}
    private synchronized CompletableFuture<NetworkDataset> networkDataset(List<TownData> mapTowns){long now=System.currentTimeMillis();List<String> names=mapTowns==null?List.of():mapTowns.stream().map(TownData::name).filter(Objects::nonNull).distinct().toList();long signature=signature(names);if(network.signature==signature&&network.at>0&&now-network.at<NETWORK_CACHE_MS)return CompletableFuture.completedFuture(network);if(networkLoading!=null&&!networkLoading.isDone())return networkLoading;NetworkDataset stale=network.signature==signature&&!network.towns.isEmpty()?network:null;networkLoading=api.fetchTownsFull(names).thenCompose(towns->{if(!names.isEmpty()&&(towns==null||towns.size()<Math.max(1,(int)(names.size()*.9))))throw new CompletionException(new IllegalStateException("Incomplete teleport network town snapshot"));Set<String> nationNames=new LinkedHashSet<>();towns.values().stream().map(TownFullData::nation).filter(n->n!=null&&!n.isBlank()).forEach(nationNames::add);return api.fetchNationsFull(List.copyOf(nationNames)).thenApply(nations->new NetworkDataset(towns,nations,System.currentTimeMillis(),signature));}).exceptionally(error->{if(stale!=null)return stale;throw new CompletionException(error);}).whenComplete((loaded,error)->{synchronized(this){if(error==null&&loaded!=null)network=loaded;networkLoading=null;}});return networkLoading;}
    private Map<String,List<ApproachRoute>> build(Seed seed,Map<String,NationFullData> nations,double ux,double uz,WildernessExposureSession.RecentEntry recent){return build(seed,nations,ux,uz,recent,true);}
    private Map<String,List<ApproachRoute>> build(Seed seed,Map<String,NationFullData> nations,double ux,double uz,WildernessExposureSession.RecentEntry recent,boolean localOnly){
        List<LocalEntry> local=localOnly?localEntries(seed.towns,nations,ux,uz):allEntries(seed.towns,nations,ux,uz);
        Map<String,List<ApproachRoute>> out=new HashMap<>();
        for(var pe:seed.players.entrySet()){
            PlayerFullData p=pe.getValue();if(p==null)continue;
            NationFullData own=nations.get(key(p.nation()));
            PlayerTeleportContext ctx=PlayerTeleportContext.of(p,null,own==null?List.of():own.allies(),own==null?List.of():own.enemies(),null);
            ArrayList<ApproachRoute> routes=new ArrayList<>();
            for(LocalEntry e:local){if(e.town!=null&&evaluator.town(ctx,e.town).status()==TeleportDestination.Eligibility.ACCESSIBLE)routes.add(route(e,ApproachRoute.Type.TOWN,recent));if(e.nation!=null&&evaluator.nation(ctx,e.nation).status()==TeleportDestination.Eligibility.ACCESSIBLE)routes.add(route(e,ApproachRoute.Type.NATION,recent));}
            routes.sort(Comparator.comparingDouble(ApproachRoute::distanceToUser));out.put(pe.getKey(),List.copyOf(routes));
        }
        return Map.copyOf(out);
    }
    private static List<LocalEntry> localEntries(Map<String,TownFullData> towns,Map<String,NationFullData> nations,double ux,double uz){Map<String,LocalEntry> physical=new LinkedHashMap<>();for(TownFullData t:towns.values()){double d=Math.hypot(t.spawnX()-ux,t.spawnZ()-uz);if(d>WildernessExposureSession.DEFAULT_RADIUS)continue;String k=t.spawnX()+":"+t.spawnZ();physical.merge(k,new LocalEntry(t.spawnX(),t.spawnZ(),d,t,null),(a,b)->new LocalEntry(a.x,a.z,a.distance,a.town!=null?a.town:b.town,a.nation!=null?a.nation:b.nation));}for(NationFullData n:nations.values()){double d=Math.hypot(n.spawnX()-ux,n.spawnZ()-uz);if(d>WildernessExposureSession.DEFAULT_RADIUS)continue;String k=n.spawnX()+":"+n.spawnZ();physical.merge(k,new LocalEntry(n.spawnX(),n.spawnZ(),d,null,n),(a,b)->new LocalEntry(a.x,a.z,a.distance,a.town!=null?a.town:b.town,a.nation!=null?a.nation:b.nation));}return physical.values().stream().sorted(Comparator.comparingDouble(LocalEntry::distance).thenComparingInt(LocalEntry::x).thenComparingInt(LocalEntry::z)).limit(LOCAL_ENTRY_CANDIDATE_LIMIT).toList();}
    private static List<LocalEntry> allEntries(Map<String,TownFullData> towns,Map<String,NationFullData> nations,double ux,double uz){ArrayList<LocalEntry> all=new ArrayList<>(towns.size()+nations.size());for(TownFullData t:towns.values())all.add(new LocalEntry(t.spawnX(),t.spawnZ(),Math.hypot(t.spawnX()-ux,t.spawnZ()-uz),t,null));for(NationFullData n:nations.values())if(n.spawnX()!=0||n.spawnZ()!=0)all.add(new LocalEntry(n.spawnX(),n.spawnZ(),Math.hypot(n.spawnX()-ux,n.spawnZ()-uz),null,n));return List.copyOf(all);}
    private static ApproachRoute route(LocalEntry e,ApproachRoute.Type type,WildernessExposureSession.RecentEntry recent){String name=type==ApproachRoute.Type.TOWN?e.town.name():e.nation.name();return new ApproachRoute(type.name().toLowerCase(Locale.ROOT)+":"+key(name),name,type,e.x,e.z,e.distance,recent!=null&&recent.type()==(type==ApproachRoute.Type.TOWN?WildernessExposureSession.Entry.Type.TOWN:WildernessExposureSession.Entry.Type.NATION)&&recent.matches(name));}
    private static Map<String,PlayerFullData> players(Map<String,CompletableFuture<PlayerFullData>> fs){Map<String,PlayerFullData> out=new HashMap<>();fs.forEach((k,f)->{try{out.put(k,f.join());}catch(RuntimeException ignored){out.put(k,null);}});return out;}
    private static String key(String s){return s==null?"":s.trim().toLowerCase(Locale.ROOT);}
    private static long signature(List<String> values){long h=1125899906842597L;for(String value:values)h=31*h+key(value).hashCode();return h;}
    private record Seed(Map<String,TownFullData> towns,Map<String,PlayerFullData> players){}
    private record NetworkDataset(Map<String,TownFullData>towns,Map<String,NationFullData>nations,long at,long signature){}
    private record LocalEntry(int x,int z,double distance,TownFullData town,NationFullData nation){}
}

package net.townymap.hunter.discovery;

import net.minecraft.client.MinecraftClient;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.api.EarthMcApiClient;
import net.townymap.model.TownData;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Async, atomically published reverse outlaw index. Last successful data survives failures. */
public final class HunterCandidateService {
    private final TownyMapConfig config; private final EarthMcApiClient api; private final AtomicBoolean refreshing=new AtomicBoolean();
    private volatile EarthMcApiClient.OutlawIndexSnapshot snapshot=new EarthMcApiClient.OutlawIndexSnapshot(Map.of(),Map.of(),0,0);
    private volatile boolean failed;
    public HunterCandidateService(TownyMapConfig config,EarthMcApiClient api){this.config=config;this.api=api;}
    public void tick(List<TownData> towns){long interval=config.hunterCandidateRefreshMinutes*60_000L;if(snapshot.refreshedAtMs()==0||System.currentTimeMillis()-snapshot.refreshedAtMs()>=interval)refresh(towns);}
    public void refresh(List<TownData> towns){if(towns==null||towns.isEmpty()||!refreshing.compareAndSet(false,true))return;List<String>names=towns.stream().map(TownData::name).toList();api.fetchOutlawTownIndex(names).whenComplete((next,error)->{MinecraftClient mc=MinecraftClient.getInstance();if(mc==null){refreshing.set(false);return;}mc.execute(()->{try{if(error==null&&next!=null&&next.townsScanned()>0){snapshot=next;failed=false;}else{failed=true;TownyMapMod.LOGGER.warn("[TownyMap] Hunter candidate outlaw-index refresh failed; retaining cached results");}}finally{refreshing.set(false);}});});}
    public List<Candidate> candidates(String filter){String needle=filter==null?"":filter.trim().toLowerCase(Locale.ROOT);ArrayList<Candidate>out=new ArrayList<>();snapshot.townsByPlayer().forEach((key,towns)->{if(towns.size()>config.hunterCandidateOutlawThreshold&&(needle.isEmpty()||key.contains(needle)))out.add(new Candidate(snapshot.displayNames().getOrDefault(key,key),towns.size(),towns));});out.sort(Comparator.comparingInt(Candidate::outlawTownCount).reversed().thenComparing(Candidate::name,String.CASE_INSENSITIVE_ORDER));return List.copyOf(out);}
    public Candidate lookup(String player){if(player==null)return null;String key=player.trim().toLowerCase(Locale.ROOT);Set<String>towns=snapshot.townsByPlayer().get(key);return towns==null?new Candidate(player.trim(),0,Set.of()):new Candidate(snapshot.displayNames().getOrDefault(key,player.trim()),towns.size(),towns);}
    public boolean refreshing(){return refreshing.get();}public boolean failed(){return failed;}public long refreshedAtMs(){return snapshot.refreshedAtMs();}public int townsScanned(){return snapshot.townsScanned();}
    public record Candidate(String name,int outlawTownCount,Set<String>towns){}
}

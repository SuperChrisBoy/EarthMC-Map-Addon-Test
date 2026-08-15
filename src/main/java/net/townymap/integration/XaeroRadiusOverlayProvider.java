package net.townymap.integration;

import net.townymap.hunter.front.HiddenThreatFrontEngine;
import net.townymap.hunter.front.HiddenThreatOrigin;
import java.util.*;

/** Read-only bridge from authoritative HunterAlert front geometry to Xaero render snapshots. */
public final class XaeroRadiusOverlayProvider {
    private volatile List<Overlay> snapshot=List.of();private Map<String,Overlay> previous=Map.of();
    public void publish(List<HiddenThreatFrontEngine.Front> fronts,long now,int limit){ArrayList<Overlay> next=new ArrayList<>();for(var front:fronts.stream().sorted(Comparator.comparingDouble(HiddenThreatFrontEngine.Front::relevance).reversed()).limit(Math.max(0,limit)).toList()){String key=front.origin().key();Overlay old=previous.get(key);double fromP=old==null?front.plausibleRadius():old.targetPlausible,fromW=old==null?front.warningRadius():old.targetWarning;next.add(new Overlay(key,front.origin().hunterName(),front.origin().type(),front.origin().label(),front.origin().x(),front.origin().z(),fromP,front.plausibleRadius(),fromW,front.warningRadius(),now,front.playerInsideWarning(),front.playerInsidePlausible(),front.relevance()));}snapshot=List.copyOf(next);Map<String,Overlay> map=new HashMap<>();next.forEach(o->map.put(o.key,o));previous=Map.copyOf(map);}
    public void clear(){previous=Map.of();snapshot=List.of();}public List<Overlay> snapshot(){return snapshot;}
    public record Overlay(String key,String hunterName,HiddenThreatOrigin.Type type,String label,double x,double z,double fromPlausible,double targetPlausible,double fromWarning,double targetWarning,long updatedAt,boolean playerInsideWarning,boolean playerInsidePlausible,double relevance){public double plausible(long now,boolean interpolate){return interpolate?lerp(fromPlausible,targetPlausible,now-updatedAt):targetPlausible;}public double warning(long now,boolean interpolate){return interpolate?lerp(fromWarning,targetWarning,now-updatedAt):targetWarning;}private static double lerp(double a,double b,long elapsed){double t=Math.clamp(elapsed/250.0,0,1);return a+(b-a)*t;}}
}

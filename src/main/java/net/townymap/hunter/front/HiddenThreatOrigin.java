package net.townymap.hunter.front;

/** One authoritative expanding geometry origin for a hidden threat actor. */
public record HiddenThreatOrigin(String hunterKey,String hunterName,boolean knownHunter,Type type,String id,String label,double x,double z,long createdAt,long travelStartsAt,double initialRadius,boolean recentEntry){
    public enum Type{LAST_KNOWN_POSITION,KILL_EVENT_ORIGIN,TOWN_SPAWN,NATION_SPAWN,OTHER_TELEPORT}
    public String key(){return hunterKey+":"+type+":"+id;}
}

package net.townymap.teleport;

public record TeleportDestination(Type type,String name,int x,int y,int z,String command,
                                  Eligibility eligibility,PhysicalAccess physicalAccess,Reason reason){
    public enum Type{TOWN_SPAWN,NATION_SPAWN}
    public enum Eligibility{ACCESSIBLE,UNCERTAIN,UNAVAILABLE}
    public enum PhysicalAccess{UNKNOWN,ACCESSIBLE,OBSTRUCTED,UNCERTAIN}
    public enum Reason{OWN_TOWN,OWN_NATION,OUTSIDER_SPAWN_ENABLED,SAME_NATION_ACCESS,ADVANCED_JOIN_ACCESS,ENEMY_NATION,OUTSIDER_SPAWN_DISABLED,NOT_MEMBER,UNKNOWN_DATA}
    public double distanceTo(double targetX,double targetZ){return Math.hypot(x-targetX,z-targetZ);}
}

package net.townymap.teleport;

import java.util.List;

public record TeleportRoute(Mode mode,List<Step> steps,TeleportDestination destination,double walkingDistance,
                            MembershipRisk membershipRisk,Quality quality,int joinHops,double score,double saving){
    public enum Mode{STANDARD,JOIN_ASSISTED,JOIN_NATION_SPAWN}
    public enum StepType{LEAVE_TOWN,JOIN_TOWN,TOWN_SPAWN,NATION_SPAWN,WALK}
    public enum MembershipRisk{LOW,MEDIUM,HIGH,CRITICAL,UNKNOWN}
    public enum Quality{GOOD,LIMITED,UNCERTAIN}
    public record Step(StepType type,String label,String command){}
}

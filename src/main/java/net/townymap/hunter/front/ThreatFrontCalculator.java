package net.townymap.hunter.front;

/** Pure radius mathematics. Elapsed time changes geometry, never threat directly. */
public final class ThreatFrontCalculator {
    private ThreatFrontCalculator(){}
    public static Radii calculate(HiddenThreatOrigin origin,long now,double plausibleSpeed,double warningSpeed,long warningLeadMs,double safetyMargin){long elapsed=Math.max(0,now-origin.travelStartsAt());double plausibleSeconds=elapsed/1000.0*Math.max(.25,safetyMargin);boolean teleport=origin.type()==HiddenThreatOrigin.Type.TOWN_SPAWN||origin.type()==HiddenThreatOrigin.Type.NATION_SPAWN||origin.type()==HiddenThreatOrigin.Type.OTHER_TELEPORT;double warningSeconds=(elapsed+(teleport?0:Math.max(0,warningLeadMs)))/1000.0*Math.max(.25,safetyMargin);double plausible=origin.initialRadius()+plausibleSeconds*Math.max(1,plausibleSpeed);double warning=origin.initialRadius()+warningSeconds*Math.max(plausibleSpeed+.1,warningSpeed);return new Radii(plausible,Math.max(plausible,warning));}
    public record Radii(double plausible,double warning){}
}

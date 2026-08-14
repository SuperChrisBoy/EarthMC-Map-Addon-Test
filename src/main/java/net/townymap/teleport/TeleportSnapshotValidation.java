package net.townymap.teleport;

/** Rejects partial bulk responses before they can poison the long-lived teleport cache. */
public final class TeleportSnapshotValidation {
    private TeleportSnapshotValidation(){}
    public static boolean usable(boolean playerPresent,int receivedTowns,int receivedNations,int requestedTowns){
        if(!playerPresent||receivedTowns<=0||receivedNations<=0)return false;
        int minimum=Math.max(1,(int)Math.floor(requestedTowns*0.80));
        return receivedTowns>=minimum;
    }
}

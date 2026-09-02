package net.townymap.hunter.tracking;

import net.townymap.model.TownData;
import java.util.*;

/** Authoritative timestamp-based state machine for one wilderness/ruin outing. */
public final class WildernessExposureSession {
    public enum State { SAFE, ENTRY_BUFFER, ACTIVE, CLAIM_GRACE }
    public enum Transition { NONE, BUFFER_STARTED, BUFFER_CANCELLED, SESSION_STARTED, GRACE_STARTED, SESSION_RESUMED, SESSION_ENDED }
    public static final double DEFAULT_RADIUS=3_000.0,MEANINGFUL_MOVE=32.0;
    public static final int MAX_ENTRIES=24;
    private final double radius;private final int maxEntries;private final long entryBufferMs,claimGraceMs,exposureTargetMs;
    private State state=State.SAFE;private Transition transition=Transition.NONE;
    private long sessionId,firstEnteredExposedAt,activatedAt,lastExposureStartedAt,safeClaimEnteredAt,cumulativeCompletedMs,lastUpdatedAt,revision;
    private double x,z,indexedX=Double.NaN,indexedZ=Double.NaN,peakModifier;
    private List<Entry> entries=List.of();private RecentEntry recentEntry;

    public WildernessExposureSession(){this(DEFAULT_RADIUS,MAX_ENTRIES,30_000,60_000,300_000);}
    public WildernessExposureSession(double radius,int maxEntries){this(radius,maxEntries,30_000,60_000,300_000);}
    public WildernessExposureSession(double radius,int maxEntries,long entryBufferMs,long claimGraceMs,long exposureTargetMs){this.radius=Math.clamp(radius,500,3000);this.maxEntries=Math.clamp(maxEntries,1,MAX_ENTRIES);this.entryBufferMs=Math.clamp(entryBufferMs,0,300_000);this.claimGraceMs=Math.clamp(claimGraceMs,0,600_000);this.exposureTargetMs=Math.clamp(exposureTargetMs,60_000,3_600_000);}

    /** Returns true only when the local entry index changed. */
    public boolean update(boolean exposed,double nx,double nz,List<TownData> towns,long now){
        x=nx;z=nz;lastUpdatedAt=now;transition=Transition.NONE;
        switch(state){
            case SAFE->{if(exposed){state=State.ENTRY_BUFFER;firstEnteredExposedAt=now;transition=Transition.BUFFER_STARTED;if(entryBufferMs==0)activate(now);}}
            case ENTRY_BUFFER->{if(!exposed){state=State.SAFE;firstEnteredExposedAt=0;transition=Transition.BUFFER_CANCELLED;}else if(now-firstEnteredExposedAt>=entryBufferMs)activate(now);}
            case ACTIVE->{if(!exposed){cumulativeCompletedMs+=Math.max(0,now-lastExposureStartedAt);lastExposureStartedAt=0;safeClaimEnteredAt=now;state=State.CLAIM_GRACE;transition=Transition.GRACE_STARTED;if(claimGraceMs==0)end();}}
            case CLAIM_GRACE->{if(exposed){state=State.ACTIVE;lastExposureStartedAt=now;safeClaimEnteredAt=0;transition=Transition.SESSION_RESUMED;}else if(now-safeClaimEnteredAt>=claimGraceMs)end();}
        }
        peakModifier=Math.max(peakModifier,exposureModifier(now));
        if(!sessionAlive())return false;
        if(!Double.isFinite(indexedX)||Math.hypot(nx-indexedX,nz-indexedZ)>=MEANINGFUL_MOVE){indexedX=nx;indexedZ=nz;entries=nearest(nx,nz,towns,radius,maxEntries);revision++;return true;}
        return false;
    }
    private void activate(long now){state=State.ACTIVE;sessionId++;activatedAt=now;lastExposureStartedAt=now;cumulativeCompletedMs=0;safeClaimEnteredAt=0;transition=Transition.SESSION_STARTED;revision++;indexedX=Double.NaN;}
    private void end(){state=State.SAFE;transition=Transition.SESSION_ENDED;firstEnteredExposedAt=activatedAt=lastExposureStartedAt=safeClaimEnteredAt=cumulativeCompletedMs=0;peakModifier=0;entries=List.of();recentEntry=null;revision++;indexedX=Double.NaN;}
    public void reset(){state=State.SAFE;transition=Transition.NONE;sessionId=firstEnteredExposedAt=activatedAt=lastExposureStartedAt=safeClaimEnteredAt=cumulativeCompletedMs=lastUpdatedAt=0;peakModifier=0;entries=List.of();recentEntry=null;revision++;indexedX=Double.NaN;}
    public void recordCommand(String command,long now){if(command==null)return;String[] p=command.trim().replaceFirst("^/","").split("\\s+");if(p.length<2)return;String root=p[0].toLowerCase(Locale.ROOT),sub=p[1].toLowerCase(Locale.ROOT);if(!(root.equals("t")||root.equals("town")||root.equals("n")||root.equals("nation"))||!sub.equals("spawn"))return;String name=p.length>=3?String.join(" ",Arrays.copyOfRange(p,2,p.length)):"";recentEntry=new RecentEntry(root.startsWith("n")?Entry.Type.NATION:Entry.Type.TOWN,name,now);indexedX=Double.NaN;}
    public State state(){return state;}public Transition transition(){return transition;}public boolean active(){return state==State.ACTIVE;}public boolean sessionAlive(){return state==State.ACTIVE||state==State.CLAIM_GRACE;}
    public long sessionId(){return sessionId;}public long entryBufferElapsedMs(long now){return state==State.ENTRY_BUFFER?Math.max(0,now-firstEnteredExposedAt):0;}public long entryBufferMs(){return entryBufferMs;}
    public long claimGraceElapsedMs(long now){return state==State.CLAIM_GRACE?Math.max(0,now-safeClaimEnteredAt):0;}public long claimGraceMs(){return claimGraceMs;}
    public long continuousExposureMs(long now){return state==State.ACTIVE?Math.max(0,now-lastExposureStartedAt):0;}public long cumulativeExposureMs(long now){return cumulativeCompletedMs+continuousExposureMs(now);}
    public double exposureModifier(long now){return modifier(cumulativeExposureMs(now),exposureTargetMs);}public double peakExposureModifier(){return peakModifier;}public long exposureTargetMs(){return exposureTargetMs;}
    public static double modifier(long cumulative,long target){if(cumulative<=0)return 0;double ratio=(double)cumulative/Math.max(1,target);if(ratio<.6)return .25*ratio/.6;if(ratio<1)return .25+.25*(ratio-.6)/.4;if(ratio<1.4)return .5+.5*(ratio-1)/.4;return 1;}
    public long durationMs(long now){return cumulativeExposureMs(now);}public long revision(){return revision;}public double x(){return x;}public double z(){return z;}public List<Entry> entries(){return entries;}
    public RecentEntry recentEntry(long now){return recentEntry!=null&&sessionAlive()&&now-recentEntry.atMs<=120_000L?recentEntry:null;}
    private static List<Entry> nearest(double x,double z,List<TownData> towns,double radius,int limit){if(towns==null)return List.of();return towns.stream().map(t->new Entry(t.name(),Entry.Type.TOWN,t.centerX(),t.centerZ(),Math.hypot(t.centerX()-x,t.centerZ()-z))).filter(e->e.distance<=radius).sorted(Comparator.comparingDouble(Entry::distance).thenComparing(Entry::name)).limit(limit).toList();}
    public record Entry(String name,Type type,int x,int z,double distance){public enum Type{TOWN,NATION}}
    public record RecentEntry(Entry.Type type,String name,long atMs){public boolean matches(String candidate){return !name.isBlank()&&candidate.equalsIgnoreCase(name);}}
}

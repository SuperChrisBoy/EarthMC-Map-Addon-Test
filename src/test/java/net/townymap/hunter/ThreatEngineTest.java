package net.townymap.hunter;

import net.townymap.hunter.model.HunterState;
import net.townymap.hunter.threat.ThreatEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThreatEngineTest {
    private final ThreatEngine engine=new ThreatEngine();
    @Test void offlineThreatDropsButPersistsForThirtyMinutes(){long now=2_000_000L;HunterState h=hunter(now);var online=engine.assess(h,80,false,false,now);h.online=HunterState.OnlineStatus.OFFLINE;h.offlineSinceMs=now;var offline=engine.assess(h,80,false,false,now);assertTrue(offline.score()<online.score());assertTrue(offline.level().ordinal()<online.level().ordinal());assertNotEquals(HunterState.ThreatLevel.SAFE,offline.level());assertEquals(HunterState.ThreatLevel.SAFE,engine.assess(h,80,false,false,now+30*60_000L+1).level());}
    @Test void returningOnlineRestoresLocationBasedAssessment(){long now=2_000_000L;HunterState h=hunter(now);h.online=HunterState.OnlineStatus.OFFLINE;h.offlineSinceMs=now;int reduced=engine.assess(h,80,false,false,now).score();h.online=HunterState.OnlineStatus.ONLINE;h.offlineSinceMs=0;assertTrue(engine.assess(h,80,false,false,now).score()>reduced);}
    @Test void exposedWildernessRiskIncreasesWithTimeAndClaimDistance(){long now=2_000_000L;HunterState h=hunter(now);int initial=engine.assess(h,1500,true,true,0,0,now).score();int oneMinute=engine.assess(h,1500,true,true,60_000,0,now).score();int remote=engine.assess(h,1500,true,true,300_000,300,now).score();assertTrue(oneMinute>initial);assertTrue(remote>oneMinute);}
    private static HunterState hunter(long now){HunterState h=new HunterState("Hunter");h.online=HunterState.OnlineStatus.ONLINE;h.visibility=HunterState.Visibility.VISIBLE;h.direct=new HunterState.Observation(0,0,now,HunterState.ObservationType.DIRECT_DYNMAP,"Town","Nation",HunterState.Confidence.HIGH);return h;}
}

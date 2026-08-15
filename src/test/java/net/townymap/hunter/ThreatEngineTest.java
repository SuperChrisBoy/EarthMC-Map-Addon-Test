package net.townymap.hunter;

import net.townymap.hunter.front.HiddenThreatFrontEngine;
import net.townymap.hunter.model.*;
import net.townymap.hunter.threat.ThreatEngine;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ThreatEngineTest {
    private final ThreatEngine engine=new ThreatEngine();
    private static HiddenThreatFrontEngine.Summary fronts(int warning,int plausible){return new HiddenThreatFrontEngine.Summary(warning,plausible,warning+plausible,null,0);}
    @Test void visibleNearHunterUsesExactPosition(){assertEquals(HunterState.ThreatLevel.CRITICAL,engine.assess(new ThreatEngine.Input(true,80,999_999,List.of(),false,0)).level());}
    @Test void hiddenDurationDoesNotDriveRisk(){var a=engine.assess(new ThreatEngine.Input(false,5000,1_000,List.of(),false,0));var b=engine.assess(new ThreatEngine.Input(false,5000,9_000_000,List.of(),false,0));assertEquals(a.level(),b.level());}
    @Test void outsideAllFrontsIsLow(){assertEquals(HunterState.ThreatLevel.LOW,engine.assessHidden(fronts(0,0),true,1,false,0).level());}
    @Test void warningFrontRaisesKnownHunterMoreThanPotential(){assertTrue(engine.assessHidden(fronts(1,0),true,0,false,0).level().ordinal()>engine.assessHidden(fronts(1,0),false,0,false,0).level().ordinal());}
    @Test void plausibleFrontIsCriticalForKnownHunter(){assertEquals(HunterState.ThreatLevel.CRITICAL,engine.assessHidden(fronts(1,1),true,0,false,0).level());}
    @Test void wildernessExposureOnlyAmplifiesIntersectingGeometry(){assertEquals(engine.assessHidden(fronts(0,0),true,0,false,0).level(),engine.assessHidden(fronts(0,0),true,1,false,0).level());assertTrue(engine.assessHidden(fronts(1,0),false,1,false,0).level().ordinal()>engine.assessHidden(fronts(1,0),false,0,false,0).level().ordinal());}
    @Test void distantReappearanceDoesNotWarn(){assertTrue(engine.assess(new ThreatEngine.Input(true,8000,0,List.of(),false,0)).level().ordinal()<=HunterState.ThreatLevel.LOW.ordinal());}
}

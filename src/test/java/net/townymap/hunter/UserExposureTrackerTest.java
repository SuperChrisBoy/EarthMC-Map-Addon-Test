package net.townymap.hunter;

import net.townymap.hunter.tracking.UserExposureTracker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserExposureTrackerTest {
    @Test void hiddenSafetyWaitsThenRampsAndNeverRemovesAllRisk(){var tracker=new UserExposureTracker();tracker.update(true,true,1_000);tracker.update(false,true,2_000);assertEquals(1,tracker.targetingMultiplier(31_000,true,60_000,240_000,.4),.001);assertEquals(.8,tracker.targetingMultiplier(182_000,true,60_000,240_000,.4),.001);assertEquals(.6,tracker.targetingMultiplier(500_000,true,60_000,240_000,.4),.001);}
    @Test void becomingVisibleImmediatelyRemovesHiddenSafetyBonus(){var tracker=new UserExposureTracker();tracker.update(false,true,1_000);assertTrue(tracker.targetingMultiplier(500_000,true,0,60_000,.4)<1);tracker.update(true,true,501_000);assertEquals(1,tracker.targetingMultiplier(501_001,true,0,60_000,.4),.001);}
}

package net.townymap.hunter;

import net.townymap.hunter.model.ApproachRoute;
import net.townymap.hunter.model.HunterState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HunterCandidateTrackingRegressionTest {
    @Test void teleportOriginRefreshDoesNotDependOnWildernessSession(){assertTrue(HunterEarlyWarningSystem.approachRefreshDue(true,false,true,1_000,0,60_000));assertTrue(HunterEarlyWarningSystem.approachRefreshDue(true,false,false,61_000,1_000,60_000));assertFalse(HunterEarlyWarningSystem.approachRefreshDue(false,true,true,61_000,0,60_000));}
    @Test void newCandidateSurvivesUntilItsFirstTrackingUpdate(){
        assertFalse(HunterEarlyWarningSystem.candidateShouldRemove(false,false,0,false,1_000));
    }
    @Test void observedCandidateExpiresOnlyAfterThirtyMinutesHidden(){
        long seen=10_000;
        assertFalse(HunterEarlyWarningSystem.candidateShouldRemove(false,true,seen,false,seen+30*60_000L));
        assertTrue(HunterEarlyWarningSystem.candidateShouldRemove(false,true,seen,false,seen+30*60_000L+1));
        assertFalse(HunterEarlyWarningSystem.candidateShouldRemove(false,true,seen,true,seen+60*60_000L));
    }
    @Test void manualWatchlistOwnsDuplicates(){
        assertTrue(HunterEarlyWarningSystem.candidateShouldRemove(true,false,0,true,1_000));
    }
    @Test void automaticQualificationRequiresEnabledOnlineAndAboveThreshold(){
        assertTrue(HunterEarlyWarningSystem.qualifiesAutomatic(true,false,true,15,10));
        assertFalse(HunterEarlyWarningSystem.qualifiesAutomatic(true,false,true,8,10));
        assertFalse(HunterEarlyWarningSystem.qualifiesAutomatic(true,false,false,25,10));
        assertFalse(HunterEarlyWarningSystem.qualifiesAutomatic(true,true,true,30,10));
        assertFalse(HunterEarlyWarningSystem.qualifiesAutomatic(false,false,true,30,10));
        assertFalse(HunterEarlyWarningSystem.qualifiesAutomatic(true,false,true,10,10));
    }

    @Test void hiddenActorKeepsClosedOpportunityAndDistanceTracksUserMovement(){
        HunterState state=new HunterState("Candidate",HunterState.Source.AUTO_HIGH_OUTLAW);
        state.visibility=HunterState.Visibility.HIDDEN;
        ApproachRoute route=new ApproachRoute("town:a","Alpha",ApproachRoute.Type.TOWN,100,100,0,false);
        state.hiddenRouteOpportunities.put(route.key(),new HunterState.HiddenRouteOpportunity(route,1_000,2_000));
        assertEquals(1,HunterEarlyWarningSystem.effectiveRoutes(state,0,0).size());
        assertEquals(Math.hypot(100,100),HunterEarlyWarningSystem.effectiveRoutes(state,0,0).getFirst().distanceToUser(),.001);
        assertEquals(100,HunterEarlyWarningSystem.effectiveRoutes(state,0,100).getFirst().distanceToUser(),.001);
    }
}

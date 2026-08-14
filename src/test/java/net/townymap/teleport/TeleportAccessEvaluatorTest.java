package net.townymap.teleport;

import net.townymap.model.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeleportAccessEvaluatorTest {
    private final TeleportAccessEvaluator evaluator=new TeleportAccessEvaluator();
    @Test void sameNationPublicIsAccessible(){assertStatus(TeleportDestination.Eligibility.ACCESSIBLE,evaluator.town(ctx("Home","Nation"),town("Target","Nation",true,false,20)));}
    @Test void sameNationPublicIgnoresOutsiderUpkeepBalance(){var result=evaluator.town(ctx("Home","Nation"),town("Target","Nation",true,false,0));assertStatus(TeleportDestination.Eligibility.ACCESSIBLE,result);assertReason(TeleportDestination.Reason.SAME_NATION_ACCESS,result);}
    @Test void sameNationNonPublicIsUnavailable(){assertReason(TeleportDestination.Reason.PUBLIC_SPAWN_DISABLED,evaluator.town(ctx("Home","Nation"),town("Target","Nation",false,true,20)));}
    @Test void foreignOutsiderSpawnIsAccessible(){assertStatus(TeleportDestination.Eligibility.ACCESSIBLE,evaluator.town(ctx("Home","Nation"),town("Target","Other",false,true,20)));}
    @Test void openMembershipDoesNotGrantSpawn(){assertReason(TeleportDestination.Reason.OUTSIDER_SPAWN_DISABLED,evaluator.town(ctx("Home","Nation"),town("Target","Other",true,false,20)));}
    @Test void enemyOverridesOutsiderSpawn(){assertReason(TeleportDestination.Reason.ENEMY_NATION,evaluator.town(ctx("Home","Nation",Set.of(),Set.of("Enemy")),town("Target","Enemy",true,true,20)));}
    @Test void enemyNationSpawnUnavailable(){assertReason(TeleportDestination.Reason.ENEMY_NATION,evaluator.nation(ctx("Home","Nation",Set.of(),Set.of("Enemy")),nation("Enemy")));}
    @Test void ownAndAlliedNationSpawnsAccessible(){assertStatus(TeleportDestination.Eligibility.ACCESSIBLE,evaluator.nation(ctx("Home","Nation",Set.of("Ally"),Set.of()),nation("Nation")));assertReason(TeleportDestination.Reason.ALLIED_NATION,evaluator.nation(ctx("Home","Nation",Set.of("Ally"),Set.of()),nation("Ally")));}
    @Test void publicForeignNationSpawnIsAccessible(){assertReason(TeleportDestination.Reason.OUTSIDER_SPAWN_ENABLED,evaluator.nation(ctx("Home","Nation"),nation("Afghanistan",true)));}
    @Test void privateForeignNationSpawnRemainsUnavailable(){assertReason(TeleportDestination.Reason.NOT_MEMBER_OR_ALLY,evaluator.nation(ctx("Home","Nation"),nation("Private",false)));}
    @Test void closerPublicNationSpawnWinsRouteRanking(){var publicAccess=evaluator.nation(ctx("Home","Nation"),nation("Afghanistan",true));TeleportRoute afghanistan=route("Afghanistan",200,publicAccess.status());TeleportRoute fartherTown=route("FarTown",3500,TeleportDestination.Eligibility.ACCESSIBLE);assertEquals("Afghanistan",TeleportRouteRanking.rank(List.of(fartherTown,afghanistan)).getFirst().destination().name());}
    @Test void lowBalanceIsUncertainNotSafe(){assertReason(TeleportDestination.Reason.UPKEEP_UNCERTAIN,evaluator.town(ctx("Home","Nation"),town("Target","Other",false,true,4.99)));}
    @Test void playerWithoutTownCanUseConfirmedOutsiderSpawn(){assertStatus(TeleportDestination.Eligibility.ACCESSIBLE,evaluator.town(ctx("",""),town("Target","Other",false,true,20)));}
    @Test void missingDataIsUncertain(){assertReason(TeleportDestination.Reason.API_DATA_MISSING,evaluator.town(null,null));}
    @Test void hypotheticalJoinChangesNationAccess(){PlayerTeleportContext before=ctx("Home","Old");TownFullData joined=town("Join","New",true,true,20);PlayerTeleportContext after=before.simulateJoin(joined,Set.of(),Set.of());TownFullData destination=town("Destination","New",true,false,20);assertReason(TeleportDestination.Reason.OUTSIDER_SPAWN_DISABLED,evaluator.town(before,destination));assertReason(TeleportDestination.Reason.SAME_NATION_ACCESS,evaluator.town(after,destination));}
    @Test void rankingNeverChoosesUnavailable(){TeleportRoute near=route("Near",5,TeleportDestination.Eligibility.UNAVAILABLE);TeleportRoute far=route("Far",50,TeleportDestination.Eligibility.ACCESSIBLE);assertEquals("Far",TeleportRouteRanking.rank(List.of(near,far)).getFirst().destination().name());}
    @Test void partialBulkSnapshotIsRejected(){assertFalse(TeleportSnapshotValidation.usable(true,40,100,100));assertFalse(TeleportSnapshotValidation.usable(false,100,100,100));assertTrue(TeleportSnapshotValidation.usable(true,80,1,100));assertTrue(TeleportSnapshotValidation.usable(true,10,0,10));}
    @Test void loadingFallbackAlwaysProvidesNearestResults(){TownData far=mapTown("Far",1000,1000),near=mapTown("Near",100,100);List<TeleportRoute> routes=TeleportFallbackRoutes.nearest(List.of(far,near),0,0);assertEquals(2,routes.size());assertEquals("Near",routes.getFirst().destination().name());assertEquals(TeleportDestination.Eligibility.UNCERTAIN,routes.getFirst().destination().eligibility());assertEquals(TeleportDestination.Reason.API_DATA_MISSING,routes.getFirst().destination().reason());}
    @Test void routeRenderingHasAConstantWorkBudget(){assertEquals(1,TeleportRenderBudget.lineSteps(0));assertEquals(TeleportRenderBudget.MAX_LINE_STEPS,TeleportRenderBudget.lineSteps(1_000_000));}
    @Test void planCacheKeyIgnoresRenderFramesButTracksInputs(){TeleportPlanCacheKey a=TeleportPlanCacheKey.of(100,200,7,"Home"),same=TeleportPlanCacheKey.of(100,200,7,"Home");assertEquals(a,same);assertNotEquals(a,TeleportPlanCacheKey.of(101,200,7,"Home"));assertNotEquals(a,TeleportPlanCacheKey.of(100,200,8,"Home"));assertNotEquals(a,TeleportPlanCacheKey.of(100,200,7,"Other"));}
    @Test void commandCooldownRequiresOneFullSecond(){TeleportCommandCooldown cooldown=new TeleportCommandCooldown();assertTrue(cooldown.tryAcquire(1_000));assertFalse(cooldown.tryAcquire(1_999));assertEquals(1,cooldown.remaining(1_999));assertTrue(cooldown.tryAcquire(2_000));}
    @Test void advancedCommandsAreAlwaysClipboardOnly(){assertEquals(TeleportCommandAction.CLIPBOARD,TeleportCommandAction.resolveMode(TeleportCommandAction.CHAT,true,true));assertEquals(TeleportCommandAction.CLIPBOARD,TeleportCommandAction.resolveMode(TeleportCommandAction.EXECUTE,true,true));assertEquals(TeleportCommandAction.CHAT,TeleportCommandAction.resolveMode(TeleportCommandAction.CHAT,false,true));assertEquals(TeleportCommandAction.CLIPBOARD,TeleportCommandAction.resolveMode(TeleportCommandAction.EXECUTE,false,false));}
    @Test void leadershipAndCouncillorRanksRequireConfirmation(){assertTrue(AdvancedCommandGuard.requiresConfirmation(player(true,false,List.of(),List.of())));assertTrue(AdvancedCommandGuard.requiresConfirmation(player(false,true,List.of(),List.of())));assertTrue(AdvancedCommandGuard.requiresConfirmation(player(false,false,List.of("Councillor"),List.of())));assertFalse(AdvancedCommandGuard.requiresConfirmation(player(false,false,List.of("Builder"),List.of("Settler"))));}
    @Test void advancedQualityUsesSignedDifferenceAndKeepsLongerRoutes(){assertEquals(TeleportRouteQuality.Rating.EXCELLENT,TeleportRouteQuality.rate(1_000));assertEquals(TeleportRouteQuality.Rating.GOOD,TeleportRouteQuality.rate(250));assertEquals(TeleportRouteQuality.Rating.SLIGHT_IMPROVEMENT,TeleportRouteQuality.rate(249));assertEquals(TeleportRouteQuality.Rating.SAME_DISTANCE,TeleportRouteQuality.rate(0));assertEquals(TeleportRouteQuality.Rating.LONGER,TeleportRouteQuality.rate(-125));assertEquals(TeleportRouteQuality.Rating.NOT_COMPARABLE,TeleportRouteQuality.rate(Double.NaN));assertEquals(125,TeleportRouteQuality.blockDifference(-125));}

    private static void assertStatus(TeleportDestination.Eligibility expected,TeleportAccessEvaluator.Result actual){assertEquals(expected,actual.status());}
    private static void assertReason(TeleportDestination.Reason expected,TeleportAccessEvaluator.Result actual){assertEquals(expected,actual.reason());}
    private static PlayerTeleportContext ctx(String town,String nation){return ctx(town,nation,Set.of(),Set.of());}
    private static PlayerTeleportContext ctx(String town,String nation,Set<String> allies,Set<String> enemies){return new PlayerTeleportContext("Player",town,nation,Set.of(),Set.of(),PlayerTeleportContext.normalized(allies),PlayerTeleportContext.normalized(enemies),false,false,false,true);}
    private static TownFullData town(String name,String nation,boolean pub,boolean outsider,double balance){return new TownFullData(
            name,"","","","","Mayor",nation,0L,0L,0L,
            pub,true,false,false,false,false,false,!nation.isBlank(),outsider,false,false,false,
            1,1,0,0,1,0,0,balance,-1.0,false,false,false,false,100,64,100,
            List.of(),List.of(),List.of(),List.of(),Map.of(),List.of());}
    private static NationFullData nation(String name){return nation(name,true);}
    private static NationFullData nation(String name,boolean isPublic){return new NationFullData(
            name,"","","","King","Capital",0L,isPublic,false,false,
            0,0,0,1,0,0,0,20.0,100,64,100,
            List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),List.of(),List.of(),List.of());}
    private static TeleportRoute route(String name,double score,TeleportDestination.Eligibility eligibility){TeleportDestination d=new TeleportDestination(TeleportDestination.Type.TOWN_SPAWN,name,(int)score,64,0,"/t spawn "+name,eligibility,TeleportDestination.PhysicalAccess.UNKNOWN,TeleportDestination.Reason.OWN_TOWN);return new TeleportRoute(TeleportRoute.Mode.STANDARD,List.of(),d,score,TeleportRoute.MembershipRisk.LOW,TeleportRoute.Quality.GOOD,0,score,0);}
    private static TownData mapTown(String name,int x,int z){return new TownData(name,0xFFFFFF,List.<int[][]>of(new int[][]{{x-8,z-8},{x+8,z-8},{x+8,z+8},{x-8,z+8}}));}
    private static PlayerFullData player(boolean mayor,boolean king,List<String> townRanks,List<String> nationRanks){return new PlayerFullData("Player","","","Player","","","Home","Nation",0,0,0,true,false,mayor,king,true,true,0,0,List.of(),townRanks,nationRanks);}
}

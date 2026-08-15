package net.townymap.hunter.front;

import net.townymap.hunter.model.ApproachRoute;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TeleportThreatSelectorTest {
    private static ApproachRoute route(String name,int x,int z){return new ApproachRoute("town:"+name,name,ApproachRoute.Type.TOWN,x,z,0,false);}
    private static TeleportThreatSelector selector(int relevant){return new TeleportThreatSelector(5000,300,relevant,128,5000,.08,.04);}
    @Test void hundredOriginsStayLatentWhileNearbySubsetIsRelevant(){var routes=new ArrayList<ApproachRoute>();for(int i=0;i<100;i++)routes.add(route("far"+i,20_000+i*100,0));routes.add(route("near",500,0));var s=selector(10).select(routes,0,0,null,null,null,true,1);assertEquals(101,s.latentCount());assertEquals(1,s.relevantClusterCount());assertEquals("near",s.relevantRepresentatives().getFirst().name());}
    @Test void nearbyOriginsClusterAndRepresentativeKeepsActualSpawn(){var s=selector(10).select(List.of(route("a",100,100),route("b",120,110),route("c",1500,0)),0,0,null,null,null,true,1);assertEquals(2,s.relevantClusterCount());assertTrue(s.relevantRepresentatives().stream().anyMatch(r->r.x()==100&&r.z()==100));}
    @Test void escapeIntersectionCanOutrankCloserTown(){var s=selector(10).select(List.of(route("close",200,500),route("intercept",600,0)),0,0,1000d,0d,"",true,1);assertEquals("intercept",s.ranked().getFirst().representative().name());assertEquals("escape-route intersection",s.ranked().getFirst().reason());}
    @Test void sharedSpawnReceivesStrongPriority(){var s=selector(10).select(List.of(route("close",200,400),route("escape",800,0)),0,0,1000d,0d,"escape",true,1);assertEquals("escape",s.ranked().getFirst().representative().name());assertEquals("shared teleport spawn",s.ranked().getFirst().reason());}
    @Test void movementReranksWithoutRebuildingTeleportGraph(){var selector=selector(3);var routes=List.of(route("west",-1000,0),route("east",5000,0));var first=selector.select(routes,-900,0,null,null,null,true,1);var unchanged=selector.select(routes,-850,0,null,null,null,true,100);var moved=selector.select(routes,4900,0,null,null,null,true,6000);assertEquals(first.indexRebuilds(),unchanged.indexRebuilds());assertEquals(unchanged.indexRebuilds(),moved.indexRebuilds());assertTrue(moved.reranks()>unchanged.reranks());assertEquals("east",moved.relevantRepresentatives().getFirst().name());}
    @Test void noClusterWorkRunsOnRenderOrInsignificantMovement(){var selector=selector(3);var routes=List.of(route("a",100,0));var first=selector.select(routes,0,0,null,null,null,true,1);var cached=selector.select(routes,10,0,null,null,null,true,100);assertEquals(first.indexRebuilds(),cached.indexRebuilds());assertEquals(first.reranks(),cached.reranks());}
}

package net.townymap.integration;

import net.townymap.hunter.front.HiddenThreatFrontEngine;
import net.townymap.hunter.front.HiddenThreatOrigin;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class XaeroRadiusOverlayProviderTest {
    private static HiddenThreatFrontEngine.Front front(String id,double plausible,double warning,double relevance){var o=new HiddenThreatOrigin("hunter","Hunter",true,HiddenThreatOrigin.Type.TOWN_SPAWN,id,id,100,200,0,0,0,false);return new HiddenThreatFrontEngine.Front(o,plausible,warning,500,false,false,relevance,1_000);}
    @Test void publishesAuthoritativeTwoFrontGeometry(){var p=new XaeroRadiusOverlayProvider();p.publish(List.of(front("a",80,120,1)),1_000,8);var o=p.snapshot().getFirst();assertEquals(80,o.plausible(1_500,false));assertEquals(120,o.warning(1_500,false));}
    @Test void interpolatesBetweenAuthoritativeUpdates(){var p=new XaeroRadiusOverlayProvider();p.publish(List.of(front("a",80,120,1)),1_000,8);p.publish(List.of(front("a",160,240,1)),2_000,8);var o=p.snapshot().getFirst();assertEquals(80,o.plausible(2_000,true));assertEquals(160,o.plausible(2_250,true));}
    @Test void renderingLimitOnlyLimitsSnapshot(){var p=new XaeroRadiusOverlayProvider();p.publish(List.of(front("a",10,20,1),front("b",10,20,3),front("c",10,20,2)),1_000,2);assertEquals(List.of("b","c"),p.snapshot().stream().map(XaeroRadiusOverlayProvider.Overlay::label).toList());}
    @Test void clearImmediatelyRemovesAllOverlays(){var p=new XaeroRadiusOverlayProvider();p.publish(List.of(front("a",10,20,1)),1_000,8);p.clear();assertTrue(p.snapshot().isEmpty());}
}

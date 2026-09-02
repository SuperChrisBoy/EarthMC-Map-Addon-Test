package net.townymap.render;

import net.townymap.hunter.front.HiddenThreatOrigin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class XaeroRadiusOverlayRendererTest {
    @Test void teleportOriginsUseColorsDistinctFromLastKnown(){var last=XaeroRadiusOverlayRenderer.palette(HiddenThreatOrigin.Type.LAST_KNOWN_POSITION);var town=XaeroRadiusOverlayRenderer.palette(HiddenThreatOrigin.Type.TOWN_SPAWN);var nation=XaeroRadiusOverlayRenderer.palette(HiddenThreatOrigin.Type.NATION_SPAWN);assertNotEquals(last,town);assertNotEquals(last,nation);assertNotEquals(town,nation);}
}

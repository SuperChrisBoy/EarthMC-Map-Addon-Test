package net.townymap.api;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EarthMcApiRouteTemplateTest {
    @Test void townRouteSnapshotOmitsLargeUnusedRosters() {
        var template=EarthMcApiClient.townRouteTemplate();
        assertEquals(Set.of("name","nation","coordinates","status","stats","trusted"),template.keySet());
        assertFalse(template.has("residents"));
        assertFalse(template.has("outlaws"));
    }

    @Test void nationRouteSnapshotOmitsLargeUnusedMembershipData() {
        var template=EarthMcApiClient.nationRouteTemplate();
        assertEquals(Set.of("name","coordinates","status","allies","enemies"),template.keySet());
        assertFalse(template.has("towns"));
        assertFalse(template.has("residents"));
        assertFalse(template.has("pacts"));
    }
}

package net.townymap.ice;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IceRoadNetworkTest {
    @Test void bundledAuthoritativeDatasetLoadsStationsLinesAndMetadata() {
        IceRoadNetwork network=IceRoadNetwork.get();
        assertEquals(751,network.stations().stream().filter(java.util.Objects::nonNull).count());
        assertTrue(network.segments().size()>1_000);
        assertEquals("Fih",network.stations().getFirst().name());
        assertTrue(network.stations().getFirst().lines().contains("TNIH: Deccan"));
    }

    @Test void blockedStationsAreExcludedFromNearestAndRouting() {
        IceRoadNetwork network=IceRoadNetwork.get();
        var station=network.stations().getFirst();
        assertEquals(station.id(),network.nearest(station.x(),station.z(),Map.of()).id());
        assertNotEquals(station.id(),network.nearest(station.x(),station.z(),
                Map.of(IceRoadNetwork.reportKey(station.id()),"OBSTRUCTED")).id());
    }

    @Test void routeRetainsOnlyItsExactColoredHighwaySubsection() {
        IceRoadNetwork network=IceRoadNetwork.get();
        var from=network.stations().get(0);
        var to=network.stations().get(1);
        var trip=network.trip(from.x(),from.z(),to.x(),to.z(),Map.of());
        assertNotNull(trip);
        assertEquals(from.name(),trip.entry());
        assertEquals(to.name(),trip.exit());
        assertFalse(trip.path().isEmpty());
        assertTrue(trip.path().stream().allMatch(segment->segment.color()!=0));
        assertTrue(trip.path().size()<network.segments().size());
        assertEquals(trip.entryId(),trip.stationIds().getFirst());
        assertEquals(trip.exitId(),trip.stationIds().getLast());
    }

    @Test void elevatorsRemainGraphNodesButCannotBeRouteEndpoints() {
        IceRoadNetwork network=IceRoadNetwork.get();
        var elevator=network.stations().stream()
                .filter(station->station!=null&&station.type().toLowerCase(java.util.Locale.ROOT).startsWith("elev"))
                .findFirst().orElseThrow();

        assertFalse(IceRoadNetwork.canEnterOrExit(elevator));
        assertFalse(network.nearest(elevator.x(),elevator.z(),Map.of()).type()
                .toLowerCase(java.util.Locale.ROOT).startsWith("elev"));
    }

    @Test void junctionsRemainGraphNodesButCannotBeRouteEndpoints() {
        IceRoadNetwork network=IceRoadNetwork.get();
        var junction=network.stations().stream()
                .filter(station->station!=null&&station.type().toLowerCase(java.util.Locale.ROOT).startsWith("jct"))
                .findFirst().orElseThrow();

        assertFalse(IceRoadNetwork.canEnterOrExit(junction));
        assertFalse(network.nearest(junction.x(),junction.z(),Map.of()).type()
                .toLowerCase(java.util.Locale.ROOT).startsWith("jct"));
    }

    @Test void routeEdgesAreClippedAtStationsInsideLongSegments() {
        var a=new IceRoadNetwork.Station(0,"A","station",40,0,"",java.util.List.of());
        var b=new IceRoadNetwork.Station(1,"B","station",100,60,"",java.util.List.of());
        var path=IceRoadNetwork.edgePath(a,b,java.util.List.of(
                new IceRoadNetwork.Point(0,0),new IceRoadNetwork.Point(100,0),
                new IceRoadNetwork.Point(100,100)));

        assertEquals(new IceRoadNetwork.Point(40,0),path.getFirst());
        assertEquals(new IceRoadNetwork.Point(100,60),path.getLast());
        assertFalse(path.contains(new IceRoadNetwork.Point(0,0)));
        assertFalse(path.contains(new IceRoadNetwork.Point(100,100)));
    }

    @Test void accessibleOnlySelectionRejectsUnknownStationsAndFindsAnAlternative() {
        IceRoadNetwork network=IceRoadNetwork.get();
        var unknown=network.stations().stream().filter(IceRoadNetwork::canEnterOrExit).findFirst().orElseThrow();
        var accessible=network.stations().stream().filter(IceRoadNetwork::canEnterOrExit)
                .filter(station->station.id()!=unknown.id()).findFirst().orElseThrow();
        var reports=Map.of(IceRoadNetwork.reportKey(accessible.id()),"ACCESSIBLE");

        assertEquals(unknown.id(),network.nearest(unknown.x(),unknown.z(),reports).id());
        assertEquals(accessible.id(),network.nearest(unknown.x(),unknown.z(),reports,true).id());
    }

    @Test void routeEdgeDropsAnOutAndBackSpurAtAJunction() {
        var a=new IceRoadNetwork.Station(0,"A","station",0,0,"",java.util.List.of());
        var b=new IceRoadNetwork.Station(1,"B","station",0,100,"",java.util.List.of());
        var path=IceRoadNetwork.edgePath(a,b,java.util.List.of(
                new IceRoadNetwork.Point(0,0),new IceRoadNetwork.Point(100,0),
                new IceRoadNetwork.Point(0,0),new IceRoadNetwork.Point(0,100)));

        assertEquals(java.util.List.of(new IceRoadNetwork.Point(0,0),
                new IceRoadNetwork.Point(0,100)),path);
    }

    @Test void shortestGeometryDropsANonClosedOverlappingSpurInBothDirections() {
        var a=new IceRoadNetwork.Station(0,"A","station",0,0,"",java.util.List.of());
        var b=new IceRoadNetwork.Station(1,"B","station",50,100,"",java.util.List.of());
        var source=java.util.List.of(new IceRoadNetwork.Point(0,0),
                new IceRoadNetwork.Point(100,0),new IceRoadNetwork.Point(50,0),
                new IceRoadNetwork.Point(50,100));

        var forward=IceRoadNetwork.edgePath(a,b,source);
        var reverse=IceRoadNetwork.edgePath(b,a,source);
        assertFalse(forward.contains(new IceRoadNetwork.Point(100,0)));
        assertFalse(reverse.contains(new IceRoadNetwork.Point(100,0)));
        assertEquals(forward.reversed(),reverse);
    }

    @Test void everyBundledRouteEdgeIsSimpleAfterGeometryRouting() {
        assertTrue(IceRoadNetwork.get().edgePathsAreSimple());
    }
}

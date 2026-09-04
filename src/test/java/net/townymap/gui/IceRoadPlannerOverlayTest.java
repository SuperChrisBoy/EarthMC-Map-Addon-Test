package net.townymap.gui;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class IceRoadPlannerOverlayTest {
    @Test void exportUsesWebsiteHighwaysSchema() {
        JsonObject json=IceRoadPlannerOverlay.websiteJsonForTest();
        assertTrue(json.has("stations"));
        JsonObject planned=json.getAsJsonObject("lines")
                .getAsJsonObject("My Highway").getAsJsonObject("Planned Line");
        assertEquals("",planned.get("prefix").getAsString());
        assertEquals("",planned.get("code").getAsString());
        assertEquals("ff55dd",planned.get("color").getAsString());
        assertEquals(64,planned.get("y").getAsDouble());
        assertTrue(planned.has("branches"));
        planned.getAsJsonObject("branches").entrySet().forEach(entry->{
            JsonObject branch=entry.getValue().getAsJsonObject();
            assertFalse(branch.has("y"));
            assertTrue(branch.has("vertices"));
            assertTrue(branch.has("stations"));
        });
    }

    @Test void defaultLineSnapChoosesStraightOrDiagonalDirections() {
        assertArrayEquals(new double[]{10.5,0.5},IceRoadPlannerOverlay.snapDirection(0.5,0.5,10,2));
        assertArrayEquals(new double[]{7.5,7.5},IceRoadPlannerOverlay.snapDirection(0.5,0.5,8,7));
    }

    @Test void segmentSnapCanAttachBetweenExistingMarkers() {
        assertArrayEquals(new double[]{40.5,0.5},IceRoadPlannerOverlay.projectOntoSegment(40,9,0.5,0.5,100.5,0.5));
        assertArrayEquals(new double[]{50.5,50.5},IceRoadPlannerOverlay.projectOntoSegment(55,45,0.5,0.5,100.5,100.5));
    }

    @Test void coordinatesSnapToBlockCentersIncludingNegativeValues() {
        assertEquals(12.5,IceRoadPlannerOverlay.blockCenter(12.01));
        assertEquals(-12.5,IceRoadPlannerOverlay.blockCenter(-12.01));
    }

    @Test void stalePointEditNeverDragsOrKeepsTheWrongSelectedPoint() {
        assertFalse(IceRoadPlannerOverlay.shouldDragPoint(true,0,8,false,-1,-1,0,3));
        assertTrue(IceRoadPlannerOverlay.shouldDragPoint(true,0,8,false,-1,-1,0,8));
    }

    @Test void staleSegmentEditOnlyDragsItsOwnTwoEndpoints() {
        assertTrue(IceRoadPlannerOverlay.shouldDragPoint(false,-1,-1,true,2,5,2,5));
        assertTrue(IceRoadPlannerOverlay.shouldDragPoint(false,-1,-1,true,2,5,2,6));
        assertFalse(IceRoadPlannerOverlay.shouldDragPoint(false,-1,-1,true,2,5,2,4));
        assertFalse(IceRoadPlannerOverlay.shouldDragPoint(false,-1,-1,true,2,5,1,5));
    }

    @Test void selectionAloneNeverAuthorizesCoordinateMutation() {
        assertFalse(IceRoadPlannerOverlay.shouldEditSelectedPoint(false));
        assertTrue(IceRoadPlannerOverlay.shouldEditSelectedPoint(true));
    }

    @Test void ordinaryLineModePlacementsCreateStandalonePointsWithoutLines() {
        List<double[]> points=new ArrayList<>();
        NavigableSet<Integer> breaks=new TreeSet<>();
        IceRoadPlannerOverlay.addStandalonePointForTest(points,breaks,10,20);
        IceRoadPlannerOverlay.addStandalonePointForTest(points,breaks,30,40);
        IceRoadPlannerOverlay.addStandalonePointForTest(points,breaks,50,60);
        assertEquals(3,points.size());
        assertEquals(new TreeSet<>(List.of(1,2)),breaks);
        assertArrayEquals(new double[]{10.5,20.5},points.get(0));
        assertArrayEquals(new double[]{50.5,60.5},points.get(2));
    }

    @Test void coordinateLabelRectanglesOnlyCollideWhenTheyActuallyOverlap() {
        assertTrue(IceRoadPlannerOverlay.rectanglesOverlap(0,0,20,12,10,5,30,17));
        assertFalse(IceRoadPlannerOverlay.rectanglesOverlap(0,0,20,12,20,0,40,12));
        assertFalse(IceRoadPlannerOverlay.rectanglesOverlap(0,0,20,12,0,12,20,24));
    }

    @Test void topologyViewShowsTurnsAndHeightChangesButNotIntersections() {
        assertTrue(IceRoadPlannerOverlay.shouldShowTopologyCoordinate(2,true,false));
        assertFalse(IceRoadPlannerOverlay.shouldShowTopologyCoordinate(3,true,false));
        assertTrue(IceRoadPlannerOverlay.shouldShowTopologyCoordinate(3,false,true));
        assertFalse(IceRoadPlannerOverlay.shouldShowTopologyCoordinate(2,false,false));
    }

    @Test void mapPanIsNotTreatedAsAPlacementClick() {
        assertFalse(IceRoadPlannerOverlay.movedBeyondClickThreshold(100,100,103,102));
        assertTrue(IceRoadPlannerOverlay.movedBeyondClickThreshold(100,100,105,100));
        assertTrue(IceRoadPlannerOverlay.movedBeyondClickThreshold(100,100,100,95));
    }

    @Test void nativeMapAlwaysReceivesReleaseAfterAnEmptyMapPress() {
        assertFalse(IceRoadPlannerOverlay.shouldConsumeRelease(false));
        assertTrue(IceRoadPlannerOverlay.shouldConsumeRelease(true));
    }
}

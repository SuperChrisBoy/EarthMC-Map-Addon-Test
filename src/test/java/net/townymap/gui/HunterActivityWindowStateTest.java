package net.townymap.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HunterActivityWindowStateTest {
    @Test void titleBarStartsDragAndControlsRemainDistinct(){
        assertEquals(HunterActivityWindowState.HeaderAction.DRAG,HunterActivityWindowState.headerAction(120,45,100,34,260,22));
        assertEquals(HunterActivityWindowState.HeaderAction.MINIMIZE,HunterActivityWindowState.headerAction(325,45,100,34,260,22));
        assertEquals(HunterActivityWindowState.HeaderAction.CLOSE,HunterActivityWindowState.headerAction(350,45,100,34,260,22));
        assertEquals(HunterActivityWindowState.HeaderAction.NONE,HunterActivityWindowState.headerAction(120,80,100,34,260,22));
    }

    @Test void dragPreservesGrabOffsetAndClampsToViewport(){
        assertEquals(180,HunterActivityWindowState.draggedX(200,20,800,260));
        assertEquals(90,HunterActivityWindowState.draggedY(100,10,450,22));
        assertEquals(4,HunterActivityWindowState.draggedX(-50,20,800,260));
        assertEquals(536,HunterActivityWindowState.draggedX(900,20,800,260));
        assertEquals(424,HunterActivityWindowState.draggedY(900,10,450,22));
    }

    @Test void centralizedCancelEndsAnActiveDrag(){
        net.townymap.TownyMapConfig config=new net.townymap.TownyMapConfig();
        config.hunterActivityWindowX=100;config.hunterActivityWindowY=34;config.hunterActivityWindowShown=true;
        assertTrue(HunterActivityOverlay.click(120,45,800,450,config));
        assertTrue(HunterActivityOverlay.draggingForTest());
        HunterActivityOverlay.cancelDrag();
        assertFalse(HunterActivityOverlay.draggingForTest());
    }
}

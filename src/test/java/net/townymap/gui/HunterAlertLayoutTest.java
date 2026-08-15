package net.townymap.gui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HunterAlertLayoutTest {
    @Test void centersAtCommonFullHdScale(){assertBounds(1920,400,760);}
    @Test void centersAtLargeAndUltrawideWidths(){assertBounds(2560,500,1030);assertBounds(3440,620,1410);}
    @Test void centersInSmallScaledGui(){assertBounds(960,320,320);assertBounds(640,420,110);}
    @Test void dynamicLocalizedWidthRemainsCentered(){assertBounds(960,517,221);}
    private static void assertBounds(int screen,int panel,int expectedX){var b=HunterAlertLayout.centered(screen,panel,12,40);assertEquals(expectedX,b.x());assertTrue(Math.abs(b.centerX()-screen/2)<=1);}
}

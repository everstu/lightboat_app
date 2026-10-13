package com.lightboat.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuPlacementTest {
    @Test fun draggingCannotLeaveVisibleArea() {
        assertEquals(MenuPlacement.Point(0f, 744f), MenuPlacement.clamp(-50f, 900f, 400, 800, 56))
        assertEquals(MenuPlacement.Point(344f, 0f), MenuPlacement.clamp(800f, -50f, 400, 800, 56))
    }

    @Test fun edgeDockingPreservesRelativeHeightAfterRotationOrKeyboardResize() {
        assertEquals(MenuPlacement.Point(344f, 372f), MenuPlacement.anchor(false, 0.5f, 400, 800, 56))
        assertEquals(MenuPlacement.Point(744f, 172f), MenuPlacement.anchor(false, 0.5f, 800, 400, 56))
        assertEquals(MenuPlacement.Point(0f, 122f), MenuPlacement.anchor(true, 0.5f, 400, 300, 56))
    }

    @Test fun tinyAreasAndInvalidFractionsStayBounded() {
        assertEquals(MenuPlacement.Point(0f, 0f), MenuPlacement.anchor(false, 1f, 20, 20, 56))
        assertEquals(MenuPlacement.Point(0f, 0f), MenuPlacement.clamp(100f, -20f, 20, 20, 56))
        assertEquals(MenuPlacement.Point(0f, 744f), MenuPlacement.anchor(true, 2f, 400, 800, 56))
    }
}

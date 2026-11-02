package com.lightboat.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptUsagePlacementTest {
    private val action = 96
    private val gap = 16

    @Test fun badgeSharesTheNameRowWhileBothStayLegible() {
        assertTrue(ScriptUsagePlacement.fitsInline(720, action, badgeWidth = 340, minimumNameWidth = 240, gap = gap))
        assertTrue(ScriptUsagePlacement.fitsInline(900, action, badgeWidth = 340, minimumNameWidth = 240, gap = gap))
    }

    @Test fun narrowEntriesMoveTheWholeBadgeBelowTheName() {
        assertFalse(ScriptUsagePlacement.fitsInline(560, action, badgeWidth = 340, minimumNameWidth = 240, gap = gap))
        assertFalse(ScriptUsagePlacement.fitsInline(720, action, badgeWidth = 520, minimumNameWidth = 480, gap = gap))
    }

    @Test fun theExactMinimumKeepsTheBadgeInlineAndOnePixelLessWrapsIt() {
        val available = action + gap + 340 + 240
        assertTrue(ScriptUsagePlacement.fitsInline(available, action, badgeWidth = 340, minimumNameWidth = 240, gap = gap))
        assertFalse(ScriptUsagePlacement.fitsInline(available - 1, action, badgeWidth = 340, minimumNameWidth = 240, gap = gap))
    }
}

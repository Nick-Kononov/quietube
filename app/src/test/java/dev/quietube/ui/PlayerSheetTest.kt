package dev.quietube.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.*
import org.junit.Test

class PlayerSheetTest {
    private val window = Size(1080f, 2400f)
    private val dock = Rect(0f, 2000f, 1080f, 2177f)

    @Test fun releasesFollowFlingsThenTheNearerEnd() {
        assertTrue(sheetTarget(.1f, 3f))
        assertFalse(sheetTarget(.9f, -3f))
        assertTrue(sheetTarget(.5f, 0f))
        assertFalse(sheetTarget(.49f, 1f))
        assertTrue(sheetTarget(.7f, -1f))
    }
    @Test fun miniPlayerLeavesWhenDraggedFarOrFlungOutwards() {
        assertTrue(dismissed(500f, 0f, 1000f))
        assertTrue(dismissed(-500f, 0f, 1000f))
        assertFalse(dismissed(300f, 0f, 1000f))
        assertTrue(dismissed(100f, 2000f, 1000f))
        assertFalse(dismissed(100f, -2000f, 1000f))
        assertFalse(dismissed(0f, 5000f, 1000f))
    }
    @Test fun videoStaysSixteenByNineBetweenDockAndFullPlayer() {
        val mini = miniVideoRect(dock)
        val full = fullVideoRect(window, 120f, videoOnly = false)
        assertEquals(Rect(0f, 120f, 1080f, 727.5f), full)
        assertEquals(177f * 16f / 9f, mini.width, .01f)
        for (step in 0..10) {
            val video = lerpRect(mini, full, step / 10f)
            assertEquals(16f / 9f, video.width / video.height, .001f)
        }
        assertEquals(mini, lerpRect(mini, full, 0f))
        assertEquals(full, lerpRect(mini, full, 1f))
    }
    @Test fun fullscreenVideoFillsTheWindow() {
        assertEquals(Rect(0f, 0f, 1080f, 2400f), fullVideoRect(window, 120f, videoOnly = true))
    }
    @Test fun dragsClampAndSettleWithoutAnimation() {
        val sheet = PlayerSheetState(expanded = true).apply { animate = false }
        val scope = TestScope()
        sheet.drag(-.3f)
        assertEquals(.7f, sheet.fraction, .0001f)
        assertTrue("A drag alone doesn't change the settled state", sheet.expanded)
        sheet.drag(5f)
        assertEquals(1f, sheet.fraction, 0f)
        sheet.drag(-.8f)
        sheet.release(scope, 0f)
        assertFalse(sheet.expanded)
        assertEquals(0f, sheet.fraction, 0f)
        sheet.release(scope, 3f)
        assertTrue(sheet.expanded)
        assertEquals(1f, sheet.fraction, 0f)
    }
    @Test fun dismissingClosesOnlyPastTheThreshold() {
        val sheet = PlayerSheetState(expanded = false).apply { animate = false }
        val scope = TestScope()
        var closed = 0
        sheet.dismissDrag(200f)
        sheet.dismissRelease(scope, 0f, 1000f) { closed++ }
        assertEquals(0, closed)
        assertEquals(0f, sheet.dismissOffset, 0f)
        sheet.dismissDrag(-600f)
        sheet.dismissRelease(scope, 0f, 1000f) { closed++ }
        assertEquals(1, closed)
        assertEquals(-1000f, sheet.dismissOffset, 0f)
        sheet.show(true)
        assertEquals(0f, sheet.dismissOffset, 0f)
        assertEquals(1f, sheet.fraction, 0f)
    }
    @Test fun backPreviewMovesWithoutSettling() {
        val sheet = PlayerSheetState(expanded = true).apply { animate = false }
        sheet.preview(.6f)
        assertEquals(.6f, sheet.fraction, 0f)
        assertTrue(sheet.expanded)
        sheet.settle(TestScope(), true)
        assertEquals(1f, sheet.fraction, 0f)
    }
}

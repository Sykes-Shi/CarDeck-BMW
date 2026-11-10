package io.github.shaonialife.cardeck.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LandscapeMapTouchTest {
    @Test fun wideBmwMapUsesItsVisibleTopLeftAndBothFarEdges() {
        fun point(x: Int, y: Int) = landscapeMapTouch(x, y, 168, false, 1232, 688, 0f)
        assertEquals(0 to 0, point(184, 16))
        assertEquals(1231 to 687, point(1415, 703))
        assertNull(point(183, 16))
        assertNull(point(184, 15))
        assertNull(point(1416, 40))
        assertNull(point(400, 704))
    }

    @Test fun dragCommitUsesNewWidthAndFullscreenKeepsOriginalOrigin() {
        assertNull(landscapeMapTouch(1200, 250, 168, false, 800, 688, 0f))
        assertEquals(1016 to 234, landscapeMapTouch(1200, 250, 168, false, 1232, 688, 0f))
        assertEquals(0 to 0, landscapeMapTouch(178, 16, 168, false, 1232, 688, 6f))
        assertEquals(0 to 0, landscapeMapTouch(168, 0, 168, true, 1232, 688, 6f))
        assertEquals(1751 to 719, landscapeMapTouch(1919, 719, 168, true, 1232, 688, 0f))
    }
}

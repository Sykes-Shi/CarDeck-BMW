package io.github.shaonialife.cardeck.launcher

import io.github.shaonialife.cardeck.launcher.ControllerFocusTraversal.Point
import io.github.shaonialife.cardeck.launcher.ControllerFocusTraversal.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerFocusTraversalTest {
    private val targets = listOf(Point(10, 10), Point(30, 10), Point(50, 10), Point(10, 40), Point(30, 40))

    @Test fun firstTurnHighlightsAnItemThenOnlyCrossesAPageAtItsEdge() {
        assertEquals(Step(0), ControllerFocusTraversal.move(targets, -1, true))
        assertEquals(Step(4), ControllerFocusTraversal.move(targets, -1, false))
        assertEquals(Step(3), ControllerFocusTraversal.move(targets, 4, false))
        assertEquals(Step(4, 1), ControllerFocusTraversal.move(targets, 4, true))
        assertEquals(Step(0, -1), ControllerFocusTraversal.move(targets, 0, false))
    }

    @Test fun fourDirectionsUseTheVisibleCellsOnTheCurrentPage() {
        assertEquals(Step(1), ControllerFocusTraversal.directional(targets, 0, true, true))
        assertEquals(Step(0), ControllerFocusTraversal.directional(targets, 1, true, false))
        assertEquals(Step(4), ControllerFocusTraversal.directional(targets, 1, false, true))
        assertEquals(Step(1), ControllerFocusTraversal.directional(targets, 4, false, false))
        assertEquals(Step(4), ControllerFocusTraversal.directional(targets, 4, false, true))
        assertEquals(Step(2, 1), ControllerFocusTraversal.directional(targets, 2, true, true))
    }
}

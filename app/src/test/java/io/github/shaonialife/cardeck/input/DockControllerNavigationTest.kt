package io.github.shaonialife.cardeck.input

import io.github.shaonialife.cardeck.input.DockControllerNavigation.Route
import org.junit.Assert.*
import org.junit.Test

class DockControllerNavigationTest {
    @Test fun singleLeftAndOrdinaryConfirmBelongToTheCurrentApp() {
        val nav = DockControllerNavigation()
        assertEquals(Route.CONTENT, nav.route(CarControllerAction.LEFT, 10))
        assertEquals(Route.CONTENT, nav.route(CarControllerAction.CONFIRM, 220))
        assertEquals(Route.CONTENT, nav.route(CarControllerAction.LEFT, 250))
        assertEquals(Route.CONTENT, nav.route(CarControllerAction.LEFT, 1050))
        assertEquals(Route.ENTER, nav.route(CarControllerAction.LEFT, 1410))
        assertTrue(nav.active)
        assertEquals(Route.ACTIVATE, nav.route(CarControllerAction.CONFIRM, 1620))
        assertFalse(nav.active)
        assertEquals(Route.CONTENT, nav.route(CarControllerAction.NEXT, 1680))
    }

    @Test fun touchLostOwnershipOrUnrelatedActionsBreakAPair() {
        val nav = DockControllerNavigation()
        nav.route(CarControllerAction.LEFT, 100)
        nav.reset()
        assertEquals(Route.CONTENT, nav.route(CarControllerAction.LEFT, 170))
        assertEquals(Route.CONTENT, nav.route(CarControllerAction.PREVIOUS, 200))
        assertEquals(Route.CONTENT, nav.route(CarControllerAction.LEFT, 220))
        assertEquals(Route.ENTER, nav.route(CarControllerAction.LEFT, 470))
        nav.reset()
        assertEquals(Route.CONTENT, nav.route(CarControllerAction.CONFIRM, 500))
    }

    @Test fun aHeldAxisIsNotADoublePressAndBothExitKeysReturnToContent() {
        val hex = "05010908a1010509090115002501750195018102050c7501950181010a2402150025017501950181027505950181010501093009311581257f750895028102050109381581257f750895018106c0"
        val decoder = requireNotNull(BmwRotaryHid.fromDescriptor(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()))
        val nav = DockControllerNavigation()
        val routes = mutableListOf<Route>()
        val held = byteArrayOf(0, -127, 0, 0)
        for ((i, report) in listOf(held, held, ByteArray(4), held).withIndex()) {
            decoder.decode(report)!!.forEach { routes.add(nav.route(it.action, 100L + 80 * i)) }
        }
        assertEquals(listOf(Route.CONTENT, Route.ENTER), routes)
        assertEquals(Route.PREVIOUS, nav.route(CarControllerAction.UP, 540))
        assertEquals(Route.NEXT, nav.route(CarControllerAction.NEXT, 570))
        assertEquals(Route.LEAVE, nav.route(CarControllerAction.RIGHT, 590))
        nav.route(CarControllerAction.LEFT, 610)
        nav.route(CarControllerAction.LEFT, 710)
        assertEquals(Route.LEAVE, nav.route(CarControllerAction.BACK, 750))
        assertFalse(nav.active)
    }

    @Test fun dockSelectionTraversesAllIconsInEitherDirection() {
        val nav = DockControllerNavigation()
        assertEquals(0, nav.advance(3, 4, true, 1))
        assertEquals(3, nav.advance(0, 4, false, 1))
        assertEquals(2, nav.advance(0, 4, true, 2))
        assertEquals(0, nav.advance(0, 1, true, 8))
    }
}

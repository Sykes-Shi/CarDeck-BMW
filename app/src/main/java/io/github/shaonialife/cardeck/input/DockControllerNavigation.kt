package io.github.shaonialife.cardeck.input

/** Two distinct HID left edges enter the dock. A held stick produces only one edge. */
internal class DockControllerNavigation {
    enum class Route { CONTENT, ENTER, PREVIOUS, NEXT, ACTIVATE, LEAVE, STAY }

    var active = false
        private set
    private var firstLeft: Long? = null

    fun reset() {
        firstLeft = null
        active = false
    }

    fun route(action: CarControllerAction, now: Long): Route {
        if (!active) {
            val last = firstLeft
            firstLeft = now.takeIf { action == CarControllerAction.LEFT }
            if (firstLeft == null || last == null || now - last !in 1..750L) return Route.CONTENT
            firstLeft = null
            active = true
            return Route.ENTER
        }
        return when (action) {
            CarControllerAction.PREVIOUS, CarControllerAction.UP -> Route.PREVIOUS
            CarControllerAction.NEXT, CarControllerAction.DOWN -> Route.NEXT
            CarControllerAction.CONFIRM -> Route.ACTIVATE.also { reset() }
            CarControllerAction.RIGHT, CarControllerAction.BACK -> Route.LEAVE.also { reset() }
            CarControllerAction.LEFT -> Route.STAY
        }
    }

    fun advance(selected: Int, count: Int, forward: Boolean, steps: Int): Int {
        require(count > 0)
        val delta = steps.coerceIn(1, 8) * if (forward) 1 else -1
        return Math.floorMod(selected + delta, count)
    }
}

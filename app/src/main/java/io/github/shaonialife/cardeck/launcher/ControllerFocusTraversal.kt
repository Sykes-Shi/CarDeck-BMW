package io.github.shaonialife.cardeck.launcher

/** Relative traversal crosses pages, directional movement uses visible target geometry. */
internal object ControllerFocusTraversal {
    data class Point(val x: Int, val y: Int)
    data class Step(val index: Int, val pageDelta: Int = 0)

    fun move(points: List<Point>, current: Int, forward: Boolean): Step {
        if (points.isEmpty()) return Step(-1)
        if (current !in points.indices) return Step(if (forward) 0 else points.lastIndex)
        val next = current + if (forward) 1 else -1
        return if (next in points.indices) Step(next) else Step(current, if (forward) 1 else -1)
    }

    fun directional(points: List<Point>, current: Int, horizontal: Boolean, positive: Boolean): Step {
        if (points.isEmpty()) return Step(-1)
        if (current !in points.indices) return Step(if (positive) 0 else points.lastIndex)
        val from = points[current]
        val sign = if (positive) 1 else -1
        val next = points.indices.filter { index ->
            val p = points[index]
            val ahead = sign * (if (horizontal) p.x - from.x else p.y - from.y)
            val cross = kotlin.math.abs(if (horizontal) p.y - from.y else p.x - from.x)
            ahead > 0 && ahead >= cross
        }.minByOrNull { index ->
            val dx = (points[index].x - from.x).toLong()
            val dy = (points[index].y - from.y).toLong()
            if (horizontal) dx * dx + 4L * dy * dy else 4L * dx * dx + dy * dy
        }
        return Step(next ?: current, if (next == null && horizontal) sign else 0)
    }
}

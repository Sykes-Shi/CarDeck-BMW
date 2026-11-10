package io.github.shaonialife.cardeck.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotaryBrightnessProbeTest {
    @Test fun staticSidebarAndChangingAppHaveSeparateNumbersWithoutConsumingTurns() {
        var time = 0L
        var level = 220
        var reads = 0
        val probe = RotaryBrightnessProbe({ time }) { width, _, row ->
            reads++
            repeat(width) { x ->
                val pixel = if (x < 4) 80 else level
                for (c in 0..2) row.put(x * 4 + c, pixel.toByte())
            }
            true
        }
        val logs = mutableListOf<String>()
        fun frame() = probe.onFrame(12, 8, 4, 2, false, logs::add)
        frame()
        assertEquals(0, reads)
        probe.start()
        frame()
        assertTrue(logs.single().endsWith("dockLuma=80 contentLuma=220"))
        probe.turn(2)
        probe.turn(-1)
        level = 110
        time = 800
        frame()
        assertTrue(logs.last().endsWith("forward=2 back=1 dockLuma=80 contentLuma=110"))
        time = 12_000
        frame()
        assertEquals(4, reads)
        assertEquals(2, logs.size)
    }
}

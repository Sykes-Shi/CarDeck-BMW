package io.github.shaonialife.cardeck.render

import android.opengl.GLES20
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Manually armed on a single projection. Only numeric light levels are emitted. */
internal class RotaryBrightnessProbe(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val readRow: (Int, Int, ByteBuffer) -> Boolean = { width, y, row ->
        GLES20.glReadPixels(0, y, width, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, row)
        GLES20.glGetError() == GLES20.GL_NO_ERROR
    },
) {
    private class SampleRun(val start: Long) {
        val forward = AtomicInteger()
        val backward = AtomicInteger()
        var last = -1_000L // GL thread only, relative to start.
    }

    private val run = AtomicReference<SampleRun?>()

    fun start() { run.set(SampleRun(now())) }

    fun turn(steps: Int) {
        val active = run.get() ?: return
        if (steps > 0) active.forward.addAndGet(steps) else active.backward.addAndGet(-steps)
    }

    fun onFrame(width: Int, height: Int, dockWidth: Int, dockHeight: Int, bottom: Boolean, log: (String) -> Unit) {
        val active = run.get() ?: return
        val elapsed = now() - active.start
        if (elapsed >= 12_000) {
            // Leave a concurrently started sample intact.
            run.compareAndSet(active, null)
            return
        }
        if (elapsed - active.last < 750 || width < 2 || height < 2) return
        active.last = elapsed
        val row = ByteBuffer.allocateDirect(width * 4)
        // Read two lines of the frame already being encoded. No screenshot or pixel data is exported.
        val split = dockWidth.coerceIn(1, width - 1)
        val railHeight = dockHeight.coerceIn(1, height - 1)
        val rows = if (bottom) intArrayOf(railHeight / 2, (railHeight + height) / 2)
            else intArrayOf(height / 3, height * 2 / 3)
        var dock = 0L
        var app = 0L
        var dockCount = 0
        var appCount = 0
        for ((index, y) in rows.withIndex()) {
            val readable = runCatching { readRow(width, y, row) }.getOrDefault(false)
            if (!readable) {
                run.compareAndSet(active, null)
                log("ROTARY-LIGHT: projection sample unavailable")
                return
            }
            for (x in 0 until width step 4) {
                val offset = x * 4
                val r = row.get(offset).toInt() and 255
                val g = row.get(offset + 1).toInt() and 255
                val b = row.get(offset + 2).toInt() and 255
                val luma = (54 * r + 183 * g + 19 * b) ushr 8
                val inDock = if (bottom) index == 0 else x < split
                if (inDock) {
                    dock += luma
                    dockCount++
                } else {
                    app += luma
                    appCount++
                }
            }
        }
        if (dockCount > 0 && appCount > 0) {
            log("ROTARY-LIGHT: tMs=$elapsed forward=${active.forward.getAndSet(0)} back=${active.backward.getAndSet(0)} " +
                "dockLuma=${dock / dockCount} contentLuma=${app / appCount}")
        }
    }
}

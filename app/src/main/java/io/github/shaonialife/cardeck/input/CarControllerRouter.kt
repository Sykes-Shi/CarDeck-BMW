package io.github.shaonialife.cardeck.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import io.github.shaonialife.cardeck.launcher.DockActivity
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.shizuku.CarInputClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Serialize physical control reports away from the AirPlay event reader. */
internal object CarControllerRouter {
    private val worker = ThreadPoolExecutor(1, 1, 10L, TimeUnit.SECONDS, ArrayBlockingQueue(20),
        { task -> Thread(task, "car-controller").apply { isDaemon = true } })
        .apply { allowCoreThreadTimeOut(true) }
    private val main = Handler(Looper.getMainLooper())
    private val waitingOnUi = AtomicInteger()
    @Volatile private var contentEpoch = 0L

    private fun targetDisplay(): Int = when {
        VirtualLauncher.mapFullscreen && VirtualLauncher.canRouteMapTouch() -> VirtualLauncher.mapDisplayId
        VirtualLauncher.dashboardMode -> VirtualLauncher.gridDisplayId
        else -> VirtualLauncher.activeAppDisplayId()
    }

    fun submit(input: CarControllerInput, live: () -> Boolean) {
        val display = targetDisplay()
        if (display <= 0 || !live()) return
        val generation = VirtualLauncher.sessionGeneration()
        val navigation = VirtualLauncher.navigationRevision()
        val presentation = VirtualLauncher.presentationRevision()
        val arrives = SystemClock.uptimeMillis()
        fun sameScene() = live() && display == targetDisplay() && VirtualLauncher.appContext() != null &&
            VirtualLauncher.isCurrentSession(generation) && VirtualLauncher.navigationRevision() == navigation &&
            VirtualLauncher.presentationRevision() == presentation
        fun current() = sameScene() && SystemClock.uptimeMillis() - arrives < 800
        if (waitingOnUi.incrementAndGet() > 20) {
            waitingOnUi.decrementAndGet()
            return
        }
        main.post {
            waitingOnUi.decrementAndGet()
            if (!current()) return@post
            when (input.action) {
                CarControllerAction.NEXT -> VirtualLauncher.noteRotaryTurn(input.steps)
                CarControllerAction.PREVIOUS -> VirtualLauncher.noteRotaryTurn(-input.steps)
                else -> Unit
            }
            if (DockActivity.handleController(input, arrives, ::sameScene, live)) {
                contentEpoch++
                return@post
            }
            // A delayed app-directed turn must not land behind the newly focused dock.
            val epoch = contentEpoch
            submitToContent(input, display) { epoch == contentEpoch && current() }
        }
    }

    fun onTouchDown(inDock: Boolean) {
        main.post { DockActivity.onControllerTouch(inDock) }
    }

    private fun submitToContent(input: CarControllerInput, display: Int, current: () -> Boolean) {
        try {
            worker.execute {
                if (!current()) return@execute
                if (input.action == CarControllerAction.BACK && VirtualLauncher.mapFullscreen) {
                    main.post { if (current() && VirtualLauncher.mapFullscreen) VirtualLauncher.onHomePressed() }
                    return@execute
                }
                val key = when (input.action) {
                    CarControllerAction.NEXT, CarControllerAction.PREVIOUS -> KeyEvent.KEYCODE_TAB
                    CarControllerAction.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                    CarControllerAction.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                    CarControllerAction.UP -> KeyEvent.KEYCODE_DPAD_UP
                    CarControllerAction.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                    CarControllerAction.CONFIRM -> KeyEvent.KEYCODE_DPAD_CENTER
                    CarControllerAction.BACK -> KeyEvent.KEYCODE_BACK
                }
                val service = CarInputClient.service ?: return@execute
                repeat(input.steps.coerceIn(1, 8)) {
                    if (!current()) return@execute
                    val sent = runCatching { service.sendNavigationKey(display, key, input.action == CarControllerAction.PREVIOUS) }.getOrDefault(false)
                    if (!sent) {
                        VirtualLauncher.logSink?.invoke("ROTARY: 未投递虚拟屏操作 display=$display key=$key")
                        return@execute
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Do not replay an expired turn or press after a blocked virtual window recovers.
        }
    }
}

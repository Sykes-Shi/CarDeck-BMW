package io.github.shaonialife.cardeck.ui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import io.github.shaonialife.cardeck.shizuku.Shell
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** 手机仍保持解锁的省电暗幕；不关闭物理面板、不解除锁屏，不改全局亮度。 */
object ScreenDimmer {
    private const val TAG = "ScreenDimmer"
    private const val IDLE_TIMEOUT_MS = 45_000L
    private const val BACKUP_KEY = "screen_off_timeout_backup"

    /** 只控制自动暗幕，沿用原 alwaysOn 偏好。 */
    @Volatile var enabled: Boolean = true
    @Volatile var logSink: ((String) -> Unit)? = null
    @Volatile private var sessionActive = false
    @Volatile private var phoneNavigationMode = false
    @Volatile private var manualRequested = false
    @Volatile private var dimShown = false
    @Volatile private var overlayReady = false
    @Volatile private var attachment: Attachment? = null
    private val transitions = AtomicLong()
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val timeoutExecutor by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "screen-timeout").apply { isDaemon = true } }
    }
    private val timeoutLock = Any()
    private var timeoutController: ScreenTimeoutController? = null

    private class Attachment(val context: Context) {
        var wm: WindowManager? = null
        var dimView: View? = null
        var watchView: View? = null
        var receiver: BroadcastReceiver? = null
        val showRunnable = Runnable { if (attachment === this) showDim(this, force = false) }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    private fun postOwned(owner: Attachment, block: () -> Unit) = onMain {
        if (attachment === owner) block()
    }

    private fun emit(context: Context, message: String) {
        val line = "DIMMER: $message"
        Log.i(TAG, line)
        val sink = logSink
        if (sink != null) sink(line) else Bg.run("dimmer-log") { DebugFileLog.append(context, line) }
    }

    private fun timeout(context: Context): ScreenTimeoutController = synchronized(timeoutLock) {
        timeoutController ?: run {
            val ctx = context.applicationContext
            val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            ScreenTimeoutController(
                backend = object : ScreenTimeoutController.Backend {
                    override fun readTimeout(): Int? {
                        val result = Shell.exec("settings get system screen_off_timeout")
                        return if (result.ok) result.output.trim().toIntOrNull()?.takeIf { it > 0 } else null
                    }
                    override fun writeTimeout(value: Int): Boolean =
                        Shell.exec("settings put system screen_off_timeout $value").ok
                    override fun readBackup(): Int? =
                        if (prefs.contains(BACKUP_KEY)) prefs.getInt(BACKUP_KEY, 60_000) else null
                    override fun clearBackup(): Boolean = prefs.edit().remove(BACKUP_KEY).commit()
                },
                execute = { block -> timeoutExecutor.execute { block() } },
                log = { emit(ctx, it) },
            ).also { timeoutController = it }
        }
    }

    /** 仅恢复 V18 等旧版本遗留值；无备份时不读写系统超时，失败保留备份供下次重试。 */
    fun recoverTimeout(context: Context) = timeout(context.applicationContext).requestRestore()

    fun setSessionActive(active: Boolean) {
        val (owner, revision) = synchronized(this) {
            sessionActive = active
            if (!active) manualRequested = false
            (attachment ?: return) to transitions.incrementAndGet()
        }
        postOwned(owner) {
            if (transitions.get() != revision) return@postOwned
            if (active && enabled) schedule(owner) else {
                mainHandler.removeCallbacks(owner.showRunnable)
                hideDim(owner)
            }
            emit(owner.context, "session_${if (active) "active" else "ended"} ${diagnosticSnapshot(owner.context)}")
        }
    }

    fun attach(context: Context) {
        val owner = synchronized(this) {
            attachment ?: Attachment(context.applicationContext).also { attachment = it }
        }
        recoverTimeout(owner.context)
        postOwned(owner) {
            ensureOverlay(owner)
            registerScreenEvents(owner)
            if (enabled && sessionActive) schedule(owner)
        }
    }

    /** 应用自动暗幕偏好；不改变手机的系统超时或锁屏设置。 */
    fun applyOptions() {
        val (owner, revision) = synchronized(this) {
            manualRequested = false
            (attachment ?: return) to transitions.incrementAndGet()
        }
        postOwned(owner) {
            if (transitions.get() != revision) return@postOwned
            mainHandler.removeCallbacks(owner.showRunnable)
            hideDim(owner)
            ensureOverlay(owner)
            if (enabled && sessionActive) schedule(owner)
            emit(owner.context, "options_applied ${diagnosticSnapshot(owner.context)}")
        }
    }

    fun refreshOptions() = applyOptions()

    /** 连接时快照的手机导航用法仅暂停自动暗幕，不改用户偏好；手动暗幕仍可使用。 */
    fun setPhoneNavigationMode(active: Boolean) {
        if (phoneNavigationMode == active) return
        phoneNavigationMode = active
        applyOptions()
    }

    fun disableDim() {
        enabled = false
        applyOptions()
    }

    fun shouldAutoDim(
        enabled: Boolean,
        sessionActive: Boolean,
        attached: Boolean,
        phoneNavigation: Boolean = false,
    ): Boolean = enabled && sessionActive && attached && !phoneNavigation

    internal fun mayShowUnlocked(interactive: Boolean?, keyguardLocked: Boolean?, deviceLocked: Boolean?): Boolean =
        interactive == true && keyguardLocked == false && deviceLocked == false

    fun detach() {
        val old = synchronized(this) {
            attachment.also {
                attachment = null
                transitions.incrementAndGet()
                sessionActive = false
                phoneNavigationMode = false
                manualRequested = false
                dimShown = false
                overlayReady = false
            }
        }
        if (old == null) return
        // 只清理捕获的旧窗口；快速重连的窗口及事件注册不受影响。
        onMain {
            mainHandler.removeCallbacks(old.showRunnable)
            old.dimView?.let { view -> runCatching { old.wm?.removeView(view) } }
            old.watchView?.let { view -> runCatching { old.wm?.removeView(view) } }
            old.receiver?.let { receiver -> runCatching { old.context.unregisterReceiver(receiver) } }
            old.dimView = null
            old.watchView = null
            old.receiver = null
            old.wm = null
            emit(old.context, "detached")
        }
    }

    fun isAttached(): Boolean = attachment != null && overlayReady

    /** 手动暗幕只在已解锁且屏幕可交互时显示；点击撤掉并恢复窗口亮度。 */
    fun enterDimNow() {
        val owner = attachment ?: return
        postOwned(owner) {
            ensureOverlay(owner)
            showDim(owner, force = true)
        }
    }

    fun onUserInteraction() {
        val owner = attachment ?: return
        postOwned(owner) {
            if (dimShown) hideDim(owner)
            if (enabled && sessionActive) schedule(owner)
        }
    }

    private fun ensureOverlay(owner: Attachment) {
        if (attachment !== owner) return
        if (!Settings.canDrawOverlays(owner.context)) {
            synchronized(this) { if (attachment === owner) overlayReady = false }
            return
        }
        if (owner.wm == null) owner.wm = owner.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        synchronized(this) { if (attachment === owner) overlayReady = owner.wm != null }
        if (owner.watchView != null) return
        val view = View(owner.context).apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) {
                    if (attachment === owner) onUserInteraction()
                }
                false
            }
        }
        val params = WindowManager.LayoutParams(
            1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { owner.wm?.addView(view, params) }
            .onSuccess { owner.watchView = view }
            .onFailure { emit(owner.context, "watcher_add_failed=${it.javaClass.simpleName}") }
    }

    private fun schedule(owner: Attachment) {
        mainHandler.removeCallbacks(owner.showRunnable)
        if (attachment === owner && shouldAutoDim(enabled, sessionActive, overlayReady, phoneNavigationMode) && screenState(owner.context).allowsDim()) {
            mainHandler.postDelayed(owner.showRunnable, IDLE_TIMEOUT_MS)
        }
    }

    private fun showDim(owner: Attachment, force: Boolean) {
        val revision = transitions.get()
        if (attachment !== owner || (!force && !shouldAutoDim(enabled, sessionActive, overlayReady, phoneNavigationMode))) return
        if (!screenState(owner.context).allowsDim()) {
            emit(owner.context, "show_skipped_locked_or_not_interactive ${diagnosticSnapshot(owner.context)}")
            return
        }
        val wm = owner.wm ?: return
        if (owner.dimView != null) return
        val view = View(owner.context).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            contentDescription = "省电暗幕，手机保持解锁，点击恢复"
            setOnClickListener { if (attachment === owner) onUserInteraction() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply {
            screenBrightness = 0f
            buttonBrightness = 0f
        }
        runCatching { wm.addView(view, params) }.onSuccess {
            owner.dimView = view
            val accepted = synchronized(this) {
                if (attachment === owner && transitions.get() == revision) {
                    dimShown = true
                    if (force) manualRequested = true
                    true
                } else false
            }
            if (!accepted) {
                owner.dimView = null
                runCatching { wm.removeView(view) }
                return@onSuccess
            }
            mainHandler.removeCallbacks(owner.showRunnable)
            emit(owner.context, "shown black=opaque windowBrightness=0 ${diagnosticSnapshot(owner.context)}")
        }.onFailure { emit(owner.context, "show_failed=${it.javaClass.simpleName}") }
    }

    private fun hideDim(owner: Attachment) {
        val view = owner.dimView ?: return
        owner.dimView = null
        runCatching { owner.wm?.removeView(view) }
        synchronized(this) {
            if (attachment === owner) {
                dimShown = false
                manualRequested = false
            }
        }
        emit(owner.context, "hidden windowBrightness=system")
    }

    private data class ScreenState(val interactive: Boolean?, val keyguard: Boolean?, val deviceLocked: Boolean?, val displayState: Int?) {
        fun allowsDim(): Boolean = mayShowUnlocked(interactive, keyguard, deviceLocked)
    }

    private fun screenState(context: Context): ScreenState {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return ScreenState(
            runCatching { power?.isInteractive }.getOrNull(),
            runCatching { keyguard?.isKeyguardLocked }.getOrNull(),
            runCatching { keyguard?.isDeviceLocked }.getOrNull(),
            runCatching { displays?.getDisplay(Display.DEFAULT_DISPLAY)?.state }.getOrNull(),
        )
    }

    /** 固定字段，不读取锁屏内容、通知、活动名称或用户数据。 */
    fun diagnosticSnapshot(context: Context): String {
        val state = screenState(context)
        val pending = context.getSharedPreferences("settings", Context.MODE_PRIVATE).contains(BACKUP_KEY)
        return "interactive=${state.interactive} keyguard=${state.keyguard} deviceLocked=${state.deviceLocked} " +
            "displayState=${state.displayState} autoDim=$enabled session=$sessionActive phoneNavigation=$phoneNavigationMode " +
            "overlayReady=$overlayReady dimShown=$dimShown manual=$manualRequested timeoutRestorePending=$pending"
    }

    private fun registerScreenEvents(owner: Attachment) {
        if (attachment !== owner || owner.receiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (attachment !== owner) return
                val event = when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> "screen_off"
                    Intent.ACTION_SCREEN_ON -> "screen_on"
                    Intent.ACTION_USER_PRESENT -> "user_present"
                    else -> return
                }
                mainHandler.removeCallbacks(owner.showRunnable)
                // 遵从真实锁屏；不唤醒、不解除 keyguard、不盖住解锁界面。
                if (!screenState(owner.context).allowsDim()) hideDim(owner) else if (enabled && sessionActive) schedule(owner)
                emit(owner.context, "event=$event ${diagnosticSnapshot(owner.context)}")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { ContextCompat.registerReceiver(owner.context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess {
                owner.receiver = receiver
                emit(owner.context, "observer_attached ${diagnosticSnapshot(owner.context)}")
            }.onFailure { emit(owner.context, "observer_failed=${it.javaClass.simpleName}") }
    }
}

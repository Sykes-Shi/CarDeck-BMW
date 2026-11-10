package io.github.shaonialife.cardeck.input

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.bt.TargetDevicePrefs
import io.github.shaonialife.cardeck.launcher.CarShortcut
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import java.security.MessageDigest
import java.util.Locale

/** Mapping scope is captured on projection start, not when the phone happens to receive a key. */
internal object CarShortcutLearning {
    private data class Session(
        val generation: Long, val target: String?, val prefs: SharedPreferences?, val calibration: ShortcutCalibration, var attempt: Long = 0,
    )

    private var session: Session? = null

    private val addressFormat = Regex("""(?:[0-9A-F]{2}:){5}[0-9A-F]{2}""")
    private val unassigned = setOf("00:00:00:00:00:00", "02:00:00:00:00:00", "FF:FF:FF:FF:FF:FF")
    private fun target(ctx: Context) = TargetDevicePrefs.get(ctx)?.address?.uppercase(Locale.ROOT)
        ?.takeIf { addressFormat.matches(it) && it !in unassigned }
    private fun prefs(ctx: Context, target: String): SharedPreferences {
        val id = MessageDigest.getInstance("SHA-256").digest(target.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return ctx.getSharedPreferences("bmw_keys_$id", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun onProjectionStarted(ctx: Context, generation: Long) {
        if (!VirtualLauncher.isCurrentSession(generation) || session?.generation == generation) return
        val target = target(ctx)
        val store = target?.let { prefs(ctx, it) }
        val saved = CarShortcut.values().mapNotNull { key -> store?.getString(key.name, null)?.let { key to it } }.toMap()
        session?.calibration?.cancel(disconnected = true)
        session = Session(generation, target, store,
            ShortcutCalibration(saved, store?.getStringSet("conflicts", null).orEmpty(), SessionController::appendLog))
    }

    @Synchronized fun onProjectionEnded() {
        session?.calibration?.cancel(disconnected = true)
        session = null
    }

    private fun save(s: Session) {
        val edit = s.prefs?.edit() ?: return
        for (action in CarShortcut.values()) {
            val token = s.calibration.mapping(action)
            if (token == null) edit.remove(action.name) else edit.putString(action.name, token)
        }
        edit.putStringSet("conflicts", s.calibration.conflicts()).apply()
    }

    private fun <T> use(ctx: Context, generation: Long, requireScreen: Boolean = true, block: (Session, Long) -> T): T? {
        // Check the receiver mode outside our monitor; protocol handling owns its own locks.
        val screen = !requireScreen || SessionController.shortcutCalibrationGeneration() == generation
        val selected = target(ctx)
        return synchronized(this) {
            val s = session?.takeIf { it.generation == generation } ?: return@synchronized null
            if (s.target != selected || !VirtualLauncher.isCurrentSession(generation)) {
                s.calibration.cancel(disconnected = true)
                return@synchronized null
            }
            if (!screen) return@synchronized null
            val now = SystemClock.elapsedRealtime()
            val before = s.calibration.revision
            s.calibration.tick(now)
            block(s, now).also { if (before != s.calibration.revision) save(s) }
        }
    }

    fun offer(ctx: Context, generation: Long, token: String, defaultAction: CarShortcut? = null): CarShortcut? =
        if (!ShortcutCalibration.safe(token)) null else use(ctx, generation) { s, now -> s.calibration.offer(token, now, defaultAction) }

    fun isCalibrating(generation: Long): Boolean {
        val ctx = VirtualLauncher.appContext() ?: return false
        return use(ctx, generation, requireScreen = false) { s, _ -> s.calibration.state?.active == true } == true
    }

    @Synchronized fun isObserving(generation: Long): Boolean =
        session?.let { it.generation == generation && VirtualLauncher.isCurrentSession(generation) &&
            it.calibration.observing(SystemClock.elapsedRealtime()) } == true

    @Synchronized fun observe(generation: Long, kind: String) {
        if (isObserving(generation)) session?.calibration?.observe(kind, SystemClock.elapsedRealtime())
    }

    fun labels(ctx: Context, generation: Long): Set<CarShortcut>? =
        use(ctx, generation) { s, _ -> CarShortcut.values().filterTo(mutableSetOf()) { s.calibration.mapping(it) != null } }
    fun isTemporary(generation: Long): Boolean = synchronized(this) {
        session?.takeIf { it.generation == generation }?.prefs == null
    }
    fun start(ctx: Context, generation: Long, key: CarShortcut): Long? =
        use(ctx, generation) { s, now -> s.calibration.start(key, now); ++s.attempt }
    fun confirm(ctx: Context, generation: Long, attempt: Long) {
        use<Unit>(ctx, generation) { s, now -> if (s.attempt == attempt) s.calibration.confirm(now) }
    }
    fun status(ctx: Context, generation: Long, attempt: Long) =
        use(ctx, generation, requireScreen = false) { s, _ -> s.calibration.state.takeIf { s.attempt == attempt } }
    fun reset(ctx: Context, generation: Long) = use(ctx, generation) { s, _ -> s.calibration.reset() }
    @Synchronized fun cancel(generation: Long, attempt: Long) {
        session?.takeIf { it.generation == generation && it.attempt == attempt }?.calibration?.cancel()
    }
}

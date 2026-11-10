package io.github.shaonialife.cardeck.input

import io.github.shaonialife.cardeck.launcher.CarShortcut

/** Two bounded, separately armed windows. Only distinct, prefiltered opaque tokens may enter. */
internal class ShortcutCalibration(initial: Map<CarShortcut, String>, conflicts: Set<String>, private val log: (String) -> Unit = {}) {
    enum class Phase { FIRST, CONFIRM, SECOND, SAVED, DIAGNOSTIC, TIMEOUT, AMBIGUOUS, DIFFERENT, SHARED }
    data class State(val key: CarShortcut, val phase: Phase, val deadline: Long = 0, val observed: Set<String> = emptySet(),
                     val diagnosticOnly: Boolean = false) {
        val capturing get() = phase == Phase.FIRST || phase == Phase.SECOND
        val active get() = capturing || phase == Phase.CONFIRM
    }

    private val blocked = conflicts.filterTo(mutableSetOf(), ::safe)
    private val mappings = initial.filterValues(::safe).toMutableMap()
    var state: State? = null
        private set
    private var first: String? = null
    private var candidate: String? = null
    private var ambiguous = false
    private var conflictsWithDefault = false
    var revision = 0
        private set

    init {
        blocked.addAll(mappings.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys)
        mappings.entries.removeAll { it.value in blocked }
    }

    fun mapping(key: CarShortcut): String? = mappings[key]
    fun conflicts(): Set<String> = blocked.toSet()

    fun start(key: CarShortcut, now: Long) {
        cancel()
        state = State(key, Phase.FIRST, now + WINDOW_MS).also { trace(it, "begin") }
    }

    fun confirm(now: Long) {
        tick(now)
        val current = state ?: return
        if (current.phase == Phase.CONFIRM) {
            candidate = null
            ambiguous = false
            state = State(current.key, Phase.SECOND, now + WINDOW_MS, diagnosticOnly = current.diagnosticOnly).also { trace(it, "begin") }
        }
    }

    fun cancel(disconnected: Boolean = false) {
        state?.takeIf { it.active }?.let { trace(it, if (disconnected) "disconnected" else "cancelled") }
        state = null
        first = null
        candidate = null
        ambiguous = false
        conflictsWithDefault = false
    }

    fun reset() {
        cancel()
        mappings.clear()
        blocked.clear()
        revision++
    }

    fun offer(token: String, now: Long, defaultAction: CarShortcut? = null): CarShortcut? {
        if (!safe(token)) return null
        tick(now)
        val current = state
        if (current?.active == true) {
            if (current.capturing) {
                if (candidate == null || candidate != token && !ambiguous) {
                    trace(current, "candidate id=" + token.substringBefore(':') + ":" + token.substringAfter(':').take(12) +
                        " default=" + (defaultAction?.name ?: "none"))
                    if (candidate == null) candidate = token else ambiguous = true
                }
                if (!compatible(current.key, defaultAction)) conflictsWithDefault = true
            }
            return null
        }
        if (token in blocked) return null
        val mapped = mappings.entries.firstOrNull { it.value == token }?.key
        if (mapped != null && !compatible(mapped, defaultAction)) {
            blockShared(token)
            return null
        }
        return mapped ?: defaultAction
    }

    fun observing(now: Long): Boolean = state?.let { it.capturing && now < it.deadline } == true

    /** Observations can describe native screen borrows and other shared signals, never mapping candidates. */
    fun observe(kind: String, now: Long) {
        val s = state ?: return
        if (!observing(now)) return
        val short = if (kind.length <= 20) kind.lowercase(java.util.Locale.ROOT) else "other"
        val label = if (short in kinds || numbered.matches(short)) short else "other"
        if (label in s.observed) return
        state = s.copy(observed = s.observed + label)
        trace(s, "observed kind=$label offsetMs=" + (WINDOW_MS - (s.deadline - now)).coerceIn(0, WINDOW_MS))
    }

    private fun trace(s: State, detail: String) {
        log("KEYCAL: key=${s.key.name} round=${if (s.phase == Phase.SECOND) 2 else 1} event=$detail")
    }

    fun tick(now: Long) {
        val current = state ?: return
        if (!current.active || now < current.deadline) return
        val token = candidate
        val result = when {
            current.phase == Phase.CONFIRM || token == null && current.observed.isEmpty() -> Phase.TIMEOUT
            ambiguous -> Phase.AMBIGUOUS
            current.phase == Phase.FIRST -> {
                first = token
                val until = current.deadline + CONFIRM_MS
                val next = if (now < until) Phase.CONFIRM else Phase.TIMEOUT
                trace(current, "result status=${next.name} candidate=${token != null} observations=${current.observed.size}")
                state = current.copy(phase = next, deadline = until, diagnosticOnly = token == null)
                return
            }
            current.diagnosticOnly || token == null -> Phase.DIAGNOSTIC
            token != first -> Phase.DIFFERENT
            conflictsWithDefault || token in blocked || mappings.any { it.key != current.key && it.value == token } -> {
                blockShared(token)
                Phase.SHARED
            }
            else -> {
                mappings[current.key] = token
                revision++
                Phase.SAVED
            }
        }
        trace(current, "result status=${result.name} observations=${current.observed.size}")
        state = current.copy(phase = result, deadline = 0)
    }

    private fun blockShared(token: String) {
        blocked.add(token)
        mappings.entries.removeAll { it.value == token }
        revision++
    }

    // A maps: context describes a page, not the printed key. A separately tested NAV
    // may request that page for destination entry. A learned MAP with the same token
    // still triggers the shared-signal check and can never be bound to both keys.
    private fun compatible(key: CarShortcut, default: CarShortcut?) =
        default == null || key == default || (key == CarShortcut.NAV && default == CarShortcut.MAP)

    companion object {
        const val WINDOW_MS = 8_000L
        const val CONFIRM_MS = 30_000L
        private val format = Regex("""(?:ui|hid):[0-9a-f]{64}""")
        private val kinds = setOf("requestui", "requestui_empty", "requestui_target", "hid", "hid_rotary", "hid_touch",
            "changemodes", "screen_take", "screen_borrow", "screen_return", "iap_media_key")
        private val numbered = Regex("""(?:mode_reason|ui_target)_(?:[1-9]|1[0-6])""")
        fun safe(token: String) = token.length in 67..68 && format.matches(token)
    }
}

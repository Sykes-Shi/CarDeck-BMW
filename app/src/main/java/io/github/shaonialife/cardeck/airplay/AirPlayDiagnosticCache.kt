package io.github.shaonialife.cardeck.airplay

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Export-only observations of existing traffic. Never issues requests or acquires a protocol lock. */
internal class AirPlayDiagnosticCache {
    data class Feedback(val generation: Int, val elapsedMs: Long, val status: Int?, val body: ByteArray?, val state: String)
    private data class Observation(val generation: Int, val elapsedMs: Long, val fields: String)

    val clientId: Long = nextId.incrementAndGet()
    private val feedback = AtomicReference<Feedback?>()
    private val modes = AtomicReference<Observation?>()
    private val volumeGet = AtomicReference<Observation?>()
    private val volumeSet = AtomicReference<Observation?>()

    fun recordFeedback(generation: Int, elapsedMs: Long, status: String, body: ByteArray) {
        val state = when {
            body.isEmpty() -> "empty"
            body.size > MAX_FEEDBACK_BYTES -> "oversized"
            else -> "body"
        }
        val sample = Feedback(generation, elapsedMs, statusCode(status), body.takeIf { state == "body" }?.copyOf(), state)
        feedback.updateAndGet { old ->
            if (old == null || newer(generation, elapsedMs, old.generation, old.elapsedMs)) sample else old
        }
    }

    /** Retained solely for the existing control-EOF diagnostic; callers must not modify body. */
    fun feedbackForGeneration(generation: Int): Feedback? = feedback.get()?.takeIf {
        it.generation == generation && it.body != null
    }

    fun recordModes(generation: Int, elapsedMs: Long, summary: String, resources: List<Map<String, Any?>>) {
        val fields = summary.take(1_024).split(' ').mapNotNull { token ->
            val pair = token.split('=', limit = 2)
            if (pair.size != 2) return@mapNotNull null
            val valid = when (pair[0]) {
                in booleanModeKeys -> pair[1] == "true" || pair[1] == "false"
                in integerModeKeys -> pair[1].toLongOrNull()?.let { it >= 0 } == true
                else -> false
            }
            token.takeIf { valid }
        }.distinct().take(10).joinToString(" ")
        val permanent = resources.take(4).firstOrNull { integer(it["resourceID"]) == 2L }
            ?.get("permanentEntity").let(::integer)
        store(modes, Observation(generation, elapsedMs, "source=local_guard peerOwnership=unknown $fields audioPermanent=${permanent ?: "unknown"}"))
    }

    enum class VolumeOperation { GET, SET }

    fun recordVolume(operation: VolumeOperation, generation: Int, elapsedMs: Long, status: String?, decibels: Float?) {
        val value = decibels?.takeIf { it.isFinite() }?.toString() ?: "unknown"
        val field = if (operation == VolumeOperation.GET) "queriedDb" else "requestedDb"
        val result = if (status == null) "failed" else "response"
        store(
            if (operation == VolumeOperation.GET) volumeGet else volumeSet,
            Observation(generation, elapsedMs, "result=$result status=${statusCode(status) ?: "unknown"} $field=$value currentPeerVolume=unknown"),
        )
    }

    /** Fresh means only a recent cached response; sampleTime is not an acknowledgement of audible playback. */
    fun lines(generation: Int, nowMs: Long): List<String> = buildList {
        val sample = feedback.get()
        if (sample == null) {
            add("kind=feedback unavailable=not_observed")
        } else {
            val age = age(nowMs, sample.elapsedMs)
            val valid = sample.generation == generation
            add("kind=feedback ${timing(sample.generation, sample.elapsedMs, generation, nowMs)} " +
                "fresh=${valid && age != null && age <= FEEDBACK_FRESH_MS} freshnessLimitMs=$FEEDBACK_FRESH_MS " +
                "status=${sample.status ?: "unknown"} bodyState=${sample.state}")
            if (valid && sample.body != null) {
                val root = runCatching { BinaryPlist.parse(sample.body) as? Map<*, *> }.getOrNull()
                val streams = root?.get("streams") as? List<*>
                if (streams == null) {
                    add("kind=feedback_stream unavailable=unparseable_or_no_streams")
                } else {
                    streams.take(MAX_STREAMS).forEachIndexed { index, entry ->
                        val stream = entry as? Map<*, *>
                        // Never stringify arbitrary dictionary values or keys from the receiver.
                        add("kind=feedback_stream cachedGeneration=${sample.generation} index=$index " +
                            "type=${integer(stream?.get("type")) ?: "unknown"} " +
                            "sampleTime=${integer(stream?.get("sampleTime")) ?: "unknown"} " +
                            "sr=${finiteNumber(stream?.get("sr")) ?: "unknown"} " +
                            "timestampRawNs=${integer(stream?.get("timestampRawNs")) ?: "unknown"}")
                    }
                    if (streams.size > MAX_STREAMS) add("kind=feedback_stream truncated=true limit=$MAX_STREAMS")
                }
            }
        }
        add(observationLine("mode_guard", modes.get(), generation, nowMs))
        add(observationLine("initial_volume_get", volumeGet.get(), generation, nowMs))
        add(observationLine("initial_volume_set", volumeSet.get(), generation, nowMs))
    }

    private fun observationLine(kind: String, sample: Observation?, generation: Int, nowMs: Long): String {
        if (sample == null) return "kind=$kind unavailable=not_observed"
        val fields = if (sample.generation == generation) sample.fields else "unavailable=generation_changed"
        return "kind=$kind ${timing(sample.generation, sample.elapsedMs, generation, nowMs)} $fields"
    }

    private fun store(slot: AtomicReference<Observation?>, sample: Observation) {
        slot.updateAndGet { old ->
            if (old == null || newer(sample.generation, sample.elapsedMs, old.generation, old.elapsedMs)) sample else old
        }
    }

    private companion object {
        val nextId = AtomicLong()
        const val MAX_FEEDBACK_BYTES = 16_384
        const val MAX_STREAMS = 4
        const val FEEDBACK_FRESH_MS = 5_000L
        val integerModeKeys = setOf("screenOwner", "screenActive", "screenPermanent", "uiRevision", "audioOwner", "audioActive")
        val booleanModeKeys = setOf("screenUnknown", "nativeReturnArmed", "audioUnknown")
        val statusPattern = Regex("^RTSP/1\\.[01] ([1-5][0-9]{2})(?: |$)")

        fun statusCode(value: String?): Int? = value?.take(64)?.let { statusPattern.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        fun newer(generation: Int, elapsedMs: Long, oldGeneration: Int, oldElapsedMs: Long): Boolean =
            generation > oldGeneration || (generation == oldGeneration && elapsedMs >= oldElapsedMs)
        fun age(nowMs: Long, elapsedMs: Long): Long? = if (elapsedMs >= 0 && nowMs >= elapsedMs) nowMs - elapsedMs else null
        fun timing(cachedGeneration: Int, elapsedMs: Long, generation: Int, nowMs: Long): String =
            "cachedGeneration=$cachedGeneration validGeneration=${cachedGeneration == generation} " +
                "observedAtElapsedMs=$elapsedMs ageMs=${age(nowMs, elapsedMs) ?: "unknown"}"
        fun integer(value: Any?): Long? = when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }
        fun finiteNumber(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
    }
}

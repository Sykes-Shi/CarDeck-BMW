package io.github.shaonialife.cardeck.audio

/** Compare two receiver counters from the same negotiated audio stream. Both are in the
 * receiver's clock domain. A pause while it owns audio is expected and never drives playback. */
internal class ReceiverAudioClock {
    data class Difference(val receiverMs: Long, val samplesMs: Long, val borrowedInWindow: Boolean) {
        val deficitMs: Long get() = receiverMs - samplesMs
    }

    private var streamId: Long? = null
    private var rate = 0
    private var lastRawNs = -1L
    private var lastSample = 0L
    private var borrowed = false
    private var borrowedInWindow = false

    @Synchronized fun start(id: Long, sampleRate: Int) {
        streamId = id
        rate = sampleRate
        lastRawNs = -1L
        lastSample = 0L
        borrowedInWindow = borrowed
    }

    @Synchronized fun noteBorrow(value: Boolean) {
        borrowed = value
        if (value) borrowedInWindow = true
    }

    @Synchronized fun observe(id: Long, rawNs: Long, sample: Long): Difference? {
        if (id != streamId || rawNs < 0L || sample < 0L || rate <= 0) return null
        // A BMW can repeat the last timestamp while native speech owns the speakers.
        // Retain the overlap until its sample clock next advances.
        if (rawNs == lastRawNs && sample == lastSample) return null
        val deltaNs = rawNs - lastRawNs
        val frames = sample - lastSample
        val overlap = borrowedInWindow
        // The BMW repeated one sample for a 64-second native call. Ordinary long gaps lack a
        // comparable baseline; extend this bound only for a window with an explicit borrow.
        val maxSeconds = if (overlap) 3_600L else 30L
        val comparable = lastRawNs >= 0 && deltaNs in 1..maxSeconds * 1_000_000_000L &&
            frames in 0..rate * 2L * maxSeconds
        lastRawNs = rawNs
        lastSample = sample
        borrowedInWindow = borrowed
        if (!comparable) return null
        return Difference(deltaNs / 1_000_000, frames * 1_000 / rate, overlap)
            .takeIf { kotlin.math.abs(it.deficitMs) >= 250 }
    }
}

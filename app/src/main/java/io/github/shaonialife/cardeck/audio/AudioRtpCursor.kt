package io.github.shaonialife.cardeck.audio

/** A media grant can change within one encrypted stream. Only the RTP marker and wall-clock
 * anchor start a new segment; the packet number, sample cursor and key nonce are never reset. */
internal class AudioRtpCursor(private val samplesPerPacket: Long) {
    var sequence = 0
        private set
    @Volatile var timestamp = 0L
        private set
    var nonce = 0L
        private set
    @Volatile var lastEpoch: Long? = null
        private set

    fun advance(epoch: Long) {
        sequence = (sequence + 1) and 0xFFFF
        timestamp += samplesPerPacket
        nonce++
        lastEpoch = epoch
    }
}

/** A negative ICMP response silences this optional port. When BMW returns main audio,
 * allow just one new timing anchor after the first packet of that new media segment. */
internal class AudioSyncCadence {
    private var quietEpoch: Long? = null

    fun allow(epoch: Long): Boolean {
        if (quietEpoch == epoch) return false
        if (quietEpoch != null) quietEpoch = epoch
        return true
    }

    fun refused(epoch: Long) {
        quietEpoch = epoch
    }
}

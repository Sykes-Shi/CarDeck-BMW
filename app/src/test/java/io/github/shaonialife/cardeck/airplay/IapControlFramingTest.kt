package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2Csm
import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.iap2.Iap2LinkPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IapControlFramingTest {
    private val ack = byteArrayOf(0xFF.toByte(), 0x5A, 0, 9, 0x40, 1, 2, 0, 0x5B)

    private fun dataFrame(
        messageId: Int,
        sequence: Int,
        body: ByteArray = ByteArray(0),
    ): ByteArray =
        Iap2LinkPacket.encode(
            Iap2LinkPacket.LinkPacket(0x40, sequence, 2, 1, Iap2Csm.encode(messageId, body)),
        )

    private fun assertPreserved(
        original: ByteArray,
        result: IapControlFraming.Result,
    ) {
        assertArrayEquals(original, result.frames.fold(ByteArray(0)) { bytes, frame -> bytes + frame })
    }

    @Test
    fun acknowledgementAndIdentificationAndCertificateRequestKeepSeparateBoundaries() {
        val accepted = dataFrame(0x1D02, 2)
        val certificate = dataFrame(0xAA00, 3)
        val original = ack + accepted + certificate
        val result = IapControlFraming.splitOrPreserve(original)
        assertEquals(41, original.size)
        assertNull(result.fallbackReason)
        assertEquals(listOf(9, 16, 16), result.frames.map { it.size })
        assertArrayEquals(ack, result.frames[0])
        assertArrayEquals(accepted, result.frames[1])
        assertArrayEquals(certificate, result.frames[2])
        assertPreserved(original, result)
    }

    @Test
    fun engineMarkerAndSynchronizationKeepSeparateBoundaries() {
        val engine = Iap2LinkEngine()
        engine.initiate(0)
        val original = engine.takeOutput()
        val result = IapControlFraming.splitOrPreserve(original)
        assertEquals(35, original.size)
        assertNull(result.fallbackReason)
        assertEquals(listOf(6, 29), result.frames.map { it.size })
        assertArrayEquals(Iap2LinkEngine.MARKER, result.frames[0])
        assertPreserved(original, result)
    }

    @Test
    fun bigEndianLengthsAndMarkerBytesInsidePayloadRemainIntact() {
        val body = ByteArray(300) { 0x55 } + Iap2LinkEngine.MARKER + ack
        val original = dataFrame(0x5001, 4, body) + ack
        val result = IapControlFraming.splitOrPreserve(original)
        assertNull(result.fallbackReason)
        assertEquals(listOf(body.size + 16, 9), result.frames.map { it.size })
        assertPreserved(original, result)
    }

    @Test
    fun malformedOrTruncatedSuffixPreservesEntireBatchIncludingValidPrefix() {
        val malformed =
            listOf(
                byteArrayOf(1),
                byteArrayOf(0xFF.toByte(), 0x55),
                Iap2LinkEngine.MARKER.copyOf().also { it[5] = 0 },
                byteArrayOf(0xFF.toByte(), 0x5A),
                ack.copyOf().also { it[3] = 8 },
                ack.copyOf().also { it[3] = 20 },
                dataFrame(0xAA00, 3).dropLast(1).toByteArray(),
            )
        for (suffix in malformed) {
            val original = ack + suffix
            val result = IapControlFraming.splitOrPreserve(original)
            assertNotNull(result.fallbackReason)
            assertEquals(1, result.frames.size)
            assertSame(original, result.frames.single())
            assertPreserved(original, result)
        }
    }

    @Test
    fun badHeaderOrPayloadChecksumPreservesWholeBatch() {
        val packet = dataFrame(0xAA00, 3)
        val malformed =
            listOf(
                packet.copyOf().also { it[8] = (it[8].toInt() xor 1).toByte() },
                packet.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() },
            )
        for (suffix in malformed) {
            val original = ack + suffix
            val result = IapControlFraming.splitOrPreserve(original)
            assertNotNull(result.fallbackReason)
            assertSame(original, result.frames.single())
            assertPreserved(original, result)
        }
    }

    @Test
    fun emptyInputProducesNoCommand() {
        val result = IapControlFraming.splitOrPreserve(ByteArray(0))
        assertTrue(result.frames.isEmpty())
        assertNull(result.fallbackReason)
    }
}

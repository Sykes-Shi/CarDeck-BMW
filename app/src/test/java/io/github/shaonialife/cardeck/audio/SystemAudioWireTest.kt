package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SystemAudioWireTest {
    @Test
    fun headerHasFixedBigEndianBytes() {
        assertArrayEquals(
            byteArrayOf(0x43, 0x44, 0x41, 0x31, 0, 0, 0xbb.toByte(), 0x80.toByte(), 0, 0, 0, 2, 0, 0, 0, 2),
            SystemAudioWire.header(48_000),
        )
    }

    @Test
    fun bothNegotiatedRatesRoundTrip() {
        listOf(44_100, 48_000).forEach { rate ->
            assertTrue(SystemAudioWire.validateHeader(SystemAudioWire.header(rate), rate))
        }
    }

    @Test
    fun rateMismatchIsRejected() {
        assertFalse(SystemAudioWire.validateHeader(SystemAudioWire.header(44_100), 48_000))
    }

    @Test
    fun unsupportedRateCannotCreateHeader() {
        listOf(-1, 0, 8_000, 96_000).forEach { rate ->
            assertTrue(runCatching { SystemAudioWire.header(rate) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun unsupportedExpectedRateIsRejected() {
        assertFalse(SystemAudioWire.validateHeader(SystemAudioWire.header(48_000), 96_000))
    }

    @Test
    fun truncatedAndOversizedHeaderAreRejected() {
        val header = SystemAudioWire.header(48_000)
        (0 until SystemAudioWire.HEADER_BYTES).forEach { size ->
            assertFalse(SystemAudioWire.validateHeader(header.copyOf(size), 48_000))
        }
        assertFalse(SystemAudioWire.validateHeader(header + byteArrayOf(0), 48_000))
    }

    @Test
    fun allHeaderFieldsAreValidated() {
        (0 until 4).forEach { field ->
            val header = SystemAudioWire.header(48_000)
            ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).putInt(field * 4, -1)
            assertFalse(SystemAudioWire.validateHeader(header, 48_000))
        }
    }

    @Test
    fun littleEndianHeaderIsRejected() {
        val header = ByteBuffer.allocate(SystemAudioWire.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(SystemAudioWire.MAGIC).putInt(48_000).putInt(2).putInt(2).array()
        assertFalse(SystemAudioWire.validateHeader(header, 48_000))
    }
}

package io.github.shaonialife.cardeck.media

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDiagnosticLinesTest {
    @Test
    fun readsUtf8CrLfAndFinalLineWithinBounds() {
        val text = "声音\r\nsecond\nlast"
        val lines = mutableListOf<String>()
        val bytes = text.toByteArray(Charsets.UTF_8)
        val result = BoundedDiagnosticLines.read(ByteArrayInputStream(bytes), bytes.size, 100, lines::add)
        assertEquals(listOf("声音", "second", "last"), lines)
        assertTrue(result.complete)
        assertEquals(bytes.size, result.bytes)
    }

    @Test
    fun totalLimitInvalidatesTruncatedDump() {
        val result = BoundedDiagnosticLines.read(ByteArrayInputStream(ByteArray(11)), 10, 20) { }
        assertFalse(result.complete)
        assertEquals(10, result.bytes)
    }

    @Test
    fun longLineIsDiscardedWholeAndInvalidatesDump() {
        val lines = mutableListOf<String>()
        val input = ByteArrayInputStream("good\n1234567890\nok".toByteArray())
        val result = BoundedDiagnosticLines.read(input, 100, 5, lines::add)
        assertEquals(listOf("good", "ok"), lines)
        assertFalse(result.complete)
    }

    @Test
    fun exactLineLimitIsAccepted() {
        val lines = mutableListOf<String>()
        val result = BoundedDiagnosticLines.read(ByteArrayInputStream("12345\n".toByteArray()), 100, 5, lines::add)
        assertEquals(listOf("12345"), lines)
        assertTrue(result.complete)
    }

    @Test
    fun emptyDumpCompletesWithoutSyntheticLine() {
        val lines = mutableListOf<String>()
        val result = BoundedDiagnosticLines.read(ByteArrayInputStream(byteArrayOf()), 100, 20, lines::add)
        assertTrue(result.complete)
        assertTrue(lines.isEmpty())
    }
}

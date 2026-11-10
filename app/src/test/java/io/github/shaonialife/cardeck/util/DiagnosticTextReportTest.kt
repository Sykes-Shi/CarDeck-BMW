package io.github.shaonialife.cardeck.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTextReportTest {
    @Test fun reportBeyondOld8192CharacterLimitIsPreserved() {
        val text = "AUDIO_DIAG field=1234567890\n".repeat(400)
        assertTrue(text.length > 8192)
        assertEquals(text, DiagnosticTextReport.bound(text))
    }

    @Test fun byteLimitCountsUtf8RatherThanCharacters() {
        val report = DiagnosticTextReport()
        repeat(5000) { report.line("中文诊断") }
        val text = report.toString()
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= DiagnosticTextReport.MAX_BYTES)
        assertTrue(text.endsWith(DiagnosticTextReport.TRUNCATION_MARKER))
        assertFalse(text.contains('\uFFFD'))
    }

    @Test fun oversizedSingleLineProducesOnlyExplicitMarker() {
        val text = DiagnosticTextReport.bound("x".repeat(20000))
        assertEquals(DiagnosticTextReport.TRUNCATION_MARKER, text)
    }

    @Test fun largeInputDoesNotExposePartialLastLine() {
        val text = "small\n" + "private_part_".repeat(2000)
        assertEquals("small\n" + DiagnosticTextReport.TRUNCATION_MARKER, DiagnosticTextReport.bound(text))
    }

    @Test fun alreadyBoundedReportIsIdempotentAndHasOnlyOneMarker() {
        val report = DiagnosticTextReport()
        repeat(3000) { report.line("field=1234567890") }
        val bounded = report.toString()
        assertEquals(bounded, DiagnosticTextReport.bound(bounded))
        assertEquals(1, bounded.split(DiagnosticTextReport.TRUNCATION_MARKER).size - 1)
    }

    @Test fun exactlyByteLimitCanBeExportedWithoutUnnecessaryMarker() {
        val text = "a".repeat(DiagnosticTextReport.MAX_BYTES)
        assertEquals(text, DiagnosticTextReport.bound(text))
    }

    @Test fun collectionStopsAfterTruncation() {
        val report = DiagnosticTextReport()
        report.line("a".repeat(20000))
        report.line("must_not_follow")
        assertEquals(DiagnosticTextReport.TRUNCATION_MARKER, report.toString())
    }

    @Test fun unicodeExportIsBoundedEvenWhenCharacterCountIsBelowLimit() {
        val text = DiagnosticTextReport.bound("中文\n".repeat(4000))
        assertTrue(text.toByteArray(Charsets.UTF_8).size <= DiagnosticTextReport.MAX_BYTES)
        assertTrue(text.endsWith(DiagnosticTextReport.TRUNCATION_MARKER))
        assertFalse(text.contains('\uFFFD'))
    }
}

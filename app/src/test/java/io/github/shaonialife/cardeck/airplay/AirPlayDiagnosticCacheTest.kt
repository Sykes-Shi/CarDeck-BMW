package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayDiagnosticCacheTest {
    private fun body(sampleTime: Long = 1024L, extra: Any? = null): ByteArray = BinaryPlist.encode(
        mapOf("streams" to listOf(mapOf("type" to 100L, "sampleTime" to sampleTime, "sr" to 48000.0,
            "timestampRawNs" to 9876543L, "private" to extra))),
    )

    @Test fun emptyCacheReportsUnknownRatherThanIdleOrMuted() {
        val lines = AirPlayDiagnosticCache().lines(1, 500)
        assertEquals(4, lines.size)
        assertTrue(lines.all { it.contains("unavailable=not_observed") })
    }

    @Test fun instancesHaveDistinctProcessLocalIds() {
        assertNotEquals(AirPlayDiagnosticCache().clientId, AirPlayDiagnosticCache().clientId)
    }

    @Test fun currentFeedbackReportsOnlyAllowlistedNumbers() {
        val cache = AirPlayDiagnosticCache()
        cache.recordFeedback(2, 1000, "RTSP/1.0 200 OK", body(extra = "private music location"))
        val text = cache.lines(2, 2200).joinToString("\n")
        assertTrue(text.contains("validGeneration=true"))
        assertTrue(text.contains("ageMs=1200 fresh=true"))
        assertTrue(text.contains("sampleTime=1024 sr=48000.0 timestampRawNs=9876543"))
        assertFalse(text.contains("private"))
        assertFalse(text.contains("music"))
    }

    @Test fun staleGenerationDoesNotExposeOldStreamValues() {
        val cache = AirPlayDiagnosticCache()
        cache.recordFeedback(2, 1000, "RTSP/1.0 200 OK", body())
        val text = cache.lines(3, 1100).joinToString("\n")
        assertTrue(text.contains("cachedGeneration=2 validGeneration=false"))
        assertTrue(text.contains("fresh=false"))
        assertFalse(text.contains("sampleTime="))
        assertNull(cache.feedbackForGeneration(3))
    }

    @Test fun lateOldGenerationCannotOverwriteNewFeedback() {
        val cache = AirPlayDiagnosticCache()
        cache.recordFeedback(3, 1000, "RTSP/1.0 200 OK", body(3000))
        cache.recordFeedback(2, 2000, "RTSP/1.0 200 OK", body(2000))
        assertTrue(cache.lines(3, 2100).any { it.contains("sampleTime=3000") })
    }

    @Test fun outOfOrderSameGenerationCannotRegressFeedback() {
        val cache = AirPlayDiagnosticCache()
        cache.recordFeedback(3, 2000, "RTSP/1.0 200 OK", body(3000))
        cache.recordFeedback(3, 1000, "RTSP/1.0 200 OK", body(2000))
        assertTrue(cache.lines(3, 2100).any { it.contains("sampleTime=3000") })
    }

    @Test fun feedbackFreshnessHasExplicitBoundaryAndClockReversalIsUnknown() {
        val cache = AirPlayDiagnosticCache()
        cache.recordFeedback(1, 1000, "RTSP/1.0 200 OK", body())
        assertTrue(cache.lines(1, 6000)[0].contains("fresh=true"))
        assertTrue(cache.lines(1, 6001)[0].contains("fresh=false"))
        assertTrue(cache.lines(1, 999)[0].contains("ageMs=unknown fresh=false"))
    }

    @Test fun feedbackBodyIsCopiedAndOversizedBodyReplacesPreviousObservation() {
        val cache = AirPlayDiagnosticCache()
        val bytes = body()
        cache.recordFeedback(1, 1000, "RTSP/1.0 200 OK", bytes)
        bytes.fill(0)
        assertTrue(cache.lines(1, 1100).any { it.contains("sampleTime=1024") })
        cache.recordFeedback(1, 2000, "RTSP/1.0 200 OK", ByteArray(16385))
        assertTrue(cache.lines(1, 2100)[0].contains("bodyState=oversized"))
        assertFalse(cache.lines(1, 2100).any { it.contains("sampleTime=") })
    }

    @Test fun emptyResponseDoesNotMasqueradeAsEarlierStreamSample() {
        val cache = AirPlayDiagnosticCache()
        cache.recordFeedback(1, 1000, "RTSP/1.0 200 OK", body())
        cache.recordFeedback(1, 2000, "RTSP/1.0 200 OK", byteArrayOf())
        assertTrue(cache.lines(1, 2100)[0].contains("bodyState=empty"))
        assertNull(cache.feedbackForGeneration(1))
    }

    @Test fun malformedBodyAndUnknownStatusNeverPrintRawContent() {
        val cache = AirPlayDiagnosticCache()
        cache.recordFeedback(1, 1000, "private response", "private payload".toByteArray())
        val text = cache.lines(1, 1100).joinToString("\n")
        assertTrue(text.contains("status=unknown"))
        assertTrue(text.contains("unavailable=unparseable_or_no_streams"))
        assertFalse(text.contains("private"))
    }

    @Test fun streamCountIsBoundedAndWrongTypesAreUnknown() {
        val cache = AirPlayDiagnosticCache()
        val streams = List(6) { mapOf("type" to "private type", "sr" to Double.NaN,
            "sampleTime" to 2.5, "timestampRawNs" to "private location") }
        cache.recordFeedback(1, 1000, "RTSP/1.0 200 OK", BinaryPlist.encode(mapOf("streams" to streams)))
        val lines = cache.lines(1, 1100)
        assertEquals(4, lines.count { it.contains(" index=") })
        assertTrue(lines.any { it.contains("truncated=true limit=4") })
        assertTrue(lines.first { it.contains("index=0") }.endsWith("type=unknown sampleTime=unknown sr=unknown timestampRawNs=unknown"))
        assertFalse(lines.joinToString().contains("private"))
    }

    @Test fun modeSnapshotRetainsNumericStateButDoesNotClaimPeerOwnership() {
        val cache = AirPlayDiagnosticCache()
        cache.recordModes(1, 1000, "screenOwner=2 screenActive=1 screenUnknown=false screenPermanent=1 " +
            "nativeReturnArmed=false uiRevision=5 audioOwner=1 audioActive=0 audioUnknown=false private=secret",
            listOf(mapOf("resourceID" to 2L, "permanentEntity" to 1L)))
        val text = cache.lines(1, 9000).first { it.contains("kind=mode_guard") }
        assertTrue(text.contains("source=local_guard peerOwnership=unknown"))
        assertTrue(text.contains("screenOwner=2"))
        assertTrue(text.contains("audioPermanent=1"))
        assertFalse(text.contains("secret"))
        assertTrue(cache.lines(2, 9000).first { it.contains("kind=mode_guard") }.endsWith("unavailable=generation_changed"))
    }

    @Test fun modeFieldsRejectTextAndOldGenerationUpdates() {
        val cache = AirPlayDiagnosticCache()
        cache.recordModes(2, 1000, "audioOwner=1 screenUnknown=false", emptyList())
        cache.recordModes(1, 2000, "audioOwner=2 screenUnknown=true", emptyList())
        assertTrue(cache.lines(2, 2100).any { it.contains("audioOwner=1") })
        cache.recordModes(2, 2200, "audioOwner=private screenUnknown=maybe uiRevision=-2", emptyList())
        val text = cache.lines(2, 2300).joinToString()
        assertFalse(text.contains("audioOwner="))
        assertFalse(text.contains("private"))
        assertFalse(text.contains("maybe"))
    }

    @Test fun volumeQueryAndSetRemainSeparateHistoricalObservations() {
        val cache = AirPlayDiagnosticCache()
        cache.recordVolume(AirPlayDiagnosticCache.VolumeOperation.GET, 2, 1000, "RTSP/1.0 501 Not Implemented", null)
        cache.recordVolume(AirPlayDiagnosticCache.VolumeOperation.SET, 2, 1100, "RTSP/1.0 200 OK", -25f)
        val text = cache.lines(2, 3000).joinToString("\n")
        assertTrue(text.contains("status=501 queriedDb=unknown currentPeerVolume=unknown"))
        assertTrue(text.contains("status=200 requestedDb=-25.0 currentPeerVolume=unknown"))
        assertTrue(text.contains("ageMs=1900"))
    }

    @Test fun volumeFailureAndNonfiniteValueAreExplicitAndOldGenerationIsHidden() {
        val cache = AirPlayDiagnosticCache()
        cache.recordVolume(AirPlayDiagnosticCache.VolumeOperation.GET, 2, 1000, null, Float.NaN)
        assertTrue(cache.lines(2, 1100).any { it.contains("result=failed status=unknown queriedDb=unknown") })
        assertTrue(cache.lines(3, 1100).first { it.contains("kind=initial_volume_get") }.endsWith("unavailable=generation_changed"))
    }
}

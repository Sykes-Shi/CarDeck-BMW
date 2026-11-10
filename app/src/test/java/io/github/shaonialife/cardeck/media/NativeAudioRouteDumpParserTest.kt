package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAudioRouteDumpParserTest {
    private val header = "T Handle UId Port Run State Format Burst Chan Mask Capacity HwFormat HwChan HwRate"

    private fun row(uid: Int = 10420, handle: String = "0x00000001") =
        "$handle $uid 190 yes 4 5 192 2 3 1536 1 2 48000"

    private fun dump(
        rows: List<String> = listOf(row()),
        direction: String = "OUTPUT",
        shared: Boolean = false,
    ): List<String> {
        val prefix = mutableListOf(
            "------------ AAudio Service ------------",
            "Stream Handles:",
            "AAudioClientTracker:",
            "  client: pid = 1234 has 1 streams",
            "AAudioEndpointManager:",
            "Exclusive MMAP Endpoints: ${if (shared) 0 else 1}",
        )
        if (shared) prefix += "Shared Endpoints: 1"
        prefix += if (shared) "#0: SHARED: sharing exclusive stream with handle = 0x00000002, XRuns = 0"
        else "#0: MMAP: framesTransferred = 4096, HW nanos = 0, port handle = 188, audio data FD = 99"
        prefix += listOf(
            "Direction: $direction",
            "Device Ids: 3, 12",
            "Sample Rate: 48000",
            "Registered Streams:",
            header,
        )
        prefix += rows
        prefix += if (shared) "SharedCloseCount: 0" else "ExclusiveCloseCount: 0"
        if (!shared) prefix += "Shared Endpoints: 0"
        return prefix
    }

    private fun parse(lines: List<String>, complete: Boolean = true, targets: Set<Int> = setOf(10420)): List<String> =
        NativeAudioRouteDumpParser(targets).let { parser -> lines.forEach(parser::accept); parser.finish(complete) }

    @Test
    fun explicitMmapOutputOfTargetUidIsObserved() {
        val result = parse(dump()).joinToString("\n")
        assertTrue(result.contains("status=observed outputStreams=1"))
        assertTrue(result.contains("path=MMAP sharing=exclusive"))
        assertTrue(result.contains("deviceIds=3,12"))
        assertFalse(result.contains("pid ="))
        assertFalse(result.contains("audio data FD"))
    }

    @Test
    fun sharedServiceDoesNotInventExplicitMmapOrLegacyPath() {
        val result = parse(dump(shared = true)).joinToString("\n")
        assertTrue(result.contains("path=unknown sharing=shared"))
        assertFalse(result.contains("path=MMAP"))
        assertFalse(result.contains("legacy", ignoreCase = true))
    }

    @Test
    fun OtherUidAndInputStreamsAreNotExposed() {
        val result = parse(dump(rows = listOf(row(12345)), direction = "INPUT")).joinToString("\n")
        assertTrue(result.contains("status=not_observed path=unknown"))
        assertFalse(result.contains("12345"))
    }

    @Test
    fun TargetInputDoesNotCountAsPlayback() {
        assertTrue(parse(dump(direction = "INPUT")).single().contains("status=not_observed"))
    }

    @Test
    fun MissingTargetDoesNotMeanLegacy() {
        val result = parse(dump(rows = emptyList())).joinToString("\n")
        assertTrue(result.contains("status=not_observed path=unknown"))
        assertFalse(result.contains("legacy", ignoreCase = true))
    }

    @Test
    fun IncompleteDumpDoesNotPublishPartialRoute() {
        val result = parse(dump(), complete = false).single()
        assertTrue(result.contains("status=unknown path=unknown"))
        assertFalse(result.contains("handle="))
    }

    @Test
    fun UnknownColumnHeaderDoesNotGuessUid() {
        val lines = dump().map { if (it == header) "T Handle Port UId Run State Format" else it }
        assertTrue(parse(lines).single().contains("status=unknown"))
    }

    @Test
    fun MalformedStreamRowIsUnknownInsteadOfAbsent() {
        assertTrue(parse(dump(rows = listOf("0x00000001 ???"))).single().contains("status=unknown"))
    }

    @Test
    fun MissingDirectionOrDumpFooterSectionIsUnknown() {
        assertTrue(parse(dump().filterNot { it.startsWith("Direction:") }).single().contains("status=unknown"))
        assertTrue(parse(dump().filterNot { it.startsWith("Shared Endpoints:") }).single().contains("status=unknown"))
    }

    @Test
    fun MoreThanFourTargetStreamsIsTruncated() {
        val rows = (1..5).map { row(handle = "0x0000000$it") }
        assertTrue(parse(dump(rows)).single().contains("status=truncated path=unknown"))
    }

    @Test
    fun UnrelatedTextCannotEscapeWhitelist() {
        val lines = dump().toMutableList().apply { add(3, "Song title=PRIVATE https://private.example/token") }
        val result = parse(lines).joinToString("\n")
        assertFalse(result.contains("PRIVATE"))
        assertFalse(result.contains("private.example"))
    }

    @Test
    fun ConflictingSameStreamIsUnknown() {
        val rows = listOf(row(), row().replace("yes 4", "no 10"))
        assertTrue(parse(dump(rows)).single().contains("status=unknown"))
    }

    @Test
    fun MissingServiceDoesNotAcceptLookalikeOutput() {
        assertTrue(parse(dump().drop(1)).single().contains("status=unknown"))
    }

    @Test
    fun AtMostTwoTargetsAndFourRowsEachKeepOutputBounded() {
        val rows = (1..4).map { row(uid = 10420, handle = "0x0000000$it") } +
            (5..8).map { row(uid = 10421, handle = "0x0000000$it") }
        val result = parse(dump(rows), targets = setOf(10420, 10421, 10422))
        assertTrue(result.size <= 10)
        assertFalse(result.any { it.contains("10422") })
    }

    @Test
    fun AospFixedWidthRowHasNoLeadingTMarker() {
        // AAudioServiceStreamBase.cpp, android-16.0.0_r1: setw columns after the handle.
        val fixed = "    0x00000001 10420    190 yes     4      5   192    2       3     1536        1      2  48000"
        assertTrue(parse(dump(rows = listOf(fixed))).first().contains("status=observed"))
    }

    @Test
    fun UnknownOemStreamPrefixIsUnknownInsteadOfAbsent() {
        assertTrue(parse(dump(rows = listOf("* " + row()))).single().contains("status=unknown"))
    }

    @Test
    fun DeclaredEndpointWithoutEndpointBodyIsUnknown() {
        val lines = listOf(
            "------------ AAudio Service ------------",
            "Exclusive MMAP Endpoints: 1",
            "Shared Endpoints: 0",
        )
        assertTrue(parse(lines).single().contains("status=unknown"))
    }

    @Test
    fun EndpointWithoutRegisteredTableIsUnknown() {
        val lines = dump(rows = emptyList()).filterNot { it == "Registered Streams:" || it == header }
        assertTrue(parse(lines).single().contains("status=unknown"))
    }

    @Test
    fun DeadlockOutsideEndpointMakesEntireSnapshotUnknown() {
        val lines = dump().toMutableList().apply { add(1, "AAudioEndpointManager Shared may be deadlocked") }
        assertTrue(parse(lines).single().contains("status=unknown"))
    }

    @Test
    fun MissingEndpointIndexIsUnknown() {
        val lines = dump().map { if (it.startsWith("#0:")) it.replace("#0:", "#1:") else it }
        assertTrue(parse(lines).single().contains("status=unknown"))
    }

    @Test
    fun ExplicitNoDeviceLiteralRemainsAnObservedStreamWithoutRouteGuess() {
        val lines = dump().map { if (it.startsWith("Device Ids:")) "Device Ids: AUDIO_PORT_HANDLE_NONE" else it }
        val result = parse(lines).joinToString("\n")
        assertTrue(result.contains("status=observed"))
        assertTrue(result.contains("deviceIds=none"))
    }
}

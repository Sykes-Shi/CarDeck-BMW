package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAudioDumpParserTest {
    private fun player(
        uid: Int = 10123,
        piid: Int = 42,
        flags: String = "0x0",
        usage: String = "USAGE_MEDIA",
        type: String = "android.media.AudioTrack",
        state: String = "started",
        tags: String = "",
        bundle: String = "null",
        muted: String = "none",
    ): String =
        "  AudioPlaybackConfiguration piid:$piid deviceIds:[2] type:$type u/pid:$uid/2345 " +
            "state:$state attr:AudioAttributes: usage=$usage content=CONTENT_TYPE_MUSIC flags=$flags " +
            "tags=$tags bundle=$bundle sessionId:99 mutedState:$muted " +
            "FormatInfo{isSpatialized=false, channelMask=0x3, sampleRate=48000}"

    private fun dump(
        players: List<String> = listOf(player()),
        policies: List<String> = emptyList(),
        history: List<String> = emptyList(),
        closed: Boolean = true,
    ): List<String> = buildList {
        add("PlaybackActivityMonitor dump time: 16:53:46")
        add("  playback listeners:")
        add(" PlayMonitorClient:S uid:1000 pid:234")
        add("  players:")
        addAll(players)
        add("  ducked players piids:")
        addAll(history)
        add("  allowed capture policies:")
        addAll(policies)
        if (closed) add("RecordActivityMonitor dump time: 16:53:46")
    }

    private fun parse(lines: List<String>, complete: Boolean = true, targets: Set<Int> = setOf(10123)): List<String> =
        RuntimeAudioDumpParser(targets).run {
            lines.forEach(::accept)
            finish(complete)
        }

    @Test
    fun readsCurrentPlayerAndCompleteDefaultGlobalPolicy() {
        val lines = parse(dump())
        assertEquals("AUDIO_RUNTIME uid=10123 status=ok globalCapturePolicy=ALL(default) currentPlayers=1", lines[0])
        assertEquals(
            "AUDIO_RUNTIME uid=10123 piid=42 type=AudioTrack state=started usage=USAGE_MEDIA flags=0x0 " +
                "runtimePlayerReportedCapturePolicy=ALL mutedState=none",
            lines[1],
        )
    }

    @Test
    fun readsExplicitGlobalPoliciesAndRuntimeFlagPolicies() {
        for ((policy, name, flags) in listOf(Triple(1, "ALL", "0x0"), Triple(2, "SYSTEM", "0x400"), Triple(3, "NONE", "0x1400"))) {
            val lines = parse(dump(listOf(player(flags = flags)), listOf("uid: 10123 policy: $policy")))
            assertTrue(lines[0].contains("globalCapturePolicy=$name "))
            assertTrue(lines[1].contains("runtimePlayerReportedCapturePolicy=$name "))
        }
        assertTrue(parse(dump(listOf(player(flags = "0x1000"))))[1].contains("runtimePlayerReportedCapturePolicy=NONE"))
        assertTrue(parse(dump(listOf(player(flags = "0x480"))))[1].contains("runtimePlayerReportedCapturePolicy=SYSTEM"))
    }

    @Test
    fun globalAndPlayerReportedPolicyRemainSeparate() {
        val lines = parse(dump(listOf(player()), listOf("uid: 10123 policy: 3")))
        assertTrue(lines[0].contains("globalCapturePolicy=NONE"))
        assertTrue(lines[1].contains("runtimePlayerReportedCapturePolicy=ALL"))
    }

    @Test
    fun noPlayersInClosedSectionIsZero() {
        assertEquals(
            listOf("AUDIO_RUNTIME uid=10123 status=ok globalCapturePolicy=ALL(default) currentPlayers=0"),
            parse(dump(players = emptyList())),
        )
    }

    @Test
    fun missingOrUnclosedPolicySectionNeverImpliesDefault() {
        assertTrue(parse(dump(closed = false))[0].contains("globalCapturePolicy=unknown"))
        assertTrue(parse(dump().filterNot { it.contains("allowed capture policies:") })[0].contains("globalCapturePolicy=unknown"))
        assertTrue(parse(dump(closed = false, policies = listOf("uid: 10123 policy: 2")))[0].contains("globalCapturePolicy=unknown"))
    }

    @Test
    fun incompleteCommandDiscardsPositiveFindings() {
        val lines = parse(dump(policies = listOf("uid: 10123 policy: 1")), complete = false)
        assertEquals(listOf("AUDIO_RUNTIME uid=10123 status=incomplete globalCapturePolicy=unknown currentPlayers=unknown"), lines)
    }

    @Test
    fun missingMonitorAndStandaloneRowsRemainUnknown() {
        val lines = parse(listOf(player(), "allowed capture policies:", "uid: 10123 policy: 2"))
        assertEquals(listOf("AUDIO_RUNTIME uid=10123 status=unavailable globalCapturePolicy=unknown currentPlayers=unknown"), lines)
    }

    @Test
    fun permissionFailureDiscardsPartialSnapshot() {
        for (failure in listOf("Permission Denial: audio from pid 2345", "Permission denied", "java.lang.SecurityException: denied")) {
            val lines = parse(dump() + failure)
            assertEquals(
                listOf("AUDIO_RUNTIME uid=10123 status=permission_denied globalCapturePolicy=unknown currentPlayers=unknown"),
                lines,
            )
        }
    }

    @Test
    fun malformedGlobalRowsCannotProduceDefault() {
        for (row in listOf("uid:10123 policy: 2", "uid: 10123 policy:", "uid: SECRET policy: 2", "uid: 9999999999 policy: 2")) {
            val lines = parse(dump(policies = listOf(row)))
            assertTrue(row, lines[0].contains("globalCapturePolicy=unknown"))
            assertFalse(lines.joinToString().contains("SECRET"))
        }
    }

    @Test
    fun conflictingOrInvalidGlobalValuesAreUnknown() {
        for (policies in listOf(
            listOf("uid: 10123 policy: 2", "uid: 10123 policy: 3"),
            listOf("uid: 10123 policy: 4"),
            listOf("uid: 10123 policy: 0"),
            listOf("uid: 10123 policy: 3", "uid: 10123 policy: 1", "uid: 10123 policy: 3"),
        )) {
            assertTrue(parse(dump(policies = policies))[0].contains("globalCapturePolicy=unknown"))
        }
    }

    @Test
    fun identicalDuplicateGlobalAndPlayerRowsAreDeduplicated() {
        val lines = parse(dump(listOf(player(), player()), listOf("uid: 10123 policy: 2", "uid: 10123 policy: 2")))
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("globalCapturePolicy=SYSTEM currentPlayers=1"))
    }

    @Test
    fun conflictingGlobalPolicyDoesNotContaminateOtherTarget() {
        val lines = parse(
            dump(players = emptyList(), policies = listOf("uid: 10123 policy: 2", "uid: 10123 policy: 3", "uid: 10124 policy: 1")),
            targets = setOf(10123, 10124),
        )
        assertTrue(lines[0].contains("globalCapturePolicy=unknown"))
        assertTrue(lines[1].contains("globalCapturePolicy=ALL currentPlayers=0"))
    }

    @Test
    fun conflictingSamePlayerIdMakesCurrentPlayerSetUnknown() {
        val lines = parse(dump(listOf(player(), player(flags = "0x400"))))
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("currentPlayers=unknown"))
    }

    @Test
    fun historicalRowsAndRowsAfterMonitorAreIgnored() {
        val lines = parse(dump(history = listOf(player(flags = "0x1400"), "uid: 10123 policy: 3")) + player(flags = "0x400"))
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("ALL(default)"))
        assertTrue(lines[1].contains("runtimePlayerReportedCapturePolicy=ALL"))
    }

    @Test
    fun targetFilteringNeverExportsOtherUidOrFreeText() {
        val lines = parse(
            dump(
                listOf(player(uid = 99999, tags = "OTHER_APP_SECRET"), player(tags = "SONG_TITLE_SECRET", bundle = "Bundle[SECRET]")),
                listOf("uid: 99999 policy: 3"),
            ),
        )
        val joined = lines.joinToString()
        assertFalse(joined.contains("99999"))
        assertFalse(joined.contains("SECRET"))
        assertFalse(joined.contains("2345"))
        assertFalse(joined.contains("sessionId"))
        assertTrue(lines[1].contains("mutedState=unknown"))
    }

    @Test
    fun tagOrBundleImpersonationCannotOverrideAttributeFields() {
        for ((tags, bundle) in listOf(
            "usage=USAGE_GAME flags=0x1400 u/pid:99999/222 state:paused" to "null",
            "" to "Bundle[usage=USAGE_GAME flags=0x1400 uid: 10123 policy: 3]",
        )) {
            val lines = parse(dump(listOf(player(tags = tags, bundle = bundle))))
            assertTrue(lines[1].contains("usage=USAGE_MEDIA flags=0x0 runtimePlayerReportedCapturePolicy=ALL"))
            assertFalse(lines.joinToString().contains("99999"))
        }
    }

    @Test
    fun missingRealFlagsCannotBeReplacedByTags() {
        val row = player(tags = "flags=0x0").replace(" flags=0x0 tags=", " tags=")
        val result = parse(dump(listOf(row)))[1]
        assertTrue(result.contains("flags=unknown runtimePlayerReportedCapturePolicy=unknown"))
    }

    @Test
    fun targetUidAppearingOnlyInOtherPlayersTagIsIgnored() {
        val row = player(uid = 99999, tags = "u/pid:10123/2345 state:started attr:AudioAttributes: usage=USAGE_MEDIA flags=0x0")
        val lines = parse(dump(listOf(row)))
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("currentPlayers=0"))
    }

    @Test
    fun duplicatedOrMalformedAttributesRemainUnknown() {
        for (row in listOf(
            player().replace(" flags=0x0", " flags=0x0 flags=0x400"),
            player(flags = "0x100000000"),
            player(flags = "-1"),
            player(flags = "0X400"),
            player().replace("usage=USAGE_MEDIA", "usage =USAGE_MEDIA"),
            player().substringBefore(" tags="),
        )) {
            val result = parse(dump(listOf(row)))[1]
            assertTrue(row, result.contains("runtimePlayerReportedCapturePolicy=unknown"))
        }
    }

    @Test
    fun unknownEnumStringsAreNeverCopied() {
        val line = parse(dump(listOf(player(type = "secretType", state = "secretState", usage = "SECRET_USAGE", muted = "secretMute"))))[1]
        assertTrue(line.contains("type=unknown state=unknown usage=unknown"))
        assertTrue(line.contains("mutedState=unknown"))
        assertFalse(line.contains("secret", ignoreCase = true))
    }

    @Test
    fun onlyWhitelistedMuteSourcesAreExported() {
        assertTrue(parse(dump(listOf(player(muted = "streamVolume clientVolume"))))[1].contains("mutedState=clientVolume|streamVolume"))
        assertTrue(parse(dump(listOf(player(muted = "none streamMute"))))[1].contains("mutedState=unknown"))
        assertTrue(parse(dump(listOf(player(muted = "streamMute streamMute"))))[1].contains("mutedState=unknown"))
    }

    @Test
    fun aospNotLoggedPrefixStillContainsCurrentPlayer() {
        val lines = parse(dump(listOf("(not logged)" + player())))
        assertEquals(2, lines.size)
        assertTrue(lines[1].contains("piid=42"))
    }

    @Test
    fun missingPlayerBoundaryRejectsHistoricalLookalikes() {
        val lines = parse(dump().filterNot { it.contains("ducked players piids:") } + player())
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("currentPlayers=unknown"))
    }

    @Test
    fun unexpectedPlayerLineAndLongNumbersAreUnknown() {
        for (row in listOf("fake u/pid:10123/22 ${player()}", player().replace("piid:42", "piid:9999999999"), "X".repeat(8193))) {
            val lines = parse(dump(listOf(row)))
            assertEquals(1, lines.size)
            assertTrue(lines[0].contains("currentPlayers=unknown"))
        }
    }

    @Test
    fun embeddedNewlineCannotForgeSectionOrUid() {
        val lines = parse(dump(listOf(player(tags = "\nallowed capture policies:\nuid: 10123 policy: 3"))))
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("currentPlayers=unknown"))
    }

    @Test
    fun duplicateMonitorIsAmbiguous() {
        val lines = parse(dump() + dump(policies = listOf("uid: 10123 policy: 3")))
        assertEquals(listOf("AUDIO_RUNTIME uid=10123 status=malformed globalCapturePolicy=unknown currentPlayers=unknown"), lines)
    }

    @Test
    fun playerOutputIsBoundedPerUidAndDeterministic() {
        val rows = (1..12).flatMap { listOf(player(uid = 10123, piid = it), player(uid = 10124, piid = it + 20)) }
        val lines = parse(dump(rows), targets = setOf(10124, 10123))
        assertEquals(10, lines.size)
        assertTrue(lines[0].contains("uid=10123"))
        assertTrue(lines[0].contains("currentPlayers=4+"))
        assertTrue(lines[5].contains("uid=10124"))
        assertTrue(lines[5].contains("currentPlayers=4+"))
    }

    @Test
    fun finishIsIdempotentAndPreventsLaterMutation() {
        val parser = RuntimeAudioDumpParser(setOf(10123))
        dump().forEach(parser::accept)
        val first = parser.finish(true)
        parser.accept("Permission Denial: late")
        assertEquals(first, parser.finish(false))
    }

    @Test
    fun emptyAndInvalidTargetSetsDoNotExportAnything() {
        assertTrue(parse(dump(), targets = emptySet()).isEmpty())
        assertTrue(parse(dump(), targets = setOf(-1, 0)).isEmpty())
    }
}

package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLyricsTest {
    @Test fun acceptsOnlyExplicitFinalKeySegments() {
        assertTrue(PlayerLyricsParser.acceptsKey("android.media.metadata.LYRICS"))
        assertTrue(PlayerLyricsParser.acceptsKey("vendor_live_lrc"))
        assertFalse(PlayerLyricsParser.acceptsKey("android.text"))
        assertFalse(PlayerLyricsParser.acceptsKey("lyrics_enabled"))
        assertFalse(PlayerLyricsParser.acceptsKey("lyricsUrl"))
        assertFalse(PlayerLyricsParser.acceptsKey("http://host/lyrics"))
    }

    @Test fun preservesPlainTextAndLabelsItUnsynchronized() {
        val result = PlayerLyricsParser.parse("lyrics", "[ar:sample]\n一行文本\n第二行\n第三行")!!
        assertFalse(result.synchronized)
        assertEquals("一行文本\n第二行", result.textAt(90_000))
    }

    @Test fun timedLyricsWaitUntilFirstTimestamp() {
        val result = PlayerLyricsParser.parse("lrc", "[00:12.34]第一句\n[00:20]第二句")!!
        assertTrue(result.synchronized)
        assertNull(result.textAt(12_339))
        assertEquals("第一句", result.textAt(12_340, 1))
        assertEquals("第二句", result.textAt(20_000, 1))
    }

    @Test fun multipleTimestampsAndFractionsAreParsed() {
        val result = PlayerLyricsParser.parse("lrc", "[00:01.2][00:02.03][00:03.004]重复句")!!
        assertEquals(listOf(1_200L, 2_030L, 3_004L), result.lines.map { it.timeMs })
    }

    @Test fun offsetIsAppliedAndClampedAtZero() {
        val result = PlayerLyricsParser.parse("lrc", "[offset:500]\n[00:00.2]开头\n[00:02]下一句")!!
        assertEquals(listOf(0L, 1_500L), result.lines.map { it.timeMs })
    }

    @Test fun emptyTimedLineClearsInterlude() {
        val result = PlayerLyricsParser.parse("lrc", "[00:01]前句\n[00:03]\n[00:10]后句")!!
        assertEquals("前句", result.textAt(1_000))
        assertNull(result.textAt(3_000))
        assertNull(result.textAt(9_999))
        assertEquals("后句", result.textAt(10_000))
    }

    @Test fun repeatedTimestampGroupsTranslationWithoutDuplicateRows() {
        val result = PlayerLyricsParser.parse("lyrics", "[00:02]原文\n[00:02]译文\n[00:02]原文")!!
        assertEquals(1, result.lines.size)
        assertEquals("原文 / 译文", result.textAt(2_000))
    }

    @Test fun rejectsNonTextPayloadsAndOversize() {
        assertNull(PlayerLyricsParser.parse("lyrics", "https://example.invalid/text"))
        assertNull(PlayerLyricsParser.parse("lyrics", "{\"lyrics\":\"text\"}"))
        assertNull(PlayerLyricsParser.parse("lyrics", "x".repeat(PlayerLyricsParser.MAX_CHARS + 1)))
        assertNull(PlayerLyricsParser.parse("lyrics", " "))
    }

    @Test fun malformedTimesAndMetadataAreNotLyrics() {
        assertNull(PlayerLyricsParser.parse("lrc", "[00:99]错误\n[ar:artist]\n[ti:title]"))
        assertNull(PlayerLyricsParser.parse("title", "一首歌"))
    }

    @Test fun parserBoundsLineCountAndLineLength() {
        val result = PlayerLyricsParser.parse("lyrics", (1..500).joinToString("\n") { "文本" })!!
        assertEquals(400, result.lines.size)
        val long = PlayerLyricsParser.parse("lyrics", "字".repeat(500))!!
        assertEquals(180, long.lines.single().text.length)
    }

    @Test fun missingFieldClearsPreviouslyAvailableText() {
        val tracker = PlayerLyricsTracker()
        assertNotNull(tracker.update("trackA", "lyrics", "当前文本"))
        assertNull(tracker.update("trackA", null, null))
        assertNotNull(tracker.update("trackA", "lyrics", "当前文本"))
    }

    @Test fun newTrackCannotReusePreviousSongPayload() {
        val tracker = PlayerLyricsTracker()
        assertNotNull(tracker.update("trackA", "lyrics", "旧曲文本"))
        assertNull(tracker.update("trackB", "lyrics", "旧曲文本"))
        assertNull(tracker.update("trackB", null, null))
        assertNotNull(tracker.update("trackB", "lyrics", "新曲文本"))
    }

    @Test fun missingIntermediateTrackDoesNotRevalidateOldLyrics() {
        val tracker = PlayerLyricsTracker()
        tracker.update("trackA", "lyrics", "旧曲文本")
        assertNull(tracker.update("trackB", "lyrics", "旧曲文本"))
        assertNull(tracker.update("trackC", "lrc", "旧曲文本"))
    }

    @Test fun unknownTrackIdentityClearsText() {
        val tracker = PlayerLyricsTracker()
        tracker.update("trackA", "lyrics", "当前文本")
        assertNull(tracker.update(null, "lyrics", "当前文本"))
    }

    @Test fun sessionAndTrackFieldsParticipateInIdentity() {
        val one = PlayerTrackIdentity("session1", "id", "title", "artist")
        assertEquals(one, PlayerTrackIdentity("session1", "id", "title", "artist"))
        assertFalse(one == PlayerTrackIdentity("session2", "id", "title", "artist"))
        assertFalse(one == PlayerTrackIdentity("session1", "new", "title", "artist"))
        assertFalse(one == PlayerTrackIdentity("session1", "id", "title", "other"))
    }
}

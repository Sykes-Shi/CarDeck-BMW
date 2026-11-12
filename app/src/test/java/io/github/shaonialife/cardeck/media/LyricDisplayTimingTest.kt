package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricDisplayTimingTest {
    private val song = MediaSessionsState.NowPlaying(
        title = "歌", artist = null, playing = true, positionMs = 0, anchoredAtMs = 1_000,
        lyrics = PlayerLyricsParser.parse("lrc", "[00:01]第一句\n[00:02]第二句\n[00:03]\n[00:05]尾句"),
    )

    @Test fun bothCarViewsAdvanceAtTheBoundaryWhileProgressKeepsRealTime() {
        assertNull(song.lyricTextAt(1_699))
        assertEquals("第一句\n第二句", song.lyricTextAt(1_700))
        assertEquals(1L, song.nextMediaTickDelayMs(2_699))
        assertEquals("第一句", song.lyricTextAt(2_699, 1))
        assertEquals("第二句", song.lyricTextAt(2_700, 1))
        assertEquals("第二句", song.lyricTextAt(2_700))
        assertEquals(1_700L, song.displayPositionAt(2_700))
        assertEquals(1_000L, song.nextMediaTickDelayMs(2_700))
        assertNull(song.lyricTextAt(3_700))
        assertEquals(527L, song.nextMediaTickDelayMs(5_173))
        assertEquals("尾句", song.lyricTextAt(5_700))
    }

    @Test fun pauseSeekAndSpeedUseTheLatestPlayerPosition() {
        val paused = song.copy(playing = false, positionMs = 1_700)
        assertEquals("第二句", paused.lyricTextAt(60_000, 1))
        assertEquals(1_000L, paused.nextMediaTickDelayMs(60_000))
        val afterSeek = paused.copy(playing = true, positionMs = 1_499, anchoredAtMs = 60_000, playbackSpeed = 2f)
        assertEquals("第一句", afterSeek.lyricTextAt(60_100, 1))
        assertEquals(101L, afterSeek.nextMediaTickDelayMs(60_000))
        assertEquals("第二句", afterSeek.lyricTextAt(60_101, 1))
        val reversing = song.copy(positionMs = 1_700, playbackSpeed = -1f)
        assertEquals(1L, reversing.nextMediaTickDelayMs(1_000))
        assertEquals("第一句", reversing.lyricTextAt(1_001, 1))
    }

    @Test fun unpositionedPlainAndOutOfRangeTracksKeepTheirNormalTick() {
        val unknown = song.copy(positionMs = -1)
        assertNull(unknown.lyricTextAt(5_000))
        assertEquals(1_000L, unknown.nextMediaTickDelayMs(5_000))
        val plain = unknown.copy(lyrics = PlayerLyricsParser.parse("lyrics", "甲\n乙"))
        assertEquals("甲\n乙", plain.lyricTextAt(5_000))
        assertEquals(1_000L, plain.nextMediaTickDelayMs(5_000))
        val ended = song.copy(durationMs = 1_699)
        assertEquals("第一句", ended.lyricTextAt(60_000, 1))
        assertEquals(1_000L, ended.nextMediaTickDelayMs(60_000))
    }
}

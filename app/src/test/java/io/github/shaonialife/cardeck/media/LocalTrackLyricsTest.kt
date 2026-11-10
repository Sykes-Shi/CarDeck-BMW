package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTrackLyricsTest {
    @Test fun importedTimedLyricsIncludeTheSourceAndFollowPauseSeekAndInstrumentalBreaks() {
        val text = "\uFEFF[00:04.00]第一行\r\n[00:07]\r\n[00:12.5]第二行"
        val lyrics = LocalTrackLyrics.parseLrc(text.toByteArray())!!
        assertEquals(PlayerLyrics.Origin.IMPORTED, lyrics.origin)
        assertEquals("本地 LRC · 同步歌词", lyrics.sourceLabel)
        assertNull(lyrics.textAt(3_999))
        assertEquals("第一行", lyrics.textAt(4_000))
        assertNull(lyrics.textAt(8_000))
        assertEquals("第二行", lyrics.textAt(14_000))
        assertEquals("第一行", lyrics.textAt(5_000))
        assertEquals(lyrics, LocalTrackLyrics.parseLrc(text.removePrefix("\uFEFF").toByteArray(Charsets.UTF_16)))
    }

    @Test fun rejectsInvalidPlainBinaryAndOversizedImports() {
        assertNull(LocalTrackLyrics.parseLrc("歌名\n歌词".toByteArray()))
        assertNull(LocalTrackLyrics.parseLrc("[00:01]take\u0000this".toByteArray()))
        assertNull(LocalTrackLyrics.parseLrc(byteArrayOf(0xc0.toByte(), 0xaf.toByte())))
        assertNull(LocalTrackLyrics.parseLrc(("[00:01]" + "A".repeat(PlayerLyricsParser.MAX_CHARS)).toByteArray()))
    }

    @Test fun importedSongsRequireExactPlayerTitleAndArtistAcrossSessions() {
        val key = LyricTrack.of("player.a", "雨天", "歌手")!!.fileKey
        assertEquals(key, LyricTrack.of("player.a", " 雨天 ", "歌手")!!.fileKey)
        assertNotEquals(key, LyricTrack.of("player.b", "雨天", "歌手")!!.fileKey)
        assertNotEquals(key, LyricTrack.of("player.a", "雨天", "其他歌手")!!.fileKey)
        assertNotEquals(key, LyricTrack.of("player.a", "雨", "天歌手")!!.fileKey)
        assertNull(LyricTrack.of("player.a", "雨天", null))
    }

    @Test fun repeatedMetadataPollsUseTheTimestampOfThePlaybackPosition() {
        assertEquals(1_000L, playbackAnchor(1_000, 8_000))
        assertEquals(1_000L, playbackAnchor(1_000, 9_000))
        assertEquals(8_000L, playbackAnchor(0, 8_000))
        assertEquals(8_000L, playbackAnchor(9_000, 8_000))
    }

    @Test fun onlineMatchWinsOverLocalAndCanStartBeforeAnyFileIsRead() {
        val local = LocalTrackLyrics.parseLrc("[00:01]示例".toByteArray())!!
        val online = local.copy(origin = PlayerLyrics.Origin.ONLINE)
        val player = local.copy(origin = PlayerLyrics.Origin.PLAYER)
        assertEquals(player, preferredLyrics(player, { error("unnecessary query") }, { error("unnecessary read") }))
        assertEquals(online, preferredLyrics(null, { online }, { error("unnecessary read") }))
        val calls = mutableListOf<String>()
        assertEquals(local, preferredLyrics(null, { calls.add("online"); null }, { calls.add("local"); local }))
        assertEquals(listOf("online", "local"), calls)
    }

    @Test fun sameTitleVersionsUsePlayerIdOrAlbumAndDurationAndSurvivePickerRecreation() {
        fun track(id: String?, album: String, ms: Long) = LyricTrack.of("player", "同名曲", "歌手", id, album, ms)!!
        val studio = track("studio-id", "专辑", 215_321)
        assertNotEquals(studio.fileKey, track("live-id", "专辑", 215_321).fileKey)
        assertEquals(studio.fileKey, track("studio-id", "稍后补全的专辑", 215_320).fileKey)
        val unnamed = track(null, "演唱会", 286_120)
        assertNotEquals(unnamed.fileKey, track(null, "专辑", 286_120).fileKey)
        assertNotEquals(unnamed.fileKey, track(null, "演唱会", 295_120).fileKey)
        listOf(studio, unnamed).forEach { original ->
            assertEquals(original, LyricTrack.of(original.pkg, original.title, original.artist, original.mediaId, original.album, original.durationMs))
        }
    }
}

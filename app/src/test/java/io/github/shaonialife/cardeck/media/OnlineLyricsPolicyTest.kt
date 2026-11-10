package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLyricsPolicyTest {
    // Invented metadata and text, never submitted to a network service.
    private val track = OnlineTrack.of("com.netease.cloudmusic", "Test Song", "Test Singer", "Test Record", 123_400)!!
    private val row = OnlineRecord("TEST SONG", "test singer", "Test Record", 123.0, false, "[00:02]test", null)

    @Test
    fun exactRecordingProducesOnlineTimedLyricsAndKeepsItsTimestamps() {
        val result = matchOnlineLyrics(track, listOf(row.copy(title = "Test Song (Live)"), row))!!
        assertEquals(PlayerLyrics.Origin.ONLINE, result.origin)
        assertNull(result.textAt(1_999))
        assertEquals("test", result.textAt(2_000))
        assertEquals(result, matchOnlineLyrics(track, listOf(row, row)))
    }

    @Test
    fun mismatchInArtistEditionOrDurationAndInstrumentalGiveNoResult() {
        for (wrong in listOf(row.copy(artist = "Test Band"), row.copy(album = "Test Song (Live)"), row.copy(seconds = 127.0),
            row.copy(seconds = null), row.copy(seconds = Double.NaN), row.copy(instrumental = true))) {
            assertNull(matchOnlineLyrics(track, listOf(wrong)))
        }
        assertNull(matchOnlineLyrics(track, emptyList()))
    }

    @Test
    fun ambiguousVersionsCannotLeakDifferentLyricsIntoThisTrack() {
        val missingMetadata = track.copy(album = "", durationMs = 0)
        assertNull(matchOnlineLyrics(missingMetadata, listOf(row, row.copy(album = "Other", synced = "[00:01]different"))))
        val plain = matchOnlineLyrics(track, listOf(row.copy(synced = null, plain = "plain text")))!!
        assertEquals(PlayerLyrics.Origin.ONLINE, plain.origin)
        assertFalse(plain.synchronized)
    }

    @Test
    fun bilingualArtistAndNormalizedPunctuationPermitTheSameRecordingAcrossPlayers() {
        val album = OnlineTrack.of("com.netease.cloudmusic", "你不爱我我就爱别人", "Peace Hotel（和平饭店）", "隐藏菜单", 238425)!!
        val song = row.copy(title = album.title, artist = "和平饭店", album = "隐藏菜单", seconds = 238.0)
        assertEquals("和平饭店", searchArtist(album))
        assertEquals(PlayerLyrics.Origin.ONLINE, matchOnlineLyrics(album, listOf(song))?.origin)
        val spotify = album.copy(pkg = "com.spotify.music", artist = "和平饭店", album = "另一张合辑")
        assertEquals(PlayerLyrics.Origin.ONLINE, matchOnlineLyrics(spotify, listOf(song))?.origin)
        assertNull(matchOnlineLyrics(spotify, listOf(song.copy(title = song.title + " (Live)"))))
    }

    @Test
    fun spotifyRecommendationLabelIsExcludedFromBothQueriesAndRecordingMatches() {
        val spotify = OnlineTrack.of("com.spotify.music", "愛, 很簡單", "陶喆 • 为你推荐", "David Tao", 269_560)!!
        assertEquals("陶喆", searchArtist(spotify))
        assertEquals("陶喆", searchArtist(spotify.copy(artist = "陶喆 • 為你推薦")))
        val record = row.copy(title = "爱，很简单", artist = "陶喆", album = "David Tao", seconds = 269.56)
        val fold = { s: String -> s.replace('愛', '爱').replace('簡', '简').replace('單', '单') }
        assertEquals(PlayerLyrics.Origin.ONLINE, matchOnlineLyrics(spotify, listOf(record), fold)?.origin)
        assertEquals(10L, chooseCatalogueSong(spotify, listOf(CatalogueSong(10L, record)), fold))
        assertNull(matchOnlineLyrics(spotify, listOf(record.copy(artist = "另一位歌手")), fold))
        assertNull(matchOnlineLyrics(spotify, listOf(record.copy(seconds = 432.35)), fold))
        assertTrue(spotify.matchesPlayback(spotify.pkg, spotify.title, "陶喆 • 为你推荐", 269_560, true))
    }

    @Test
    fun legitimateArtistCreditsAreNeverDiscardedAfterAnArbitraryBullet() {
        val spotify = track.copy(pkg = "com.spotify.music", artist = "A • B")
        assertEquals("A • B", searchArtist(spotify))
        assertNull(matchOnlineLyrics(spotify, listOf(row.copy(artist = "A"))))
        val otherPlayer = track.copy(artist = "歌手 • 为你推荐")
        assertEquals(otherPlayer.artist, searchArtist(otherPlayer))
    }

    @Test
    fun catalogueChoosesStudioOrShowRecordingAndRejectsACover() {
        val current = OnlineTrack.of("com.spotify.music", "你不爱我我就爱别人", "和平饭店", "隐藏菜单", 238425)!!
        val studio = CatalogueSong(1415576908, row.copy(title = current.title, artist = "Peace Hotel(和平饭店)", album = current.album, seconds = 238.425))
        val show = CatalogueSong(1861583359, studio.record.copy(album = "草莓星球来的人 第1期", seconds = 241.340))
        val cover = CatalogueSong(1945552357, studio.record.copy(artist = "子昂己意", seconds = 241.346))
        val results = listOf(show, studio, cover)
        assertEquals(studio.id, chooseCatalogueSong(current, results))
        assertEquals(show.id, chooseCatalogueSong(current.copy(durationMs = 241340, album = show.record.album!!), results))
        assertNull(chooseCatalogueSong(current.copy(album = "", durationMs = 239700), results))
        assertNull(chooseCatalogueSong(current, listOf(cover)))
    }

    @Test
    fun onlyNeteaseNumericMediaIdCanDisambiguateOtherwiseEqualVerifiedCatalogueRows() {
        val current = OnlineTrack.of(track.pkg, track.title, track.artist, track.album, track.durationMs, "42")!!
        val songs = listOf(CatalogueSong(42, row), CatalogueSong(43, row))
        assertEquals(42L, chooseCatalogueSong(current, songs))
        assertNull(chooseCatalogueSong(current, listOf(songs.first().copy(record = row.copy(artist = "Other band")))))
        assertNull(chooseCatalogueSong(current.copy(catalogueId = null), songs))
        assertNull(OnlineTrack.of("com.spotify.music", track.title, track.artist, track.album, track.durationMs, "42")!!.catalogueId)
        assertNull(OnlineTrack.of(track.pkg, track.title, track.artist, track.album, track.durationMs, "https://example.test/private")!!.catalogueId)
    }

    @Test
    fun catalogueCreditsAndInstrumentalSentinelsNeverBecomeSyncedLyrics() {
        val parsed = parseCatalogueLyrics("{\"t\":0,\"c\":[]}\n[00:01.200]sample")!!
        assertEquals(PlayerLyrics.Origin.NETEASE, parsed.origin)
        assertEquals("sample", parsed.textAt(1200))
        assertNull(parseCatalogueLyrics("[99:00.00]纯音乐，请欣赏"))
        assertNull(parseCatalogueLyrics("{\"t\":0}"))
    }

    @Test
    fun ignoredAppsMissingMetadataAndControlCharactersAreNeverQueried() {
        for (pkg in listOf("com.youku.phone", "com.baidu.BaiduMap", null)) {
            assertNull(OnlineTrack.of(pkg, "Video", "Someone", null, 1000))
        }
        assertNull(OnlineTrack.of(track.pkg, null, track.artist, null, 123))
        assertNull(OnlineTrack.of(track.pkg, track.title, "line\nbreak", null, 123))
    }

    @Test
    fun oldCompletionIsNotForANewTrackAnotherPlayerOrPausedPlayback() {
        assertTrue(track.matchesPlayback(track.pkg, track.title, track.artist, track.durationMs, true))
        assertFalse(track.matchesPlayback(track.pkg, "Next song", track.artist, track.durationMs, true))
        assertFalse(track.matchesPlayback("com.spotify.music", track.title, track.artist, track.durationMs, true))
        assertFalse(track.matchesPlayback(track.pkg, track.title, track.artist, track.durationMs, false))
    }

    @Test
    fun serverCooldownIsNeverShortenedAndClientFailuresDoNotHammerTheProvider() {
        assertEquals(75_000L, lyricsRetryDelayMs("75", 0))
        assertEquals(60_000L, lyricsRetryDelayMs("unreadable", 0))
        assertEquals(75_000L, lyricsRetryDelayMs("Thu, 1 Jan 1970 00:01:15 GMT", 0))
        assertTrue(lyricsRetryDelayMs("9999999999999999999999999", 0) >= 24 * 3_600_000L)
        assertTrue(OnlineLyricState.FAILURE.ttlMs >= 10_000L)
        assertTrue(OnlineLyricState.ABSENT.ttlMs > OnlineLyricState.FAILURE.ttlMs)
    }

    @Test fun neteaseQueriesItsOwnCatalogueFirstAndSpotifyRetainsItsLrclibFirstMatch() {
        assertEquals(listOf(LyricProvider.NETEASE, LyricProvider.LRCLIB), lyricProvidersFor("com.netease.cloudmusic"))
        assertEquals(listOf(LyricProvider.LRCLIB, LyricProvider.NETEASE), lyricProvidersFor("com.spotify.music"))
        assertTrue(OnlineLyricState.TIMEOUT.isTransientFailure())
        assertTrue(OnlineLyricState.NO_NETWORK.isTransientFailure())
        assertTrue(OnlineLyricState.FAILURE.isTransientFailure())
        assertFalse(OnlineLyricState.ABSENT.isTransientFailure())
        assertFalse(OnlineLyricState.LIMITED.isTransientFailure())
    }

    @Test fun eightMinuteXuWeiRecordingDoesNotReceiveFourMinuteStudioOrAnotherLiveTimeline() {
        val track = OnlineTrack.of("com.netease.cloudmusic", "曾经的你", "许巍", "", 515_000)!!
        val live = CatalogueSong(1960403857L, OnlineRecord(track.title, track.artist, "许巍此时此刻巡回演唱会DVD", 515.413, false, null, null))
        val studio = CatalogueSong(167975L, live.record.copy(album = "每一刻都是崭新的", seconds = 261.504))
        val otherLive = CatalogueSong(29378163L, live.record.copy(seconds = 456.629))
        assertEquals(live.id, chooseCatalogueSong(track, listOf(studio, otherLive, live)))
        assertNull(chooseCatalogueSong(track, listOf(otherLive, studio)))
    }

    @Test fun onlyNeteaseTransportFailuresMayUseTheMobileRetry() {
        for (failure in listOf("dns", "connect", "transport", "tls_connect")) {
            assertTrue(allowsCatalogueTransportRetry(LyricProvider.NETEASE, OnlineLyricState.FAILURE, failure))
            assertFalse(allowsCatalogueTransportRetry(LyricProvider.LRCLIB, OnlineLyricState.FAILURE, failure))
        }
        assertTrue(allowsCatalogueTransportRetry(LyricProvider.NETEASE, OnlineLyricState.TIMEOUT, "timeout"))
        assertTrue(allowsCatalogueTransportRetry(LyricProvider.NETEASE, OnlineLyricState.TIMEOUT, "deadline"))
        for (reason in listOf("http_401", "http_403", "http_404", "http_429", "api_403", "permission", "tls_identity", "tls_protocol", "tls_io", "io", "invalid_json", "deadline")) {
            assertFalse(allowsCatalogueTransportRetry(LyricProvider.NETEASE, OnlineLyricState.FAILURE, reason))
        }
        for (state in listOf(OnlineLyricState.LIMITED, OnlineLyricState.ABSENT, OnlineLyricState.NO_NETWORK)) {
            assertFalse(allowsCatalogueTransportRetry(LyricProvider.NETEASE, state, "timeout"))
        }
    }

    @Test fun androidTlsExceptionsPreserveTransportAndResponseStage() {
        fun handshake(cause: Throwable) = javax.net.ssl.SSLHandshakeException("Handshake failed").apply { initCause(cause) }
        assertEquals("timeout", lyricsIoFailure(handshake(java.net.SocketTimeoutException()), true))
        assertEquals("transport", lyricsIoFailure(handshake(java.io.EOFException()), true))
        assertEquals("transport", lyricsIoFailure(javax.net.ssl.SSLProtocolException("Connection reset by peer"), true))
        assertEquals("dns", lyricsIoFailure(java.io.IOException(java.net.UnknownHostException()), true))
        val opaque = javax.net.ssl.SSLHandshakeException("Handshake failed")
        assertEquals("tls_connect", lyricsIoFailure(opaque, true))
        assertEquals("tls_io", lyricsIoFailure(opaque, false))
    }

    @Test fun tlsIdentityProtocolAndNetworkPermissionErrorsNeverTriggerMobileRetry() {
        val blocked = listOf(
            javax.net.ssl.SSLHandshakeException("unexpected EOF").apply { initCause(java.security.cert.CertificateException()) } to "tls_identity",
            javax.net.ssl.SSLPeerUnverifiedException("peer") to "tls_identity",
            javax.net.ssl.SSLHandshakeException("CERTIFICATE_VERIFY_FAILED") to "tls_identity",
            javax.net.ssl.SSLHandshakeException("hostname verification failed") to "tls_identity",
            javax.net.ssl.SSLProtocolException("unexpected record") to "tls_protocol",
            javax.net.ssl.SSLHandshakeException("tlsv1 alert protocol version") to "tls_protocol",
            java.net.SocketException("socket failed: EPERM (Operation not permitted)") to "permission",
        )
        for ((error, expected) in blocked) {
            val detail = lyricsIoFailure(error, true)
            assertEquals(expected, detail)
            assertFalse(allowsCatalogueTransportRetry(LyricProvider.NETEASE, OnlineLyricState.FAILURE, detail))
        }
    }

    @Test fun mobileRetryOnlyAllowsTheTwoCanonicalPublicHttpsCataloguePaths() {
        fun permitted(value: String) = isPublicCatalogueEndpoint(java.net.URL(value))
        assertTrue(permitted("https://music.163.com/api/search/get?type=1&s=query"))
        assertTrue(permitted("https://music.163.com/api/song/lyric?id=1&lv=-1"))
        for (bad in listOf(
            "http://music.163.com/api/song/lyric", "https://music.163.com:9999/api/search/get",
            "https://music.163.com/private", "https://music.163.com.example.com/api/song/lyric",
            "https://lrclib.net/api/search/get", "https://api.spotify.com/api/search/get",
            "https://name@music.163.com/api/search/get", "https://music.163.com/api/search/get#fragment",
        )) assertFalse(bad, permitted(bad))
    }
}

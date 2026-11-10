package io.github.shaonialife.cardeck.media

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal data class OnlineTrack(
    val pkg: String, val title: String, val artist: String, val album: String, val durationMs: Long, val catalogueId: Long? = null,
) {
    // Spotify sometimes appends its recommendation context to ARTIST. Keep the original for
    // session identity, but use just the credited artist for both catalogue searches and matching.
    val recordingArtist: String
        get() {
            if (pkg != "com.spotify.music") return artist
            val separator = artist.lastIndexOf('•')
            if (separator <= 0) return artist
            val label = artist.substring(separator + 1).trim()
            if (label !in spotifyRecommendationLabels) return artist
            return artist.substring(0, separator).trim().ifEmpty { artist }
        }

    fun matchesPlayback(pkg: String?, title: String?, artist: String?, durationMs: Long, playing: Boolean): Boolean =
        playing && this.pkg == pkg && this.title == title?.trim() && this.artist == artist?.trim() &&
            this.durationMs == durationMs.coerceAtLeast(0)

    companion object {
        private val spotifyRecommendationLabels = setOf("为你推荐", "為你推薦")
        // Limit outbound metadata to the tested music players, not video, navigation or browsers.
        private val musicPlayers = setOf("com.netease.cloudmusic", "com.spotify.music", "com.tencent.qqmusic", "com.kugou.android")

        fun of(pkg: String?, title: String?, artist: String?, album: String?, durationMs: Long, mediaId: String? = null): OnlineTrack? {
            if (pkg !in musicPlayers || title.isNullOrBlank() || artist.isNullOrBlank()) return null
            val fields = listOf(title, artist, album.orEmpty())
            if (fields.any { it.length > 256 || it.any(Char::isISOControl) }) return null
            // The player ID is only a local tie-breaker among results returned by the public song search.
            val id = if (pkg == "com.netease.cloudmusic" && mediaId != null && mediaId.length in 1..16 &&
                mediaId.all { it in '0'..'9' }) mediaId.toLongOrNull()?.takeIf { it > 0 } else null
            return OnlineTrack(pkg!!, title.trim(), artist.trim(), album.orEmpty().trim(), durationMs.coerceAtLeast(0), id)
        }
    }
}

internal data class OnlineRecord(
    val title: String, val artist: String, val album: String?, val seconds: Double?,
    val instrumental: Boolean, val synced: String?, val plain: String?,
)

private val spaces = Regex("\\s+")
private val artistSeparator = Regex("""\s*(?:[,;、，；]|\s+[&×/]\s+|\s+feat\.?\s+)\s*""")
private val edition = Regex("""\b(?:live|remix|instrumental|acoustic|demo|karaoke|unplugged)\b|现场|現場|演唱会|演唱會|伴奏|翻唱""")
private val bilingual = Regex("""^([^()]+?)\s*\(([^()]+)\)$""")
private val han = Regex("""\p{IsHan}""")
private val latinName = Regex("""[A-Za-z][A-Za-z .’'&-]*""")

private fun normalized(text: String) =
    Normalizer.normalize(text.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(spaces, " ")

/** Common artist display convention, e.g. Peace Hotel(和平饭店). Keep unrelated parentheses intact. */
private fun bilingualNames(name: String): List<String> {
    val parts = bilingual.matchEntire(Normalizer.normalize(name, Normalizer.Form.NFKC))
        ?.groupValues?.drop(1)?.map(String::trim) ?: return emptyList()
    if (parts.count { han.containsMatchIn(it) } != 1 || parts.none { latinName.matches(it) }) return emptyList()
    if (parts.any { edition.containsMatchIn(it.lowercase(Locale.ROOT)) }) return emptyList()
    return parts
}

internal fun searchArtist(track: OnlineTrack): String {
    val artist = track.recordingArtist
    val aliases = bilingualNames(artist)
    return aliases.firstOrNull { han.containsMatchIn(it) == han.containsMatchIn(track.title) } ?: artist
}

/** Exact normalized title and artist identities are mandatory. Album differences between services
 * may represent a reissue; in that case require a close duration and matching edition hints. */
internal class RecordingMatch(private val track: OnlineTrack, private val fold: (String) -> String = { it }) {
    private fun key(s: String) = normalized(fold(s))
    // Spotify "愛, 很簡單" and catalogue "爱，很简单" keep the same comma after width folding.
    private fun titleKey(s: String) = key(s).replace(" ,", ",").replace(", ", ",")
    private val title = titleKey(track.title)
    private val artist = key(track.recordingArtist)
    private val album = key(track.album)
    private fun artists(s: String) = s.split(artistSeparator).filter(String::isNotBlank).sorted()
    private fun sameArtist(left: String, right: String): Boolean {
        val a = artists(left)
        val b = artists(right)
        if (a == b) return true
        if (a.size != 1 || b.size != 1) return false
        return (bilingualNames(left) + left).toSet().intersect((bilingualNames(right) + right).toSet()).isNotEmpty()
    }
    private fun editions(s: String) = edition.findAll(s).map { it.value }.toSet()

    fun rank(row: OnlineRecord): Int {
        if (row.title.length > 256 || row.artist.length > 256 || (row.album?.length ?: 0) > 256) return -1
        if (titleKey(row.title) != title) return -1
        val candidateArtist = key(row.artist)
        if (candidateArtist != artist && !sameArtist(candidateArtist, artist)) return -1
        val candidateAlbum = key(row.album.orEmpty())
        val exactAlbum = album.isNotEmpty() && candidateAlbum == album
        if (!exactAlbum && album.isNotEmpty() && editions(album) != editions(candidateAlbum)) return -1
        if (track.durationMs == 0L) return if (exactAlbum) 3 else if (album.isEmpty()) 0 else -1
        val seconds = row.seconds?.takeIf { it.isFinite() && it > 0 } ?: return -1
        val delta = abs(seconds * 1000 - track.durationMs)
        return when {
            delta <= 2_000 && exactAlbum -> 4
            delta <= 2_000 -> 2
            else -> -1
        }
    }
}

internal fun matchOnlineLyrics(track: OnlineTrack, records: List<OnlineRecord>, fold: (String) -> String = { it }): PlayerLyrics? {
    val matcher = RecordingMatch(track, fold)
    val ranked = records.map { it to matcher.rank(it) }.filter { it.second >= 0 }
    val best = ranked.maxOfOrNull { it.second } ?: return null
    val matches = ranked.filter { it.second == best }.map { it.first }
    if (matches.any { it.instrumental }) return null
    // Rows without lyrics do not conflict with available text; distinct recordings still must agree.
    return matches.mapNotNull {
        PlayerLyricsParser.parse("online.lrc", it.synced)?.takeIf { parsed -> parsed.synchronized }
            ?: PlayerLyricsParser.parse("online.lyrics", it.plain)
    }.distinct().singleOrNull()?.copy(origin = PlayerLyrics.Origin.ONLINE)
}

internal data class CatalogueSong(val id: Long, val record: OnlineRecord)

internal fun chooseCatalogueSong(track: OnlineTrack, songs: List<CatalogueSong>, fold: (String) -> String = { it }): Long? {
    val matcher = RecordingMatch(track, fold)
    val ranked = songs.filter { it.id > 0 }.map { it.id to matcher.rank(it.record) }.filter { it.second >= 0 }
    track.catalogueId?.let { id -> if (ranked.any { it.first == id }) return id }
    val score = ranked.maxOfOrNull { it.second } ?: return null
    if (score == 0) return null
    return ranked.filter { it.second == score }.map { it.first }.distinct().singleOrNull()
}

internal fun parseCatalogueLyrics(raw: String?): PlayerLyrics? {
    if (raw.isNullOrBlank() || raw.length > PlayerLyricsParser.MAX_CHARS) return null
    // Some public LRC files prefix the actual time-tagged rows with JSON credit entries.
    val clean = raw.lineSequence().filterNot { it.trimStart().startsWith("{") }.joinToString("\n")
    val parsed = PlayerLyricsParser.parse("netease.lrc", clean) ?: return null
    if (parsed.lines.none { it.text.isNotBlank() && !it.text.contains("纯音乐，请欣赏") && !it.text.contains("纯音乐，请您欣赏") }) return null
    return parsed.copy(origin = PlayerLyrics.Origin.NETEASE)
}

internal enum class OnlineLyricState(val hint: String?, val ttlMs: Long) {
    SEARCHING("正在查找歌词…", 0),
    FOUND(null, 24 * 3_600_000L),
    ABSENT("未找到匹配这首歌的歌词", 3_600_000L),
    NO_NETWORK("CarDeck 暂未找到可用上网通道，歌词稍后重试", 10_000L),
    TIMEOUT("歌词查询超时，稍后重试", 12_000L),
    FAILURE("歌词服务暂时不可用，稍后重试", 20_000L),
    LIMITED("歌词服务请求受限，稍后重试", 0),
}

internal enum class LyricProvider { LRCLIB, NETEASE }

internal fun lyricProvidersFor(pkg: String): List<LyricProvider> =
    if (pkg == "com.netease.cloudmusic") listOf(LyricProvider.NETEASE, LyricProvider.LRCLIB)
    else listOf(LyricProvider.LRCLIB, LyricProvider.NETEASE)

internal fun OnlineLyricState.isTransientFailure() =
    this == OnlineLyricState.NO_NETWORK || this == OnlineLyricState.TIMEOUT || this == OnlineLyricState.FAILURE

internal fun allowsCatalogueTransportRetry(provider: LyricProvider, state: OnlineLyricState, detail: String): Boolean =
    provider == LyricProvider.NETEASE && (
        state == OnlineLyricState.TIMEOUT && (detail == "timeout" || detail == "deadline") ||
            state == OnlineLyricState.FAILURE && detail in setOf("dns", "connect", "transport", "tls_connect")
    )

/** Return bounded diagnostic codes only. Android may wrap socket or certificate errors inside
 * SSLHandshakeException, and some providers report only SSLException before the first response. */
internal fun lyricsIoFailure(error: java.io.IOException, awaitingHeaders: Boolean): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
    val tls = causes.any { it is javax.net.ssl.SSLException }
    val messages = causes.map { it.message.orEmpty().lowercase(Locale.ROOT) }
    fun mentions(vararg terms: String) = messages.any { msg -> terms.any { it in msg } }
    if (causes.any { it is SecurityException } ||
        mentions("permission denied", "operation not permitted", "eacces", "eperm")) return "permission"
    if (tls) {
        if (causes.any { it is javax.net.ssl.SSLPeerUnverifiedException || it is javax.net.ssl.SSLKeyException ||
                it is java.security.GeneralSecurityException } ||
            mentions("certificat", "certpath", "cert path", "chain validation", "trust anchor", "trustanchor", "hostname", "host name",
                "pinning", "pkix", "unknown ca", "unknown_ca", "peer not authenticated")) return "tls_identity"
        if (mentions("protocol_version", "protocol version", "unsupported protocol", "wrong version",
                "wrong_version", "no protocols", "cipher", "handshake_failure", "alert handshake failure")) return "tls_protocol"
    }
    if (causes.any { it is java.net.SocketTimeoutException }) return "timeout"
    if (causes.any { it is java.net.UnknownHostException }) return "dns"
    if (causes.any { it is java.net.ConnectException || it is java.net.NoRouteToHostException }) return "connect"
    if (causes.any { it is java.io.EOFException } ||
        mentions("connection reset", "econnreset", "connection closed", "broken pipe",
            "unexpected end of stream", "ssl_error_syscall")) return "transport"
    if (causes.any { it is javax.net.ssl.SSLProtocolException }) return "tls_protocol"
    if (tls) return if (awaitingHeaders) "tls_connect" else "tls_io"
    return "io"
}

internal fun isPublicCatalogueEndpoint(url: java.net.URL): Boolean =
    url.protocol == "https" && url.host == "music.163.com" && url.port == -1 && url.userInfo == null && url.ref == null &&
        (url.path == "/api/search/get" || url.path == "/api/song/lyric")

/** Accept both HTTP forms. Do not shorten the server's delay, including for very large integers. */
internal fun lyricsRetryDelayMs(value: String?, wallClockMs: Long): Long {
    val header = value?.trim().orEmpty()
    if (header.isNotEmpty() && header.all { it in '0'..'9' }) {
        return (header.toLongOrNull() ?: Long.MAX_VALUE).coerceAtMost(Long.MAX_VALUE / 4_000) * 1_000
    }
    val date = runCatching { ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    return date?.let { (it - wallClockMs).coerceIn(0, Long.MAX_VALUE / 4) } ?: 60_000L
}

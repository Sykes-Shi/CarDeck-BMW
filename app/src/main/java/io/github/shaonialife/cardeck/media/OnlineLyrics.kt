package io.github.shaonialife.cardeck.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.os.SystemClock
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.net.ProjectionInternet
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.random.Random

/** Optional lyrics fallback. Requests and cache entries are scoped to the selected recording. */
object OnlineLyrics {
    private const val MAX_BYTES = 1024 * 1024
    private data class Outcome(val state: OnlineLyricState, val lyrics: PlayerLyrics? = null, val retryMs: Long = 0, val detail: String = "")
    private data class Entry(val outcome: Outcome, val expires: Long)
    private data class Request(val track: OnlineTrack, val session: Long, val revision: Long)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "online-lyrics").apply { isDaemon = true } }
    private val cache = object : LinkedHashMap<OnlineTrack, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<OnlineTrack, Entry>?) = size > 32
    }
    private var wanted: Request? = null
    private var revision = 0L
    private var busy = false
    private var connection: HttpsURLConnection? = null
    // A service's Retry-After applies only to that service, across all songs.
    private val pauseUntil = mutableMapOf<LyricProvider, Long>() // accessed by the single worker
    private var lastDirectRetryOwner: Pair<Long, Long>? = null // session and owner revision, without retaining track metadata
    private val simplify by lazy { runCatching { android.icu.text.Transliterator.getInstance("Traditional-Simplified") }.getOrNull() }
    private var nextRequestAt = 0L

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("online_lyrics", Context.MODE_PRIVATE)
            .getBoolean("enabled", ctx.packageName == "io.github.shaonialife.cardeck.bmwprobe")

    fun isDirectRetryEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("online_lyrics", Context.MODE_PRIVATE)
            .getBoolean("direct_catalogue_retry", ctx.packageName == "io.github.shaonialife.cardeck.bmwprobe")

    fun setDirectRetryEnabled(ctx: Context, enabled: Boolean) {
        val stale = synchronized(this) {
            ctx.getSharedPreferences("online_lyrics", Context.MODE_PRIVATE).edit().putBoolean("direct_catalogue_retry", enabled).apply()
            cache.entries.removeAll { it.value.outcome.state.isTransientFailure() }
            invalidate()
        }
        cancel(stale)
        MediaSessionsState.resyncHook?.invoke()
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        val stale = synchronized(this) {
            ctx.getSharedPreferences("online_lyrics", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
            cache.clear()
            invalidate()
        }
        cancel(stale)
        MediaSessionsState.resyncHook?.invoke()
    }

    fun clearPending() = cancel(synchronized(this) {
        if (wanted == null && connection == null) null else invalidate()
    })

    fun onProjectionEnded() {
        clearPending()
        synchronized(this) { cache.entries.removeAll { it.value.outcome.state.isTransientFailure() } }
    }

    private fun invalidate(): HttpsURLConnection? {
        revision++
        wanted = null
        return connection.also { connection = null }
    }

    private fun cancel(stale: HttpsURLConnection?) {
        stale?.let { conn -> Thread({ runCatching { conn.disconnect() } }, "lyrics-cancel").apply { isDaemon = true }.start() }
    }

    fun forTrack(
        ctx: Context, pkg: String?, title: String?, artist: String?, album: String?, durationMs: Long, playing: Boolean, mediaId: String? = null,
    ): PlayerLyrics? {
        if (!isEnabled(ctx) || !projecting()) return null
        val track = OnlineTrack.of(pkg, title, artist, album, durationMs, mediaId) ?: return null
        val app = ctx.applicationContext
        synchronized(this) {
            if (!playing) {
                return cache[track]?.takeIf { SystemClock.elapsedRealtime() < it.expires }?.outcome?.lyrics
            }
            val request = Request(track, VirtualLauncher.sessionGeneration(), revision)
            wanted = request
            val now = SystemClock.elapsedRealtime()
            cache[track]?.takeIf { now < it.expires }?.let { return it.outcome.lyrics }
            if (!busy && now >= nextRequestAt) {
                busy = true
                worker.execute { load(app, request) }
            }
            return null
        }
    }

    fun statusFor(ctx: Context, pkg: String?, title: String?, artist: String?, durationMs: Long): String? {
        if (!isEnabled(ctx) || !projecting()) return null
        return synchronized(this) {
            val track = wanted?.track ?: return null
            if (track.pkg != pkg || track.title != title?.trim() || track.artist != artist?.trim() ||
                track.durationMs != durationMs.coerceAtLeast(0)) return null
            val now = SystemClock.elapsedRealtime()
            val state = cache[track]?.takeIf { now < it.expires }?.outcome?.state
                ?: OnlineLyricState.SEARCHING
            state.hint
        }
    }

    private fun projecting() = VirtualLauncher.appContext() != null && SessionController.videoSender?.isRunning == true
    private fun current(ctx: Context, req: Request): Boolean = synchronized(this) {
        val playing = MediaSessionsState.nowPlaying ?: return false
        req == wanted && isEnabled(ctx) && projecting() && VirtualLauncher.isCurrentSession(req.session) &&
            req.track.matchesPlayback(playing.pkg, playing.title, playing.artist, playing.durationMs, playing.playing) &&
            req.track.album == playing.album.orEmpty().trim() &&
            (req.track.catalogueId == null || req.track.catalogueId == playing.mediaId?.toLongOrNull())
    }

    private fun load(ctx: Context, req: Request) {
        val outcome = try {
            if (current(ctx, req)) fetch(ctx, req) else null
        } catch (_: Exception) {
            Outcome(OnlineLyricState.FAILURE)
        } finally {
            synchronized(this) { connection = null }
        }
        var logState = false
        val accepted = synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            nextRequestAt = now + 1_000L
            busy = false
            if (outcome == null || !current(ctx, req)) false else {
                logState = cache[req.track]?.outcome?.state != outcome.state
                cache[req.track] = Entry(outcome, now + maxOf(outcome.state.ttlMs, outcome.retryMs))
                true
            }
        }
        if (accepted && outcome != null) {
            if (logState) SessionController.appendLog("LYRICS: lookup status=${outcome.state.name} source=${outcome.lyrics?.origin?.name ?: "none"}")
            MediaSessionsState.resyncHook?.invoke()
        }
    }

    private fun fetch(ctx: Context, req: Request): Outcome {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Outcome(OnlineLyricState.NO_NETWORK)
        val outcomes = mutableMapOf<LyricProvider, Outcome>()
        val lookupDeadline = SystemClock.elapsedRealtime() + 28_000L
        for (provider in lyricProvidersFor(req.track.pkg)) {
            if (!current(ctx, req)) return Outcome(OnlineLyricState.NO_NETWORK)
            val network = ProjectionInternet.select(cm) ?: return Outcome(OnlineLyricState.NO_NETWORK)
            // A slow/unreachable first service must not spend the fallback service's deadline.
            val lookup = Lookup(ctx, req, cm, network, lookupDeadline)
            val outcome = lookup.attempt(provider) {
                when (provider) {
                    LyricProvider.LRCLIB -> queryLrclib(req, lookup)
                    LyricProvider.NETEASE -> queryCatalogue(req, lookup)
                }
            }
            if (outcome.state == OnlineLyricState.FOUND) return outcome
            outcomes[provider] = outcome
            if (allowsCatalogueTransportRetry(provider, outcome.state, outcome.detail) &&
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                current(ctx, req) && SystemClock.elapsedRealtime() < lookupDeadline
            ) {
                // Try the same public catalogue while there is still lookup time. In particular a
                // slow second provider must not exhaust the budget for an authorized mobile retry.
                val skip = when {
                    !isDirectRetryEnabled(ctx) -> "disabled"
                    lastDirectRetryOwner == (req.session to req.revision) -> "already_attempted"
                    else -> null
                }
                val cell = if (skip == null) ProjectionInternet.selectDirectLyricsFallback(cm, network) else null
                if (cell == null) {
                    SessionController.appendLog("LYRICS: provider=NETEASE cellularRetry=skipped reason=${skip ?: "no_eligible_route"}")
                } else {
                    val mobile = Lookup(ctx, req, cm, cell, lookupDeadline, network)
                    val retry = mobile.attempt(provider) { queryCatalogue(req, mobile) }
                    if (retry.lyrics != null) return retry.copy(lyrics = retry.lyrics.copy(origin = PlayerLyrics.Origin.NETEASE_MOBILE_RETRY))
                    outcomes[provider] = retry
                }
            }
        }
        // A network or provider failure is not evidence that a track has no lyrics.
        return outcomes.values.firstOrNull { it.state == OnlineLyricState.NO_NETWORK }
            ?: outcomes.values.firstOrNull { it.state == OnlineLyricState.TIMEOUT }
            ?: outcomes.values.firstOrNull { it.state == OnlineLyricState.FAILURE }
            ?: outcomes.values.filter { it.state == OnlineLyricState.LIMITED }.minByOrNull { it.retryMs }
            ?: Outcome(OnlineLyricState.ABSENT)
    }

    private fun queryLrclib(req: Request, lookup: Lookup): Outcome {
        val title = req.track.title
        val artist = searchArtist(req.track)
        val rows = JSONArray(lookup.read("search", "https://lrclib.net/api/search?track_name=${encode(title)}&artist_name=${encode(artist)}"))
        val records = (0 until minOf(rows.length(), 20)).mapNotNull { rows.optJSONObject(it)?.let(::record) }
        return outcomeFor(matchOnlineLyrics(req.track, records, ::simplified))
    }

    private fun queryCatalogue(req: Request, lookup: Lookup): Outcome {
        val query = encode(simplified("${req.track.title} ${searchArtist(req.track)}"))
        val search = JSONObject(lookup.read("search", "https://music.163.com/api/search/get?type=1&limit=15&offset=0&s=$query"))
        val result = search.optJSONObject("result")
        if (search.optInt("code") != 200 || result == null) return Outcome(OnlineLyricState.FAILURE, detail = "api_${search.optInt("code", -1)}")
        val rows = result.optJSONArray("songs") ?: return Outcome(
            if (result.optInt("songCount", -1) == 0) OnlineLyricState.ABSENT else OnlineLyricState.FAILURE, detail = "no_rows")
        val songs = (0 until minOf(rows.length(), 15)).mapNotNull { rows.optJSONObject(it)?.let(::catalogueSong) }
        val id = chooseCatalogueSong(req.track, songs, ::simplified) ?: return Outcome(OnlineLyricState.ABSENT, detail = "no_matching_recording")
        val response = JSONObject(lookup.read("lyrics", "https://music.163.com/api/song/lyric?id=$id&lv=-1"))
        if (response.optInt("code") != 200) return Outcome(OnlineLyricState.FAILURE, detail = "api_${response.optInt("code", -1)}")
        return outcomeFor(parseCatalogueLyrics(response.optJSONObject("lrc")?.opt("lyric") as? String))
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
    private fun simplified(value: String): String = if (value.any { it.code > 127 }) simplify?.transliterate(value) ?: value else value
    private fun outcomeFor(lyrics: PlayerLyrics?) = Outcome(if (lyrics == null) OnlineLyricState.ABSENT else OnlineLyricState.FOUND, lyrics)
    private class ReadFailure(val outcome: Outcome) : java.io.IOException()

    private class Lookup(
        val ctx: Context, val req: Request, val cm: ConnectivityManager, val network: Network,
        lookupDeadline: Long, private val failedDefault: Network? = null,
    ) {
        private val startedAt = SystemClock.elapsedRealtime()
        private val deadline = minOf(lookupDeadline, startedAt + 10_000L)
        private var stage = "search"
        private var awaitingHeaders = false
        @Suppress("DEPRECATION")
        private val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()

        fun attempt(provider: LyricProvider, block: () -> Outcome): Outcome {
            val remaining = (pauseUntil[provider] ?: 0) - SystemClock.elapsedRealtime()
            if (remaining > 0) return Outcome(OnlineLyricState.LIMITED, retryMs = remaining)
            val outcome = try {
                val result = block()
                if (failedDefault != null) checkLive(checkRoute = true)
                result
            } catch (e: ReadFailure) {
                e.outcome
            } catch (e: java.io.IOException) {
                val detail = lyricsIoFailure(e, awaitingHeaders)
                Outcome(if (detail == "timeout") OnlineLyricState.TIMEOUT else OnlineLyricState.FAILURE, detail = detail)
            } catch (e: Exception) {
                Outcome(OnlineLyricState.FAILURE, detail = when (e) {
                    is org.json.JSONException -> "invalid_json"
                    is SecurityException -> "permission"
                    else -> "internal"
                })
            }
            if (outcome.state == OnlineLyricState.LIMITED) pauseUntil[provider] = SystemClock.elapsedRealtime() + outcome.retryMs
            if (current(ctx, req)) {
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                val route = when {
                    caps == null -> "lost"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    else -> "other"
                }
                SessionController.appendLog("LYRICS: provider=${provider.name} status=${outcome.state.name} stage=$stage route=$route" +
                    " cellularRetry=${failedDefault != null}" +
                    " detail=${outcome.detail.ifEmpty { "none" }} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            }
            return outcome
        }

        private fun checkLive(checkRoute: Boolean = false) {
            if (!current(ctx, req) || failedDefault != null && !isDirectRetryEnabled(ctx)) {
                throw ReadFailure(Outcome(OnlineLyricState.NO_NETWORK, detail = "cancelled"))
            }
            if (checkRoute && !(if (failedDefault == null) ProjectionInternet.isCurrent(cm, network)
                else ProjectionInternet.isDirectLyricsFallbackCurrent(cm, failedDefault, network))) {
                throw ReadFailure(Outcome(OnlineLyricState.NO_NETWORK, detail = "route_changed"))
            }
            if (SystemClock.elapsedRealtime() >= deadline) throw ReadFailure(Outcome(OnlineLyricState.TIMEOUT, detail = "deadline"))
        }

        fun read(step: String, address: String): String {
            stage = step
            checkLive(checkRoute = true)
            val url = URL(address)
            // The user's VPN may publish a loopback HTTP proxy. Reusing it for the explicitly
            // permitted mobile retry would send this HTTPS request back through that same proxy.
            val conn = (if (failedDefault != null) {
                if (!isPublicCatalogueEndpoint(url)) throw SecurityException()
                network.openConnection(url, Proxy.NO_PROXY)
            } else {
                network.openConnection(url)
            }) as HttpsURLConnection
            try {
                val remaining = (deadline - SystemClock.elapsedRealtime()).coerceIn(1, 6_000).toInt()
                conn.connectTimeout = minOf(4_000, remaining)
                conn.readTimeout = remaining
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                // Do not share global WebView cookies, a music account or authorization headers.
                conn.setRequestProperty("Cookie", "")
                conn.setRequestProperty("User-Agent", "CarDeck-BMW/$version (based on https://github.com/shaonialife/cardeck)")
                conn.setRequestProperty("Accept", "application/json")
                synchronized(OnlineLyrics) {
                    checkLive()
                    connection = conn
                }
                checkLive(checkRoute = true)
                // Consume the one mobile retry for this recording only when sending the request.
                // A missing cell or a route change before this point can recover on the next poll.
                if (failedDefault != null) lastDirectRetryOwner = req.session to req.revision
                awaitingHeaders = true
                val code = conn.responseCode
                awaitingHeaders = false
                when (code) {
                    429, 503 -> {
                        val wait = lyricsRetryDelayMs(conn.getHeaderField("Retry-After"), System.currentTimeMillis())
                        throw ReadFailure(Outcome(OnlineLyricState.LIMITED, retryMs = maxOf(1_000, wait) + Random.nextLong(1, 501)))
                    }
                    200 -> Unit
                    else -> throw ReadFailure(Outcome(OnlineLyricState.FAILURE, detail = "http_$code"))
                }
                if (conn.contentLengthLong > MAX_BYTES) throw ReadFailure(Outcome(OnlineLyricState.FAILURE))
                val body = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkLive(checkRoute = failedDefault != null)
                        val n = input.read(buffer)
                        if (n == -1) {
                            checkLive(checkRoute = true)
                            return body.toString("UTF-8")
                        }
                        if (n <= 0 || body.size() + n > MAX_BYTES) throw ReadFailure(Outcome(OnlineLyricState.FAILURE))
                        body.write(buffer, 0, n)
                    }
                }
            } finally {
                synchronized(OnlineLyrics) { if (connection === conn) connection = null }
                conn.disconnect()
            }
        }
    }

    private fun catalogueSong(row: JSONObject): CatalogueSong? {
        val id = (row.opt("id") as? Number)?.toLong()?.takeIf { it in 1..9_999_999_999_999_999L } ?: return null
        val title = row.opt("name") as? String ?: return null
        val artists = row.optJSONArray("artists") ?: return null
        if (artists.length() !in 1..20) return null
        val names = (0 until artists.length()).map { artists.optJSONObject(it)?.opt("name") as? String ?: return null }
        if (names.any(String::isBlank)) return null
        return CatalogueSong(id, OnlineRecord(title, names.joinToString(", "), row.optJSONObject("album")?.opt("name") as? String,
            (row.opt("duration") as? Number)?.toDouble()?.div(1000), false, null, null))
    }

    private fun record(row: JSONObject): OnlineRecord? {
        fun string(k: String) = row.opt(k) as? String
        return OnlineRecord(
            string("trackName") ?: return null, string("artistName") ?: return null, string("albumName"),
            (row.opt("duration") as? Number)?.toDouble(), row.optBoolean("instrumental", false),
            string("syncedLyrics"), string("plainLyrics"),
        )
    }
}

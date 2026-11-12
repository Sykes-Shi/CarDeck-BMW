package io.github.shaonialife.cardeck.media

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import io.github.shaonialife.cardeck.launcher.VirtualLauncher

/**
 * 通知监听服务：借通知使用权枚举系统活跃媒体会话，向应用提供「正在播放」状态
 * （歌名/歌手/播放中）。音乐卡片（仪表盘）据此渲染与控制。
 *
 * **为什么必须有通知使用权**：`MediaSessionManager.getActiveSessions()` 在未授权时
 * 仍会返回会话（所以播放状态拿得到），但 `metadata` 恒为 null——表现为「播放状态在变、
 * 歌名歌手却始终为空」。这个组合最容易误判成解析 bug，实际是权限没给（2026-10-18 实测）。
 *
 * 授权路径：系统设置「通知使用权」，或 Shizuku（shell）执行
 * `cmd notification allow_listener <本组件>`（见 [NotificationAccess]）。
 */
class MediaListenerService : NotificationListenerService() {
    private var connected = false
    companion object {
        fun componentName(context: android.content.Context): ComponentName = ComponentName(context, MediaListenerService::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        // 已连上即代表系统认可本应用的通知使用权（此时 metadata 才会可读）。
        // 两个状态都置位：NotificationAccess 供「是否要再试授权」判断，
        // MediaSessionsState 供 UI 文案区分「没在播放」与「读不到信息」。
        NotificationAccess.markGranted()
        NotificationAccess.markConnected()
        MediaSessionsState.accessGranted = true
        try {
            manager.addOnActiveSessionsChangedListener(
                sessionListener,
                componentName(this),
            )
            MediaSessionsState.resyncHook = { mainHandler.post { refresh() } }
            update(manager.getActiveSessions(componentName(this)))
            mainHandler.removeCallbacks(periodicRefresh)
            mainHandler.post(periodicRefresh)
        } catch (_: SecurityException) {
            // 通知使用权被撤销：静默（授权后系统会重新绑定本服务）
            MediaSessionsState.accessGranted = false
            setOnlineOwner(null)
            MediaSessionsState.publish(null)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        connected = false
        MediaSessionsState.accessGranted = false
        NotificationAccess.markDisconnected()
        // 必须清空：否则卡片会保留断开前的歌名/播放状态，出现「正在播放 + 未在播放」
        // 甚至「正在播放 + 无法读取播放信息」的自相矛盾显示（2026-10-19 实测：
        // 重装后监听器未被系统绑定，卡片长时间停在旧状态）。
        MediaSessionsState.publish(null)
        mainHandler.removeCallbacks(periodicRefresh)
        clearWatched()
        MediaSessionsState.resyncHook = null
    }

    override fun onDestroy() {
        connected = false
        mainHandler.removeCallbacks(periodicRefresh)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionListener) }
        MediaSessionsState.publish(null)
        MediaSessionsState.accessGranted = false
        MediaSessionsState.resyncHook = null
        clearWatched()
        artExecutor.shutdownNow()
        super.onDestroy()
    }

    private val manager by lazy {
        getSystemService(android.content.Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            update(controllers ?: emptyList())
        }

    /** 已注册播放回调的会话（避免重复注册）。 */
    private val watched = mutableMapOf<android.media.session.MediaSession.Token, Pair<android.media.session.MediaController, android.media.session.MediaController.Callback>>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 兜底轮询：部分播放器不触发回调/回调丢失时保证卡片信息更新。 */
    private val periodicRefresh =
        object : Runnable {
            override fun run() {
                if (!connected) return
                refresh()
                mainHandler.postDelayed(this, 1_000)
            }
        }

    private class ArtEntry(val key: Any, val sampledAt: Long) {
        @Volatile var bitmap: android.graphics.Bitmap? = null
        @Volatile var color: Int = ArtworkTone.DEFAULT
    }

    /** 同时保存各播放器封面，暂停的网易云页面不会借用其他应用封面。仅保留活跃包。 */
    private val artCache = mutableMapOf<String, ArtEntry>()
    private val lyricsTrackers = mutableMapOf<String, PlayerLyricsTracker>()
    private val lyricsLogKeys = mutableMapOf<String, Pair<Any?, String>>()
    private var onlineOwner: Pair<android.media.session.MediaSession.Token, LyricTrack>? = null

    private fun setOnlineOwner(next: Pair<android.media.session.MediaSession.Token, LyricTrack>?) {
        if (onlineOwner != next) {
            OnlineLyrics.clearPending()
            onlineOwner = next
        }
    }

    /** 封面 uri 解码专用单线程（串行；主线程不能做 IO/解码）。 */
    private val artExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "media-art").apply { isDaemon = true }
        }

    private fun update(controllers: List<android.media.session.MediaController>) {
        val active = controllers.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in active }.forEach { token ->
            watched.remove(token)?.let { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        }
        controllers.forEach { controller ->
            if (controller.sessionToken !in watched) {
                val callback =
                    object : android.media.session.MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) {
                            refresh(playbackEvent = true)
                        }

                        override fun onMetadataChanged(metadata: MediaMetadata?) {
                            refresh()
                        }
                    }
                controller.registerCallback(callback, mainHandler)
                watched[controller.sessionToken] = controller to callback
            }
        }
        refresh()
    }

    private fun clearWatched() {
        setOnlineOwner(null)
        watched.values.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        watched.clear()
        artCache.clear()
        lyricsTrackers.clear()
        lyricsLogKeys.clear()
    }

    /** 从活跃会话提取正在播放态：优先 PLAYING 的会话，否则保留第一个有元数据的。 */
    private fun refresh(playbackEvent: Boolean = false) {
        if (!connected) return
        val controllers =
            try {
                manager.getActiveSessions(componentName(this))
            } catch (_: SecurityException) {
                MediaSessionsState.accessGranted = false
                emptyList()
            }
        val candidates = controllers.map {
            MediaTargetPolicy.Candidate(it.packageName, it.playbackState?.state == PlaybackState.STATE_PLAYING, it.metadata != null)
        }
        val selected = controllers.map { it.packageName }.distinct().mapNotNull { pkg ->
            MediaTargetPolicy.pickIndex(candidates, pkg)?.let { controllers[it] }
        }
        val primary = MediaTargetPolicy.pickIndex(candidates)?.let { controllers[it] }
        if (primary == null) setOnlineOwner(null)
        artCache.keys.retainAll(selected.map { it.packageName }.toSet())
        lyricsTrackers.keys.retainAll(selected.map { it.packageName }.toSet())
        lyricsLogKeys.keys.retainAll(selected.map { it.packageName }.toSet())
        val snapshots =
            selected.sortedBy { it.sessionToken != primary?.sessionToken }.map { controller ->
                val meta = controller.metadata
                val state = controller.playbackState
                val playing = state?.state == PlaybackState.STATE_PLAYING
                val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                MediaSessionsState.NowPlaying(
                    title = title,
                    artist =
                        meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    playing = playing,
                    art = artFor(controller, meta, title),
                    pkg = controller.packageName,
                    positionMs = state?.position ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    playbackSpeed = if (playing) (state?.playbackSpeed ?: 1f) else 0f,
                    durationMs = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                    anchoredAtMs = playbackAnchor(state?.lastPositionUpdateTime ?: 0L, android.os.SystemClock.elapsedRealtime()),
                    sessionToken = controller.sessionToken,
                    actions = state?.actions ?: 0L,
                    lyrics = lyricsFor(controller, meta, title, controller.sessionToken == primary?.sessionToken, playing),
                    mediaId = meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    backdropColor = artCache[controller.packageName]?.color ?: ArtworkTone.DEFAULT,
                )
            }
        MediaSessionsState.publishAll(snapshots, primary?.packageName, playbackEvent)
    }

    private fun lyricsFor(
        controller: android.media.session.MediaController,
        meta: MediaMetadata?,
        title: String?,
        primary: Boolean,
        playing: Boolean,
    ): PlayerLyrics? {
        val pkg = controller.packageName
        // 歌名/作者/媒体 ID 与会话一起定界；中间的空元数据也必须清空 UI。
        val mediaId = meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val track = LyricTrack.of(pkg, title, artist, mediaId, album, duration)
        val identity = when {
            track != null -> controller.sessionToken to track
            title.isNullOrBlank() && mediaId.isNullOrBlank() -> null
            else -> PlayerTrackIdentity(controller.sessionToken, mediaId?.take(256), title?.take(256), artist?.take(256))
        }
        val keys = runCatching { meta?.keySet()?.asSequence()?.take(PlayerLyricsParser.MAX_KEYS)?.filter(PlayerLyricsParser::acceptsKey)?.sorted()?.take(8)?.toList() }
            .getOrNull().orEmpty()
        val provided = keys.firstNotNullOfOrNull { key ->
            runCatching { meta?.getText(key) }.getOrNull()?.takeIf {
                it.length in 1..PlayerLyricsParser.MAX_CHARS
            }?.toString()?.takeIf { it.isNotBlank() }?.let { key to it }
        }
        val playerLyrics = lyricsTrackers.getOrPut(pkg) { PlayerLyricsTracker() }.update(identity, provided?.first, provided?.second)
        val activeLookup = primary && playing && MediaSessionsState.accessGranted && playerLyrics == null && track != null
        if (primary) setOnlineOwner(if (activeLookup && track != null) controller.sessionToken to track else null)
        val lyrics = preferredLyrics(playerLyrics, online = {
            if (track == null || !MediaSessionsState.accessGranted) null
            else OnlineLyrics.forTrack(this, pkg, title, artist, album, duration, activeLookup, mediaId)
        }, local = { LocalTrackLyrics.forTrack(this, track) })
        val status = when {
            lyrics?.synchronized == true -> "timed"
            lyrics != null -> "plain"
            provided != null -> "hidden_invalid_or_stale"
            else -> "unavailable"
        }
        val source = lyrics?.origin?.name ?: "none"
        val logKey = identity to "$status|$source|${keys.joinToString(",")}"
        if (lyricsLogKeys.put(pkg, logKey) != logKey) {
            // 只记录可用性和明确字段名，不写歌词正文、完整元数据或会话令牌。
            VirtualLauncher.logSink?.invoke("LYRICS: pkg=$pkg status=$status source=$source keys=${keys.joinToString(",").ifEmpty { "none" }}")
        }
        return lyrics
    }

    /**
     * 封面提取（带缓存）：仅「会话+歌名+封面 uri」变化时重新提取——位图键直接从
     * metadata 取；uri 型封面（content:// 等）经后台线程解码，完成前先给无封面态。
     */
    private fun artFor(
        controller: android.media.session.MediaController,
        meta: MediaMetadata?,
        title: String?,
    ): android.graphics.Bitmap? {
        val artUri =
            meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val key = PlayerTrackIdentity(
            controller.sessionToken,
            meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            title,
            meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        ) to artUri
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = artCache[controller.packageName]
        if (key == previous?.key) {
            if (previous.bitmap != null) return previous.bitmap
            // 键没变但封面为空：2 秒内不重试（getBitmap 有成本），超后重提取——
            // app 在切歌后补封面（bitmap 键从无到有）靠这条路兜住
            if (now - previous.sampledAt < 2_000) return null
        }
        val entry = ArtEntry(key, now)
        artCache[controller.packageName] = entry
        val direct =
            meta
                ?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (direct != null) {
            artExecutor.execute { setArtwork(entry, scaleArt(direct)) }
            return null
        }
        if (artUri != null && android.net.Uri.parse(artUri).scheme in setOf("content", "android.resource")) {
            // 解码在专用线程；就绪后不主动 refresh——periodicRefresh 下一轮（≤1s）拉取
            artExecutor.execute {
                val bmp =
                    runCatching {
                        // 封面只用播放器已提供的位图/本地内容，不为车载页面额外联网。
                        val input = contentResolver.openInputStream(android.net.Uri.parse(artUri))
                        input?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                if (bmp != null) {
                    setArtwork(entry, scaleArt(bmp))
                }
            }
        }
        return null
    }

    /** 硬件位图不能直接取像素；只在后台取少量颜色样本，不保留第二张完整封面。 */
    private fun setArtwork(entry: ArtEntry, bitmap: android.graphics.Bitmap) {
        entry.color = runCatching {
            val readable = if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else bitmap
            try {
                val pixels = IntArray(64)
                for (index in pixels.indices) {
                    val x = ((index % 8 + 0.5f) * readable.width / 8).toInt().coerceIn(0, readable.width - 1)
                    val y = ((index / 8 + 0.5f) * readable.height / 8).toInt().coerceIn(0, readable.height - 1)
                    pixels[index] = readable.getPixel(x, y)
                }
                ArtworkTone.fromPixels(pixels)
            } finally {
                if (readable !== bitmap) readable.recycle()
            }
        }.getOrDefault(ArtworkTone.DEFAULT)
        entry.bitmap = bitmap
    }

    /** 封面缩到 ≤512px（卡片显示 ~120dp，控制内存）。 */
    private fun scaleArt(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= 512) return src
        val sample =
            generateSequence(1) { it * 2 }.first { maxSide / it <= 512 }
        return android.graphics.Bitmap
            .createScaledBitmap(src, src.width / sample, src.height / sample, true)
    }
}

/** 全局「正在播放」状态（本进程内共享；GridActivity 音乐卡监听渲染）。 */
/** Metadata refreshes can repeat one position sample. Preserve its monotonic clock anchor across polls. */
internal fun playbackAnchor(updateTime: Long, sampledAt: Long): Long =
    if (updateTime in 1..sampledAt) updateTime else sampledAt

internal inline fun preferredLyrics(player: PlayerLyrics?, online: () -> PlayerLyrics?, local: () -> PlayerLyrics?) =
    player ?: online() ?: local()

object MediaSessionsState {
    private const val LYRIC_LEAD_MS = 300L
    internal const val PROGRESS_TICK_MS = 1_000L

    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val playing: Boolean,
        val art: android.graphics.Bitmap? = null,
        /** 媒体会话所属应用（点击封面拉回正在播放的应用）。 */
        val pkg: String? = null,
        /** 采样时刻的播放位置（ms）。 */
        val positionMs: Long = 0L,
        /** 播放速度（暂停时 0——不外推）。 */
        val playbackSpeed: Float = 1f,
        val durationMs: Long = 0L,
        /** 位置采样的 SystemClock.elapsedRealtime 锚点。 */
        val anchoredAtMs: Long = 0L,
        /** 只在进程内用于精确控制，禁止写入诊断日志。 */
        val sessionToken: android.media.session.MediaSession.Token? = null,
        val actions: Long = 0L,
        val lyrics: PlayerLyrics? = null,
        val backdropColor: Int = ArtworkTone.DEFAULT,
        val mediaId: String? = null,
        val album: String? = null,
    ) {
        /** 渲染时刻的推算播放位置（播放中按速度外推，钳制在时长内）。 */
        fun displayPositionAt(elapsedRealtimeMs: Long): Long {
            if (positionMs < 0) return 0
            val base =
                if (playing && anchoredAtMs > 0) {
                    positionMs + ((elapsedRealtimeMs - anchoredAtMs) * playbackSpeed).toLong()
                } else {
                    positionMs
                }
            // 有些播放器先提供进度、后补时长；歌词仍跟随真实进度，进度条由 UI 判断时长。
            return if (durationMs > 0) base.coerceIn(0, durationMs) else base.coerceAtLeast(0)
        }

        fun lyricTextAt(elapsedRealtimeMs: Long, maxLines: Int = 2): String? {
            if (positionMs < 0 && lyrics?.synchronized == true) return null
            return lyrics?.textAt(lyricPositionAt(elapsedRealtimeMs), maxLines)
        }

        private fun lyricPositionAt(elapsedRealtimeMs: Long): Long =
            displayPositionAt(elapsedRealtimeMs).coerceAtMost(Long.MAX_VALUE - LYRIC_LEAD_MS) + LYRIC_LEAD_MS

        /** Wake at the next displayed line, or at the normal progress tick if it comes first. */
        internal fun nextMediaTickDelayMs(elapsedRealtimeMs: Long): Long {
            if (lyrics?.synchronized != true || !playing || positionMs < 0 || anchoredAtMs <= 0 ||
                !playbackSpeed.isFinite() || playbackSpeed == 0f
            ) return PROGRESS_TICK_MS
            val at = lyricPositionAt(elapsedRealtimeMs)
            val distance = if (playbackSpeed > 0) {
                val next = lyrics.lines.firstOrNull { it.timeMs > at } ?: return PROGRESS_TICK_MS
                if (durationMs > 0 && next.timeMs - LYRIC_LEAD_MS > durationMs) return PROGRESS_TICK_MS
                next.timeMs - at
            } else {
                val current = lyrics.lines.lastOrNull { it.timeMs <= at } ?: return PROGRESS_TICK_MS
                if (current.timeMs <= LYRIC_LEAD_MS) return PROGRESS_TICK_MS
                at - current.timeMs + 1
            }
            return kotlin.math.ceil(distance / kotlin.math.abs(playbackSpeed.toDouble()))
                .toLong().coerceIn(1L, PROGRESS_TICK_MS)
        }
    }

    @Volatile
    var nowPlaying: NowPlaying? = null
        private set

    @Volatile
    private var packageStates: Map<String, NowPlaying> = emptyMap()

    fun forPackage(packageName: String): NowPlaying? = packageStates[packageName]

    fun publishAll(values: List<NowPlaying>, selectedPackage: String?, playbackEvent: Boolean = false) {
        val next = values.mapNotNull { value -> value.pkg?.let { it to value } }.toMap()
        val changed = packageStates.keys != next.keys || next.any { (pkg, value) -> !sameContent(packageStates[pkg], value) }
        packageStates = next
        // A seek or speed change may keep the same title, lyrics and play/pause state.
        // Refresh its displayed line and deadline immediately; periodic sampling stays quiet.
        publishNow(next[selectedPackage], changed || playbackEvent)
    }

    private fun sameContent(old: NowPlaying?, value: NowPlaying?): Boolean =
        old?.title == value?.title && old?.artist == value?.artist && old?.playing == value?.playing &&
            old?.art === value?.art && old?.pkg == value?.pkg && old?.actions == value?.actions &&
            old?.sessionToken == value?.sessionToken && old?.durationMs == value?.durationMs &&
            old?.lyrics == value?.lyrics && old?.backdropColor == value?.backdropColor

    /**
     * 通知使用权是否已授予。
     *
     * **未授权时 `metadata` 恒为 null**（会话本身仍可见、播放状态照常变化），
     * 卡片必须据此区分「真的没在播放」与「有播放但读不到信息」，
     * 否则文案会误导成「媒体信息未连接」。
     */
    @Volatile
    var accessGranted: Boolean = false

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun publish(value: NowPlaying?) {
        packageStates = value?.pkg?.let { mapOf(it to value) } ?: emptyMap()
        publishNow(value, true)
    }

    private fun publishNow(value: NowPlaying?, packageChanged: Boolean) {
        val old = nowPlaying
        nowPlaying = value
        // 位置每秒都在变（1s 兜底轮询）——日志只看内容键，避免刷屏；
        // listeners 仍每次通知（UI 据此刷新进度文本）
        val contentChanged = !sameContent(old, value)
        if (contentChanged) {
            VirtualLauncher.logSink?.invoke(
                "MEDIA: " + (
                    value?.let { "${it.title ?: "?"} - ${it.artist ?: "?"} playing=${it.playing}" }
                        ?: "无活跃会话"
                ),
            )
        }
        // 内容未变（仅位置锚点随轮询更新）不通知：进度走秒由 UI 侧 mediaTick 每秒自刷新
        // ——此前 anchoredAtMs 恒变导致每秒全量通知监听者，与 mediaTick 形成双路重复渲染
        if (!contentChanged && !packageChanged) return
        listeners.forEach { runCatching { it() } }
    }

    /** 重同步钩子（服务连接时安装）：UI 乐观更新后调用以拉取真实状态。 */
    @Volatile
    var resyncHook: (() -> Unit)? = null

    fun requestResync() {
        resyncHook?.invoke()
    }
}

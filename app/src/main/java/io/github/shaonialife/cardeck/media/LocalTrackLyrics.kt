package io.github.shaonialife.cardeck.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.concurrent.Executors

internal data class LyricTrack private constructor(
    val pkg: String, val title: String, val artist: String,
    val mediaId: String?, val album: String?, val durationMs: Long,
) {
    // Length prefixes keep punctuation and separators in real titles distinct.
    val fileKey: String by lazy {
        val identity = listOf(pkg, title, artist, mediaId.orEmpty(), album.orEmpty(), durationMs.toString())
            .joinToString("") { "${it.length}:$it" }
        MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun of(pkg: String?, title: String?, artist: String?, mediaId: String? = null, album: String? = null, durationMs: Long = 0): LyricTrack? {
            if (pkg.isNullOrBlank() || title.isNullOrBlank() || artist.isNullOrBlank()) return null
            if (pkg.length > 256 || title.length > 512 || artist.length > 512) return null
            val id = mediaId?.trim()?.takeIf { it.length in 1..512 }
            val albumName = album?.trim()?.takeIf { it.length in 1..512 }
            // Prefer the player's track ID. Without one, distinguish album/live versions by album and duration.
            return if (id != null) LyricTrack(pkg.trim(), title.trim(), artist.trim(), id, null, 0)
            else LyricTrack(pkg.trim(), title.trim(), artist.trim(), null, albumName, durationMs.coerceAtLeast(0L) / 1_000L * 1_000L)
        }
    }
}

/** Store a private copy of an explicitly chosen timed LRC. All provider and disk reads are off the UI thread. */
internal object LocalTrackLyrics {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "local-track-lyrics").apply { isDaemon = true } }
    private val cache = object : LinkedHashMap<LyricTrack, PlayerLyrics?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricTrack, PlayerLyrics?>?) = size > 24
    }
    private val loading = mutableSetOf<LyricTrack>()
    private const val MAX_BYTES = 4 * PlayerLyricsParser.MAX_CHARS + 4


    fun forTrack(context: Context, track: LyricTrack?): PlayerLyrics? {
        if (track == null) return null
        synchronized(cache) {
            if (cache.containsKey(track)) return cache[track]
            if (!loading.add(track)) return null
        }
        val app = context.applicationContext
        worker.execute {
            val lyrics = runCatching {
                file(app, track).openRead().use { parseLrc(readBounded(it)) }
            }.getOrNull()
            synchronized(cache) {
                loading.remove(track)
                cache[track] = lyrics
            }
            if (lyrics != null) MediaSessionsState.resyncHook?.invoke()
        }
        return null
    }

    fun importFile(context: Context, track: LyricTrack, uri: Uri, report: (String) -> Unit) = change(track, report) {
        require(uri.scheme == "content") { "请从手机文件选择器选择 LRC 文件。" }
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readBounded) ?: error("无法打开文件。")
        val lyrics = parseLrc(bytes) ?: error("未找到可显示的带时间戳歌词，请选择有效的 LRC 文件。")
        val target = file(context, track)
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (e: Exception) {
            target.failWrite(stream)
            throw e
        }
        lyrics to "已为《${track.title}》保存同步歌词"
    }

    fun remove(context: Context, track: LyricTrack, report: (String) -> Unit) = change(track, report) {
        val target = file(context, track)
        target.delete()
        check(!target.baseFile.exists()) { "无法移除歌词，请重试。" }
        null to "《${track.title}》的本地歌词已移除"
    }

    private fun change(track: LyricTrack, report: (String) -> Unit, action: () -> Pair<PlayerLyrics?, String>) {
        worker.execute {
            val message = try {
                val (value, result) = action()
                synchronized(cache) { cache[track] = value }
                MediaSessionsState.resyncHook?.invoke()
                result
            } catch (e: IllegalArgumentException) {
                e.message ?: "歌词文件无效。"
            } catch (e: IllegalStateException) {
                e.message ?: "歌词操作失败。"
            } catch (_: Exception) {
                "无法读写这份歌词，请重新选择手机上的 LRC 文件。"
            }
            Handler(Looper.getMainLooper()).post { report(message) }
        }
    }

    private fun file(context: Context, track: LyricTrack): AtomicFile =
        AtomicFile(File(File(context.noBackupFilesDir, "track_lrc"), track.fileKey))

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(count > 0 && output.size() + count <= MAX_BYTES) { "歌词文件过大或无法读取。" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun parseLrc(bytes: ByteArray): PlayerLyrics? {
        if (bytes.size !in 1..MAX_BYTES) return null
        val utf16 = bytes.size >= 2 && (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ||
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
        val text = try {
            (if (utf16) Charsets.UTF_16 else Charsets.UTF_8).newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return null
        }.removePrefix("\uFEFF")
        if (text.any { it.isISOControl() && it !in "\r\n\t" }) return null
        return PlayerLyricsParser.parse("imported.lrc", text)?.takeIf { it.synchronized }
            ?.copy(origin = PlayerLyrics.Origin.IMPORTED)
    }
}

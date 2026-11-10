package io.github.shaonialife.cardeck.media

/** 内容只来自播放器明确给出的歌词字段；没有时间戳时保留静态节选，不假装逐句同步。 */
data class PlayerLyrics(
    val sourceKey: String,
    val lines: List<Line>,
    val synchronized: Boolean,
    val origin: Origin = Origin.PLAYER,
) {
    enum class Origin { PLAYER, IMPORTED, ONLINE, NETEASE, NETEASE_MOBILE_RETRY }
    val sourceLabel: String get() {
        val name = when (origin) {
            Origin.IMPORTED -> "本地 LRC"
            Origin.ONLINE -> "LRCLIB"
            Origin.NETEASE -> "网易云音乐"
            Origin.NETEASE_MOBILE_RETRY -> "网易云音乐（移动数据）"
            Origin.PLAYER -> "播放器提供"
        }
        return "$name · " + if (synchronized) "同步歌词" else "歌词节选"
    }
    data class Line(val timeMs: Long, val text: String)

    fun textAt(positionMs: Long, maxLines: Int = 2): String? {
        if (lines.isEmpty()) return null
        if (!synchronized) return lines.take(maxLines).joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
        val index = lines.indexOfLast { it.timeMs <= positionMs.coerceAtLeast(0) }
        if (index < 0 || lines[index].text.isBlank()) return null
        // 空时间行常表示间奏，不能越过它继续显示上一句。
        return lines.drop(index).take(maxLines).takeWhile { it.text.isNotBlank() }
            .joinToString("\n") { it.text }.takeIf { it.isNotBlank() }
    }
}

internal object PlayerLyricsParser {
    const val MAX_CHARS = 24_000
    const val MAX_KEYS = 64
    private const val MAX_LINES = 400
    private const val MAX_LINE_CHARS = 180
    private val timestamp = Regex("\\[(\\d{1,3}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val info = Regex("^\\[(?:ar|ti|al|by|re|ve|length):.*]$", RegexOption.IGNORE_CASE)
    private val offsetTag = Regex("^\\[offset:([+-]?\\d{1,6})]$", RegexOption.IGNORE_CASE)

    /** Android 没有标准歌词键，只接受末段明确叫 lyric/lyrics/lrc 的自定义字段。 */
    fun acceptsKey(key: String): Boolean {
        if (key.length !in 1..96 || !key.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' }) return false
        return key.substringAfterLast('.').substringAfterLast('_').lowercase() in setOf("lyric", "lyrics", "lrc")
    }

    fun parse(sourceKey: String, raw: String?): PlayerLyrics? {
        if (!acceptsKey(sourceKey) || raw.isNullOrBlank() || raw.length > MAX_CHARS) return null
        if (raw.trimStart().startsWith("{") || raw.trimStart().startsWith("http://") || raw.trimStart().startsWith("https://")) return null
        val input = raw.lineSequence().take(MAX_LINES).map { it.trim() }.toList()
        val offset = input.firstNotNullOfOrNull { offsetTag.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?.coerceIn(-60_000, 60_000) ?: 0L
        val timed = mutableListOf<PlayerLyrics.Line>()
        val plain = mutableListOf<PlayerLyrics.Line>()
        input.forEach { line ->
            if (info.matches(line) || offsetTag.matches(line)) return@forEach
            var consumed = 0
            val times = mutableListOf<Long>()
            while (times.size < 8) {
                val match = timestamp.find(line, consumed)?.takeIf { it.range.first == consumed } ?: break
                val fraction = match.groupValues[3].padEnd(3, '0').ifEmpty { "000" }.toLong()
                val time = match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1_000 + fraction
                times.add((time - offset).coerceAtLeast(0))
                consumed = match.range.last + 1
            }
            if (times.isNotEmpty()) {
                val text = clean(line.substring(consumed))
                times.forEach { if (timed.size < MAX_LINES) timed.add(PlayerLyrics.Line(it, text)) }
            } else if (line.isNotBlank() && !line.startsWith("[")) {
                plain.add(PlayerLyrics.Line(0, clean(line)))
            }
        }
        if (timed.isNotEmpty()) {
            val grouped = timed.sortedBy { it.timeMs }.groupBy { it.timeMs }.map { (time, sameTime) ->
                PlayerLyrics.Line(time, sameTime.map { it.text }.distinct().joinToString(" / ").take(MAX_LINE_CHARS))
            }
            if (grouped.none { it.text.isNotBlank() }) return null
            return PlayerLyrics(sourceKey, grouped, true)
        }
        val nonEmpty = plain.filter { it.text.isNotBlank() }
        return if (nonEmpty.isEmpty()) null else PlayerLyrics(sourceKey, nonEmpty, false)
    }

    private fun clean(value: String): String = value.filter { it == '\t' || !it.isISOControl() }
        .replace('\t', ' ').trim().take(MAX_LINE_CHARS)
}

/** 同包媒体会话会跨曲复用；新曲仍带旧歌词时先隐藏，等新的歌词字段到达。 */
internal class PlayerLyricsTracker {
    private var identity: Any? = null
    private var previousSongPayload: Pair<String, String>? = null
    private var lastAcceptedPayload: Pair<String, String>? = null
    private var lastParsedPayload: Pair<String, String>? = null
    private var parsed: PlayerLyrics? = null

    fun update(trackIdentity: Any?, key: String?, raw: String?): PlayerLyrics? {
        if (trackIdentity == null) {
            parsed = null
            lastParsedPayload = null
            return null
        }
        if (identity != trackIdentity) {
            previousSongPayload = lastAcceptedPayload
            identity = trackIdentity
            lastParsedPayload = null
            parsed = null
        }
        if (key == null || raw.isNullOrBlank() || raw.length > PlayerLyricsParser.MAX_CHARS) {
            parsed = null
            lastParsedPayload = null
            return null
        }
        val payload = key to raw
        if (payload.second == previousSongPayload?.second) return null
        if (payload != lastParsedPayload) {
            parsed = PlayerLyricsParser.parse(key, raw)
            lastParsedPayload = payload
            if (parsed != null) lastAcceptedPayload = payload
        }
        return parsed
    }
}

internal data class PlayerTrackIdentity(
    val sessionIdentity: Any,
    val mediaId: String?,
    val title: String?,
    val artist: String?,
)

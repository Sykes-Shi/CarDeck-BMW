package io.github.shaonialife.cardeck.media

/**
 * 仅消费当前播放器及 UID 捕获策略，不保存 dump 原文、媒体标签或其他应用数据。
 * dumpsys 文本不是稳定 API；结构变形时保留 unknown，不能把缺失记录当作允许采集。
 */
class RuntimeAudioDumpParser(targetUids: Set<Int>) {
    private enum class Section { OUTSIDE, MONITOR, PLAYERS, BETWEEN, POLICIES, DONE }

    private data class UidState(
        val players: LinkedHashMap<Int, Player> = linkedMapOf(),
        var playerLimit: Boolean = false,
        var policy: Int? = null,
        var policyConflict: Boolean = false,
    )

    private data class Player(
        val piid: Int,
        val type: String,
        val state: String,
        val usage: String,
        val flags: Long?,
        val muted: String,
    ) {
        fun line(uid: Int): String =
            "AUDIO_RUNTIME uid=$uid piid=$piid type=$type state=$state usage=$usage " +
                "flags=${flags?.let { "0x${it.toString(16).uppercase()}" } ?: "unknown"} " +
                "runtimePlayerReportedCapturePolicy=${flags?.let { policyName(policyFromFlags(it)) } ?: "unknown"} " +
                "mutedState=$muted"
    }

    private val targets = targetUids.filter { it > 0 }.sorted().associateWith { UidState() }
    private var section = Section.OUTSIDE
    private var monitorSeen = false
    private var duplicateMonitor = false
    private var playersSeen = false
    private var playersClosed = false
    private var playersMalformed = false
    private var policiesSeen = false
    private var policiesClosed = false
    private var policiesMalformed = false
    private var permissionDenied = false
    private var finished: List<String>? = null

    fun accept(line: String) {
        if (finished != null) return
        if (line.length > MAX_LINE_LENGTH || line.any { it == '\n' || it == '\r' || it == '\u0000' }) {
            invalidateCurrentSection()
            return
        }
        val text = line.trim()
        if (text.startsWith("Permission Denial:") || text.startsWith("Permission denied") ||
            text.startsWith("java.lang.SecurityException:")
        ) {
            permissionDenied = true
            return
        }
        if (text.startsWith("PlaybackActivityMonitor dump time:")) {
            if (monitorSeen) duplicateMonitor = true
            monitorSeen = true
            section = Section.MONITOR
            return
        }
        if (!monitorSeen || duplicateMonitor || section == Section.DONE) return
        when (section) {
            Section.MONITOR -> {
                if (text == "players:") {
                    playersSeen = true
                    section = Section.PLAYERS
                }
            }
            Section.PLAYERS -> {
                when {
                    text == "ducked players piids:" -> {
                        playersClosed = true
                        section = Section.BETWEEN
                    }
                    text.isNotEmpty() -> readPlayer(text)
                }
            }
            Section.BETWEEN -> {
                // 此段含历史事件；即使含 player/uid 文本也不用于当前快照。
                if (text == "allowed capture policies:") {
                    policiesSeen = true
                    section = Section.POLICIES
                }
            }
            Section.POLICIES -> {
                when {
                    text.startsWith("RecordActivityMonitor dump time:") -> {
                        policiesClosed = true
                        section = Section.DONE
                    }
                    text.isNotEmpty() -> readPolicy(text)
                }
            }
            else -> Unit
        }
    }

    fun finish(complete: Boolean): List<String> {
        finished?.let { return it }
        val usable = complete && !permissionDenied && monitorSeen && !duplicateMonitor
        val playersUsable = usable && playersSeen && playersClosed && !playersMalformed
        val policiesUsable = usable && policiesSeen && policiesClosed && !policiesMalformed
        val result = buildList {
            for ((uid, target) in targets) {
                val status = when {
                    permissionDenied -> "permission_denied"
                    !complete -> "incomplete"
                    !monitorSeen -> "unavailable"
                    duplicateMonitor || playersMalformed || policiesMalformed || target.policyConflict -> "malformed"
                    !playersUsable || !policiesUsable -> "unknown_format"
                    else -> "ok"
                }
                val policy = when {
                    !policiesUsable || target.policyConflict -> "unknown"
                    target.policy == null -> "ALL(default)"
                    else -> policyName(target.policy!!)
                }
                val count = when {
                    !playersUsable -> "unknown"
                    target.playerLimit -> "$MAX_PLAYERS_PER_UID+"
                    else -> target.players.size.toString()
                }
                add("AUDIO_RUNTIME uid=$uid status=$status globalCapturePolicy=$policy currentPlayers=$count")
                if (playersUsable) target.players.values.forEach { add(it.line(uid)) }
            }
        }
        finished = result
        return result
    }

    private fun invalidateCurrentSection() {
        when (section) {
            Section.PLAYERS -> playersMalformed = true
            Section.POLICIES -> policiesMalformed = true
            else -> Unit
        }
    }

    private fun readPlayer(text: String) {
        // UID 只从严格位置读取，绝不在 tags、bundle 或历史事件中搜索数字。
        val match = PLAYER_PREFIX.matchEntire(text)
        if (match == null) {
            playersMalformed = true
            return
        }
        val uid = match.groupValues[3].toIntOrNull()
        if (uid == null) {
            playersMalformed = true
            return
        }
        val target = targets[uid] ?: return
        val piid = match.groupValues[1].toIntOrNull()
        if (piid == null) {
            playersMalformed = true
            return
        }
        val attrAndTail = match.groupValues[5]
        val boundary = listOf(attrAndTail.indexOf(" tags="), attrAndTail.indexOf(" bundle="))
            .filter { it >= 0 }.minOrNull()
        // AOSP 总会输出 tags/bundle；缺失时不能从未知后缀里找 flags。
        val attributes = boundary?.let { ATTRIBUTE_HEADER.matchEntire(attrAndTail.substring(0, it)) }
        val usage = attributes?.groupValues?.get(1)?.takeIf { it in USAGES } ?: "unknown"
        val flagsText = attributes?.groupValues?.get(4)
        val flags = flagsText?.takeIf { FLAGS.matches(it) }?.removePrefix("0x")?.toLongOrNull(16)
        // 非空 tags/非 null bundle 可能含伪装字段；只解析完全无自由文本的尾部。
        val tail = boundary?.let { EMPTY_TAGS_TAIL.matchEntire(attrAndTail.substring(it)) }
        val muteTokens = tail?.groupValues?.get(1)?.trim()?.split(Regex(" +"))
        val muted = when {
            muteTokens == listOf("none") -> "none"
            muteTokens == null || muteTokens.isEmpty() || muteTokens.any { it !in MUTE_SOURCES } -> "unknown"
            muteTokens.distinct().size != muteTokens.size -> "unknown"
            else -> muteTokens.sorted().joinToString("|")
        }
        val player = Player(
            piid,
            TYPES[match.groupValues[2]] ?: "unknown",
            match.groupValues[4].takeIf { it in STATES } ?: "unknown",
            usage,
            flags,
            muted,
        )
        val existing = target.players[piid]
        when {
            existing != null && existing != player -> playersMalformed = true
            existing != null -> Unit
            target.players.size >= MAX_PLAYERS_PER_UID -> target.playerLimit = true
            else -> target.players[piid] = player
        }
    }

    private fun readPolicy(text: String) {
        val match = POLICY_ROW.matchEntire(text)
        if (match == null) {
            policiesMalformed = true
            return
        }
        val uid = match.groupValues[1].toIntOrNull()
        if (uid == null) {
            policiesMalformed = true
            return
        }
        val target = targets[uid] ?: return
        val policy = match.groupValues[2].toIntOrNull()
        if (policy == null || policy !in 1..3 || (target.policy != null && target.policy != policy)) {
            target.policyConflict = true
        } else {
            target.policy = policy
        }
    }

    companion object {
        private const val MAX_PLAYERS_PER_UID = 4
        private const val MAX_LINE_LENGTH = 8192
        private val PLAYER_PREFIX = Regex(
            "^(?:\\(not logged\\) *)?AudioPlaybackConfiguration piid:([0-9]{1,10}) " +
                "(?:deviceIds:\\[[0-9, -]{0,128}\\]|deviceId:[0-9]{1,10}) " +
                "type:([A-Za-z0-9 .()/_-]{1,80}) u/pid:([0-9]{1,10})/[0-9]{1,10} " +
                "state:([A-Za-z _-]{1,40}) attr:AudioAttributes: (.*)$",
        )
        private val ATTRIBUTE_HEADER = Regex(
            "^usage=([A-Za-z0-9_]+) content=([A-Za-z0-9_]+)(?: source=([A-Za-z0-9_]+))? flags=(\\S+)$",
        )
        private val FLAGS = Regex("^0x[0-9A-Fa-f]{1,8}$")
        private val EMPTY_TAGS_TAIL = Regex(
            "^ tags= bundle=null sessionId:[0-9]{1,10} mutedState:([A-Za-z ]{1,160}) " +
                "FormatInfo\\{isSpatialized=(?:true|false), channelMask=0x[0-9a-fA-F]{1,8}, sampleRate=[0-9]{1,6}\\}$",
        )
        private val POLICY_ROW = Regex("^uid: ([0-9]{1,10}) policy: ([0-9]{1,2})$")
        private val TYPES = mapOf(
            "android.media.AudioTrack" to "AudioTrack",
            "android.media.MediaPlayer" to "MediaPlayer",
            "android.media.SoundPool" to "SoundPool",
            "OpenSL ES AudioPlayer (Buffer Queue)" to "OpenSL_BufferQueue",
            "OpenSL ES AudioPlayer (URI/FD)" to "OpenSL_URI_FD",
            "AAudio" to "AAudio",
            "hardware source" to "hardware_source",
            "external proxy" to "external_proxy",
        )
        private val STATES = setOf("started", "paused", "stopped", "idle", "released", "unknown")
        private val USAGES = setOf(
            "USAGE_UNKNOWN", "USAGE_MEDIA", "USAGE_VOICE_COMMUNICATION", "USAGE_VOICE_COMMUNICATION_SIGNALLING",
            "USAGE_ALARM", "USAGE_NOTIFICATION", "USAGE_NOTIFICATION_RINGTONE", "USAGE_NOTIFICATION_COMMUNICATION_REQUEST",
            "USAGE_NOTIFICATION_COMMUNICATION_INSTANT", "USAGE_NOTIFICATION_COMMUNICATION_DELAYED", "USAGE_NOTIFICATION_EVENT",
            "USAGE_ASSISTANCE_ACCESSIBILITY", "USAGE_ASSISTANCE_NAVIGATION_GUIDANCE", "USAGE_ASSISTANCE_SONIFICATION",
            "USAGE_GAME", "USAGE_ASSISTANT", "USAGE_CALL_ASSISTANT", "USAGE_EMERGENCY", "USAGE_SAFETY", "USAGE_VEHICLE_STATUS",
            "USAGE_ANNOUNCEMENT", "USAGE_SPEAKER_CLEANUP", "USAGE_VIRTUAL_SOURCE",
        )
        private val MUTE_SOURCES = setOf(
            "master", "streamVolume", "streamMute", "opPlayAudio", "clientVolume", "volumeShaper", "portVolume", "opControlAudio",
        )

        private fun policyFromFlags(flags: Long): Int = when {
            flags and 0x1000L != 0L -> 3
            flags and 0x400L != 0L -> 2
            else -> 1
        }

        private fun policyName(policy: Int): String = when (policy) {
            1 -> "ALL"
            2 -> "SYSTEM"
            3 -> "NONE"
            else -> "unknown"
        }
    }
}

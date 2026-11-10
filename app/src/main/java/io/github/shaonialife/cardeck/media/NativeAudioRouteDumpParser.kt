package io.github.shaonialife.cardeck.media

/**
 * AOSP android-16.0.0_r1 media.aaudio 端点/UID 白名单；缺失不推断为 legacy。
 * 格式依据 services/oboeservice/{AAudioEndpointManager,AAudioServiceEndpoint,
 * AAudioServiceStreamBase,AAudioServiceStreamShared}.cpp，原文不进入结果。
 * https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-16.0.0_r1/
 */
internal class NativeAudioRouteDumpParser(targetUids: Set<Int>) {
    private val targets = targetUids.filter { it > 0 }.sorted().take(2)
    private val streams = targets.associateWith { linkedMapOf<String, Stream>() }
    private val overflow = mutableSetOf<Int>()
    private var sawService = false
    private var sawExclusive = false
    private var sawShared = false
    private var expectedExclusive = -1
    private var expectedShared = -1
    private var exclusiveCount = 0
    private var sharedCount = 0
    private var malformed = false
    private var section: String? = null
    private var endpoint: Endpoint? = null

    private data class Endpoint(
        val sharing: String,
        val path: String,
        var direction: String? = null,
        var rate: String = "unknown",
        var devices: String = "unknown",
        var registered: Boolean = false,
        var header: Boolean = false,
    )

    private data class Stream(
        val handle: String,
        val port: String,
        val running: String,
        val state: String,
        val rate: String,
        val devices: String,
        val path: String,
        val sharing: String,
    )

    fun accept(line: String) {
        val text = line.trim()
        if (text.contains("may be deadlocked")) {
            malformed = true
            return
        }
        if (text == "------------ AAudio Service ------------") {
            sawService = true
            return
        }
        when {
            text.matches(Regex("Exclusive MMAP Endpoints: \\d+")) -> {
                finishEndpoint()
                if (sawExclusive) malformed = true
                sawExclusive = true
                expectedExclusive = decimal(text.substringAfter(':').trim())?.toInt() ?: -1
                section = "exclusive"
                return
            }
            text.matches(Regex("Shared Endpoints: \\d+")) -> {
                finishEndpoint()
                if (sawShared) malformed = true
                sawShared = true
                expectedShared = decimal(text.substringAfter(':').trim())?.toInt() ?: -1
                section = "shared"
                return
            }
            text.matches(Regex("(?:Exclusive|Shared)[A-Za-z]+Count:.*")) -> {
                finishEndpoint()
                return
            }
            text.startsWith("#") -> {
                finishEndpoint()
                val index = text.substringBefore(':').removePrefix("#").toIntOrNull()
                endpoint = when {
                    section == "exclusive" && index == exclusiveCount &&
                        text.matches(Regex("#\\d+:\\s*MMAP:.*")) -> {
                        exclusiveCount++
                        Endpoint("exclusive", "MMAP")
                    }
                    section == "shared" && index == sharedCount &&
                        text.matches(Regex("#\\d+:\\s*SHARED: sharing exclusive stream with handle = 0x[0-9a-fA-F]+.*")) -> {
                        sharedCount++
                        Endpoint("shared", "unknown")
                    }
                    else -> {
                        malformed = true
                        null
                    }
                }
                return
            }
        }
        val current = endpoint ?: return
        when {
            text.startsWith("Direction:") -> {
                current.direction = text.substringAfter(':').trim().takeIf { it == "OUTPUT" || it == "INPUT" }
                if (current.direction == null) malformed = true
            }
            text.startsWith("Sample Rate:") -> {
                current.rate = decimal(text.substringAfter(':').trim()) ?: "unknown"
                if (current.rate == "unknown") malformed = true
            }
            text.startsWith("Device Ids:") -> {
                val value = text.substringAfter(':').trim()
                // 同版本 media/libaudiofoundation/AudioContainers.cpp 的空向量是此字面量。
                if (value == "AUDIO_PORT_HANDLE_NONE") {
                    current.devices = "none"
                } else if (value.matches(Regex("\\[?\\s*\\d+(?:\\s*,\\s*\\d+){0,3}\\s*\\]?"))) {
                    val ids = Regex("\\d+").findAll(value).map { decimal(it.value) }.toList()
                    current.devices = if (ids.all { it != null }) ids.joinToString(",") else "unknown"
                } else {
                    current.devices = "unknown"
                }
                if (current.devices == "unknown") malformed = true
            }
            text == "Registered Streams:" -> current.registered = true
            current.registered && text.startsWith("T ") -> {
                val fields = text.split(Regex("\\s+"))
                current.header = fields.take(7) == listOf("T", "Handle", "UId", "Port", "Run", "State", "Format")
                if (!current.header) malformed = true
            }
            current.registered && text.startsWith("0x") -> readStream(text, current)
            current.registered && text.isNotEmpty() -> malformed = true
        }
    }

    private fun finishEndpoint() {
        endpoint?.let {
            if (it.direction == null || it.rate == "unknown" || it.devices == "unknown" ||
                !it.registered || !it.header
            ) malformed = true
        }
        endpoint = null
    }

    private fun readStream(text: String, current: Endpoint) {
        val fields = text.split(Regex("\\s+"))
        // 此固定 AOSP 版本表头有 T，但 Base::dump() 行首实际为四个空格和 0x handle，
        // Shared 只追加队列列，MMAP 没有 dump() 覆盖；不猜测厂商新增的前缀列。
        // 固定前四列先校验，再读取 UID；厂商格式变化记 unknown，不能猜列偏移。
        if (!current.header || fields.size < 13 ||
            !fields[0].matches(Regex("0x[0-9a-fA-F]{1,8}")) ||
            decimal(fields[1]) == null || decimal(fields[2]) == null ||
            fields[3] !in listOf("yes", "no") || decimal(fields[4]) == null
        ) {
            malformed = true
            return
        }
        val uid = fields[1].toIntOrNull() ?: return
        if (uid !in targets || current.direction == "INPUT") return
        if (current.direction != "OUTPUT") {
            malformed = true
            return
        }
        val owned = streams.getValue(uid)
        val handle = fields[0].lowercase()
        if (handle !in owned && owned.size >= 4) {
            overflow.add(uid)
            return
        }
        val value = Stream(handle, fields[2], fields[3], fields[4], current.rate, current.devices, current.path, current.sharing)
        val prior = owned[handle]
        if (prior != null && prior != value) malformed = true else owned[handle] = value
    }

    fun finish(complete: Boolean): List<String> {
        finishEndpoint()
        val valid = complete && sawService && sawExclusive && sawShared && !malformed &&
            expectedExclusive == exclusiveCount && expectedShared == sharedCount
        return targets.flatMap { uid ->
            val owned = streams.getValue(uid).values
            when {
                !valid -> listOf("AUDIO_NATIVE uid=$uid status=unknown path=unknown")
                uid in overflow -> listOf("AUDIO_NATIVE uid=$uid status=truncated path=unknown")
                owned.isEmpty() -> listOf("AUDIO_NATIVE uid=$uid status=not_observed path=unknown")
                else -> listOf("AUDIO_NATIVE uid=$uid status=observed outputStreams=${owned.size}") + owned.map {
                    "AUDIO_NATIVE uid=$uid handle=${it.handle} port=${it.port} running=${it.running} state=${it.state}" +
                        " path=${it.path} sharing=${it.sharing} rate=${it.rate} deviceIds=${it.devices}"
                }
            }
        }
    }

    private fun decimal(value: String): String? {
        val number = value.toLongOrNull() ?: return null
        return value.takeIf { it.matches(Regex("\\d{1,10}")) && number in 0L..Int.MAX_VALUE.toLong() }
    }
}

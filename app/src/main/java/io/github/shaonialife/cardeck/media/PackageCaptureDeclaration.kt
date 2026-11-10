package io.github.shaonialife.cardeck.media

import android.content.pm.ApplicationInfo
import android.util.Printer

/**
 * 只读诊断包级音频采集声明（含系统按 targetSdk 应用的默认值）。
 * 播放器仍可在运行时收紧策略，因此此结果不作为实际可采集性的判定。
 */
object PackageCaptureDeclaration {
    private val declarationField = Regex("^allowsPlaybackCapture\\b")

    fun read(info: ApplicationInfo): String =
        readDump { emit -> info.dump(Printer { line -> emit(line) }, "") }

    /**
     * dump 是公开 API，但字段文本不是稳定协议：格式变化或读取不完整时保留 unknown。
     * 逐行消费，只保存声明状态，避免缓存或输出含路径等信息的完整 dump。
     */
    internal fun readDump(dump: ((String) -> Unit) -> Unit): String {
        var declaration: Boolean? = null
        var invalid = false
        try {
            dump { line ->
                val text = line.trim()
                if (declarationField.containsMatchIn(text)) {
                    val value = when (text) {
                        "allowsPlaybackCapture=true" -> true
                        "allowsPlaybackCapture=false" -> false
                        else -> null
                    }
                    if (value == null || (declaration != null && declaration != value)) {
                        invalid = true
                    } else {
                        declaration = value
                    }
                }
            }
        } catch (_: Exception) {
            // 即使异常前读到 true，也不能把不完整的诊断结果记为允许。
            return "unknown"
        }
        return if (invalid) {
            "unknown"
        } else {
            when (declaration) {
                true -> "allowed"
                false -> "disallowed"
                null -> "unknown"
            }
        }
    }
}

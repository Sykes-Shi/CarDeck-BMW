package io.github.shaonialife.cardeck.media

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** dumpsys 逐行过滤前就限制内存；超长行不截成看似有效的字段。 */
internal object BoundedDiagnosticLines {
    data class Result(val complete: Boolean, val bytes: Int)

    fun read(
        input: InputStream,
        maxBytes: Int,
        maxLineBytes: Int,
        onLine: (String) -> Unit,
    ): Result {
        require(maxBytes > 0 && maxBytes < Int.MAX_VALUE && maxLineBytes > 0)
        val buffer = ByteArray(4_096)
        val line = ByteArrayOutputStream(minOf(maxLineBytes, 256))
        var bytes = 0
        var oversized = false
        var valid = true
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - bytes + 1))
            if (count < 0) {
                if (line.size() > 0 && !oversized) onLine(line.toString("UTF-8"))
                return Result(valid, bytes)
            }
            if (count > maxBytes - bytes) return Result(false, maxBytes)
            bytes += count
            for (index in 0 until count) {
                val value = buffer[index].toInt() and 0xff
                if (value == '\n'.code) {
                    if (!oversized) onLine(line.toString("UTF-8").removeSuffix("\r"))
                    line.reset()
                    oversized = false
                } else if (!oversized) {
                    if (line.size() >= maxLineBytes) {
                        valid = false
                        oversized = true
                        line.reset()
                    } else {
                        line.write(value)
                    }
                }
            }
        }
    }
}

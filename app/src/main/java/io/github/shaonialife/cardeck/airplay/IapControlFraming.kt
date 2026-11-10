package io.github.shaonialife.cardeck.airplay

import io.github.shaonialife.cardeck.iap2.Iap2LinkEngine
import io.github.shaonialife.cardeck.iap2.Iap2LinkPacket

/**
 * BMW 分帧实验只改变 POST 边界，不重编码链路帧。整块先校验再拆分，避免尾部不完整时
 * 已经投递前缀、随后回退原块而重复发送；未知格式整块保留，由调用方记录诊断。
 */
internal object IapControlFraming {
    data class Result(
        val frames: List<ByteArray>,
        val fallbackReason: String? = null,
    )

    fun splitOrPreserve(bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result(emptyList())
        val ranges = ArrayList<IntRange>()
        var offset = 0
        fun preserve(reason: String) = Result(listOf(bytes), reason)
        while (offset < bytes.size) {
            val remaining = bytes.size - offset
            if (remaining < 2) return preserve("帧头不完整")
            if (bytes[offset] != 0xFF.toByte()) return preserve("未知帧头")
            val length =
                when (bytes[offset + 1]) {
                    0x55.toByte() -> {
                        val marker = Iap2LinkEngine.MARKER
                        if (remaining < marker.size) return preserve("探测标记不完整")
                        if (marker.indices.any { bytes[offset + it] != marker[it] }) return preserve("探测标记无效")
                        marker.size
                    }

                    0x5A.toByte() -> {
                        if (remaining < Iap2LinkPacket.HEADER_BYTES) return preserve("链路包头不完整")
                        val size = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
                        if (size < Iap2LinkPacket.HEADER_BYTES) return preserve("链路包长无效")
                        if (size > remaining) return preserve("链路包体不完整")
                        val payloadOffset = offset + Iap2LinkPacket.HEADER_BYTES
                        if (!Iap2LinkPacket.verify(bytes, offset, payloadOffset)) return preserve("链路头校验失败")
                        if (size > Iap2LinkPacket.HEADER_BYTES && !Iap2LinkPacket.verify(bytes, payloadOffset, offset + size)) {
                            return preserve("链路载荷校验失败")
                        }
                        size
                    }

                    else -> return preserve("未知帧头")
                }
            ranges += offset until offset + length
            offset += length
        }
        return Result(ranges.map { bytes.copyOfRange(it.first, it.last + 1) })
    }
}

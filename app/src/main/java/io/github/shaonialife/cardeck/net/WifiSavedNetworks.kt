package io.github.shaonialife.cardeck.net

/**
 * 解析 `cmd wifi list-networks` 的输出（纯函数，单测覆盖）。
 *
 * 真实格式（空格对齐、**无制表符**，SSID 可含空格；同一网络按安全类型可能多行）：
 * ```
 * Network Id      SSID                         Security type
 * 0            2-502                            wpa2-psk
 * 1            测试手机的iPhone                    wpa3-sae
 * ```
 * 首列为 netId，末列为安全类型。保留 SSID 内部的空格，避免把相似热点当作同一个。
 */
object WifiSavedNetworks {
    private val row = Regex("""^\s*(\d+)\s+(.+?)\s+\S+\s*$""")
    /**
     * 返回 SSID 精确匹配的 netId 列表（去重；未匹配返回空）。
     * 调用方只在列表非空时才可能执行 `forget-network`。
     */
    fun idsOf(
        listNetworksOutput: String,
        ssid: String,
    ): List<Int> =
        listNetworksOutput
            .lineSequence()
            .drop(1)
            .mapNotNull { line ->
                val match = row.matchEntire(line) ?: return@mapNotNull null
                if (ssid.isBlank() || match.groupValues[2] != ssid) return@mapNotNull null
                match.groupValues[1].toIntOrNull()
            }.distinct()
            .toList()
}

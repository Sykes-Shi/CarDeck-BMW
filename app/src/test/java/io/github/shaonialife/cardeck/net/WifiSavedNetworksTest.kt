package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `cmd wifi list-networks` 解析：只用于「询问用户是否清除」，
 * 判错会误删用户网络，因此边界必须锁死。
 */
class WifiSavedNetworksTest {
    /** 真机（荣耀 Android 17）实测输出：空格对齐、无制表符。 */
    private val realOutput =
        """
        Network Id      SSID                         Security type
        0            2-502                            wpa2-psk
        0            2-502                            wpa3-sae^
        1            测试手机的iPhone                    wpa3-sae
        2            Example_office                    open
        2            Example_office                    owe 
        3            Audi_MMI_9310_5GHz               wpa2-psk
        """.trimIndent()

    @Test
    fun findsExactMatchIgnoringOtherNetworks() {
        assertEquals(listOf(3), WifiSavedNetworks.idsOf(realOutput, "Audi_MMI_9310_5GHz"))
    }

    @Test
    fun dedupesMultipleSecurityRowsForSameNetwork() {
        // 同一 netId 可能有 wpa2/wpa3 两行
        assertEquals(listOf(0), WifiSavedNetworks.idsOf(realOutput, "2-502"))
    }

    @Test
    fun handlesSsidWithSpacesAndNonAscii() {
        assertEquals(listOf(2), WifiSavedNetworks.idsOf(realOutput, "Example_office"))
        assertEquals(listOf(1), WifiSavedNetworks.idsOf(realOutput, "测试手机的iPhone"))
    }

    @Test
    fun removingSavedCarWifiDoesNotMatchAnotherSsidWithDifferentSpacing() {
        val listing = "Network Id  SSID  Security type\n7    BMW   12345    wpa2-psk\n8    BMW 12345    wpa2-psk"
        assertEquals(listOf(7), WifiSavedNetworks.idsOf(listing, "BMW   12345"))
        assertEquals(listOf(8), WifiSavedNetworks.idsOf(listing, "BMW 12345"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(listing, "BMW  12345"))
    }

    @Test
    fun noMatchReturnsEmpty() {
        // 不存在的 SSID 绝不能被近似匹配到（否则就是误删别人的网络）
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, "Audi_MMI_9310"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, "Redmi K40"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf(realOutput, ""))
    }

    @Test
    fun headerOnlyOrEmptyOutputIsSafe() {
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("Network Id SSID Security type", "X"))
        assertEquals(emptyList<Int>(), WifiSavedNetworks.idsOf("", "X"))
    }

    @Test
    fun skipsMalformedRows() {
        val messy =
            """
            Network Id      SSID                         Security type
            notanumber   Foo                          wpa2-psk
            7            OnlyTwoColumns
            8            Bar                          wpa2-psk
            """.trimIndent()
        assertEquals(listOf(8), WifiSavedNetworks.idsOf(messy, "Bar"))
    }
}

package io.github.shaonialife.cardeck.launcher.settings

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationPackageTest {
    @Test
    fun vpnServiceIsProtectedEvenInALegacyComponentKey() {
        val type = OrientationPackage.inspect("com.example.provider/.Home") { pkg ->
            assertEquals("com.example.provider", pkg)
            listOf(null, Manifest.permission.BIND_VPN_SERVICE)
        }
        assertFalse(type.mayRestart)
        assertNull(type.compatAction(true))
        assertEquals("disable --no-kill", type.compatAction(false))
    }

    @Test
    fun clashIsProtectedEvenWhenItsPrivateServiceIsInvisible() {
        val type = OrientationPackage.inspect("com.github.metacubex.clash.meta") { emptyList() }
        assertEquals(OrientationPackage.VPN, type)
    }

    @Test
    fun missingMetadataAllowsOnlyRestartFreeLegacyCleanup() {
        val type = OrientationPackage.inspect("com.example.old") { null }
        assertFalse(type.mayRestart)
        assertNull(type.compatAction(true))
        assertEquals("disable --no-kill", type.compatAction(false))
    }

    @Test
    fun appThatAddsVpnServiceOnUpdateIsReclassifiedBeforeTheNextWrite() {
        var permissions = listOf<String?>(Manifest.permission.ACCESS_NETWORK_STATE)
        fun inspect() = OrientationPackage.inspect("com.example.media") { permissions }
        assertTrue(inspect().mayRestart)
        assertEquals("enable", inspect().compatAction(true))
        assertEquals("disable", inspect().compatAction(false))
        permissions = permissions + Manifest.permission.BIND_VPN_SERVICE
        assertNull(inspect().compatAction(true))
    }
}

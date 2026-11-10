package io.github.shaonialife.cardeck.launcher.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNavigationSessionPolicyTest {
    @Test
    fun queuedMapAndMediaActionsExpireAfterLiveModeSwitchWithoutChangingConnection() {
        val old = PhoneNavigationSessionPolicy(false, 7, 0)
        val phone = PhoneNavigationSessionPolicy(true, 7, 1)
        assertFalse(old.allowsMapAction(phone.generation, phone.revision))
        assertFalse(old.allowsAppLaunch("com.youku.phone", phone.generation, phone.revision))
        assertTrue(phone.allowsAppLaunch("com.youku.phone", 7, 1))
        assertFalse(phone.allowsMapAction(7, 1))
    }

    @Test
    fun returningToCarModeDoesNotReviveTheOldMapTap() {
        val old = PhoneNavigationSessionPolicy(false, 7, 0)
        val returned = PhoneNavigationSessionPolicy(false, 7, 2)
        assertFalse(old.allowsMapAction(returned.generation, returned.revision))
        assertTrue(returned.allowsMapAction(7, 2))
        assertTrue(returned.allowsAppLaunch("com.baidu.BaiduMap", 7, 2))
    }

    @Test
    fun phoneModeBlocksAutomaticMapAndEverySupportedExplicitMap() {
        val policy = PhoneNavigationSessionPolicy(enabled = true, generation = 7)
        assertFalse(policy.allowsMapAction(7))
        listOf("com.baidu.BaiduMap", "com.autonavi.minimap", "com.autonavi.amapauto", "com.tencent.map").forEach {
            assertFalse(policy.allowsAppLaunch(it, 7))
        }
    }

    @Test
    fun phoneModeLeavesVideoAndMusicOnTheirExistingCarPath() {
        val policy = PhoneNavigationSessionPolicy(enabled = true, generation = 7)
        listOf("com.youku.phone", "com.netease.cloudmusic", "com.spotify.music").forEach {
            assertTrue(policy.allowsAppLaunch(it, 7))
        }
    }

    @Test
    fun normalSessionRetainsMapAndMediaBehavior() {
        val policy = PhoneNavigationSessionPolicy(enabled = false, generation = 7)
        assertTrue(policy.allowsMapAction(7))
        assertTrue(policy.allowsAppLaunch("com.baidu.BaiduMap", 7))
        assertTrue(policy.allowsAppLaunch("com.youku.phone", 7))
    }

    @Test
    fun oldNormalSessionCannotLaunchMapOrMediaAfterPhoneModeReconnect() {
        val old = PhoneNavigationSessionPolicy(enabled = false, generation = 7)
        val current = PhoneNavigationSessionPolicy(enabled = true, generation = 8)
        assertFalse(old.allowsMapAction(current.generation))
        assertFalse(old.allowsAppLaunch("com.baidu.BaiduMap", current.generation))
        assertFalse(old.allowsAppLaunch("com.youku.phone", current.generation))
        assertFalse(current.allowsMapAction(current.generation))
    }

    @Test
    fun nextConnectionChoiceDoesNotMutateCurrentSessionSnapshot() {
        var savedPreference = true
        val current = PhoneNavigationSessionPolicy(savedPreference, generation = 7)
        savedPreference = false
        val next = PhoneNavigationSessionPolicy(savedPreference, generation = 8)
        assertTrue(current.enabled)
        assertFalse(current.allowsMapAction(7))
        assertTrue(next.allowsMapAction(8))
        assertFalse(current.allowsAppLaunch("com.baidu.BaiduMap", 8))
    }
}

package io.github.shaonialife.cardeck.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationSessionPolicyTest {
    private val map = "com.baidu.BaiduMap"
    private val otherMap = "com.autonavi.minimap"
    private val video = "com.youku.phone"

    private fun policy() = OrientationSessionPolicy { it == map || it == otherMap }

    @Test
    fun disconnectedManualCleanupUsesPhoneNavigationPreference() {
        val policy = policy()
        val idle = policy.token()
        assertFalse(policy.permits(idle, map, fallbackPhoneNavigation = true))
        assertTrue(policy.permits(idle, video, fallbackPhoneNavigation = true))
        assertTrue(policy.permits(idle, map, fallbackPhoneNavigation = false))
        assertNull(policy.sessionToken())
    }

    @Test
    fun phoneSessionProtectsAllKnownMapsWithoutDisablingOtherApps() {
        val policy = policy()
        val session = policy.begin(true)
        assertFalse(policy.permits(session, map))
        assertFalse(policy.permits(session, otherMap))
        assertTrue(policy.permits(session, video))
        assertTrue(policy.isActive(session))
    }

    @Test
    fun componentKeysCannotBypassMapProtection() {
        val policy = policy()
        val session = policy.begin(true)
        assertFalse(policy.permits(session, "$map/.MainActivity"))
        assertTrue(policy.permits(session, "$map.unrelated"))
    }

    @Test
    fun preferencesAloneDoNotChangeActiveSessionPolicy() {
        val policy = policy()
        val normal = policy.begin(false)
        assertTrue(policy.permits(normal, map, fallbackPhoneNavigation = true))
        val phone = policy.begin(true)
        assertFalse(policy.permits(phone, map, fallbackPhoneNavigation = false))
        assertFalse(policy.permits(normal, video))
    }

    @Test
    fun disconnectInvalidatesQueuedApplyAndDelayedProbe() {
        val policy = policy()
        val session = policy.begin(false)
        val cleanup = policy.end()
        assertFalse(policy.permits(session, video))
        assertFalse(policy.isActive(session))
        assertNull(policy.sessionToken())
        assertTrue(policy.permits(cleanup, video))
    }

    @Test
    fun repeatedDetachDoesNotInvalidateAlreadyQueuedCleanup() {
        val policy = policy()
        policy.begin(false)
        val cleanup = policy.end()
        assertEquals(cleanup, policy.end())
        assertEquals(setOf(video), policy.resetCandidates(cleanup, setOf(video)))
    }

    @Test
    fun oldCleanupCannotDisableNewSessionOverrides() {
        val policy = policy()
        policy.begin(false)
        val cleanup = policy.end()
        val current = policy.begin(false)
        assertTrue(policy.permits(current, video))
        assertFalse(policy.permits(cleanup, video))
        assertTrue(policy.resetCandidates(cleanup, setOf(map, video)).isEmpty())
    }

    @Test
    fun secondCompatCommandIsRejectedAfterGenerationChanges() {
        val policy = policy()
        val old = policy.begin(false)
        assertTrue(policy.permits(old, map))
        policy.begin(true)
        assertFalse(policy.permits(old, map))
        assertFalse(policy.isActive(old))
    }

    @Test
    fun cleanupUsesAttemptedPackagesNotPreferenceMembership() {
        val policy = policy()
        policy.begin(false)
        val cleanup = policy.end()
        assertEquals(setOf(video), policy.resetCandidates(cleanup, setOf(video)))
        assertFalse(map in policy.resetCandidates(cleanup, setOf(video)))
        assertTrue(policy.resetCandidates(cleanup, emptySet()).isEmpty())
    }

    @Test
    fun protectedResidualMapsAreRetainedDuringPhoneNavigation() {
        val policy = policy()
        policy.begin(true)
        val cleanup = policy.end()
        val persisted = setOf(map, "$otherMap/.Main", video)
        assertEquals(setOf(video), policy.resetCandidates(cleanup, persisted, fallbackPhoneNavigation = true))
        assertEquals(setOf(map, "$otherMap/.Main", video), persisted)
    }

    @Test
    fun processRestartStillFiltersPersistedMapAttempts() {
        val restarted = policy()
        val cleanup = restarted.end()
        assertEquals(setOf(video), restarted.resetCandidates(cleanup, setOf(map, video), fallbackPhoneNavigation = true))
    }

    @Test
    fun laterNormalSessionCanRestorePreviouslyProtectedAttempts() {
        val policy = policy()
        policy.begin(true)
        val oldCleanup = policy.end()
        assertTrue(policy.resetCandidates(oldCleanup, setOf(map), fallbackPhoneNavigation = true).isEmpty())
        policy.begin(false)
        val normalCleanup = policy.end()
        assertEquals(setOf(map), policy.resetCandidates(normalCleanup, setOf(map), fallbackPhoneNavigation = false))
    }

    @Test
    fun staleIdlePreferenceWriteCannotAffectNewConnection() {
        val policy = policy()
        val settingsAction = policy.token()
        policy.begin(false)
        assertFalse(policy.permits(settingsAction, video))
        assertFalse(policy.isCurrent(settingsAction))
    }

    @Test
    fun resetReturnsIndependentBoundedSelection() {
        val policy = policy()
        val cleanup = policy.end()
        val persisted = linkedSetOf(video)
        val candidates = policy.resetCandidates(cleanup, persisted)
        persisted.add(map)
        assertEquals(setOf(video), candidates)
    }

    @Test
    fun legacyPreferenceRecoveryRequiresExplicitResidualScreenPath() {
        val policy = policy()
        val cleanup = policy.end()
        assertTrue(policy.resetCandidates(cleanup, emptySet(), legacyEnabled = setOf(video)).isEmpty())
        assertEquals(
            setOf(video),
            policy.resetCandidates(cleanup, emptySet(), legacyEnabled = setOf(video), recoverLegacy = true),
        )
    }

    @Test
    fun legacyRecoveryAlsoProtectsPhoneMapsAndRejectsOldCleanup() {
        val policy = policy()
        val cleanup = policy.end()
        assertEquals(
            setOf(video),
            policy.resetCandidates(cleanup, emptySet(), true, setOf(map, video), recoverLegacy = true),
        )
        policy.begin(false)
        assertTrue(policy.resetCandidates(cleanup, emptySet(), legacyEnabled = setOf(video), recoverLegacy = true).isEmpty())
    }

    @Test
    fun legacyRecoveryCannotEndAnActiveOrientationSession() {
        val policy = policy()
        val session = policy.begin(false)
        assertNull(policy.idleRecoveryToken())
        assertTrue(policy.isActive(session))
        assertTrue(policy.permits(session, map))
    }

    @Test
    fun idleRecoveryTokenBecomesInvalidWhenAConnectionStarts() {
        val policy = policy()
        val recovery = policy.idleRecoveryToken()!!
        assertTrue(policy.permits(recovery, video))
        policy.begin(false)
        assertFalse(policy.permits(recovery, video))
        assertTrue(policy.resetCandidates(recovery, setOf(video), recoverLegacy = true).isEmpty())
    }

    @Test
    fun livePhoneSwitchInvalidatesOldWorkButKeepsNewNonMapWritesAvailable() {
        val policy = policy()
        val before = policy.begin(false)
        val after = policy.updatePhoneNavigation(true)!!
        assertFalse(policy.isCurrent(before))
        assertFalse(policy.permits(before, video))
        assertTrue(policy.isActive(after))
        assertFalse(policy.permits(after, map))
        assertFalse(policy.permits(after, "$otherMap/.Main"))
        assertTrue(policy.permits(after, video))
    }

    @Test
    fun switchingBackDoesNotUnfreezeMapsOrReviveOldWork() {
        val policy = policy()
        val original = policy.begin(false)
        val phone = policy.updatePhoneNavigation(true)!!
        val car = policy.updatePhoneNavigation(false)!!
        assertFalse(policy.isCurrent(original))
        assertFalse(policy.isCurrent(phone))
        assertFalse(policy.permits(car, map))
        assertTrue(policy.permits(car, video))
        val cleanup = policy.end()
        assertEquals(setOf(video), policy.resetCandidates(cleanup, setOf(map, video), fallbackPhoneNavigation = false))
        assertFalse(policy.permits(cleanup, map, fallbackPhoneNavigation = false))
    }

    @Test
    fun turningOffInitialPhoneModeAlsoFreezesMapWritesUntilNextConnection() {
        val policy = policy()
        val initialPhone = policy.begin(true)
        val car = policy.updatePhoneNavigation(false)!!
        assertFalse(policy.isCurrent(initialPhone))
        assertFalse(policy.permits(car, otherMap))
        assertTrue(policy.permits(car, video))
    }

    @Test
    fun unchangedModeDoesNotInvalidateWorkOrFreezeNormalMapWrites() {
        val policy = policy()
        val normal = policy.begin(false)
        assertEquals(normal, policy.updatePhoneNavigation(false))
        assertTrue(policy.permits(normal, map))
        assertTrue(policy.isActive(normal))
    }

    @Test
    fun disconnectedModeUpdateCannotChangeCleanupOwnership() {
        val policy = policy()
        policy.begin(false)
        val cleanup = policy.end()
        assertNull(policy.updatePhoneNavigation(true))
        assertTrue(policy.isCurrent(cleanup))
        assertEquals(setOf(map), policy.resetCandidates(cleanup, setOf(map)))
    }

    @Test
    fun nextConnectionReleasesStickyProtectionAndRejectsPreviousTokens() {
        val policy = policy()
        policy.begin(false)
        policy.updatePhoneNavigation(true)
        val previous = policy.updatePhoneNavigation(false)!!
        val cleanup = policy.end()
        val next = policy.begin(false)
        assertFalse(policy.permits(previous, video))
        assertFalse(policy.permits(cleanup, video))
        assertTrue(policy.permits(next, map))
        val nextCleanup = policy.end()
        assertEquals(setOf(map, video), policy.resetCandidates(nextCleanup, setOf(map, video)))
    }
}

package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyMediaVolumeRecoveryTest {
    @Test
    fun oldMutedOutputRestoresRecordedLevel() {
        assertEquals(LegacyMediaVolumeRecovery.Decision.Restore(4), LegacyMediaVolumeRecovery.decide(4, 0, 15))
    }

    @Test
    fun userChangedVolumeIsPreserved() {
        assertEquals(LegacyMediaVolumeRecovery.Decision.PreserveCurrent, LegacyMediaVolumeRecovery.decide(12, 3, 15))
    }

    @Test
    fun invalidOrZeroBackupDoesNotInventANewVolume() {
        listOf(-1, 0).forEach {
            assertEquals(LegacyMediaVolumeRecovery.Decision.PreserveCurrent, LegacyMediaVolumeRecovery.decide(it, 0, 15))
        }
    }

    @Test
    fun changedDeviceVolumeRangeClampsRestoreTarget() {
        assertEquals(LegacyMediaVolumeRecovery.Decision.Restore(7), LegacyMediaVolumeRecovery.decide(12, 0, 7))
    }

    @Test
    fun UnknownCurrentVolumeKeepsBackupForRetry() {
        assertEquals(LegacyMediaVolumeRecovery.Decision.Retry, LegacyMediaVolumeRecovery.decide(4, -1, 15))
    }

    @Test
    fun unknownMaximumKeepsBackupForRetry() {
        assertEquals(LegacyMediaVolumeRecovery.Decision.Retry, LegacyMediaVolumeRecovery.decide(4, 0, 0))
    }
}

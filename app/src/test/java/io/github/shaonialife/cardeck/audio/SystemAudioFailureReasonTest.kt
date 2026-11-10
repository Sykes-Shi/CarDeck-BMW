package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemAudioFailureReasonTest {
    @Test
    fun acceptsIdentityFailuresButNotArbitraryIdentityDetails() {
        assertEquals(
            "invalid_shell_package",
            SystemAudioFailureReason.fromPipeError("Remote error: invalid_shell_package"),
        )
        assertEquals(
            "invalid_shell_package_uid",
            SystemAudioFailureReason.fromPipeError("Remote error: invalid_shell_package_uid"),
        )
        assertEquals(
            "unknown_remote_error",
            SystemAudioFailureReason.fromPipeError("invalid_shell_package_uid package=private"),
        )
    }

    @Test
    fun acceptsKnownReasonWithAndroidReliablePipePrefix() {
        assertEquals("record_stalled", SystemAudioFailureReason.fromPipeError("Remote error: record_stalled"))
        assertEquals("consumer_closed", SystemAudioFailureReason.fromPipeError("consumer_closed"))
    }

    @Test
    fun acceptsOnlyKnownStageAndExceptionType() {
        assertEquals("build_record:SecurityException", SystemAudioFailureReason.fromPipeError("Remote error: build_record:SecurityException"))
        assertEquals("unknown_remote_error", SystemAudioFailureReason.fromPipeError("untrusted_stage:SecurityException"))
    }

    @Test
    fun rejectsMessagesAndNewlinesWithoutExportingThem() {
        listOf("build_record:SecurityException token=secret", "record_stalled\nsecret", "build_record:java.lang.Exception", "private_data").forEach {
            assertEquals("unknown_remote_error", SystemAudioFailureReason.fromPipeError(it))
        }
    }

    @Test
    fun rejectsMissingOversizedOrEmptyType() {
        listOf(null, "", "build_record:", "build_record:" + "A".repeat(81), "A".repeat(129)).forEach {
            assertEquals("unknown_remote_error", SystemAudioFailureReason.fromPipeError(it))
        }
    }
}

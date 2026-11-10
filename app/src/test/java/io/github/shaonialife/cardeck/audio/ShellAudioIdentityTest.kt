package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellAudioIdentityTest {
    @Test
    fun acceptsActualShellProcessAndMatchingInstalledShellPackage() {
        assertNull(ShellAudioIdentity.validationFailure(2000, "com.android.shell", 2000))
    }

    @Test
    fun ordinaryAppCannotUseShellPackageNameToClaimShellIdentity() {
        assertEquals(
            "unsupported_uid",
            ShellAudioIdentity.validationFailure(10420, "com.android.shell", 2000),
        )
    }

    @Test
    fun rootIsNotSilentlyReattributedToShell() {
        assertEquals(
            "unsupported_uid",
            ShellAudioIdentity.validationFailure(0, "com.android.shell", 2000),
        )
    }

    @Test
    fun shellUidWithAnotherPackageIsRejected() {
        assertEquals(
            "invalid_shell_package",
            ShellAudioIdentity.validationFailure(2000, "io.github.shaonialife.cardeck", 2000),
        )
    }

    @Test
    fun missingPackageNameIsRejected() {
        assertEquals("invalid_shell_package", ShellAudioIdentity.validationFailure(2000, null, 2000))
    }

    @Test
    fun shellNamedPackageOwnedByAnotherUidIsRejected() {
        assertEquals(
            "invalid_shell_package_uid",
            ShellAudioIdentity.validationFailure(2000, "com.android.shell", 1000),
        )
    }

    @Test
    fun unknownInstalledPackageUidIsRejected() {
        assertEquals(
            "invalid_shell_package_uid",
            ShellAudioIdentity.validationFailure(2000, "com.android.shell", -1),
        )
    }
}

package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiJoinRecoveryTest {
    private fun path(chooseRetry: Boolean?, retryFinishes: Boolean = false, allowManual: Boolean = false): List<String> {
        val steps = mutableListOf<String>()
        WifiJoinRecovery.run(
            chooseRetryOrManual = { chooseRetry },
            finishRetry = { steps.add("retry"); retryFinishes },
            confirmManual = { steps.add("confirm"); allowManual },
            joinManually = { steps.add("manual") },
        )
        return steps
    }

    @Test
    fun failedDedicatedRetryCanContinueIntoManualJoiningWithoutAnotherHandshake() {
        assertEquals(listOf("retry", "confirm", "manual"), path(true, allowManual = true))
    }

    @Test
    fun failedRetryCannotOpenSystemWifiWithoutExplicitChoice() {
        assertEquals(listOf("retry", "confirm"), path(true, allowManual = false))
        assertEquals(emptyList<String>(), path(null))
    }

    @Test
    fun workingRetryAndDirectManualChoiceEachFinishOnlyOnce() {
        assertEquals(listOf("retry"), path(true, retryFinishes = true))
        assertEquals(listOf("manual"), path(false))
    }
}

package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwControlIapCompatibilityTest {
    @Test
    fun onlyTheObservedManufacturerAndModelEnableTheExperiment() {
        assertTrue(BmwControlIapCompatibility.matches("BMW Group MGU-50-P"))
        assertTrue(BmwControlIapCompatibility.matches("  bmw  GROUP\tMGU-50-P  "))
        listOf("unknown", "", "BMW", "BMW Group MGU-118", "Other MGU-50-P", "BMW Group MGU-50-P-extra")
            .forEach { identity -> assertFalse(identity, BmwControlIapCompatibility.matches(identity)) }
    }
}

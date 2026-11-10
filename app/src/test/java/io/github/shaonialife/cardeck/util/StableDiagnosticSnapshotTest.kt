package io.github.shaonialife.cardeck.util

import org.junit.Assert.assertEquals
import org.junit.Test

class StableDiagnosticSnapshotTest {
    @Test fun stableClientReturnsObservation() {
        val client = Any()
        assertEquals(listOf("ok"), StableDiagnosticSnapshot.read({ client }) { listOf("ok") })
    }

    @Test fun reconnectDuringReadDiscardsEntireOldResult() {
        var client = Any()
        assertEquals(listOf("unavailable=client_changed"), StableDiagnosticSnapshot.read({ client }) {
            client = Any()
            listOf("old generation values")
        })
    }

    @Test fun disconnectDuringReadDiscardsOldResult() {
        var client: Any? = Any()
        assertEquals(listOf("unavailable=client_changed"), StableDiagnosticSnapshot.read({ client }) {
            client = null
            listOf("old values")
        })
    }

    @Test fun failureInStableClientIsExplicit() {
        val client = Any()
        assertEquals(listOf("unavailable=snapshot_failed"), StableDiagnosticSnapshot.read({ client }) {
            error("private exception message")
        })
    }

    @Test fun absentClientDoesNotInvokeCollector() {
        assertEquals(listOf("unavailable=no_current_client"), StableDiagnosticSnapshot.read<Any>({ null }) {
            error("must not run")
        })
    }
}

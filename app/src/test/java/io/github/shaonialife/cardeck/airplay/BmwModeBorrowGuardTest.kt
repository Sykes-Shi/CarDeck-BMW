package io.github.shaonialife.cardeck.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BmwModeBorrowGuardTest {
    private fun phoneState(active: Boolean, vararg changes: Map<String, Any?>): Map<String, Any?> = mapOf(
        "type" to "changeModes", "params" to mapOf(
            "appStates" to listOf(mapOf("appStateID" to 2L, "state" to active),
                mapOf("appStateID" to 1L, "speechMode" to if (active) 1L else -1L)),
            "resources" to changes.toList(),
        ),
    )

    @Test fun overlappingNativeCallBorrowsGrantFreshMediaOnlyAfterFinalAcknowledgedReturn() {
        val g = guard()
        val firstEpoch = g.mediaEpoch()
        val begin = phoneState(true, row(2L, 3L, "ring"))
        g.apply(begin)
        g.apply(begin)
        assertEquals(null, g.mediaEpoch())
        assertEquals(2L, g.appStates().first()["entity"])
        assertEquals(2L, g.appStates().last()["entity"])
        assertEquals(1L, g.appStates().last()["speechMode"])
        g.apply(phoneState(true, row(2L, 3L, "conversation")))
        g.apply(phoneState(false, row(2L, 4L, "conversation")))
        g.acknowledgeModes()
        assertEquals(null, g.mediaEpoch())
        assertEquals(0L, g.appStates().first()["entity"])
        assertEquals(-1L, g.appStates().last()["speechMode"])
        val end = command(row(2L, 4L, "ring"))
        g.apply(end)
        assertEquals(null, g.mediaEpoch()) // return declaration failed or ACK still outstanding
        g.acknowledgeModes()
        val resumed = g.mediaEpoch()
        assertTrue(resumed != null && resumed != firstEpoch)
        g.apply(end)
        g.acknowledgeModes()
        assertEquals(resumed, g.mediaEpoch())
    }

    @Test fun phoneStateOnlyCanEndAfterResourceReturnAndNeverAffectsAnotherClient() {
        val bmw = guard()
        val other = guard("other receiver")
        val start = phoneState(true, row(2L, 3L, "call"))
        bmw.apply(start)
        other.apply(start)
        assertEquals(0L, other.mediaEpoch())
        assertEquals(0L, other.appStates().first()["entity"])
        bmw.apply(command(row(2L, 4L, "call")))
        bmw.acknowledgeModes()
        assertEquals(null, bmw.mediaEpoch())
        bmw.apply(mapOf("type" to "changeModes", "params" to mapOf(
            "appStates" to listOf(mapOf("appStateID" to 2L, "state" to false),
                mapOf("appStateID" to 1L, "speechMode" to -1L)))))
        bmw.acknowledgeModes()
        assertEquals(2L, bmw.mediaEpoch())
    }

    @Test fun aNewMediaSegmentKeepsItsEncryptedPacketSequenceAcrossTheNativeCall() {
        val g = guard()
        val cursor = io.github.shaonialife.cardeck.audio.AudioRtpCursor(1024)
        val sent = mutableListOf<Pair<Long, Boolean>>()
        fun packet(readEpoch: Long?) {
            if (readEpoch != null && readEpoch == g.mediaEpoch()) {
                sent += cursor.nonce to (cursor.lastEpoch != readEpoch)
                cursor.advance(readEpoch)
            }
        }
        val original = g.mediaEpoch()
        packet(original)
        packet(original)
        g.apply(phoneState(true, row(2L, 3L, "call")))
        packet(original) // read started before borrow
        repeat(3_000) { packet(g.mediaEpoch()) }
        g.apply(phoneState(false, row(2L, 4L, "call")))
        g.acknowledgeModes()
        packet(original) // very short call between reading and sending
        packet(null) // read began during a call that has now ended
        packet(g.mediaEpoch())
        packet(g.mediaEpoch())
        assertEquals(listOf(0L to true, 1L to false, 2L to true, 3L to false), sent)
        assertEquals(4096L, cursor.timestamp)
        assertEquals(4, cursor.sequence)
    }
    @Test
    fun rotaryInputIsInvalidatedByBorrowAndNeverGrantedToUnknownReceiverOrNativeScreen() {
        val bmw = guard()
        val before = bmw.controllerInputRevision()
        assertTrue(before != null)
        bmw.apply(command(row(id = "camera")))
        assertEquals(null, bmw.controllerInputRevision())
        bmw.apply(command(row(transfer = 4L, id = "camera")))
        val after = bmw.controllerInputRevision()
        assertTrue(after != null && after != before)
        assertEquals(null, nativeAfterBorrow().controllerInputRevision())
        assertEquals(null, guard("another receiver").controllerInputRevision())
    }
    private fun guard(identity: String = "BMW Group MGU-50-P") = BmwModeBorrowGuard(identity)

    private fun row(resource: Any? = 1L, transfer: Any? = 3L, id: Any? = "borrow-a"): Map<String, Any?> =
        mapOf("resourceID" to resource, "transferType" to transfer, "borrowID" to id)

    private fun command(vararg rows: Any?): Map<String, Any?> =
        mapOf("type" to "changeModes", "params" to mapOf("resources" to rows.toList()))

    private fun owner(guard: BmwModeBorrowGuard, resource: Int): Any? = guard.resources()[resource - 1]["entity"]

    private fun observedTake(): Map<String, Any?> = mapOf(
        "resourceID" to 1L,
        "transferType" to 1L,
        "transferPriority" to 500L,
        "takeConstraint" to 500L,
        "borrowConstraint" to 100L,
    )

    private fun nativeAfterBorrow(): BmwModeBorrowGuard = guard().also {
        it.apply(command(row()))
        it.apply(command(observedTake()))
    }

    private fun requestUi(guard: BmwModeBorrowGuard) =
        guard.restoreRequestedUi(guard.captureUiRestoreRevision())

    @Test
    fun newClientStartsWithControllerResources() {
        val guard = guard()
        assertEquals(
            listOf(
                mapOf("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L),
                mapOf("resourceID" to 2L, "entity" to 1L, "permanentEntity" to 1L),
            ),
            guard.resources(),
        )
        assertFalse(guard.isScreenBorrowed())
    }

    @Test
    fun screenBorrowChangesTemporaryOwnerOnly() {
        val guard = guard()
        assertTrue(guard.apply(command(row())).contains("borrowed=1"))
        assertTrue(guard.isScreenBorrowed())
        assertEquals(2L, owner(guard, 1))
        assertEquals(1L, guard.resources()[0]["permanentEntity"])
        assertEquals(1L, owner(guard, 2))
    }

    @Test
    fun matchingUnborrowRestoresController() {
        val guard = guard()
        guard.apply(command(row()))
        assertTrue(guard.apply(command(row(transfer = 4L))).contains("returned=1"))
        assertFalse(guard.isScreenBorrowed())
        assertEquals(1L, owner(guard, 1))
    }

    @Test
    fun duplicateBorrowAndDoubleReturnAreIdempotent() {
        val guard = guard()
        guard.apply(command(row()))
        assertTrue(guard.apply(command(row())).contains("duplicate=1"))
        assertTrue(guard.summary().contains("screenActive=1"))
        guard.apply(command(row(transfer = 4L)))
        assertFalse(guard.isScreenBorrowed())
        assertTrue(guard.apply(command(row(transfer = 4L))).contains("unmatched=1"))
        assertFalse(guard.isScreenBorrowed())
    }

    @Test
    fun differentBorrowIdsRequireBothReturns() {
        val guard = guard()
        guard.apply(command(row(id = "a")))
        guard.apply(command(row(id = "b")))
        assertTrue(guard.summary().contains("screenActive=2"))
        guard.apply(command(row(transfer = 4L, id = "b")))
        assertTrue(guard.isScreenBorrowed())
        guard.apply(command(row(transfer = 4L, id = "a")))
        assertFalse(guard.isScreenBorrowed())
    }

    @Test
    fun mismatchDoesNotReleaseOtherBorrow() {
        val guard = guard()
        guard.apply(command(row(id = "a")))
        assertTrue(guard.apply(command(row(transfer = 4L, id = "b"))).contains("unmatched=1"))
        assertTrue(guard.isScreenBorrowed())
        assertTrue(guard.summary().contains("screenActive=1"))
    }

    @Test
    fun audioBorrowIsIndependentOfScreen() {
        val guard = guard()
        guard.apply(command(row(resource = 2L)))
        assertFalse(guard.isScreenBorrowed())
        assertEquals(1L, owner(guard, 1))
        assertEquals(2L, owner(guard, 2))
        assertEquals(1L, guard.resources()[1]["permanentEntity"])
        guard.apply(command(row(resource = 2L, transfer = 4L)))
        assertEquals(1L, owner(guard, 2))
    }

    @Test
    fun sameTokenOnTwoResourcesDoesNotCrossRelease() {
        val guard = guard()
        guard.apply(command(row(), row(resource = 2L)))
        guard.apply(command(row(transfer = 4L)))
        assertEquals(1L, owner(guard, 1))
        assertEquals(2L, owner(guard, 2))
    }

    @Test
    fun exactKnownReceiverGateDoesNotUseBroadBmwMatch() {
        for (identity in listOf("", "BMW", "BMW Group MGU-50", "BMW Group MGU-50-P extra", "Audi MIB3", "fakeBMW Group MGU-50-P")) {
            val guard = guard(identity)
            assertTrue(guard.apply(command(row())).startsWith("modeBorrowGuard=receiver_not_supported"))
            assertFalse(guard.isScreenBorrowed())
        }
    }

    @Test
    fun receiverGateMatchesExistingWhitespaceAndCaseNormalization() {
        val guard = guard("  bmw\tGROUP   mgu-50-p  ")
        guard.apply(command(row()))
        assertTrue(guard.isScreenBorrowed())
    }

    @Test
    fun anotherClientNeverInheritsKnownOrUnknownBorrow() {
        val first = guard()
        first.apply(command(row(), row(id = null)))
        assertTrue(first.isScreenBorrowed())
        val second = guard()
        assertFalse(second.isScreenBorrowed())
        assertTrue(second.summary().contains("screenUnknown=false"))
    }

    @Test
    fun missingOrInvalidBorrowTokenEnablesStickyHold() {
        for (id in listOf(null, "", "   ", 1L, true, byteArrayOf(1), "bad\ntoken", "x".repeat(129))) {
            val guard = guard()
            val result = guard.apply(command(row(id = id)))
            assertTrue(result.contains("held=1"))
            assertTrue(guard.isScreenBorrowed())
            assertTrue(guard.summary().contains("screenActive=0 screenUnknown=true"))
            guard.apply(command(row(transfer = 4L, id = id)))
            assertTrue(guard.isScreenBorrowed())
        }
    }

    @Test
    fun unknownHoldSurvivesLaterMatchingKnownBorrowReturn() {
        val guard = guard()
        guard.apply(command(row(id = null)))
        guard.apply(command(row(id = "known")))
        guard.apply(command(row(transfer = 4L, id = "known")))
        assertTrue(guard.isScreenBorrowed())
        assertTrue(guard.summary().contains("screenActive=0 screenUnknown=true"))
    }

    @Test
    fun invalidUnborrowCannotClearKnownBorrow() {
        val guard = guard()
        guard.apply(command(row()))
        guard.apply(command(row(transfer = 4L, id = null)))
        assertTrue(guard.isScreenBorrowed())
        guard.apply(command(row(transfer = 4L)))
        assertFalse(guard.isScreenBorrowed())
    }

    @Test
    fun invalidUnborrowDoesNotCreateNewBorrow() {
        val guard = guard()
        guard.apply(command(row(transfer = 4L, id = null)))
        assertFalse(guard.isScreenBorrowed())
    }

    @Test
    fun maximumLengthTokenIsMatchedExactlyWithoutTrimming() {
        val guard = guard()
        val id = "x".repeat(126) + " z"
        guard.apply(command(row(id = id)))
        assertTrue(guard.isScreenBorrowed())
        guard.apply(command(row(transfer = 4L, id = id)))
        assertFalse(guard.isScreenBorrowed())
        guard.apply(command(row(id = " a ")))
        guard.apply(command(row(transfer = 4L, id = "a")))
        assertTrue(guard.isScreenBorrowed())
    }

    @Test
    fun eightDistinctBorrowsCanReturnWithoutUnknownHold() {
        val guard = guard()
        repeat(8) { guard.apply(command(row(id = "id-$it"))) }
        assertTrue(guard.summary().contains("screenActive=8 screenUnknown=false"))
        repeat(8) { guard.apply(command(row(transfer = 4L, id = "id-$it"))) }
        assertFalse(guard.isScreenBorrowed())
    }

    @Test
    fun duplicateAtCapacityDoesNotCreateOverflowHold() {
        val guard = guard()
        repeat(8) { guard.apply(command(row(id = "id-$it"))) }
        assertTrue(guard.apply(command(row(id = "id-0"))).contains("duplicate=1"))
        assertTrue(guard.summary().contains("screenUnknown=false"))
    }

    @Test
    fun ninthDistinctBorrowHoldsEvenAfterAllKnownIdsReturn() {
        val guard = guard()
        repeat(9) { guard.apply(command(row(id = "id-$it"))) }
        assertTrue(guard.summary().contains("screenActive=8 screenUnknown=true"))
        repeat(9) { guard.apply(command(row(transfer = 4L, id = "id-$it"))) }
        assertTrue(guard.isScreenBorrowed())
        assertTrue(guard.summary().contains("screenActive=0 screenUnknown=true"))
    }

    @Test
    fun takeAndUntakeAreExplicitlyRetainedLegacyWithoutClearingBorrow() {
        val guard = guard()
        guard.apply(command(row(), row(resource = 2L)))
        val result = guard.apply(command(row(transfer = 1L), row(transfer = 2L), row(resource = 2L, transfer = 1L)))
        assertTrue(result.contains("retained_legacy=3"))
        assertTrue(guard.isScreenBorrowed())
        assertEquals(2L, owner(guard, 2))
    }

    @Test
    fun takeAndUntakeDoNotPretendToImplementPermanentOwnership() {
        val guard = guard()
        val before = guard.resources()
        assertTrue(guard.apply(command(row(transfer = 1L), row(transfer = 2L))).contains("retained_legacy=2"))
        assertEquals(before, guard.resources())
    }

    @Test
    fun requestUiAndOtherCommandsCannotClearOrCreateBorrow() {
        val guard = guard()
        val request = command(row()).toMutableMap().apply { this["type"] = "requestUI" }
        assertTrue(guard.apply(request).contains("unsupported_command"))
        assertFalse(guard.isScreenBorrowed())
        guard.apply(command(row()))
        guard.apply(request)
        assertTrue(guard.isScreenBorrowed())
    }

    @Test
    fun invalidEnvelopeAndUnknownResourceRowsLeaveStateUntouched() {
        val guard = guard()
        val before = guard.resources()
        for (request in listOf(
            mapOf("type" to "changeModes"),
            mapOf("type" to "changeModes", "params" to "secret"),
            mapOf("type" to "changeModes", "params" to mapOf("resources" to "secret")),
            command(null, "secret", row(resource = 99L), row(transfer = 99L)),
        )) {
            guard.apply(request)
            assertEquals(before, guard.resources())
        }
    }

    @Test
    fun floatingPointStringBooleanEnumsAreNotCoerced() {
        for (value in listOf("1", 1.0, 1.2, true, null, Long.MAX_VALUE)) {
            val guard = guard()
            guard.apply(command(row(resource = value)))
            assertFalse(guard.isScreenBorrowed())
        }
        for (value in listOf("3", 3.0, 3.2, true, null, Long.MAX_VALUE)) {
            val guard = guard()
            guard.apply(command(row(transfer = value)))
            assertFalse(guard.isScreenBorrowed())
        }
    }

    @Test
    fun integerNumberRepresentationsAreAccepted() {
        for ((resource, transfer) in listOf(1L to 3L, 1 to 3, 1.toShort() to 3.toShort(), 1.toByte() to 3.toByte())) {
            val guard = guard()
            guard.apply(command(row(resource = resource, transfer = transfer)))
            assertTrue(guard.isScreenBorrowed())
        }
    }

    @Test
    fun resourceListOverflowPreservesBothResourcesConservatively() {
        val guard = guard()
        val rows = Array(33) { row(transfer = 4L) }
        assertTrue(guard.apply(command(*rows)).contains("resource_list_overflow_hold"))
        assertTrue(guard.isScreenBorrowed())
        assertEquals(2L, owner(guard, 2))
        assertTrue(guard.summary().contains("screenUnknown=true"))
        assertTrue(guard.summary().contains("audioUnknown=true"))
    }

    @Test
    fun summariesAndResourcesNeverExposeTokensReasonsOrUrls() {
        val guard = guard()
        val secretRow = row(id = "PRIVATE_BORROW_ID").toMutableMap().apply { this["reasonStr"] = "PRIVATE_REASON" }
        val request = command(secretRow).toMutableMap().apply { this["url"] = "https://PRIVATE_URL" }
        val output = guard.apply(request) + guard.summary() + guard.resources()
        assertFalse(output.contains("PRIVATE"))
        assertFalse(output.contains("https"))
        assertFalse(output.contains("borrowID"))
        assertFalse(output.contains("reasonStr"))
    }

    @Test
    fun returnedResourceMapsCannotMutateInternalState() {
        val guard = guard()
        val first = guard.resources()
        (first[0] as MutableMap<String, Any?>)["entity"] = 999L
        assertEquals(1L, owner(guard, 1))
        guard.apply(command(row()))
        assertEquals(999L, first[0]["entity"])
        assertEquals(2L, owner(guard, 1))
    }

    @Test
    fun mixedResourceBatchPreservesIndependentNestedBorrows() {
        val guard = guard()
        guard.apply(command(row(id = "a"), row(id = "b"), row(resource = 2L, id = "c")))
        guard.apply(command(row(transfer = 4L, id = "a"), row(resource = 2L, transfer = 4L, id = "c")))
        assertTrue(guard.isScreenBorrowed())
        assertEquals(1L, owner(guard, 2))
        guard.apply(command(row(transfer = 4L, id = "b")))
        assertFalse(guard.isScreenBorrowed())
    }

    @Test
    fun fieldSequenceWaitsInNativeUntilFreshRequestUi() {
        val guard = guard()
        guard.apply(command(row().toMutableMap().apply {
            this["transferPriority"] = 500L
            this["unborrowConstraint"] = 1000L
        }))
        assertFalse(guard.isScreenAvailableToController())
        assertEquals("receiver_screen_borrowed", requestUi(guard).reason)
        assertTrue(guard.apply(command(observedTake())).contains("screenTake=1"))
        assertFalse(guard.isScreenBorrowed())
        assertFalse(guard.isScreenAvailableToController())
        assertEquals(mapOf("resourceID" to 1L, "entity" to 2L, "permanentEntity" to 2L), guard.resources()[0])
        val restored = requestUi(guard)
        assertTrue(restored.allowed)
        assertEquals("bmw_take_then_request_ui", restored.reason)
        assertTrue(guard.isScreenAvailableToController())
        assertEquals(mapOf("resourceID" to 1L, "entity" to 1L, "permanentEntity" to 1L), guard.resources()[0])
    }

    @Test
    fun duplicateObservedTakeDoesNotRestoreOrLosePendingPermission() {
        val guard = nativeAfterBorrow()
        repeat(3) {
            guard.apply(command(observedTake()))
            assertFalse(guard.isScreenAvailableToController())
            assertEquals(2L, guard.resources()[0]["permanentEntity"])
        }
        assertEquals("bmw_take_then_request_ui", requestUi(guard).reason)
    }

    @Test
    fun requestQueuedBeforeTakeCannotUseLaterNativeState() {
        val guard = guard()
        guard.apply(command(row()))
        val earlierRequest = guard.captureUiRestoreRevision()
        guard.apply(command(observedTake()))
        val rejected = guard.restoreRequestedUi(earlierRequest)
        assertFalse(rejected.allowed)
        assertEquals("mode_state_changed", rejected.reason)
        assertEquals(2L, owner(guard, 1))
        assertTrue(requestUi(guard).allowed)
    }

    @Test
    fun requestQueuedBeforeNewBorrowAndTakeCannotReclaimScreen() {
        val guard = nativeAfterBorrow()
        val earlierRequest = guard.captureUiRestoreRevision()
        guard.apply(command(row(id = "new-camera")))
        guard.apply(command(observedTake()))
        assertEquals("mode_state_changed", guard.restoreRequestedUi(earlierRequest).reason)
        assertEquals(2L, owner(guard, 1))
        assertEquals("bmw_take_then_request_ui", requestUi(guard).reason)
    }

    @Test
    fun newBorrowBlocksPendingNativeReturnUntilMatchingReleaseAndFreshUi() {
        val guard = nativeAfterBorrow()
        val earlierRequest = guard.captureUiRestoreRevision()
        guard.apply(command(row(id = "second-camera")))
        assertEquals("mode_state_changed", guard.restoreRequestedUi(earlierRequest).reason)
        assertEquals("receiver_screen_borrowed", requestUi(guard).reason)
        guard.apply(command(row(transfer = 4L, id = "second-camera")))
        // 借屏结束仍回到原生永久归属，不能被 screenReturned 误当成手机已获得画面。
        assertFalse(guard.isScreenAvailableToController())
        assertEquals(2L, guard.resources()[0]["permanentEntity"])
        assertTrue(requestUi(guard).allowed)
    }

    @Test
    fun delayedUnborrowForConvertedBorrowCannotReturnNativeScreen() {
        val guard = nativeAfterBorrow()
        assertTrue(guard.apply(command(row(transfer = 4L))).contains("unmatched=1"))
        assertFalse(guard.isScreenAvailableToController())
        assertEquals(2L, guard.resources()[0]["permanentEntity"])
        assertTrue(requestUi(guard).allowed)
    }

    @Test
    fun multipleKnownBorrowsAreNotCollapsedByObservedTake() {
        val guard = guard()
        guard.apply(command(row(id = "a"), row(id = "b")))
        guard.apply(command(observedTake()))
        assertTrue(guard.summary().contains("screenActive=2"))
        assertEquals("receiver_screen_borrowed", requestUi(guard).reason)
        guard.apply(command(row(transfer = 4L, id = "a")))
        assertFalse(requestUi(guard).allowed)
    }

    @Test
    fun unknownOrOverflowBorrowCannotEnterNativeReturnState() {
        val guards = listOf(
            guard().also { it.apply(command(row(), row(id = null))) },
            guard().also { item -> repeat(9) { item.apply(command(row(id = "id-$it"))) } },
        )
        for (guard in guards) {
            guard.apply(command(observedTake()))
            assertTrue(guard.summary().contains("screenUnknown=true"))
            assertEquals(1L, guard.resources()[0]["permanentEntity"])
            assertEquals("receiver_screen_borrowed", requestUi(guard).reason)
        }
    }

    @Test
    fun wrongMissingOrNonIntegerConstraintsDoNotEndBorrow() {
        val cases = mutableListOf<Map<String, Any?>>()
        for ((key, good) in listOf("transferPriority" to 500L, "takeConstraint" to 500L, "borrowConstraint" to 100L)) {
            cases += observedTake().toMutableMap().apply { remove(key) }
            for (bad in listOf(null, true, good.toDouble(), good.toString(), 0L, 1000L)) {
                cases += observedTake().toMutableMap().apply { this[key] = bad }
            }
        }
        cases += observedTake().toMutableMap().apply { this["borrowID"] = "unexpected" }
        cases += observedTake().toMutableMap().apply { this["unborrowConstraint"] = 1000L }
        for (take in cases) {
            val guard = guard()
            guard.apply(command(row()))
            guard.apply(command(take))
            assertTrue(guard.isScreenBorrowed())
            assertEquals(1L, guard.resources()[0]["permanentEntity"])
            assertFalse(requestUi(guard).allowed)
        }
    }

    @Test
    fun unsupportedNewTakeRevokesPreviouslyGrantedNativeReturnPermission() {
        val cases = listOf(
            observedTake().toMutableMap().apply { this["takeConstraint"] = 1000L },
            observedTake().toMutableMap().apply { remove("takeConstraint") },
            observedTake().toMutableMap().apply { this["borrowConstraint"] = 500L },
            observedTake().toMutableMap().apply { this["transferPriority"] = 100L },
        )
        for (take in cases) {
            val guard = nativeAfterBorrow()
            guard.apply(command(take))
            assertEquals("native_screen_restore_not_supported", requestUi(guard).reason)
            assertEquals(2L, owner(guard, 1))
            // 失去单一已知借用前提后，再来的普通 Take 不重新扩大恢复授权。
            guard.apply(command(observedTake()))
            assertFalse(requestUi(guard).allowed)
        }
    }

    @Test
    fun untakeRevokesNativeReturnPermissionWithoutPretendingToImplementUntake() {
        val guard = nativeAfterBorrow()
        guard.apply(command(row(transfer = 2L, id = null)))
        assertEquals("native_screen_restore_not_supported", requestUi(guard).reason)
        assertEquals(2L, owner(guard, 1))
    }

    @Test
    fun unknownOrMalformedScreenTransferRevokesNativeReturnPermission() {
        val cases = mutableListOf<Map<String, Any?>>()
        cases += observedTake().toMutableMap().apply { remove("transferType") }
        for (bad in listOf(null, true, 99L, 1.0, "1")) {
            cases += observedTake().toMutableMap().apply { this["transferType"] = bad }
        }
        for (change in cases) {
            val guard = nativeAfterBorrow()
            guard.apply(command(change))
            assertEquals("native_screen_restore_not_supported", requestUi(guard).reason)
            assertEquals(2L, owner(guard, 1))
            assertEquals(2L, guard.resources()[0]["permanentEntity"])
        }
        // 不明音频或其它资源不影响已限定到主屏的回切依据。
        for (resource in listOf(2L, 99L)) {
            val guard = nativeAfterBorrow()
            guard.apply(command(row(resource = resource, transfer = 99L)))
            assertTrue(requestUi(guard).allowed)
        }
    }

    @Test
    fun unsupportedTakeDuringNewBorrowRemainsRevokedAfterUnborrow() {
        val guard = nativeAfterBorrow()
        guard.apply(command(row(id = "new")))
        guard.apply(command(observedTake().toMutableMap().apply { this["takeConstraint"] = 1000L }))
        guard.apply(command(row(transfer = 4L, id = "new")))
        assertEquals("native_screen_restore_not_supported", requestUi(guard).reason)
        assertEquals(2L, owner(guard, 1))
    }

    @Test
    fun mixedTakeAndUnborrowConservativelyHoldRegardlessOfRowOrder() {
        for (takeFirst in listOf(true, false)) {
            val guard = guard()
            guard.apply(command(row()))
            val take = observedTake()
            val unborrow = row(transfer = 4L)
            guard.apply(if (takeFirst) command(take, unborrow) else command(unborrow, take))
            assertFalse(guard.isScreenAvailableToController())
            assertTrue(guard.summary().contains("screenUnknown=true"))
            assertFalse(requestUi(guard).allowed)
        }
    }

    @Test
    fun mixedTakeAndNewBorrowCannotRearmReturnRegardlessOfRowOrder() {
        for (takeFirst in listOf(true, false)) {
            val guard = nativeAfterBorrow()
            val take = observedTake()
            val borrow = row(id = "new")
            guard.apply(if (takeFirst) command(take, borrow) else command(borrow, take))
            guard.apply(command(row(transfer = 4L, id = "new")))
            assertFalse(requestUi(guard).allowed)
            assertTrue(guard.summary().contains("screenUnknown=true"))
        }
    }

    @Test
    fun screenRecoveryNeverReleasesAudioBorrow() {
        val guard = guard()
        guard.apply(command(row(), row(resource = 2L, id = "audio")))
        guard.apply(command(observedTake(), observedTake().toMutableMap().apply { this["resourceID"] = 2L }))
        assertEquals(2L, owner(guard, 2))
        assertTrue(requestUi(guard).allowed)
        assertEquals(2L, owner(guard, 2))
        assertEquals(1L, guard.resources()[1]["permanentEntity"])
    }

    @Test
    fun unknownReceiverAndPlainTakeRemainLegacy() {
        val other = guard("BMW Group different")
        other.apply(command(row()))
        other.apply(command(observedTake()))
        assertEquals("current_modes", requestUi(other).reason)
        val known = guard()
        known.apply(command(observedTake()))
        assertTrue(known.isScreenAvailableToController())
        assertEquals("current_modes", requestUi(known).reason)
    }

    @Test
    fun nativePendingStateDoesNotCrossClientBoundary() {
        val old = nativeAfterBorrow()
        val fresh = guard()
        assertFalse(old.isScreenAvailableToController())
        assertTrue(fresh.isScreenAvailableToController())
        assertEquals("current_modes", requestUi(fresh).reason)
    }

    @Test
    fun previousUiTokenIsConsumedBySuccessfulNativeReturn() {
        val guard = nativeAfterBorrow()
        val token = guard.captureUiRestoreRevision()
        assertTrue(guard.restoreRequestedUi(token).allowed)
        assertEquals("mode_state_changed", guard.restoreRequestedUi(token).reason)
        guard.apply(command(row(id = "next")))
        assertEquals("mode_state_changed", guard.restoreRequestedUi(token).reason)
        assertFalse(requestUi(guard).allowed)
    }

    @Test
    fun overflowWhileNativePendingNeverAllowsUiRestore() {
        val guard = nativeAfterBorrow()
        guard.apply(command(*Array(33) { row(resource = 99L) }))
        assertEquals("receiver_screen_borrowed", requestUi(guard).reason)
        assertFalse(guard.isScreenAvailableToController())
    }

    @Test
    fun plainTakeAfterSuccessfulRecoveryDoesNotReenterNativePending() {
        val guard = nativeAfterBorrow()
        assertTrue(requestUi(guard).allowed)
        guard.apply(command(observedTake()))
        assertTrue(guard.isScreenAvailableToController())
        assertEquals("current_modes", requestUi(guard).reason)
    }
}

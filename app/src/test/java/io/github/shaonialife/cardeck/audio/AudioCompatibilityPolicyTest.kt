package io.github.shaonialife.cardeck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCompatibilityPolicyTest {
    private val bmw = "BMW Group MGU-50-P"

    @Test
    fun bmwIgnoresDisabledRejectedAndWorkingHistory() {
        listOf(
            AudioProfileMemory.Entry(disabled = true),
            AudioProfileMemory.Entry(rejected = setOf("previously-rejected")),
            AudioProfileMemory.Entry(working = "previously-working"),
            AudioProfileMemory.Entry(
                working = "previously-working",
                rejected = setOf("previously-rejected"),
                disabled = true,
            ),
        ).forEach { remembered ->
            assertEquals(AudioProfileMemory.Entry(), AudioCompatibilityPolicy.entryForSession(bmw, remembered))
        }
    }

    @Test
    fun bmwSessionUsesAdvertisedCandidateOrderWithoutChangingRememberedEntry() {
        val candidates =
            listOf(
                AudioProfile("media", 1L shl 15, 48_000, 250),
                AudioProfile("media", 1L shl 11, 44_100, 250),
            )
        val remembered =
            AudioProfileMemory.Entry(
                working = candidates[1].key,
                rejected = setOf(candidates[0].key),
                disabled = true,
            )
        val before = remembered.copy()

        val selected = AudioCompatibilityPolicy.entryForSession(bmw, remembered)
        assertEquals(candidates, AudioProfileMemory.order(candidates, selected))
        assertEquals("忽略仅作用于本轮，不删除历史值", before, remembered)
    }

    @Test
    fun otherReceiversKeepAllHistoryAndExistingDurationPolicy() {
        val remembered =
            AudioProfileMemory.Entry(
                working = "working",
                rejected = setOf("rejected"),
                disabled = true,
            )
        listOf("Honda HFT", "Audi MIB3", "unknown", "", "BMW Group MGU-118").forEach { identity ->
            assertSame(remembered, AudioCompatibilityPolicy.entryForSession(identity, remembered))
            assertTrue(AudioCompatibilityPolicy.allowDurationAttribution(identity))
        }
    }

    @Test
    fun exactBmwGateSharesExistingCaseAndWhitespaceNormalization() {
        val remembered = AudioProfileMemory.Entry(disabled = true)
        listOf(bmw, "  bmw  GROUP\tMGU-50-P  ").forEach { identity ->
            assertEquals(AudioProfileMemory.Entry(), AudioCompatibilityPolicy.entryForSession(identity, remembered))
            assertFalse(AudioCompatibilityPolicy.allowDurationAttribution(identity))
        }
        listOf("BMW Group MGU-50-P-extra", "Other MGU-50-P", "BMW Group MGU-50").forEach { identity ->
            assertSame(remembered, AudioCompatibilityPolicy.entryForSession(identity, remembered))
            assertTrue(AudioCompatibilityPolicy.allowDurationAttribution(identity))
        }
    }

    @Test
    fun bmwDurationAttributionIsDisabledRegardlessOfHistoricalOutcome() {
        // 策略故意没有存活时长入口：19 秒断开与超过 20 秒存活均不能覆盖此门控。
        listOf(
            AudioProfileMemory.Entry(),
            AudioProfileMemory.Entry(working = "long-lived"),
            AudioProfileMemory.Entry(rejected = setOf("short-lived"), disabled = true),
        ).forEach { remembered ->
            AudioCompatibilityPolicy.entryForSession(bmw, remembered)
            assertFalse(AudioCompatibilityPolicy.allowDurationAttribution(bmw))
        }
    }
}

package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTargetPolicyTest {
    private fun candidate(pkg: String, playing: Boolean = false, metadata: Boolean = true) =
        MediaTargetPolicy.Candidate(pkg, playing, metadata)

    @Test fun targetDoesNotFollowOtherPlayingApp() {
        assertEquals(1, MediaTargetPolicy.pickIndex(listOf(candidate("other", true), candidate("target")), "target"))
    }

    @Test fun missingTargetDoesNotFallback() {
        assertNull(MediaTargetPolicy.pickIndex(listOf(candidate("other", true)), "target"))
    }

    @Test fun playingTargetWinsWithinSamePackage() {
        assertEquals(1, MediaTargetPolicy.pickIndex(listOf(candidate("target"), candidate("target", true)), "target"))
    }

    @Test fun generalCardPrefersPlayingSession() {
        assertEquals(1, MediaTargetPolicy.pickIndex(listOf(candidate("one"), candidate("two", true))))
    }

    @Test fun metadataWinsOverEmptySession() {
        assertEquals(1, MediaTargetPolicy.pickIndex(listOf(candidate("one", metadata = false), candidate("two"))))
    }

    @Test fun emptyListHasNoTarget() {
        assertNull(MediaTargetPolicy.pickIndex(emptyList(), "target"))
    }
}

package io.github.shaonialife.cardeck.launcher

import org.junit.Assert.*
import org.junit.Test

class CarShortcutTest {
    @Test fun duplicateEdgesNeverStartParallelLaunchesAndACompletedPressHasABriefDebounce() {
        val gate = CarShortcutGate()
        val edge = gate.claim(CarShortcut.MEDIA, 4, 0)!!
        assertNull(gate.claim(CarShortcut.MEDIA, 4, 820))
        gate.finished(edge)
        val second = gate.claim(CarShortcut.MEDIA, 4, 1000)!!
        gate.finished(second)
        assertNull(gate.claim(CarShortcut.MEDIA, 4, 1020))
        assertNotNull(gate.claim(CarShortcut.MEDIA, 4, 1700))
    }

    @Test fun staleCompletionDoesNotReleaseAnotherRequestAndDetachInvalidatesIt() {
        val gate = CarShortcutGate()
        val old = gate.claim(CarShortcut.MEDIA, 4, 0)!!
        val map = gate.claim(CarShortcut.MAP, 4, 10)!!
        gate.finished(old)
        assertFalse(gate.owns(old))
        assertTrue(gate.owns(map))
        assertNull(gate.claim(CarShortcut.MAP, 4, 900))
        val newer = gate.claim(CarShortcut.MAP, 5, 909)!!
        assertFalse(gate.owns(map))
        gate.reset()
        assertFalse(gate.owns(newer))
    }

    @Test fun aVideoCannotDisplaceThePlayingOrPausedMusicApp() {
        val video = "com.youku.phone"
        val spotify = "com.spotify.music"
        val netease = "com.netease.cloudmusic"
        val available = setOf(netease, spotify, video)
        assertEquals(spotify, ShortcutMusic.choose(video, setOf(video, spotify), available, netease, available::contains))
        assertEquals(spotify, ShortcutMusic.choose(spotify, emptySet(), available, netease, available::contains))
        assertEquals(spotify, ShortcutMusic.choose(video, setOf(video), setOf(video), spotify, available::contains))
        assertEquals(netease, ShortcutMusic.choose(video, setOf(video), setOf(video), null, available::contains))
        assertNull(ShortcutMusic.choose(video, setOf(video), setOf(video), null, setOf(video)::contains))
    }
}

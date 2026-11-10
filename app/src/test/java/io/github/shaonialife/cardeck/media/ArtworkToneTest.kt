package io.github.shaonialife.cardeck.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkToneTest {
    @Test fun emptyAndTransparentInputUseDefault() {
        assertEquals(ArtworkTone.DEFAULT, ArtworkTone.fromPixels(intArrayOf()))
        assertEquals(ArtworkTone.DEFAULT, ArtworkTone.fromPixels(intArrayOf(0x00FFFFFF)))
    }

    @Test fun whiteCoverStillProducesDarkOpaqueBackdrop() {
        val color = ArtworkTone.fromPixels(intArrayOf(0xFFFFFFFF.toInt()))
        assertEquals(255, color ushr 24)
        assertTrue((color ushr 16 and 255) <= 95)
        assertTrue((color ushr 8 and 255) <= 95)
        assertTrue((color and 255) <= 95)
    }

    @Test fun coverHueIsRetained() {
        val color = ArtworkTone.fromPixels(intArrayOf(0xFFFF0000.toInt()))
        assertTrue((color ushr 16 and 255) > (color ushr 8 and 255))
        assertTrue((color ushr 16 and 255) > (color and 255))
    }

    @Test fun transparentPixelsDoNotSkewAverage() {
        val red = 0xFFFF0000.toInt()
        assertEquals(ArtworkTone.fromPixels(intArrayOf(red)), ArtworkTone.fromPixels(intArrayOf(red, 0x0000FF00)))
    }
}

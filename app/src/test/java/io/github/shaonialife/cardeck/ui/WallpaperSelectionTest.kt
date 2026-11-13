package io.github.shaonialife.cardeck.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WallpaperSelectionTest {
    @Test fun bothModesKeepTheCustomWallpaper() {
        for (day in listOf(false, true)) {
            val loaded = WallpaperSelection(2, day).load({ "my photo" }, { error("custom photo was available") })
            assertEquals("my photo", loaded)
        }
    }

    @Test fun resetOrUnreadablePhotoLoadsTheMatchingBundledImage() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        val day = WallpaperSelection(4, true).load({ null }) { File(assets, it) }!!
        val night = WallpaperSelection(4, false).load({ null }) { File(assets, it) }!!
        assertEquals("wallpaper_day.jpg", day.name)
        assertEquals("wallpaper_default.jpg", night.name)
        for (file in listOf(day, night)) {
            assertTrue(file.length() > 2)
            file.inputStream().use { image ->
                assertEquals(0xff, image.read())
                assertEquals(0xd8, image.read())
            }
        }
        assertNotEquals(day.readBytes().contentHashCode(), night.readBytes().contentHashCode())
    }

    @Test fun lateLoadsCannotMatchAfterAPaletteSwitchOrANewPhoto() {
        val old = WallpaperSelection(1, true)
        assertNotEquals(old, old.copy(day = false))
        assertNotEquals(old, old.copy(revision = 2))
        assertEquals(old, WallpaperSelection(1, true))
    }
}

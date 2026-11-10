package io.github.shaonialife.cardeck.launcher.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class MapUiScaleTest {
    @Test fun newDefaultEnlargesMapByOneQuarter() {
        assertEquals(250, MapUiScale.densityDpi(MapUiScale.DEFAULT))
    }

    @Test fun originalSizeRemainsAvailable() {
        assertEquals(200, MapUiScale.densityDpi(100))
    }

    @Test fun largestOptionIsBounded() {
        assertEquals(300, MapUiScale.densityDpi(150))
    }

    @Test fun invalidStoredValuesUseSafeDefault() {
        listOf(Int.MIN_VALUE, -1, 0, 124, 151, Int.MAX_VALUE).forEach {
            assertEquals(125, MapUiScale.normalise(it))
            assertEquals(250, MapUiScale.densityDpi(it))
        }
    }

    @Test fun bmwDashboardExposesObservedWideLayoutAtPreferredOrLargestUsableScale() {
        assertEquals(200, MapUiScale.baiduTileDensity(250, 1232, 688))
        assertEquals(200, MapUiScale.baiduTileDensity(200, 1182, 688))
        assertEquals(240, MapUiScale.baiduTileDensity(300, 1418, 825))
        assertEquals(250, MapUiScale.baiduTileDensity(250, 1920, 1080))
    }

    @Test fun smallLandscapeTilesKeepTheExistingMinimumAndPortraitTilesKeepThePreference() {
        assertEquals(200, MapUiScale.baiduTileDensity(300, 700, 650))
        assertEquals(300, MapUiScale.baiduTileDensity(300, 650, 700))
    }
}

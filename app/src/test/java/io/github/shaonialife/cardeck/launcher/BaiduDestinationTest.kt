package io.github.shaonialife.cardeck.launcher

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class BaiduDestinationTest {
    @Test fun navOpensTheBaiduPoiSearchPageWithoutStartingGuidanceOrSupplyingAnyDestination() {
        val uri = URI(BaiduMapPage.DESTINATION.uri)
        assertEquals("baidumap", uri.scheme)
        assertEquals("map", uri.host)
        assertEquals("/gopage", uri.path)
        assertEquals(setOf("page=poisearchpage", "mode=NORMAL_MODE", "src=andr.shaonialife.cardeck"), uri.query.split("&").toSet())
        assertNotEquals(uri.path, URI(BaiduMapPage.CRUISE.uri).path)
        assertEquals("baidumap://map?src=andr.shaonialife.cardeck", BaiduMapPage.HOME.uri)
    }

    @Test fun baiduOnPhoneOrABackgroundTaskCannotConfirmPlacementOnTheCarScreen() {
        val dump = """
            Display #0 (activities from top to bottom):
              topResumedActivity=ActivityRecord{abc u0 com.baidu.BaiduMap/.Main t7}
            Display #42 (activities from top to bottom):
              mResumedActivity: ActivityRecord{def u0 com.other.app/.Top t9}
              * Hist #0: ActivityRecord{fff u0 com.baidu.BaiduMap/.Old t8}
        """.trimIndent()
        assertEquals(BaiduMapPlacement(), baiduOnMapDisplay(0, dump))
        assertEquals(BaiduMapPlacement(taskId = 8), baiduOnMapDisplay(42, dump))
        assertEquals(BaiduMapPlacement(), baiduOnMapDisplay(41, dump))
        val car = BaiduMapActivity("com.baidu.BaiduMap/.Maps", 9)
        assertEquals(car, baiduOnMapDisplay(42, dump.replace("com.other.app/.Top", "com.baidu.BaiduMap/.Maps")).activity)
        assertEquals(car, baiduOnMapDisplay(42, dump.replace("u0 com.other.app/.Top", "324:com.baidu.BaiduMap/.Maps/u0")).activity)
        assertNull(baiduOnMapDisplay(42, dump.replace("com.other.app/.Top", "com.baidu.BaiduMap.fake/.Maps")).activity)
    }

    @Test fun android16DecimalIdentityAndDisplayAreaResumedLinePlaceExistingCarTask() {
        val dump = """
            Display #74 (activities from top to bottom):
              topResumedActivity=ActivityRecord{1712345678 u0 com.baidu.BaiduMap/com.baidu.baidumaps.MapsActivity t127}
            Display #0 (activities from top to bottom):
              Resumed: ActivityRecord{234567890 u0 com.baidu.BaiduMap/.OnPhone t703}
        """.trimIndent()
        val expected = BaiduMapPlacement(BaiduMapActivity("com.baidu.BaiduMap/com.baidu.baidumaps.MapsActivity", 127), 127)
        assertEquals(expected, baiduOnMapDisplay(74, dump))
        assertEquals(expected, baiduOnMapDisplay(74, dump.replace("topResumedActivity=", "Resumed: ")))
        assertEquals(expected, baiduOnMapDisplay(74, dump.replace("Display #74 (activities from top to bottom):", "Display: mDisplayId=74")))
    }

    @Test fun onlyTheActiveSearchFragmentConfirmsTheSearchPage() {
        val search = """
            TASK 127
            PoiSearchPage{
            mState=7
            mAdded=true mRemoving=false
            mHidden=false mDetached=false
        """.trimIndent()
        assertTrue(hasActiveBaiduSearchPage(search, 127))
        assertFalse(hasActiveBaiduSearchPage(search, 703))
        assertFalse(hasActiveBaiduSearchPage("TASK 703\n" + search, 703))
        assertFalse(hasActiveBaiduSearchPage(search.replace("mState=7", "mState=1"), 127))
        assertFalse(hasActiveBaiduSearchPage(search.replace("mRemoving=false", "mRemoving=true"), 127))
        assertFalse(hasActiveBaiduSearchPage(search.replace("mHidden=false", "mHidden=true"), 127))
        assertFalse(hasActiveBaiduSearchPage(search.replace("mAdded=true", "MapHome{\nmAdded=true"), 127))
        assertFalse(hasActiveBaiduSearchPage(search.replace("PoiSearchPage{", "MapHome{"), 127))
    }
}

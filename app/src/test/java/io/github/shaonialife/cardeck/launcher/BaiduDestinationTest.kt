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
        assertNull(baiduActivityOnMapDisplay(0, dump))
        assertNull(baiduActivityOnMapDisplay(42, dump))
        assertNull(baiduActivityOnMapDisplay(41, dump))
        assertEquals("def", baiduActivityOnMapDisplay(42, dump.replace("com.other.app/.Top", "com.baidu.BaiduMap/.Maps")))
        assertEquals("def", baiduActivityOnMapDisplay(42, dump.replace("u0 com.other.app/.Top", "324:com.baidu.BaiduMap/.Maps/u0")))
        assertNull(baiduActivityOnMapDisplay(42, dump.replace("com.other.app/.Top", "com.baidu.BaiduMap.fake/.Maps")))
    }

    @Test fun onlyTheActiveSearchFragmentConfirmsTheSearchPage() {
        val search = """
            PoiSearchPage{
            mState=7
            mAdded=true mRemoving=false
            mHidden=false mDetached=false
        """.trimIndent()
        assertTrue(hasActiveBaiduSearchPage(search))
        assertFalse(hasActiveBaiduSearchPage(search.replace("mState=7", "mState=1")))
        assertFalse(hasActiveBaiduSearchPage(search.replace("mRemoving=false", "mRemoving=true")))
        assertFalse(hasActiveBaiduSearchPage(search.replace("mHidden=false", "mHidden=true")))
        assertFalse(hasActiveBaiduSearchPage(search.replace("mAdded=true", "MapHome{\nmAdded=true")))
        assertFalse(hasActiveBaiduSearchPage(search.replace("PoiSearchPage{", "MapHome{")))
    }
}

package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MobileDataPreferencePlanTest {
    private val groups = mapOf(
        10001 to setOf("car"),
        10002 to setOf("map"),
        10003 to setOf("music", "music.player"),
        10004 to setOf("browser"),
        1010005 to setOf("work"),
        1050 to setOf("system"),
        10006 to setOf("vpn"),
    )
    private val eligible = setOf("map", "music", "music.player", "browser")

    @Test
    fun parserDistinguishesAnAbsentSettingFromUnknownOrTruncatedData() {
        assertEquals(emptySet<Int>(), MobileDataPreferencePlan.parseSetting("null"))
        assertEquals(setOf(3, 1010055, 10026), MobileDataPreferencePlan.parseSetting("3;1010055;10026"))
        for (bad in listOf("1;bad", "1;2;", "-7", "10026,10027", "10026; 10027", "2147483648")) {
            assertNull(MobileDataPreferencePlan.parseSetting(bad))
        }
    }

    @Test
    fun serviceVerificationRequiresAnIndependentRecognizedSection() {
        assertNull(MobileDataPreferencePlan.observedUids(""))
        assertNull(MobileDataPreferencePlan.observedUids("Mobile data preferred UIDs:\n{10002}"))
        assertNull(MobileDataPreferencePlan.observedUids("Mobile data preferred UIDs:\n{10002xx}\nDefault requests:"))
        assertEquals(emptySet<Int>(), MobileDataPreferencePlan.observedUids("Default requests:"))
        assertEquals(setOf(10002, 10003), MobileDataPreferencePlan.observedUids(
            "  Mobile data preferred UIDs:\n    {10002, 10003}\n  Default requests:\n",
        ))
    }

    @Test
    fun installInventoryChecksEveryLineAndEnumeratesAllSharedUidSiblings() {
        assertEquals(mapOf(10002 to setOf("a.app", "a.app_b")), MobileDataPreferencePlan.installed(
            "package:a.app uid:10002\npackage:a.app_b uid:10002\npackage:a.work uid:1010002", 0,
        ))
        assertNull(MobileDataPreferencePlan.installed("package:a.app uid:10002\nmalformed", 0))
    }

    @Test
    fun changingSelectionRemovesOnlyOwnedEntriesAndLeavesOriginalPreferencesAndOtherUsersAlone() {
        val before = setOf(1050, 10002, 10004, 1010005)
        val result = MobileDataPreferencePlan.plan(before, mapOf(10004 to setOf("browser")), groups, eligible,
            setOf("map", "music", "music.player", "work", "car", "system", "vpn"), 10001)
        assertEquals(setOf(1050, 10002, 10003, 1010005), result.after)
        assertEquals(mapOf(10003 to setOf("music", "music.player")), result.owners)
        assertEquals(setOf("work", "car", "system", "vpn"), result.skipped)
    }

    @Test
    fun partiallySelectedSharedUidIsSkippedAndExistingManualPreferenceIsRetained() {
        val result = MobileDataPreferencePlan.plan(setOf(10003), emptyMap(), groups, eligible, setOf("music"), 10001)
        assertEquals(setOf("music"), result.skipped)
        assertEquals(emptyMap<Int, Set<String>>(), result.target)
        assertEquals(setOf(10003), result.after)
        assertEquals(emptyMap<Int, Set<String>>(), result.owners)
    }

    @Test
    fun restoreCleansOrphansButDoesNotRemoveReusedOrNewlySharedUidPreferences() {
        val before = setOf(10002, 10003, 10004, 10008)
        val owned = mapOf(10002 to setOf("map.old"), 10003 to setOf("music"), 10008 to setOf("gone"))
        val result = MobileDataPreferencePlan.plan(before, owned, groups, eligible, emptySet(), 10001)
        assertEquals(setOf(10002, 10003, 10004), result.after)
        assertEquals(setOf(10002, 10003), result.reassigned)
        assertEquals(emptyMap<Int, Set<String>>(), result.owners)
    }
}

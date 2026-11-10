package io.github.shaonialife.cardeck.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DirectAppCatalogTest {
    private val clash = "com.github.metacubex.clash.meta"
    private val owners = mapOf(clash to 10100)
    private val map = DirectAppCatalog.App("com.baidu.BaiduMap", "百度地图", 10200)
    private val music = DirectAppCatalog.App("com.netease.cloudmusic", "网易云音乐", 10201)
    private val video = DirectAppCatalog.App("com.youku.phone", "优酷", 10202)
    private val apps = listOf(map, music, video)

    private fun dump(uids: String, owner: String = clash, user: Int = 0, type: Int = 1, ownerUid: Int = 10100) =
        "VPNs:\n  $user: $owner\n    Active package name: $owner\n    Active vpn type: $type\n" +
            "    NetworkCapabilities: [ Transports: CELLULAR|VPN Capabilities: INTERNET $uids OwnerUid: $ownerUid AdminUids: [$ownerUid] ]"
    private fun read(s: String) = DirectAppCatalog.parseLive(0, owners, apps, s)

    @Test fun disallowedExportPicksListedAppsAllowedExportPicksTheirComplement() {
        val selection = "\uFEFF${map.pkg}\r\n${video.pkg}\ncom.old.uninstalled\n"
        assertEquals(setOf(map.pkg, video.pkg), DirectAppCatalog.fromExport(apps, selection, false))
        assertEquals(setOf(music.pkg), DirectAppCatalog.fromExport(apps, selection, true))
    }

    @Test fun emptyUnmatchedAndSubscriptionLikeInputCannotSelectEveryApp() {
        listOf("", "   ", "com.old.uninstalled", "https://example.com/sub", "${map.pkg}\n#ignored?").forEach {
            assertNull(DirectAppCatalog.fromExport(apps, it, true))
        }
    }

    @Test fun allowedExportContainingAllCandidatesProducesAValidEmptyDirectSet() {
        assertEquals(emptySet<String>(), DirectAppCatalog.fromExport(apps, apps.joinToString("\n") { it.pkg }, true))
    }

    @Test fun recognizesAospSingleUidAndHolesInEffectiveVpnRanges() {
        assertEquals(setOf(map.pkg, video.pkg), read(dump("Uid: 10201")))
        assertEquals(setOf(music.pkg), read(dump("Uids: <{0-10200, 10202, 10214-99999}>")))
        assertEquals(emptySet<String>(), read(dump("Uids: <{0-99999}>")))
    }

    @Test fun physicalRouteForBixbyRequiresExplicitInactiveVpnOrPositiveUidExclusion() {
        val inactive = "VPNs:\n  0: [Legacy VPN]\n    Active package name: [Legacy VPN]\n    Active vpn type: -1"
        val all = apps.map { it.pkg }.toSet()
        assertEquals(all, DirectAppCatalog.parseLive(0, emptyMap(), apps, inactive, true))
        assertNull(read(inactive))
        assertNull(DirectAppCatalog.parseLive(0, owners, apps, inactive.substringBefore("Active vpn type:"), true))
        assertNull(DirectAppCatalog.parseLive(0, owners, apps, inactive.replace("  0:", "  10:"), true))
        assertNull(DirectAppCatalog.parseLive(0, owners, apps, dump("Uid: 10201", owner = "com.other.vpn"), true))
        assertEquals(setOf(map.pkg, video.pkg), DirectAppCatalog.parseLive(0, owners, apps, dump("Uid: 10201"), true))
    }

    @Test fun spotifyInsideClashRangesStaysOnItsVpnAndOnlyStrictRangeListsAreAccepted() {
        val spotify = DirectAppCatalog.App("com.spotify.music", "Spotify", 10420)
        fun split(field: String) = DirectAppCatalog.parseLive(0, owners, listOf(spotify, map), dump(field))
        assertEquals(setOf(map.pkg), split("Uids: <{0-10199, 10201-99999}>"))
        for (broken in listOf("Uid: 10420, 10200", "Uids: 0-10420", "Uids: <{10420}", "Uids: <{10420}}>","Uids: <{}>")) {
            assertNull(split(broken))
        }
    }

    @Test fun malformedRangesMissingOrExtraneousUidDataNeverGiveAnInferredList() {
        listOf("", "AdminUids: [10200]", "Uid: 10200 10201", "Uids: <{}>", "Uids: <{0-200,}>",
            "Uids: <{12-10}>", "Uids: <{0-broken}>", "Uids: <{10200}>...", "Uids: <{9999999999999}>",
            "Uids: <{100000-199999}>", "Uid: 10001 Uids: <{10002}>").forEach {
            assertNull(it, read(dump(it)))
        }
    }

    @Test fun onlyTheCurrentUserActiveClashServiceAndItsOwnerUidAreAccepted() {
        assertNull(read(dump("Uid: 10200", user = 10)))
        assertNull(read(dump("Uid: 10200", type = 2)))
        assertNull(read(dump("Uid: 10200", owner = "com.example.vpn")))
        assertNull(read(dump("Uid: 10200", ownerUid = 10200)))
        assertNull(read(dump("Uid: 10200").replace("CELLULAR|VPN", "CELLULAR")))
        assertNull(read(dump("Uid: 10200").replace("Active package name: $clash", "Active package name: com.other.vpn")))
        assertNull(read(dump("Uid: 10200") + "\n" + dump("Uid: 10201")))
        val second = dump("Uids: <{1000000-1099999}>", user = 10, ownerUid = 1010100).substringAfter('\n')
        assertEquals(setOf(map.pkg, video.pkg), read(dump("Uid: 10201") + "\n" + second))
    }

    @Test fun excludesServiceCoreAndCrossProfileIdentities() {
        val self = "io.github.shaonialife.cardeck.bmwprobe"
        for (pkg in listOf(self, "io.github.shaonialife.cardeck", clash, "com.android.settings", "com.google.android.gms")) {
            assertFalse(DirectAppCatalog.eligibleOwner(pkg, 10200, 10100, self))
        }
        assertFalse(DirectAppCatalog.eligibleOwner(map.pkg, 1000, 10100, self))
        assertFalse(DirectAppCatalog.eligibleOwner(map.pkg, 10100, 10100, self))
        assertFalse(DirectAppCatalog.eligibleOwner(map.pkg, 110200, 10100, self))
    }

    @Test fun independentPublisherPreloadsQualifyWithoutListingDomesticBrands() {
        assertEquals(true, DirectAppCatalog.eligiblePreload("com.tencent.mm", launchable = true) { true })
        assertEquals(true, DirectAppCatalog.eligiblePreload("com.eg.android.AlipayGphone", launchable = true) { true })
        assertFalse(DirectAppCatalog.eligiblePreload("com.example.service", launchable = false) { true })
        assertFalse(DirectAppCatalog.eligiblePreload("com.example.settings", launchable = true) { false })
        assertFalse(DirectAppCatalog.eligiblePreload("com.samsung.android.settings", launchable = true) { true })
        assertFalse(DirectAppCatalog.eligiblePreload("com.example.settings", launchable = true) { error("unknown signer") })
    }
}

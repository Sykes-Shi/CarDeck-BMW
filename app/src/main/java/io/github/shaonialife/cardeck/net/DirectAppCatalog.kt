package io.github.shaonialife.cardeck.net

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Process
import io.github.shaonialife.cardeck.shizuku.Shell
import java.io.File

/** 本地读取当前用户的应用和 Clash 分流。这里不写系统设置，也不保留原始 VPN dump。 */
object DirectAppCatalog {
    data class App(val pkg: String, val label: String, val uid: Int)

    private const val USER_RANGE = 100_000
    private val knownClash = setOf("com.github.metacubex.clash.meta", "com.github.kr328.clash")
    private val platformFamilies = listOf(
        "android.", "com.android.", "com.samsung.", "com.sec.", "com.google.android.",
        "com.qualcomm.", "com.qti.", "org.codeaurora.", "com.mediatek.", "vendor.",
    )
    private val packageName = Regex("""[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+""")
    private val thirdPartyRow = Regex("""package:(/[^\r\n]+\.apk)=([\w.]+)\s+uid:(\d+)""")

    @Suppress("DEPRECATION")
    fun installed(ctx: Context): List<App> {
        val self = Process.myUid()
        val user = self / USER_RANGE
        val all = Shell.exec("cmd package list packages -U --user $user")
        val owners = if (all.ok) MobileDataPreferencePlan.installed(all.output, user) else null
        if (owners.isNullOrEmpty()) return emptyList()
        val pm = ctx.packageManager
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS
        val visible = pm.getInstalledPackages(flags).associateBy { it.packageName }
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
        val thirdParty = Shell.exec("cmd package list packages -3 -e -f -U --user $user")
        if (!thirdParty.ok) return emptyList()
        val paths = mutableMapOf<String, Pair<Int, String>>()
        for (line in thirdParty.output.lineSequence().filter { it.isNotBlank() }) {
            val row = thirdPartyRow.matchEntire(line.trim()) ?: return emptyList()
            val uid = row.groupValues[3].toIntOrNull() ?: return emptyList()
            paths[row.groupValues[2]] = uid to row.groupValues[1]
        }
        return owners.mapNotNull { (uid, names) ->
            val pkg = names.singleOrNull() ?: return@mapNotNull null
            if (!eligibleOwner(pkg, uid, self, ctx.packageName)) return@mapNotNull null
            val apk = paths[pkg]?.takeIf { it.first == uid }?.second
            val installed = visible[pkg]
            val info = installed ?: archiveInfo(pm, apk ?: return@mapNotNull null, flags) ?: return@mapNotNull null
            val ai = info.applicationInfo ?: return@mapNotNull null
            if (pkg != info.packageName || !ai.enabled || installed != null && ai.uid != uid) return@mapNotNull null
            if (apk == null || ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) {
                if (installed == null || !eligiblePreload(pkg, pkg in launchers) {
                        pm.checkSignatures(pkg, "android") == PackageManager.SIGNATURE_NO_MATCH
                    }) return@mapNotNull null
            }
            if (Manifest.permission.INTERNET !in info.requestedPermissions.orEmpty() ||
                info.services.orEmpty().any { it.permission == Manifest.permission.BIND_VPN_SERVICE }) return@mapNotNull null
            val label = if (installed == null) pkg else runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg)
            App(pkg, label, uid)
        }.sortedWith(compareBy({ it.label }, { it.pkg }))
    }

    /** 对包可见性隐藏的第三方程序，只有能枚举整套安装 APK 并解析其声明才纳入。 */
    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? = runCatching {
        val base = File(path)
        val dir = base.parentFile ?: return@runCatching null
        val parts = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") } ?: return@runCatching null
        if (base !in parts) return@runCatching null
        pm.getPackageArchiveInfo(if (parts.size == 1) base.path else dir.path, flags)
    }.getOrNull()

    /** 非平台厂商预装的用户应用也可配置，例如自带的微信。系统页面、服务和系统签名组件跳过。 */
    internal fun eligiblePreload(pkg: String, launchable: Boolean, independentSigner: () -> Boolean): Boolean =
        launchable && platformFamilies.none { pkg.startsWith(it) } && runCatching(independentSigner).getOrDefault(false)

    internal fun eligibleOwner(pkg: String, uid: Int, self: Int, ownPkg: String): Boolean =
        uid != self && uid / USER_RANGE == self / USER_RANGE && uid % USER_RANGE in 10_000..19_999 &&
            packageName.matches(pkg) && pkg != ownPkg && pkg != "io.github.shaonialife.cardeck" &&
            !pkg.startsWith("io.github.shaonialife.cardeck.") && pkg !in knownClash &&
            pkg != "moe.shizuku.privileged.api" && pkg != "com.android.vending" &&
            !pkg.startsWith("com.android.") && !pkg.startsWith("com.google.android.gms") &&
            !pkg.startsWith("com.google.android.gsf")

    fun fromExport(apps: List<App>, text: String, selectedUseVpn: Boolean): Set<String>? {
        if (text.length > 1_048_576) return null
        val rows = text.removePrefix("\uFEFF").lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (rows.isEmpty() || rows.any { !packageName.matches(it) }) return null
        val candidates = apps.mapTo(mutableSetOf()) { it.pkg }
        val selected = candidates.intersect(rows.toSet())
        if (selected.isEmpty()) return null
        return if (selectedUseVpn) candidates - selected else selected
    }

    @Suppress("DEPRECATION")
    fun fromLiveClash(ctx: Context, apps: List<App>, acceptConfirmedInactive: Boolean = false): Set<String>? {
        val user = Process.myUid() / USER_RANGE
        val owners = knownClash.mapNotNull { pkg ->
            val uid = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() ?: return@mapNotNull null
            (pkg to uid).takeIf { uid / USER_RANGE == user }
        }.toMap()
        if (owners.isEmpty() && !acceptConfirmedInactive) return null
        // 忽略 VPN 日志/历史 session，只保留 AOSP dump 的状态头、用户分段和三个活动字段。
        val result = Shell.exec("dumpsys -t 4 vpn_management | grep -E '^([[:space:]]*VPNs:|  [0-9]+: |    (Active package name:|Active vpn type:|NetworkCapabilities:))'", 6_000L)
        return if (result.ok) parseLive(user, owners, apps, result.output, acceptConfirmedInactive) else null
    }

    internal fun parseLive(user: Int, owners: Map<String, Int>, apps: List<App>, dump: String,
        acceptConfirmedInactive: Boolean = false): Set<String>? {
        val lines = dump.lines()
        if (lines.firstOrNull()?.trim() != "VPNs:") return null
        val header = Regex("""  (\d+): (\S+|\[Legacy VPN])""")
        val sections = lines.mapIndexedNotNull { index, line -> header.matchEntire(line)?.let { index to it } }
        val (start, match) = sections.singleOrNull { it.second.groupValues[1].toIntOrNull() == user } ?: return null
        val owner = match.groupValues[2]
        val stop = sections.firstOrNull { it.first > start }?.first ?: lines.size
        val fields = lines.subList(start + 1, stop).map { it.trim() }
        fun field(key: String) = fields.filter { it.startsWith(key) }.singleOrNull()?.removePrefix(key)?.trim()
        if (field("Active package name:") != owner) return null
        val vpnType = field("Active vpn type:")
        // Android VpnManager.TYPE_VPN_NONE. A VPN excluded from CarDeck might still own other
        // UIDs, so callers needing a system-app exception require this explicit OS result.
        if (acceptConfirmedInactive && vpnType == "-1") {
            return apps.filter { it.uid / USER_RANGE == user }.mapTo(mutableSetOf()) { it.pkg }
        }
        val uid = owners[owner] ?: return null
        if (vpnType != "1" || owner !in knownClash || uid / USER_RANGE != user) return null
        val caps = field("NetworkCapabilities:") ?: return null
        val transports = Regex("""^\[\s*Transports:\s*([A-Z0-9_|]+)\s""").find(caps)?.groupValues?.get(1) ?: return null
        if (!caps.trimEnd().endsWith(']') || "VPN" !in transports.split('|')) return null
        // OwnerUid 与同一当前用户的真实 Clash 包再次交叉验证。异常/OEM 格式走导入。
        val ownerField = Regex("""\sOwnerUid:\s*(\d+)(?=\s|])""").findAll(caps).singleOrNull() ?: return null
        if (ownerField.groupValues[1].toIntOrNull() != uid) return null
        val keys = Regex("""\s(Uid|Uids):\s*""").findAll(caps).toList()
        val key = keys.singleOrNull() ?: return null
        if (key.range.last >= ownerField.range.first) return null
        val value = caps.substring(key.range.last + 1, ownerField.range.first).trim()
        val single = key.groupValues[1] == "Uid"
        // Android's ICU rejects an unescaped closing brace that host java.util.regex accepts.
        // These are literal Android dump delimiters. Validate the enclosing form independently
        // of the numeric UID/range grammar below.
        val raw = if (single) {
            if (value.isEmpty() || value.any { it !in '0'..'9' }) return null
            value
        } else {
            if (!value.startsWith("<{") || !value.endsWith("}>")) return null
            value.substring(2, value.length - 2)
        }
        val ranges = raw.split(',').map { part ->
            val pair = Regex("""(\d+)(?:-(\d+))?""").matchEntire(part.trim()) ?: return null
            val first = pair.groupValues[1].toIntOrNull() ?: return null
            val last = if (pair.groupValues[2].isEmpty()) first else pair.groupValues[2].toIntOrNull() ?: return null
            if (last < first) return null
            first..last
        }
        val startUid = user.toLong() * USER_RANGE
        if (ranges.none { it.last >= startUid && it.first < startUid + USER_RANGE }) return null
        return apps.filter { it.uid / USER_RANGE == user && ranges.none { range -> it.uid in range } }.mapTo(mutableSetOf()) { it.pkg }
    }
}

package io.github.shaonialife.cardeck.net

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.Process
import io.github.shaonialife.cardeck.shizuku.Shell

/** Serializes both permanent, user-reviewed preferences and separately journaled projection leases. */
object MobileDataRouting {
    private const val SETTING = "mobile_data_preferred_uids"
    private const val OWNED = "owned"
    private const val PENDING = "pending"
    private const val ADD = "add"
    private const val REMOVE = "remove"
    private const val EXCLUDED = "unchecked_projection_packages"

    private fun preferences(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("mobile_data_preference_v1", Context.MODE_PRIVATE)

    private fun leases(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("mobile_data_projection_v1", Context.MODE_PRIVATE)

    fun hasChanges(context: Context): Boolean = preferences(context).let {
        !it.getStringSet(OWNED, emptySet()).isNullOrEmpty() || it.contains(PENDING)
    }

    fun apply(context: Context, packages: Set<String>, unchecked: Set<String> = emptySet()): String =
        if (packages.isEmpty()) "请选择至少一个直连应用。" else update(context, packages.toSet(), unchecked)

    fun restore(context: Context): String = update(context, emptySet())

    @Synchronized
    private fun update(context: Context, selected: Set<String>, unchecked: Set<String> = emptySet()): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return "请稍后在后台重试。"
        return try {
            ProjectionAppMobileData.pauseForManualChange()
            releaseProjection(context)
            check(!hasLeases(context)) { "临时设置尚未撤回，请在 Shizuku 就绪后重试。" }
            change(context, selected, unchecked)
        } catch (e: IllegalStateException) {
            e.message ?: "操作未完成，请检查 Shizuku 后重试。"
        } catch (_: Exception) {
            "操作未完成，请检查 Shizuku 后重试；上次操作的恢复记录保留在本机。"
        }
    }

    private fun checked(command: String, failure: String): String {
        val r = Shell.exec(command, 7_000L)
        check(r.ok) { failure }
        return r.output.trim()
    }

    private fun readSetting(user: Int): Pair<String, Set<Int>> {
        val raw = checked("settings --user $user get secure $SETTING 2>&1", "无法读取移动数据偏好，请检查 Shizuku。")
        val uids = MobileDataPreferencePlan.parseSetting(raw)
        check(uids != null) { "设备返回的 UID 名单格式不受支持，已停止操作。" }
        return raw to uids
    }

    private fun installed(user: Int): Map<Int, Set<String>> {
        val result = checked("cmd package list packages -U --user $user 2>&1", "无法读取当前用户的安装应用。")
        val parsed = MobileDataPreferencePlan.installed(result, user)
        check(parsed != null && parsed.isNotEmpty()) { "当前用户的安装应用列表不完整，请检查 Shizuku 后重试。" }
        return parsed
    }

    private fun change(ctx: Context, selected: Set<String>, unchecked: Set<String>): String {
        val selfUid = Process.myUid()
        val user = selfUid / MobileDataPreferencePlan.userRange
        // Android 16 ConnectivityService observes this Secure setting in the system user's context.
        if (user != 0) return "批量移动数据优先仅支持手机主用户，请在主用户中操作。"
        val prefs = preferences(ctx)
        val (raw, before) = readSetting(user)
        val interrupted = recover(prefs, before)
        val oldOwners = decode(prefs.getStringSet(OWNED, emptySet()).orEmpty())
        if (selected.isEmpty() && oldOwners.isEmpty()) return interrupted + "当前没有可撤回的移动数据偏好。"
        val eligibleApps = if (selected.isEmpty()) emptyList() else DirectAppCatalog.installed(ctx)
        val groups = installed(user)
        if (ctx.packageName !in groups[selfUid].orEmpty()) return "当前用户的应用清单不完整，未更改偏好。"
        if (selected.isNotEmpty() && eligibleApps.isEmpty()) return "未取得可核对的应用清单，未更改偏好。"
        val eligible = eligibleApps.filter { groups[it.uid]?.contains(it.pkg) == true }.mapTo(mutableSetOf()) { it.pkg }
        val change = MobileDataPreferencePlan.plan(before, oldOwners, groups, eligible, selected, selfUid)
        if (selected.isNotEmpty() && change.target.isEmpty()) {
            return interrupted + "没有可单独设置的应用。请确认应用已安装，并检查共用 UID 的应用是否全部选中。"
        }

        val added = change.after - before
        val removed = before - change.after
        // Missing package metadata may make an entire batch disappear from the catalog.
        // Only explicit restore may withdraw an installed app when eligibility cannot be confirmed.
        if (selected.isNotEmpty() && removed.any { uid -> groups[uid]?.any { it !in eligible } == true }) {
            return "部分已设置的应用未能重新核对，未更改原有名单。请刷新后重试，或单独撤回上次设置。"
        }
        val writeProblem = writeChange(user, prefs, raw, before, oldOwners, change)
        if (writeProblem != null) return writeProblem
        if (selected.isNotEmpty()) {
            val prev = prefs.getStringSet(EXCLUDED, emptySet()).orEmpty()
            check(prefs.edit().putStringSet(EXCLUDED, (prev - selected) + unchecked).commit()) { "未能记录取消勾选的应用，请重新应用。" }
        }

        var seen = observed()
        for (attempt in 1..2) {
            if (seen == change.after) break
            Thread.sleep(350L)
            seen = observed()
        }
        val skippedMessage = if (change.skipped.isEmpty()) "" else " 跳过 ${change.skipped.size} 个未安装、受保护或共用 UID 的应用。"
        val identityMessage = if (change.reassigned.isEmpty()) "" else " ${change.reassigned.size} 个 UID 的所属应用已变，保留其系统设置。"
        val verification = if (seen == change.after) "Android 网络服务已确认名单。" else "已保存偏好，生效待验证。"
        val scope = if (selected.isEmpty()) "已从设置中撤回 ${removed.size} 个由 CarDeck 新增的 UID。" else
            "本次选中 ${change.target.values.sumOf { it.size }} 个应用，新增 ${added.size} 个移动数据优先 UID，撤回 ${removed.size} 个。"
        return interrupted + scope + verification + skippedMessage + identityMessage
    }

    private fun writeChange(user: Int, prefs: SharedPreferences, raw: String, before: Set<Int>,
        oldOwners: Map<Int, Set<String>>, change: MobileDataPreferencePlan.Change, live: () -> Boolean = { true }): String? {
        val added = change.after - before
        val removed = before - change.after
        if (!live()) return "投屏状态已变化。"
        if (change.after != before) {
            // Journal precedes an external mutation; a killed process can reconcile it on the next explicit action.
            check(prefs.edit().putStringSet(PENDING, encode(change.owners))
                .putStringSet(ADD, added.map(Int::toString).toSet())
                .putStringSet(REMOVE, removed.map(Int::toString).toSet()).commit()) { "无法保存撤回记录，已停止操作。" }
            val cmd = if (change.after.isEmpty()) {
                "settings --user $user delete secure $SETTING"
            } else {
                "settings --user $user put secure $SETTING '${change.after.sorted().joinToString(";")}'"
            }
            if (!live()) {
                clearJournal(prefs, oldOwners)
                return "投屏状态已变化。"
            }
            // Raw setting has passed strict numeric validation. Recheck before writing to avoid clobbering a newer edit.
            val result = Shell.exec(
                "v=\$(settings --user $user get secure $SETTING 2>&1) || exit 73; " +
                    "[ \"\$v\" = '$raw' ] || exit 72; $cmd 2>&1",
                7_000L,
            )
            if (result.exitCode == 72 || result.exitCode == 73) {
                clearJournal(prefs, oldOwners)
                return "系统设置同时发生变化或读取失败。请重新打开列表后再试。"
            }
            val actual = readSetting(user).second
            if (!result.ok || actual != change.after) {
                recover(prefs, actual)
                return "未能完整写入系统偏好。请检查 Shizuku 或重新读取名单后再试。"
            }
        }
        clearJournal(prefs, change.owners)
        return null
    }

    /** Only emit the two stable AOSP labels and the UID line; avoid collecting addresses, applications or DNS. */
    private fun observed(): Set<Int>? {
        val result = Shell.exec(
            "dumpsys -t 4 connectivity | sed -n -e '/^[[:space:]]*Mobile data preferred UIDs:[[:space:]]*$/ {p;n;p;}' " +
                "-e '/^[[:space:]]*Default requests:[[:space:]]*$/p'",
            6_000L,
        )
        return if (result.ok) MobileDataPreferencePlan.observedUids(result.output) else null
    }

    internal fun hasLeases(ctx: Context) = leases(ctx).let { it.contains(PENDING) || !it.getStringSet(OWNED, null).isNullOrEmpty() }

    /** Only check UIDs of current or leased packages. Package visibility can hide a sibling sharing
     * a UID, so the app-facing PackageManager alone is not sufficient before changing its policy. */
    private fun inspectUid(uid: Int): Set<String> {
        val text = checked("cmd package list packages --user 0 --uid $uid -U 2>&1", "未取得应用身份，本轮不修改。")
        val map = MobileDataPreferencePlan.installed(text, 0)
        check(map != null && map.keys.all { it == uid }) { "系统未能核对 UID 的所属应用。" }
        return map[uid].orEmpty()
    }

    @Synchronized internal fun releaseProjection(ctx: Context) {
        if (!hasLeases(ctx)) return
        check(Process.myUid() / MobileDataPreferencePlan.userRange == 0)
        syncProjection(ctx, emptySet(), mutableSetOf(), { _, _ -> false }, { true })
    }

    /** Caller runs on the single projection worker. Manual writes take this same monitor.
     * The durable projection journal owns only preferences absent before this session. */
    @Synchronized internal fun syncProjection(
        ctx: Context, packages: Set<String>, suppressed: MutableSet<String>,
        needsCellular: (DirectAppCatalog.App, Boolean) -> Boolean, live: () -> Boolean,
    ): Set<Int> {
        val self = Process.myUid()
        if (self / MobileDataPreferencePlan.userRange != 0 || !live()) return emptySet()
        val prefs = leases(ctx)
        if (packages.isEmpty() && !hasLeases(ctx)) return emptySet()
        val (raw, current) = readSetting(0)
        recover(prefs, current)
        val owned = decode(prefs.getStringSet(OWNED, emptySet()).orEmpty())
        // The user removed one of our temporary entries while connected. Respect that choice.
        owned.filterKeys { it !in current }.values.forEach { suppressed.addAll(it) }
        val excluded = preferences(ctx).getStringSet(EXCLUDED, emptySet()).orEmpty()
        val apps = (packages - excluded - suppressed).mapNotNull { eligibleTarget(ctx, it, self) }
        val inspect = owned.keys + apps.map { it.uid }
        val installed = inspect.associateWith(::inspectUid).filterValues { it.isNotEmpty() }
        if (owned.keys.any { it in current && it !in installed }) {
            check(inspectUid(self).contains(ctx.packageName)) { "当前用户的包管理服务尚未就绪。" }
        }
        val candidates = apps.filter { app ->
            val soleOwner = installed[app.uid] == setOf(app.pkg)
            if (app.pkg == BixbyNetworkPolicy.PACKAGE && !soleOwner) {
                io.github.shaonialife.cardeck.SessionController.appendLog("BIXBY-NET: no private UID confirmed; leaving policy unchanged")
            }
            soleOwner && needsCellular(app, owned[app.uid] == setOf(app.pkg))
        }
        val selected = candidates.mapTo(mutableSetOf()) { it.pkg }
        val change = MobileDataPreferencePlan.plan(current, owned, installed, selected, selected, self)
        if (current != change.after || owned != change.owners) {
            val problem = writeChange(0, prefs, raw, current, owned, change, live)
            check(problem == null) { problem ?: "临时设置未完成。" }
        }
        return change.owners.keys
    }

    @Suppress("DEPRECATION")
    private fun eligibleTarget(ctx: Context, pkg: String, self: Int): DirectAppCatalog.App? {
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_PERMISSIONS or
                android.content.pm.PackageManager.GET_SERVICES or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { return null }
        val app = info.applicationInfo ?: return null
        if (!DirectAppCatalog.eligibleOwner(pkg, app.uid, self, ctx.packageName) || !app.enabled ||
            android.Manifest.permission.INTERNET !in info.requestedPermissions.orEmpty() ||
            info.services.orEmpty().any { it.permission == android.Manifest.permission.BIND_VPN_SERVICE }) return null
        val preinstalled = app.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (pkg == BixbyNetworkPolicy.PACKAGE) {
            // Android verifies updates against the original system package. The complete UID
            // membership is independently verified by shell in syncProjection before any write.
            if (!preinstalled) return null
        } else if (preinstalled) {
            val independentApp = DirectAppCatalog.eligiblePreload(pkg, pm.getLaunchIntentForPackage(pkg) != null) {
                pm.checkSignatures(pkg, "android") == android.content.pm.PackageManager.SIGNATURE_NO_MATCH
            }
            if (!independentApp) return null
        }
        return DirectAppCatalog.App(pkg, pkg, app.uid)
    }

    private fun encode(groups: Map<Int, Set<String>>): Set<String> =
        groups.map { (uid, names) -> "$uid:${names.sorted().joinToString(",")}" }.toSet()

    private fun decode(entries: Set<String>): Map<Int, Set<String>> = entries.associate { entry ->
        val uid = entry.substringBefore(':').toIntOrNull()
        check(uid != null && ':' in entry && entry.substringAfter(':').isNotEmpty()) { "本机恢复记录异常，请在系统联网管理中核对。" }
        uid to entry.substringAfter(':').split(',').toSet()
    }

    private fun clearJournal(prefs: SharedPreferences, groups: Map<Int, Set<String>>) {
        check(prefs.edit().putStringSet(OWNED, encode(groups)).remove(PENDING).remove(ADD).remove(REMOVE).commit()) {
            "恢复记录写入失败，请保留 CarDeck 并再次执行操作。"
        }
    }

    private fun recover(prefs: SharedPreferences, current: Set<Int>): String {
        val pending = prefs.getStringSet(PENDING, null) ?: return ""
        val added = prefs.getStringSet(ADD, emptySet()).orEmpty().map(String::toInt)
        val removed = prefs.getStringSet(REMOVE, emptySet()).orEmpty().map(String::toInt)
        val old = decode(prefs.getStringSet(OWNED, emptySet()).orEmpty())
        val targetReached = current.containsAll(added) && removed.none { it in current }
        val originalIntact = added.none { it in current } && current.containsAll(removed)
        return when {
            targetReached -> { clearJournal(prefs, decode(pending)); "" }
            originalIntact -> { clearJournal(prefs, old); "" }
            else -> {
                // Settings were edited externally during a partial write. Avoid claiming unconfirmed additions.
                clearJournal(prefs, old.filterKeys { it in current })
                "上次操作期间名单被改动，仅保留可确认的撤回记录。"
            }
        }
    }
}

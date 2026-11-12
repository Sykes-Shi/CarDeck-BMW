package io.github.shaonialife.cardeck.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** poiSearchPage is the entry used by Baidu Maps' own PoiSearchTileService. */
internal enum class BaiduMapPage(val uri: String, val label: String) {
    CRUISE("baidumap://map/navi/cruiser?src=andr.shaonialife.cardeck", "百度巡航"),
    DESTINATION("baidumap://map/gopage?page=poisearchpage&mode=NORMAL_MODE&src=andr.shaonialife.cardeck", "百度地点搜索"),
    HOME("baidumap://map?src=andr.shaonialife.cardeck", "百度地图首页"),
}

internal data class BaiduMapActivity(val component: String, val taskId: Int)
internal data class BaiduMapPlacement(
    val activity: BaiduMapActivity? = null,
    val taskId: Int? = null,
)

/** Android 16 prints decimal ActivityRecord identities but accepts hex dump selectors.
 * Use the task ID and component instead; both also identify the target on older versions. */
internal fun baiduOnMapDisplay(display: Int, dump: String): BaiduMapPlacement {
    if (display <= 0) return BaiduMapPlacement()
    var onTarget = false
    var sawTop = false
    var top: BaiduMapActivity? = null
    var existingTask: Int? = null
    for (line in dump.lineSequence()) {
        val displayHeader = activityDisplay.find(line)
        if (displayHeader != null) {
            if (onTarget) break
            onTarget = displayHeader.groupValues[1].toIntOrNull() == display
            continue
        }
        if (!onTarget) continue
        val record = displayActivity.find(line) ?: continue
        val task = record.groupValues[3].toIntOrNull() ?: continue
        val component = record.groupValues[2]
        val baidu = component.substringBefore('/') == BaiduCruise.PACKAGE
        if (baidu && existingTask == null) existingTask = task
        if (!sawTop && !record.groupValues[1].startsWith("* Hist")) {
            sawTop = true
            if (baidu) top = BaiduMapActivity(component, task)
        }
    }
    return BaiduMapPlacement(top, top?.taskId ?: existingTask)
}

private val activityDisplay = Regex("""^\s*Display(?:\s+#|:\s*mDisplayId=)(\d+)\b""")
private val displayActivity = Regex(
    """^\s*(topResumedActivity|mResumedActivity|Resumed|\* Hist\s+#\d+)\s*[:=]\s*ActivityRecord\{[0-9a-fA-F]+\s+(?:u\d+\s+|\d+:)([\w.]+/[\w.$]+)(?:/u\d+)?\s+t(\d+)\b""",
)

/** In the activity dump, an old search fragment can remain in the back stack while the map is open. */
internal fun hasActiveBaiduSearchPage(dump: String, targetTask: Int): Boolean {
    var resumed = false
    var added = false
    var inSearchPage = false
    var inTask = false
    for (line in dump.lineSequence().map(String::trim)) {
        when {
            line.startsWith("TASK ") -> {
                inTask = line == "TASK $targetTask"
                inSearchPage = false
            }
            line.endsWith("{") -> {
                inSearchPage = inTask && line == "PoiSearchPage{"
                resumed = false
                added = false
            }
            inSearchPage && line.startsWith("mState=") -> resumed = line == "mState=7"
            inSearchPage && line.startsWith("mAdded=") -> added = line == "mAdded=true mRemoving=false"
            inSearchPage && line.startsWith("mHidden=") -> {
                if (resumed && added && line == "mHidden=false mDetached=false") return true
                inSearchPage = false
            }
        }
    }
    return false
}

object BaiduCruise {
    const val PACKAGE = "com.baidu.BaiduMap"
    private const val PREFS_NAME = "settings"
    private const val KEY_AUTOMATIC = "baiduCruiseOnNewMapTask"

    internal fun resolveComponent(context: Context, page: BaiduMapPage): ComponentName? =
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(page.uri)).setPackage(PACKAGE)
            val activity =
                context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                    ?: return@runCatching null
            if (
                activity.packageName != PACKAGE ||
                !activity.exported ||
                !activity.enabled ||
                !activity.applicationInfo.enabled
            ) {
                return@runCatching null
            }
            ComponentName(PACKAGE, activity.name)
        }.getOrNull()

    fun automaticEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_AUTOMATIC, false)

    fun setAutomaticEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTOMATIC, enabled).apply()
    }
}

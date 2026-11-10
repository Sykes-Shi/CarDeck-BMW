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

/** Only accept a resumed Baidu activity on the requested non-phone display. */
internal fun baiduActivityOnMapDisplay(display: Int, dump: String): String? {
    if (display <= 0) return null
    var onTarget = false
    for (line in dump.lineSequence()) {
        val displayHeader = activityDisplay.find(line)
        if (displayHeader != null) {
            if (onTarget) break
            onTarget = displayHeader.groupValues[1].toIntOrNull() == display
        } else if (onTarget) {
            val top = resumedActivity.find(line) ?: continue
            return top.groupValues[1].takeIf { top.groupValues[2] == BaiduCruise.PACKAGE }
        }
    }
    return null
}

private val activityDisplay = Regex("""^\s*Display #(\d+)\s*\(activities[^)]*\):""")
private val resumedActivity = Regex("""^\s*(?:topResumedActivity|mResumedActivity)\s*[:=]\s*ActivityRecord\{([a-fA-F0-9]{1,8})\s+(?:u\d+\s+|\d+:)([\w.]+)/""")

/** In the activity dump, an old search fragment can remain in the back stack while the map is open. */
internal fun hasActiveBaiduSearchPage(dump: String): Boolean {
    var resumed = false
    var added = false
    var inSearchPage = false
    for (line in dump.lineSequence().map(String::trim)) {
        when {
            line.endsWith("{") -> {
                inSearchPage = line == "PoiSearchPage{"
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

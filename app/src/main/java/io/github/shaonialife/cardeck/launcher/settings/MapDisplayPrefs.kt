package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context

/** 只改变车机地图的布局密度；不修改手机字体、视频像素或触摸坐标。 */
object MapDisplayPrefs {
    private const val KEY = "map_ui_scale_percent"
    private const val WIDE_CRUISE = "baidu_cruise_wide_tile"

    fun wideCruise(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(WIDE_CRUISE, true)

    fun setWideCruise(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(WIDE_CRUISE, enabled).apply()
    }

    fun percent(context: Context): Int = MapUiScale.normalise(
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt(KEY, MapUiScale.DEFAULT),
    )

    fun setPercent(context: Context, percent: Int) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putInt(KEY, MapUiScale.normalise(percent)).apply()
    }

    fun densityDpi(context: Context): Int = MapUiScale.densityDpi(percent(context))
}

internal object MapUiScale {
    const val DEFAULT = 125

    fun normalise(percent: Int): Int = if (percent in listOf(100, 125, 150)) percent else DEFAULT

    fun densityDpi(percent: Int): Int = 200 * normalise(percent) / 100

    /** On the observed 1182x688 @200dpi tile Baidu placed its search bar at the left,
     * leaving the middle available to signal lights. Preserve as much of the requested
     * scale as fits that logical area. Never go below the existing 100% size. */
    fun baiduTileDensity(requestedDpi: Int, width: Int, height: Int): Int {
        if (width <= height || height <= 0) return requestedDpi
        val fit = minOf(width * 160L / 945, height * 160L / 550)
        return minOf(requestedDpi.toLong(), fit.coerceAtLeast(200)).toInt()
    }
}

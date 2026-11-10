package io.github.shaonialife.cardeck.launcher.settings

import android.content.Context

/** 建屏读取偏好；连接中的切换必须经过 VirtualLauncher 的归属与忙状态检查。 */
object PhoneNavigationPrefs {
    private const val KEY = "phone_navigation_mode"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }

    fun isMapPackage(packageName: String): Boolean = packageName in MAP_PACKAGES

    private val MAP_PACKAGES = setOf(
        "com.baidu.BaiduMap",
        "com.autonavi.minimap",
        "com.autonavi.amapauto",
        "com.tencent.map",
    )
}

/** 同一份不可变决策用于自动地图、显式启动和触摸；旧会话延迟请求一律失效。 */
internal class PhoneNavigationSessionPolicy(val enabled: Boolean, val generation: Long, val revision: Long = 0) {
    fun allowsMapAction(currentGeneration: Long, currentRevision: Long = revision): Boolean =
        !enabled && generation == currentGeneration && revision == currentRevision

    fun allowsAppLaunch(packageName: String, currentGeneration: Long, currentRevision: Long = revision): Boolean =
        generation == currentGeneration && revision == currentRevision && (!enabled || !PhoneNavigationPrefs.isMapPackage(packageName))
}

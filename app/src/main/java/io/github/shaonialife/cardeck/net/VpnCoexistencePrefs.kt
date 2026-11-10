package io.github.shaonialife.cardeck.net

import android.content.Context

/** 实验模式需由用户主动开启；会话开始时读取，开关不直接改变正在使用的网络。 */
object VpnCoexistencePrefs {
    private const val PREFS_NAME = "settings"
    private const val KEY_ENABLED = "vpn_coexistence_enabled"

    fun isEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}

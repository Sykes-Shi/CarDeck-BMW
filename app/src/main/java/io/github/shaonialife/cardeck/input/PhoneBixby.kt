package io.github.shaonialife.cardeck.input

import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import io.github.shaonialife.cardeck.net.BixbyNetworkPolicy
import io.github.shaonialife.cardeck.net.ProjectionAppMobileData
import io.github.shaonialife.cardeck.shizuku.Shell
import java.util.concurrent.atomic.AtomicBoolean

/** Open Bixby on the physical phone. Never send an unscoped assistant intent or simulate a power key. */
internal object PhoneBixby {
    private const val PACKAGE = BixbyNetworkPolicy.PACKAGE
    private const val HOME = "com.samsung.android.bixby.assistanthome.AssistantHomeLauncherActivity"
    private val inFlight = AtomicBoolean()

    enum class Result(val hint: String) {
        VOICE("已向手机上的 Bixby 请求语音交互"),
        HOME_SCREEN("已请求打开 Bixby；若未进入聆听，点手机上的麦克风"),
        LOCKED("请解锁手机后再唤起 Bixby"),
        UNAVAILABLE("没有找到可打开的 Bixby 入口，请先在手机上打开并设置 Bixby"),
        BUSY("正在打开 Bixby"),
        EXPIRED("此次唤起已结束"),
    }

    /** Call on a worker. An activity is supplied only for the explicit phone-side test. */
    @Suppress("DEPRECATION")
    fun open(context: Context, activity: Activity? = null, generation: Long? = null, live: () -> Boolean): Result {
        if (!inFlight.compareAndSet(false, true)) return Result.BUSY
        try {
            if (!live()) return Result.EXPIRED
            if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return Result.UNAVAILABLE
            if (context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) return Result.LOCKED
            if (generation != null) {
                ProjectionAppMobileData.preparePhoneBixby(generation)
                if (!live()) return Result.EXPIRED
            }
            val pm = context.packageManager
            val service = runCatching { Settings.Secure.getString(context.contentResolver, "voice_interaction_service") }.getOrNull()
            if (service != null && ComponentName.unflattenFromString(service)?.packageName == PACKAGE && live()) {
                // Show the configured voice service only when Bixby owns it for this Android user.
                val result = Shell.exec("cmd voiceinteraction show 2>&1", 6500)
                if (result.ok && !result.output.contains("error", true) && !result.output.contains("fail", true) &&
                    !result.output.contains("exception", true) && !result.output.contains("timed out", true)
                ) return Result.VOICE
            }
            val candidates = listOf(Intent.ACTION_VOICE_COMMAND, "android.intent.action.VOICE_ASSIST", Intent.ACTION_ASSIST)
            for (action in candidates) {
                val intent = Intent(action).setPackage(PACKAGE)
                val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                if (!publicActivity(context, info)) continue
                intent.component = ComponentName(PACKAGE, info!!.name)
                if (!live()) return Result.EXPIRED
                if (start(activity, intent)) return Result.VOICE
            }
            val defaultIntent = pm.getLaunchIntentForPackage(PACKAGE)
            val homes = listOfNotNull(defaultIntent, Intent(Intent.ACTION_MAIN).setComponent(ComponentName(PACKAGE, HOME)))
            for (intent in homes) {
                val info = intent.component?.let { runCatching { pm.getActivityInfo(it, 0) }.getOrNull() }
                if (!publicActivity(context, info)) continue
                if (!live()) return Result.EXPIRED
                if (start(activity, intent)) return Result.HOME_SCREEN
            }
            return Result.UNAVAILABLE
        } catch (_: RuntimeException) {
            return Result.UNAVAILABLE
        } finally {
            inFlight.set(false)
        }
    }

    private fun publicActivity(ctx: Context, info: ActivityInfo?): Boolean =
        info != null && info.packageName == PACKAGE && info.exported && info.enabled && info.applicationInfo.enabled &&
            (info.permission.isNullOrEmpty() || ctx.checkSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)

    private fun start(activity: Activity?, intent: Intent): Boolean {
        val component = intent.component ?: return false
        val name = component.flattenToString()
        if (component.packageName != PACKAGE || !Regex("""[a-zA-Z0-9_.$/]+""").matches(name)) return false
        val action = intent.action ?: Intent.ACTION_MAIN
        val args = "-a '$action' -n '$name' --display 0"
        if (activity != null) {
            // This test is initiated by the visible phone Activity, including when Shizuku is off.
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            return runCatching {
                activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
                true
            }.getOrDefault(false)
        }
        val result = Shell.exec("am start --user current $args 2>&1", 4_000)
        return result.ok && result.output.contains("Starting:", true) &&
            !result.output.contains("Error:", true) && !result.output.contains("Exception", true) &&
            !result.output.contains("Permission Denial", true)
    }
}

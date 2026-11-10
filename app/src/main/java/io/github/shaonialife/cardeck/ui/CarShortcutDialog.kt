package io.github.shaonialife.cardeck.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.shaonialife.cardeck.SessionController
import io.github.shaonialife.cardeck.input.CarShortcutLearning
import io.github.shaonialife.cardeck.input.PhoneBixby
import io.github.shaonialife.cardeck.input.SteeringDialProbe
import io.github.shaonialife.cardeck.launcher.VirtualLauncher
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.input.ShortcutCalibration.Phase
import io.github.shaonialife.cardeck.launcher.CarShortcut

internal class CarShortcutDialog(private val activity: AppCompatActivity) {
    fun open() {
        val gen = SessionController.shortcutCalibrationGeneration()
        val learned = gen?.let { CarShortcutLearning.labels(activity, it) }
        if (gen == null || learned == null) {
            AlertDialog.Builder(activity).setTitle("车机实体按键")
                .setMessage("按键校准需要先连接 BMW 并打开 CarDeck 画面。可先在手机试用 Bixby。")
                .setPositiveButton("试用 Bixby") { _, _ -> testBixby() }.setNegativeButton("关闭", null).show()
            return
        }
        val keys = CarShortcut.values()
        val labels = mapOf(CarShortcut.MEDIA to "音乐", CarShortcut.MAP to "地图", CarShortcut.OPTION to "设置",
            CarShortcut.NAV to "百度地图搜目的地")
        val rows = keys.map { "${it.name} → ${labels.getValue(it)}" + if (it in learned) "　已校准" else "" } +
            listOf("检测方向盘拨号键", "检测旋钮明暗变化", "清除校准")
        AlertDialog.Builder(activity).setTitle("车机实体按键").setItems(rows.toTypedArray()) { _, index ->
            if (index < keys.size) capture(gen, keys[index]) else if (index == keys.size) detectDial(gen)
            else if (index == keys.size + 1) {
                AlertDialog.Builder(activity).setTitle("检测旋钮明暗变化")
                    .setMessage("停车后，让车机停在容易复现的静止页面。开始后先停两秒，再慢慢来回转动旋钮。12 秒内会记录投屏画面两处的明暗数值，结束后可导出日志。")
                    .setPositiveButton("开始") { _, _ ->
                        val message = if (VirtualLauncher.startRotaryBrightnessProbe(gen)) "检测已开始" else "请先连接 CarDeck"
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("取消", null).show()
            } else {
                AlertDialog.Builder(activity).setMessage("清除本车的按键校准和共用信号记录？")
                    .setPositiveButton("清除") { _, _ -> CarShortcutLearning.reset(activity, gen) }
                    .setNegativeButton("取消", null).show()
            }
        }.setNeutralButton("试用 Bixby") { _, _ -> testBixby() }.setNegativeButton("关闭", null).show()
    }

    private fun testBixby() {
        val generation = SessionController.shortcutCalibrationGeneration()
        Bg.run("bixby-phone-test") {
            val result = PhoneBixby.open(activity.applicationContext, activity, generation) {
                !activity.isFinishing && !activity.isDestroyed && (generation == null || VirtualLauncher.isCurrentSession(generation))
            }
            SessionController.appendLog("BMW-VOICE: phoneTest=${result.name}")
            activity.runOnUiThread { if (!activity.isDestroyed) Toast.makeText(activity, result.hint, Toast.LENGTH_LONG).show() }
        }
    }

    private fun detectDial(gen: Long) {
        val sample = SessionController.steeringDialProbe.start(gen, SystemClock.elapsedRealtime())
        SessionController.appendLog("BMW-DIAL: sample started")
        val dialog = AlertDialog.Builder(activity).setTitle("检测方向盘拨号键")
            .setMessage("停车后，在 8 秒内按一次方向盘拨号键，然后等待结果。期间请勿操作车机触屏或旋钮。")
            .setPositiveButton("关闭", null).show()
        val handler = Handler(Looper.getMainLooper())
        val finish = Runnable {
            val events = SessionController.steeringDialProbe.finish(sample) ?: return@Runnable
            val received = events.joinToString("、") { it.label }
            val result = when {
                !VirtualLauncher.isCurrentSession(gen) -> "投屏已结束，请在下次连接时再测。"
                events.isEmpty() -> "本轮未收到可观察的车机按键或切屏事件。"
                SteeringDialProbe.Event.PHONE in events -> "本轮收到${received}。"
                else -> "本轮收到${received}。未识别到独立拨号信号。"
            }
            SessionController.appendLog("BMW-DIAL: $result")
            if (!activity.isDestroyed && dialog.isShowing) dialog.setMessage(result)
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(finish)
            if (SessionController.steeringDialProbe.finish(sample) != null) SessionController.appendLog("BMW-DIAL: sample closed early")
        }
        handler.postDelayed(finish, 8_000)
    }

    private fun capture(gen: Long, key: CarShortcut) {
        val attempt = CarShortcutLearning.start(activity, gen, key) ?: return
        val handler = Handler(Looper.getMainLooper())
        val temporary = CarShortcutLearning.isTemporary(gen)
        val dialog = AlertDialog.Builder(activity).setTitle("校准 ${key.name}").setMessage("")
            .setPositiveButton("再按一次", null).setNegativeButton("取消", null).create()
        val update = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val state = CarShortcutLearning.status(activity, gen, attempt)
                val seconds = state?.let { ((it.deadline - SystemClock.elapsedRealtime() + 999) / 1000).coerceAtLeast(0) } ?: 0
                val message = when (state?.phase) {
                    Phase.FIRST, Phase.SECOND -> "停车后只按一下车上的 ${key.name} 键，等倒计时结束（${seconds} 秒）。"
                    Phase.CONFIRM -> (if (state.diagnosticOnly) "第一次只收到原车事件，可以再测一次作对照。" else "第一次已收到一个独立信号。") +
                        "将车机切回 CarDeck 画面，再点「再按一次」，按同一个键。"
                    Phase.DIAGNOSTIC -> if ("screen_take" in state.observed)
                        "宝马请求切回原车画面，CarDeck 已记录此次切屏。此信号由多项原车操作共用。" + fallback(key)
                        else "两轮事件已记录。该键尚未匹配到可绑定的独立信号。" + fallback(key)
                    Phase.SAVED -> if (temporary) "已校准，本次连接有效。选择目标车机后可保存校准。" else "已为这台车保存 ${key.name} 的校准。"
                    Phase.SHARED -> "该信号对应多个按键，已停用它的快捷操作。可清除校准后重新测。"
                    Phase.DIFFERENT -> "两次收到的信号不同。请重新校准，每轮只按同一个键。"
                    Phase.AMBIGUOUS -> "一轮内收到了多个不同信号。重新校准时只操作目标按键。"
                    Phase.TIMEOUT -> if (state.observed.isEmpty()) "这 8 秒内 CarDeck 没收到按键信号。" + fallback(key)
                        else "已记录车机事件，但未完成两次独立信号确认。可继续测试另一个键，全部测完后导出日志。"
                    null -> "采样已结束。将车机切回 CarDeck 后可重新校准。"
                }
                dialog.setMessage(message)
                val next = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                next.visibility = if (state?.capturing == true) View.GONE else View.VISIBLE
                next.text = if (state?.phase == Phase.CONFIRM) "再按一次" else "完成"
                next.isEnabled = state?.phase != Phase.CONFIRM || SessionController.shortcutCalibrationGeneration() == gen
                next.setOnClickListener {
                    if (state?.phase == Phase.CONFIRM) {
                        CarShortcutLearning.confirm(activity, gen, attempt)
                        handler.removeCallbacks(this)
                        run()
                    } else dialog.dismiss()
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility = if (state?.active == true) View.VISIBLE else View.GONE
                if (state?.active == true) handler.postDelayed(this, 250L)
            }
        }
        val lifecycle = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) dialog.dismiss() }
        dialog.setOnDismissListener {
            handler.removeCallbacks(update)
            activity.lifecycle.removeObserver(lifecycle)
            CarShortcutLearning.cancel(gen, attempt)
        }
        dialog.show()
        activity.lifecycle.addObserver(lifecycle)
        update.run()
    }

    private fun fallback(key: CarShortcut) = when (key) {
        CarShortcut.OPTION -> "可从车机上的 CarDeck 应用列表打开「车机设置」。"
        CarShortcut.NAV -> "可从车机上的「搜目的地」打开百度地图。"
        CarShortcut.MAP -> "可从 CarDeck 应用列表打开地图。"
        CarShortcut.MEDIA -> "可从 CarDeck 首页音乐卡打开车载播放器。"
    }
}

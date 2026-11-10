package io.github.shaonialife.cardeck.launcher

import android.app.Activity
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/** 只收口车机自有桌面窗口，避免虚拟屏释放后透明桌面迁回手机并覆盖连接页。 */
internal object LauncherActivityTracker {
    private data class Entry(
        val activity: WeakReference<Activity>,
        val targetDisplayId: Int,
    )

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun onCreated(
        activity: Activity,
        targetDisplayId: Int,
    ) {
        require(activity is GridActivity || activity is DockActivity || activity is CarMediaActivity || activity is SettingsActivity)
        synchronized(lock) {
            entries.removeAll { it.activity.get() == null || it.activity.get() === activity }
            entries.add(Entry(WeakReference(activity), targetDisplayId))
        }
        log(activity, targetDisplayId, "create")
    }

    fun onResumed(
        activity: Activity,
        targetDisplayId: Int,
    ) {
        log(activity, targetDisplayId, "resume")
    }

    fun onDestroyed(activity: Activity) {
        val targetDisplayId =
            synchronized(lock) {
                val target = entries.firstOrNull { it.activity.get() === activity }?.targetDisplayId ?: -1
                entries.removeAll { it.activity.get() == null || it.activity.get() === activity }
                target
            }
        log(activity, targetDisplayId, "destroy")
    }

    fun finishCaptured() {
        // 在 detach 调用线程上先取快照；主线程稍后执行时不能再读取注册表，
        // 否则快速重连后新建的桌面也会被上一轮清理关闭。
        val captured =
            synchronized(lock) {
                entries.toList().also { entries.clear() }
            }
        if (captured.isEmpty()) return
        VirtualLauncher.logSink?.invoke("LAUNCHER-UI: detach captured=${captured.size}")
        val finish =
            Runnable {
                captured.forEach { entry ->
                    val activity = entry.activity.get() ?: return@forEach
                    log(activity, entry.targetDisplayId, "cleanup")
                    if (!activity.isDestroyed && !activity.isFinishing) {
                        // finishAndRemoveTask 会连带清理同任务的手机操作页；这里只关该实例。
                        runCatching { activity.finish() }
                            .onFailure {
                                VirtualLauncher.logSink?.invoke(
                                    "LAUNCHER-UI: ${activity.javaClass.simpleName} finish failed=${it.javaClass.simpleName}",
                                )
                            }
                    }
                }
            }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finish.run()
        } else {
            mainHandler.post(finish)
        }
    }

    private fun log(
        activity: Activity,
        targetDisplayId: Int,
        event: String,
    ) {
        val displayId = runCatching { activity.display?.displayId ?: -1 }.getOrDefault(-1)
        val taskId = runCatching { activity.taskId }.getOrDefault(-1)
        VirtualLauncher.logSink?.invoke(
            "LAUNCHER-UI: ${activity.javaClass.simpleName} $event " +
                "instance=${Integer.toHexString(System.identityHashCode(activity))} " +
                "display=$displayId task=$taskId target=$targetDisplayId",
        )
    }
}

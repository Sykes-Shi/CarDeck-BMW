package io.github.shaonialife.cardeck.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.net.DirectAppCatalog
import io.github.shaonialife.cardeck.net.MobileDataRouting
import io.github.shaonialife.cardeck.util.Bg

/** Phone-side review of the actual per-app split before changing Android routing preferences. */
internal class DirectMobileDataDialog(private val activity: AppCompatActivity) {
    private val context = activity.applicationContext
    private var busy = false

    fun open() = work("正在读取手机上的直连应用…", {
        val apps = runCatching { DirectAppCatalog.installed(context) }.getOrDefault(emptyList())
        apps to runCatching { DirectAppCatalog.fromLiveClash(context, apps) }.getOrNull()
    }) { (apps, live) ->
        if (apps.isEmpty()) {
            options(apps, "暂时未读到应用，请检查 Shizuku")
        } else if (live == null) {
            options(apps, "未读到 Clash 的分应用名单")
        } else if (live.isEmpty()) {
            options(apps, "当前分流里没有可批量设置的直连应用")
        } else {
            preview(apps, live, liveSelection = true)
        }
    }

    private fun options(apps: List<DirectAppCatalog.App>, title: String) {
        val actions = arrayListOf<Pair<String, () -> Unit>>()
        if (apps.isNotEmpty()) actions.add("从 Clash 导出的名单选择" to { importMode(apps) })
        actions.add("打开三星流量监控" to ::openSystemSettings)
        if (MobileDataRouting.hasChanges(context)) actions.add("撤销 CarDeck 的移动数据设置" to ::restore)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> actions[index].second.invoke() }
            .setNegativeButton("关闭", null).show()
    }

    private fun importMode(apps: List<DirectAppCatalog.App>) {
        AlertDialog.Builder(activity)
            .setTitle("从 Clash 导入")
            .setMessage(
                "在 Clash 的「访问控制应用包列表」菜单中导出，回到这里选择 Clash 当前的访问控制模式。" +
                    "导出的只是勾选名单，两种模式的含义相反。",
            )
            .setPositiveButton("仅允许已选择的应用") { _, _ -> importClipboard(apps, selectedUseVpn = true) }
            .setNegativeButton("不允许已选择的应用") { _, _ -> importClipboard(apps, selectedUseVpn = false) }
            .setNeutralButton("返回") { _, _ -> open() }
            .show()
    }

    private fun importClipboard(apps: List<DirectAppCatalog.App>, selectedUseVpn: Boolean) {
        // Read only after an explicit import action. The contents never enter a log or preferences.
        val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = manager.primaryClip
        val text = if (clip != null && clip.itemCount == 1) clip.getItemAt(0).text?.toString().orEmpty() else ""
        val direct = DirectAppCatalog.fromExport(apps, text, selectedUseVpn)
        if (direct.isNullOrEmpty()) {
            showMessage("没有可应用的名单", "请在 Clash 的访问控制应用包列表中重新导出，并核对当前模式。")
        } else {
            preview(apps, direct, liveSelection = false)
        }
    }

    private fun preview(apps: List<DirectAppCatalog.App>, direct: Set<String>, liveSelection: Boolean) {
        val rows = apps.filter { it.pkg in direct }
        if (rows.isEmpty()) {
            options(apps, "没有可设置的直连应用")
            return
        }
        val chosen = rows.mapTo(mutableSetOf()) { it.pkg }
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val selectionCount = TextView(activity).apply {
            text = "直连应用（已选 ${chosen.size}）"
            textSize = 20f
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(selectionCount)
            addView(TextView(activity).apply {
                val source = if (liveSelection) "已按当前 Clash 分流勾选。" else "已按导出的 Clash 名单勾选。"
                text = source + "勾选项在车外也会优先使用流量。取消勾选的应用也会从投屏临时设置中排除。"
                textSize = 14f
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setCustomTitle(header)
            .setMultiChoiceItems(rows.map { "${it.label}\n${it.pkg}" }.toTypedArray(), BooleanArray(rows.size) { true }) { dialog, position, checked ->
                if (checked) chosen.add(rows[position].pkg) else chosen.remove(rows[position].pkg)
                selectionCount.text = "直连应用（已选 ${chosen.size}）"
                (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = chosen.isNotEmpty()
            }
            .setPositiveButton("应用移动数据优先") { _, _ -> apply(chosen.toSet(), direct - chosen, liveSelection) }
            .setNegativeButton("取消", null)
            .setNeutralButton("更多") { _, _ -> options(apps, "直连应用设置") }
            .create()
        dialog.show()
    }

    private fun apply(selected: Set<String>, unchecked: Set<String>, liveSelection: Boolean) {
        work("正在设置移动数据优先…", {
            val current = DirectAppCatalog.fromLiveClash(context, DirectAppCatalog.installed(context))
            if ((liveSelection && current == null) || (current != null && !current.containsAll(selected))) {
                "Clash 名单已变化或与导入结果不同，请重新读取并核对。"
            } else {
                MobileDataRouting.apply(context, selected, unchecked)
            }
        }) { showMessage("移动数据设置结果", it) }
    }

    private fun restore() {
        work("正在撤销 CarDeck 的设置…", { MobileDataRouting.restore(context) }) {
            showMessage("撤销结果", it)
        }
    }

    private fun openSystemSettings() {
        try {
            activity.startActivity(Intent(Settings.ACTION_DATA_USAGE_SETTINGS))
        } catch (_: android.content.ActivityNotFoundException) {
            showMessage("流量监控", activity.getString(R.string.samsung_data_settings_path))
        } catch (_: SecurityException) {
            showMessage("流量监控", activity.getString(R.string.samsung_data_settings_path))
        }
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton("完成", null).show()
    }

    private fun <T> work(label: String, action: () -> T, render: (T) -> Unit) {
        if (busy) return
        busy = true
        val button = activity.findViewById<TextView>(R.id.btnAppMobileData)
        button.isEnabled = false
        button.text = label
        Bg.run("direct-mobile-data") {
            val result = runCatching(action)
            activity.runOnUiThread {
                busy = false
                if (!activity.isFinishing && !activity.isDestroyed) {
                    button.setText(R.string.app_mobile_data_button)
                    button.isEnabled = true
                    result.fold(render) { showMessage("操作未完成", "请确认 Shizuku 已就绪，也可以到「连接 → 流量监控 → 联网管理」设置。") }
                }
            }
        }
    }
}

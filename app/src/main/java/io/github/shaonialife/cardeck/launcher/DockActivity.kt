package io.github.shaonialife.cardeck.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.input.CarControllerInput
import io.github.shaonialife.cardeck.input.CarControllerAction
import io.github.shaonialife.cardeck.input.DockControllerNavigation
import io.github.shaonialife.cardeck.ui.WallpaperSelection
import io.github.shaonialife.cardeck.ui.WallpaperStore
import io.github.shaonialife.cardeck.util.Bg

/**
 * CarPlay dock（独立虚拟屏，含时钟/电量/运行中应用/回桌面）。
 *
 * 两种形态（布局见 res/layout）：
 *  - 横屏车机：dock 是左侧竖条 → activity_dock_sidebar.xml（时钟居顶、回桌面居底）
 *  - 竖屏车机：dock 是底部横条 → activity_dock_bar.xml（⌂ | 应用横排 | 时间/电量）
 * 形态由 dock 虚拟屏自身宽高决定（竖条 height>width；横条 width>height），与车机方向自动对齐。
 */
class DockActivity : CarAppearanceActivity() {
    companion object {
        // All access is on the main looper. An old activity cannot receive a new display's keys.
        private var showing = java.lang.ref.WeakReference<DockActivity>(null)
        internal fun handleController(input: CarControllerInput, at: Long, scene: () -> Boolean, grant: () -> Boolean): Boolean {
            val activity = showing.get() ?: return false
            return activity.ownsDock() && activity.route(input, at, scene, grant)
        }
        internal fun onControllerTouch(inDock: Boolean) {
            showing.get()?.let { if (!inDock || !it.navigation.active) it.leaveDock() }
        }
    }

    private val dockGeneration = VirtualLauncher.sessionGeneration()
    private val navigation = DockControllerNavigation()
    private val cursor = CarControllerFocus()
    private var lease: (() -> Boolean)? = null
    private var screenGrant: (() -> Boolean)? = null
    private var selectedTag: Any? = null
    private val home get() = findViewById<ImageView>(R.id.dockHome)
    private val mapButton get() = findViewById<ImageView>(R.id.dockControllerMap)
    private fun ownsDock() = VirtualLauncher.isCurrentSession(dockGeneration) &&
        VirtualLauncher.dockDisplayId > 0 && display?.displayId == VirtualLauncher.dockDisplayId && !isDestroyed && !isFinishing

    private val checkGrant = object : Runnable {
        override fun run() {
            if (lease?.invoke() != true || !ownsDock()) leaveDock()
            else if (navigation.active) pollHandler.postDelayed(this, 250)
        }
    }
    private val dashboardIconListener: (Boolean) -> Unit = { runOnUiThread { renderHome() } }

    private fun renderHome() {
        val grid = navigation.active
        val dashboard = VirtualLauncher.dashboardMode
        home.setImageResource(if (grid || !dashboard) R.drawable.ic_dock_dashboard else R.drawable.ic_dock_home)
        home.contentDescription = if (grid || dashboard) "应用列表" else "分屏首页"
        mapButton.visibility = if (grid && dashboard && VirtualLauncher.canRouteMapTouch() &&
            VirtualLauncher.defaultMapComponent(this) != null) View.VISIBLE else View.GONE
    }

    private fun leaveDock() {
        val hadCursor = selectedTag != null
        selectedTag = null
        navigation.reset()
        lease = null
        screenGrant = null
        pollHandler.removeCallbacks(checkGrant)
        if (hadCursor) {
            cursor.clear()
            renderHome()
        }
    }

    private fun entries(): List<View> = buildList {
        if (barForm) add(home)
        appsContainer?.let { container -> for (i in 0 until container.childCount) add(container.getChildAt(i)) }
        if (mapButton.visibility == View.VISIBLE) add(mapButton)
        if (!barForm) add(home)
    }

    private fun select(view: View) {
        selectedTag = view.tag
        cursor.select(view)
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        // The temporary map button may need its first layout before it has drawable bounds.
        if (view.width == 0 || view.height == 0) view.post {
            if (navigation.active && selectedTag == view.tag && view.isAttachedToWindow) {
                cursor.clear()
                cursor.select(view)
            }
        }
    }

    private fun activate(tag: Any?, allowed: () -> Boolean) {
        if (lease?.invoke() != true || !ownsDock()) {
            leaveDock()
            return
        }
        leaveDock()
        val generation = VirtualLauncher.sessionGeneration()
        val nav = VirtualLauncher.navigationRevision()
        when (tag) {
            R.id.dockHome -> VirtualLauncher.openControllerAppList(allowed)
            R.id.dockControllerMap -> VirtualLauncher.submitShortcut(CarShortcut.MAP, allowed)
            is ComponentName -> Bg.run("dock-controller-app") {
                if (allowed()) VirtualLauncher.launchApp(tag, expectedSession = generation, expectedNavigation = nav)
            }
        }
    }

    private fun route(input: CarControllerInput, at: Long, scene: () -> Boolean, grant: () -> Boolean): Boolean {
        if (lease?.invoke() == false) leaveDock()
        return when (navigation.route(input.action, at)) {
            DockControllerNavigation.Route.CONTENT -> {
                // A single left still reaches the app. Its own focus/page change is allowed
                // between left edges; a receiver borrow, reconnection or touch cancels the pair.
                lease = if (input.action == CarControllerAction.LEFT) grant else null
                false
            }
            DockControllerNavigation.Route.ENTER -> {
                lease = scene
                screenGrant = grant
                renderHome()
                select(home)
                pollHandler.postDelayed(checkGrant, 250)
                VirtualLauncher.logSink?.invoke("ROTARY: 左栏已选中应用列表")
                true
            }
            DockControllerNavigation.Route.LEAVE -> { leaveDock(); true }
            DockControllerNavigation.Route.ACTIVATE -> { activate(selectedTag, grant); true }
            DockControllerNavigation.Route.STAY -> true
            else -> {
                val targets = entries()
                val index = targets.indexOfFirst { it.tag == selectedTag }.takeIf { it >= 0 } ?: targets.indexOf(home)
                val forward = input.action == CarControllerAction.DOWN || input.action == CarControllerAction.NEXT
                select(targets[navigation.advance(index, targets.size, forward, input.steps)])
                true
            }
        }
    }
    private var appsContainer: LinearLayout? = null
    private var clockView: TextView? = null
    private var batteryText: TextView? = null
    private var batteryBar: View? = null
    private var batteryFill: View? = null
    private var barForm = false

    /**
     * 设计基准密度（420dpi）的当前倍率。dock 屏密度由 SessionController 按画布短边等比
     * 下发（奥迪 1160×540→420 不变；测试台 2320×1080→840，dock 与文字/图标整体 ×2）。
     * 布局 XML 用 dp 自动随密度缩放；字号/模糊半径等按「设计 px × 本倍率」在代码里设，
     * 保证高分辨率车机下 dock 内容与画布同比例（2026-10-17 修复「高分辨率下显得小」）。
     */
    private val designScale: Float
        get() = resources.displayMetrics.densityDpi / 420f

    private val refreshListener: () -> Unit = { runOnUiThread { refresh() } }

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable =
        object : Runnable {
            override fun run() {
                refresh()
                pollHandler.postDelayed(this, 3_000)
            }
        }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker =
        object : Runnable {
            override fun run() {
                // 无秒显示（用户要求）：对齐到下一分钟边界刷新，秒内不做无用重绘
                clockView?.text =
                    android.text.format.DateFormat
                        .format("HH:mm", System.currentTimeMillis())
                clockView?.let { fitClockText(it) }
                val now = System.currentTimeMillis()
                clockHandler.postDelayed(this, 60_000L - now % 60_000L + 80L)
            }
        }

    /** 壁纸背景（全流画布切片，与桌面同源同路径——交界处逐像素连续）。 */
    private var wallpaperView: ImageView? = null
    private var wallpaperRequested: WallpaperSelection? = null

    private val wallpaperListener: (Long) -> Unit = {
        runOnUiThread { refreshWallpaperIfChanged() }
    }

    /** dock 外观偏好监听（设置页拖动滑条即时生效）。 */
    private val dockPrefsListener: () -> Unit = { runOnUiThread { applyDockPrefs() } }

    private val batteryReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) = updateBattery(intent)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherActivityTracker.onCreated(this, VirtualLauncher.dockDisplayId)
        val dm = resources.displayMetrics
        barForm = dm.widthPixels > dm.heightPixels
        setContentView(if (barForm) R.layout.activity_dock_bar else R.layout.activity_dock_sidebar)

        clockView = findViewById(R.id.dockClock)
        batteryText = findViewById(R.id.dockBatteryText)
        batteryBar = findViewById(R.id.dockBatteryBar)
        batteryFill = findViewById(R.id.dockBatteryFill)
        appsContainer = findViewById(R.id.dockApps)
        wallpaperView = findViewById(R.id.dockWallpaper)
        home.tag = R.id.dockHome
        mapButton.tag = R.id.dockControllerMap
        home.setOnClickListener {
            val grant = screenGrant
            if (navigation.active && grant != null) activate(home.tag, grant)
            else Bg.run("dock-home") { VirtualLauncher.onHomePressed() }
        }
        mapButton.setOnClickListener { screenGrant?.let { grant -> activate(mapButton.tag, grant) } }
        VirtualLauncher.addDashboardListener(dashboardIconListener)
        renderHome()

        VirtualLauncher.addRunningAppsListener(refreshListener)
        pollHandler.postDelayed(pollRunnable, 3_000)
        clockHandler.post(clockTicker)
        // 字号按「设计 px × designScale」设置（不进 XML：荣耀 ROM 上 autoSize 对窄 dock
        // 不生效，仍需布局完成后按实测宽度手工缩钟表——见 fitClockText；13/22 为原型值）
        clockView?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 22f * designScale)
        batteryText?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f * designScale)
        clockView?.post { fitClockText(clockView!!) }
        setupWallpaper()
        // 注册变更通知 + 立即对账一次（兜底注册前发生的更换——事件不补发）
        WallpaperStore
            .addListener(wallpaperListener)
        refreshWallpaperIfChanged()
        // dock 外观（暗幕/模糊）：应用当前值 + 监听设置页调整
        DockPrefs.addListener(dockPrefsListener)
        applyDockPrefs()
        // 系统受保护广播：ACTION_BATTERY_CHANGED 为 sticky，注册即回填当前电量
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onDestroy() {
        LauncherActivityTracker.onDestroyed(this)
        VirtualLauncher.removeRunningAppsListener(refreshListener)
        VirtualLauncher.removeDashboardListener(dashboardIconListener)
        pollHandler.removeCallbacksAndMessages(null)
        clockHandler.removeCallbacks(clockTicker)
        WallpaperStore
            .removeListener(wallpaperListener)
        DockPrefs.removeListener(dockPrefsListener)
        runCatching { unregisterReceiver(batteryReceiver) }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        LauncherActivityTracker.onResumed(this, VirtualLauncher.dockDisplayId)
        refresh()
    }

    override fun onCarAppearanceChanged(day: Boolean) {
        refreshWallpaperIfChanged()
        applyDockPrefs()
        clockView?.setTextColor(carPalette.text)
        batteryText?.setTextColor(carPalette.secondary)
        val tint = android.content.res.ColorStateList.valueOf(carPalette.text)
        home.imageTintList = tint
        mapButton.imageTintList = tint
    }

    override fun onStart() {
        super.onStart()
        if (ownsDock()) showing = java.lang.ref.WeakReference(this)
    }

    override fun onStop() {
        if (showing.get() === this) showing.clear()
        leaveDock()
        super.onStop()
    }

    /**
     * 壁纸切片：dock 窗口内画「全流画布壁纸」中本条带对应的部分。
     * dock 虚拟屏与流 1:1（420dpi），canvas 视图 = 流画布尺寸：
     *  - 侧栏（横屏车机）：流左条，偏移 (0, 0)
     *  - 底栏（竖屏车机）：流底条，偏移 (0, -(appHeight))
     * 窗口必须不透明：半透明窗口下系统会往这块虚拟屏合成手机自身壁纸
     * （曾致 dock 显示手机壁纸、与桌面壁纸错位——2026-10-12 实测根因）。
     */
    private fun setupWallpaper() {
        val launcher = VirtualLauncher
        val streamW = launcher.streamWidthPx
        val streamH = launcher.streamHeightPx
        if (streamW <= 0 || streamH <= 0) return
        val view = wallpaperView ?: return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (barForm) {
            params.topMargin = -(streamH - launcher.dockHeightPx)
        } else {
            params.leftMargin = 0
            params.topMargin = 0
        }
        params.width = streamW
        params.height = streamH
        view.layoutParams = params
        refreshWallpaperIfChanged()
    }

    /**
     * 应用 dock 外观偏好：暗幕强度（scrim View 的黑 alpha）+ 背景高斯模糊
     * （RenderEffect，API 31+；半径按 dock 物理像素，最大 40px——乘 designScale
     * 保证高分辨率车机上模糊观感与奥迪基准一致）。
     */
    private fun applyDockPrefs() {
        val scrim = findViewById<View>(R.id.dockScrim) ?: return
        val alpha = (DockPrefs.scrimOpacity(this) * 255 / 100).coerceIn(0, 255)
        scrim.background =
            android.graphics.drawable.ColorDrawable(
                if (carPalette.day) androidx.core.graphics.ColorUtils.setAlphaComponent(carPalette.surface, alpha.coerceAtLeast(228))
                else android.graphics.Color.argb(alpha, 0, 0, 0),
            )
        val wallpaper = wallpaperView ?: return
        val percent = DockPrefs.blur(this)
        if (percent <= 0) {
            wallpaper.setRenderEffect(null)
        } else {
            val radius = percent / 100f * 40f * designScale
            wallpaper.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    radius,
                    radius,
                    android.graphics.Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    /** 壁纸版本变化时后台重解码并换图（监听器模式下与桌面同步更新）。 */
    private fun refreshWallpaperIfChanged() {
        val view = wallpaperView ?: return
        val selection = WallpaperStore.selection(carPalette.day)
        if (selection == wallpaperRequested) return
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val width = params.width
        val height = params.height
        if (width <= 0 || height <= 0) return
        wallpaperRequested = selection
        Bg.run("wallpaper-dock") {
            val bitmap =
                runCatching {
                    WallpaperStore.aspectFillCanvas(
                        this@DockActivity,
                        width,
                        height,
                        selection,
                    )
                }.getOrNull()
            if (bitmap != null) {
                VirtualLauncher.logSink?.invoke(
                    "WALLPAPER-DOCK: bitmap=${bitmap.width}x${bitmap.height} " +
                        "view=${params.width}x${params.height} top=${params.topMargin}",
                )
                runOnUiThread {
                    if (!isDestroyed && selection == WallpaperStore.selection(carPalette.day)) view.setImageBitmap(bitmap)
                }
            } else {
                runOnUiThread {
                    if (selection == wallpaperRequested) wallpaperRequested = null
                }
            }
        }
    }

    /** 逐级缩小字号直至文本宽度放得下（设计 px × designScale；行程 22→12，对照设计原型）。 */
    private fun fitClockText(view: TextView) {
        val available = view.width - view.paddingLeft - view.paddingRight
        if (available <= 0) return
        val scale = designScale
        var size = 22f * scale
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        val paint = view.paint
        while (size > 12f * scale && paint.measureText(view.text.toString()) > available) {
            size -= 0.5f * scale
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    private fun updateBattery(intent: Intent?) {
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return
        val percent = level * 100 / scale
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        runOnUiThread {
            batteryText?.text = (if (charging) "⚡" else "") + "$percent%"
            val bar = batteryBar
            val fill = batteryFill
            if (bar != null && fill != null) {
                bar.post {
                    val width = bar.width
                    if (width > 0) {
                        fill.layoutParams =
                            (fill.layoutParams as FrameLayout.LayoutParams).apply {
                                this.width =
                                    (width * percent / 100)
                                        .coerceAtLeast((3 * resources.displayMetrics.density).toInt())
                            }
                    }
                }
                fill.setBackgroundColor(if (percent <= 20) 0xFFFF6B6B.toInt() else 0xFF34C759.toInt())
            }
        }
    }

    private var lastRendered: List<String> = emptyList()

    private fun refresh() {
        val container = appsContainer ?: return
        val current =
            synchronized(VirtualLauncher.runningApps) {
                VirtualLauncher.runningApps.map { it.flattenToString() }
            }
        if (current == lastRendered) return
        lastRendered = current
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val iconPx = ((if (barForm) 32 else 30) * density).toInt()
        val pad = (6 * density).toInt()
        val apps = synchronized(VirtualLauncher.runningApps) { VirtualLauncher.runningApps.toList() }
        val iconCache = VirtualLauncher.iconCache
        apps.forEach { component ->
            val drawable = iconCache[component] ?: packageManager.defaultActivityIcon
            // 位图化渲染（与桌面网格同一路径）：直接 setImageDrawable 会让 ImageView
            // 长期持有共享 Drawable 实例，其 bounds 被网格重绑改写后渲染错位
            // （实机故障：点 home 后 dock 图标变小且偏左）
            val bitmap = AppIcons.rounded(drawable, iconPx)
            container.addView(
                ImageView(this).apply {
                    tag = component
                    setImageBitmap(bitmap)
                    setPadding(pad, pad, pad, pad)
                    contentDescription = component.shortClassName
                    setOnClickListener {
                        leaveDock()
                        val ownerSession = VirtualLauncher.sessionGeneration()
                        val ownerNavigation = VirtualLauncher.navigationRevision()
                        Bg.run("launch-app") { VirtualLauncher.launchApp(component, expectedSession = ownerSession, expectedNavigation = ownerNavigation) }
                    }
                },
                LinearLayout.LayoutParams(iconPx + pad * 2, iconPx + pad * 2),
            )
        }
        if (navigation.active) select(entries().firstOrNull { it.tag == selectedTag } ?: home)
    }

    override fun onBackPressed() = Unit // dock 常驻，不响应返回
}

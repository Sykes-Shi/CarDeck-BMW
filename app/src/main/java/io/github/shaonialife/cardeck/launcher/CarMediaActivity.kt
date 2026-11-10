package io.github.shaonialife.cardeck.launcher

import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import io.github.shaonialife.cardeck.R
import io.github.shaonialife.cardeck.media.MediaSessionsState
import io.github.shaonialife.cardeck.media.ArtworkTone
import io.github.shaonialife.cardeck.media.TargetedMediaControls
import io.github.shaonialife.cardeck.util.Bg
import java.util.Locale

/** 自有车载播放页只呈现媒体会话；选歌仍交给原应用，保持账号与曲库行为。 */
class CarMediaActivity : CarControllerActivity() {
    companion object {
        const val EXTRA_TARGET_PACKAGE = "car_media_target_package"
        const val EXTRA_LAUNCH_SESSION = "car_media_session"
        const val EXTRA_LAUNCH_REQUEST = "car_media_request"
        const val NETEASE_PACKAGE = "com.netease.cloudmusic"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var targetPackage = NETEASE_PACKAGE
    private var targetLabel = "网易云音乐"
    private var launchSession = -1L
    private var launchRequest = -1L
    private var started = false
    private var pageReady = false
    private var commandPending = false
    private var displayed: MediaSessionsState.NowPlaying? = null
    private var artwork: android.graphics.Bitmap? = null
    private var backdrop: MediaBackdrop? = null
    private var feedback: String? = null
    private var feedbackUntil = 0L
    private val stateListener = { runOnUiThread { if (started) render() } }
    private val tick = object : Runnable {
        override fun run() {
            if (!started || !validSession()) return
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIntent(intent)
        if (!validLaunch()) return
        LauncherActivityTracker.onCreated(this, display?.displayId ?: -1)
        setContentView(R.layout.activity_car_media)
        pageReady = true
        backdrop = MediaBackdrop(findViewById(R.id.carMediaBackdrop), findViewById(R.id.carMediaBackdropVeil))
        findViewById<View>(R.id.carMediaBack).setOnClickListener { returnHome() }
        findViewById<View>(R.id.carMediaOpenOriginal).setOnClickListener { openOriginal() }
        findViewById<View>(R.id.carMediaPrevious).setOnClickListener { send(TargetedMediaControls.Command.PREVIOUS) }
        findViewById<View>(R.id.carMediaPlayPause).setOnClickListener { send(TargetedMediaControls.Command.PLAY_PAUSE) }
        findViewById<View>(R.id.carMediaNext).setOnClickListener { send(TargetedMediaControls.Command.NEXT) }
        val cover = findViewById<View>(R.id.carMediaCoverFrame)
        cover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 16f * resources.displayMetrics.density)
            }
        }
        cover.clipToOutline = true
        val landscapeBody = findViewById<View>(R.id.carMediaBody)
        val coverContainer = landscapeBody ?: cover.parent as View
        coverContainer.addOnLayoutChangeListener { body, _, _, _, _, _, _, _, _ ->
            val availableWidth = body.width - body.paddingLeft - body.paddingRight
            val side = if (landscapeBody != null) minOf(body.height, (availableWidth * 0.34f).toInt())
                else minOf((resources.displayMetrics.density * 240).toInt(), (availableWidth * 0.62f).toInt())
            if (side <= 0) return@addOnLayoutChangeListener
            if (cover.layoutParams.width != side || cover.layoutParams.height != side) {
                cover.layoutParams = cover.layoutParams.apply {
                    width = side
                    height = side
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = returnHome()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 旧启动请求晚到时只丢弃该请求，不能关掉已属于新连接的播放页。
        if (!VirtualLauncher.ownsCarMediaLaunch(
                display?.displayId ?: -1,
                intent.getLongExtra(EXTRA_LAUNCH_SESSION, -1L),
                intent.getLongExtra(EXTRA_LAUNCH_REQUEST, -1L),
            )) return
        setIntent(intent)
        readIntent(intent)
        if (validLaunch() && started) render()
    }

    private fun readIntent(value: Intent) {
        launchSession = value.getLongExtra(EXTRA_LAUNCH_SESSION, -1L)
        launchRequest = value.getLongExtra(EXTRA_LAUNCH_REQUEST, -1L)
        targetPackage = value.getStringExtra(EXTRA_TARGET_PACKAGE)?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) } ?: NETEASE_PACKAGE
        targetLabel = if (targetPackage == NETEASE_PACKAGE) "网易云音乐" else runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPackage, 0)).toString()
        }.getOrDefault("音乐应用")
        displayed = null
        feedback = null
    }

    private fun validSession(): Boolean {
        val valid = !isFinishing && !isDestroyed && VirtualLauncher.ownsCarMedia(display?.displayId ?: -1, launchSession)
        if (!valid && !isFinishing) finish()
        return valid
    }

    private fun validLaunch(): Boolean {
        val valid = VirtualLauncher.ownsCarMediaLaunch(display?.displayId ?: -1, launchSession, launchRequest)
        if (!valid && !isFinishing) finish()
        return valid
    }

    override fun onStart() {
        super.onStart()
        if (!pageReady || !validSession()) return
        started = true
        MediaSessionsState.addListener(stateListener)
        MediaSessionsState.requestResync()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        if (pageReady && validSession()) LauncherActivityTracker.onResumed(this, display?.displayId ?: -1)
    }

    override fun onStop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        MediaSessionsState.removeListener(stateListener)
        backdrop?.clear()
        if (pageReady) {
            findViewById<TextView>(R.id.carMediaLyrics).text = ""
            findViewById<TextView>(R.id.carMediaNextLyric).text = ""
            findViewById<View>(R.id.carMediaLyricsBlock).visibility = View.GONE
            findViewById<TextView>(R.id.carMediaLyricsSource).apply { text = ""; visibility = View.GONE }
        }
        super.onStop()
    }

    override fun onDestroy() {
        MediaSessionsState.removeListener(stateListener)
        handler.removeCallbacksAndMessages(null)
        LauncherActivityTracker.onDestroyed(this)
        super.onDestroy()
    }

    private fun render() {
        if (!validSession()) return
        val now = MediaSessionsState.forPackage(targetPackage)
        displayed = now
        findViewById<TextView>(R.id.carMediaSource).text = "$targetLabel · 车载播放"
        findViewById<TextView>(R.id.carMediaOpenOriginal).text = if (targetPackage == NETEASE_PACKAGE) "打开网易云选歌" else "打开${targetLabel}选歌"
        findViewById<TextView>(R.id.carMediaTitle).text = now?.title?.takeIf { it.isNotBlank() } ?: "选择一首音乐"
        findViewById<TextView>(R.id.carMediaArtist).text = now?.artist.orEmpty()
        val elapsed = SystemClock.elapsedRealtime()
        val status = findViewById<TextView>(R.id.carMediaStatus)
        status.text =
            if (feedback != null && elapsed < feedbackUntil) feedback else when {
                !MediaSessionsState.accessGranted -> "播放信息未连接，可先打开原应用选歌；通知使用权需在手机端开启"
                now == null -> "先在${targetLabel}选歌，返回这里继续播放"
                now.playing -> "正在播放"
                else -> "已暂停"
            }
        val cover = findViewById<ImageView>(R.id.carMediaCover)
        if (artwork !== now?.art) {
            artwork = now?.art
            cover.setImageBitmap(artwork)
        }
        cover.visibility = if (artwork == null) View.INVISIBLE else View.VISIBLE
        backdrop?.bind(artwork, now?.backdropColor ?: ArtworkTone.DEFAULT)
        val lyricText = if (MediaSessionsState.accessGranted) now?.lyricTextAt(elapsed) else null
        val lyricStatus = if (MediaSessionsState.accessGranted && now != null && now.lyrics == null) {
            io.github.shaonialife.cardeck.media.OnlineLyrics.statusFor(this, now.pkg, now.title, now.artist, now.durationMs)
        } else null
        val hasLyrics = MediaSessionsState.accessGranted && now?.lyrics != null
        val currentLine = lyricText?.substringBefore('\n').orEmpty()
        val nextLine = lyricText?.substringAfter('\n', "").orEmpty()
        findViewById<TextView>(R.id.carMediaLyrics).apply {
            text = currentLine
            visibility = if (currentLine.isNotEmpty()) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.carMediaNextLyric).apply {
            text = nextLine
            visibility = if (nextLine.isNotEmpty()) View.VISIBLE else View.GONE
        }
        status.visibility = if (hasLyrics && now?.playing == true && (feedback == null || elapsed >= feedbackUntil)) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.carMediaLyricsSource).apply {
            text = if (hasLyrics) now?.lyrics?.sourceLabel else lyricStatus
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.carMediaLyricsBlock).visibility =
            if (currentLine.isNotEmpty() || nextLine.isNotEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.carMediaTitle).maxLines =
            if (hasLyrics && findViewById<View>(R.id.carMediaBody) != null) 1 else 2
        val knownDuration = now != null && now.durationMs > 0
        findViewById<ProgressBar>(R.id.carMediaProgress).apply {
            progress = if (knownDuration) (now!!.displayPositionAt(elapsed) * 1_000 / now.durationMs).toInt() else 0
            alpha = if (knownDuration) 1f else 0.35f
        }
        findViewById<TextView>(R.id.carMediaElapsed).text = if (knownDuration) formatTime(now!!.displayPositionAt(elapsed)) else "—:—"
        findViewById<TextView>(R.id.carMediaDuration).text = if (knownDuration) formatTime(now!!.durationMs) else "—:—"
        findViewById<ImageView>(R.id.carMediaPlayPause).setImageResource(if (now?.playing == true) R.drawable.ic_media_pause else R.drawable.ic_media_play)
        listOf(
            R.id.carMediaPrevious to TargetedMediaControls.Command.PREVIOUS,
            R.id.carMediaPlayPause to TargetedMediaControls.Command.PLAY_PAUSE,
            R.id.carMediaNext to TargetedMediaControls.Command.NEXT,
        ).forEach { (id, command) ->
            findViewById<View>(id).apply {
                isEnabled = !commandPending && MediaSessionsState.accessGranted && TargetedMediaControls.supported(now, command)
                alpha = if (isEnabled) 1f else 0.3f
            }
        }
    }

    private fun send(command: TargetedMediaControls.Command) {
        val expected = displayed ?: return
        if (commandPending || !validSession()) return
        commandPending = true
        render()
        val ownerDisplay = display?.displayId ?: -1
        val ownerSession = launchSession
        Bg.run("car-media-control") {
            val ok = VirtualLauncher.ownsCarMedia(ownerDisplay, ownerSession) &&
                TargetedMediaControls.send(applicationContext, expected, command)
            runOnUiThread {
                commandPending = false
                if (!started || !validSession()) return@runOnUiThread
                if (!ok) showFeedback("播放状态已变化，请稍后重试或打开原应用")
                render()
            }
        }
    }

    private fun openOriginal() {
        if (!validSession()) return
        val component = packageManager.getLaunchIntentForPackage(targetPackage)?.component
        if (component == null) { showFeedback("没有找到${targetLabel}的打开入口"); return }
        val ownerDisplay = display?.displayId ?: -1
        val ownerSession = launchSession
        val ownerNavigation = VirtualLauncher.navigationRevision()
        Bg.run("car-media-original") {
            if (!VirtualLauncher.ownsCarMedia(ownerDisplay, ownerSession)) return@run
            val ok = VirtualLauncher.launchApp(component, expectedSession = ownerSession, expectedNavigation = ownerNavigation)
            runOnUiThread {
                if (ok && !isDestroyed) finish() else if (started) showFeedback("打开失败，请返回首页重试")
            }
        }
    }

    private fun returnHome() {
        val ownerDisplay = display?.displayId ?: -1
        val ownerSession = launchSession
        if (validSession()) Bg.run("car-media-home") {
            if (VirtualLauncher.ownsCarMedia(ownerDisplay, ownerSession)) VirtualLauncher.showDashboard()
        }
        finish()
    }

    private fun showFeedback(message: String) {
        feedback = message
        feedbackUntil = SystemClock.elapsedRealtime() + 5_000
        if (started) render()
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1_000
        return if (seconds >= 3_600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3_600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }
}

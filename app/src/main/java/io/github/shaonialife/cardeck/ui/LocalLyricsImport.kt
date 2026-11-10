package io.github.shaonialife.cardeck.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.media.LocalTrackLyrics
import io.github.shaonialife.cardeck.media.LyricTrack
import io.github.shaonialife.cardeck.media.MediaSessionsState

/** The picker result belongs to the song shown when the user opened the dialog, including after recreation. */
internal class LocalLyricsImport(private val activity: AppCompatActivity, state: Bundle?) {
    private val app = activity.applicationContext
    private var pending = LyricTrack.of(
        state?.getString("lrc.pkg"), state?.getString("lrc.title"), state?.getString("lrc.artist"),
        state?.getString("lrc.mediaId"), state?.getString("lrc.album"), state?.getLong("lrc.duration") ?: 0,
    )
    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pending
        pending = null
        if (uri != null && target != null) LocalTrackLyrics.importFile(app, target, uri, ::showResult)
    }

    fun save(state: Bundle) {
        pending?.let {
            state.putString("lrc.pkg", it.pkg)
            state.putString("lrc.title", it.title)
            state.putString("lrc.artist", it.artist)
            state.putString("lrc.mediaId", it.mediaId)
            state.putString("lrc.album", it.album)
            state.putLong("lrc.duration", it.durationMs)
        }
    }

    fun open() {
        val now = MediaSessionsState.nowPlaying
        val track = now?.let { LyricTrack.of(it.pkg, it.title, it.artist, it.mediaId, it.album, it.durationMs) }
        if (!MediaSessionsState.accessGranted || track == null) {
            showResult("请先播放要配歌词的歌曲，并确认 CarDeck 已取得通知使用权、可以显示歌名和歌手。")
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("《${track.title}》的本地歌词")
            .setMessage("${track.artist}\n\n选择这首歌带时间戳的 LRC 文件。播放器和在线服务暂无歌词时，音乐主页和首页卡片会显示本地歌词。")
            .setPositiveButton("选择 LRC 文件") { _, _ ->
                pending = track
                runCatching { picker.launch(arrayOf("*/*")) }.onFailure {
                    pending = null
                    showResult("未能打开手机文件选择器。")
                }
            }
            .setNeutralButton("移除本曲歌词") { _, _ -> LocalTrackLyrics.remove(app, track, ::showResult) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showResult(message: String) { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
}

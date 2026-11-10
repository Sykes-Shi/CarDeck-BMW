package io.github.shaonialife.cardeck.media

import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/** 点击时重新核对包名和会话令牌；全局媒体键可能误操作另一个音源，故不作兜底。 */
object TargetedMediaControls {
    enum class Command { PLAY_PAUSE, PREVIOUS, NEXT }

    fun supported(now: MediaSessionsState.NowPlaying?, command: Command): Boolean {
        if (now?.sessionToken == null) return false
        val required = when (command) {
            Command.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            Command.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
            Command.PLAY_PAUSE -> if (now.playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY
        }
        return now.actions and required != 0L ||
            (command == Command.PLAY_PAUSE && now.actions and PlaybackState.ACTION_PLAY_PAUSE != 0L)
    }

    /** 调用方在后台执行，返回值只表示命令已提交，真实播放态仍由会话回调驱动。 */
    fun send(context: Context, expected: MediaSessionsState.NowPlaying, command: Command): Boolean =
        runCatching {
            if (!MediaSessionsState.accessGranted || !supported(expected, command)) return false
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val controller = manager.getActiveSessions(MediaListenerService.componentName(context)).firstOrNull {
                it.packageName == expected.pkg && it.sessionToken == expected.sessionToken
            } ?: return false
            val state = controller.playbackState ?: return false
            val current = expected.copy(playing = state.state == PlaybackState.STATE_PLAYING, actions = state.actions)
            if (!supported(current, command)) return false
            when (command) {
                Command.PREVIOUS -> controller.transportControls.skipToPrevious()
                Command.NEXT -> controller.transportControls.skipToNext()
                Command.PLAY_PAUSE -> if (current.playing) controller.transportControls.pause() else controller.transportControls.play()
            }
            MediaSessionsState.requestResync()
            true
        }.getOrDefault(false)
}

package com.liquidglass.app.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 媒体通知按钮事件接收器。
 *
 * 通知 RemoteViews 中 [上一首/播放暂停/下一首/进度条拖动] 通过 PendingIntent 触发本 Receiver，
 * 由本 Receiver 转发到 [MusicControllerManager] 控制实际播放。
 *
 * 注册在 AndroidManifest.xml，exported=false 仅本应用可触发。
 */
class MediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PREV -> MusicControllerManager.previous()
            ACTION_NEXT -> MusicControllerManager.next()
            ACTION_PLAY_PAUSE -> MusicControllerManager.playPause()
            ACTION_SEEK -> {
                // SeekBar 拖动：progress 范围 0-1000，按比例换算到实际播放位置
                val progress = intent.getIntExtra(EXTRA_SEEK_PROGRESS, -1)
                val durationMs = intent.getLongExtra(EXTRA_SEEK_DURATION, 0L)
                if (progress >= 0 && durationMs > 0) {
                    val targetMs = (durationMs * progress / 1000L)
                    MusicControllerManager.seekTo(targetMs)
                }
            }
        }
    }

    companion object {
        const val ACTION_PREV = "com.liquidglass.app.MEDIA_PREV"
        const val ACTION_NEXT = "com.liquidglass.app.MEDIA_NEXT"
        const val ACTION_PLAY_PAUSE = "com.liquidglass.app.MEDIA_PLAY_PAUSE"
        const val ACTION_SEEK = "com.liquidglass.app.MEDIA_SEEK"
        const val EXTRA_SEEK_PROGRESS = "seek_progress"
        const val EXTRA_SEEK_DURATION = "seek_duration"
    }
}

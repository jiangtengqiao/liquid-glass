# -*- coding: utf-8 -*-
"""第四轮：PlaybackService 简化（去 BitmapLoader）+ MainActivity 补 onOpenSong + background import"""
import io, os

BASE = r'E:\Kun\default_workspace\android\app\src\main\java\com\kun\glasssuite'
def read(p): return io.open(os.path.join(BASE, p), encoding='utf-8').read()
def write(p, s): io.open(os.path.join(BASE, p), 'w', encoding='utf-8', newline='').write(s)

# 1) PlaybackService 整体重写为简化版
playback = '''package com.kun.glasssuite.player

import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.PlayerNotificationManager
import com.kun.glasssuite.R

/**
 * 媒体会话服务：锁屏媒体通知（标题/艺术家/进度/播放控制）+ 状态栏常驻。
 * 封面加载由 MediaSession 元数据携带（默认通知布局）。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var notificationManager: PlayerNotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerManager.player

        mediaSession = MediaSession.Builder(this, player).build()

        notificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setChannelNameResourceId(R.string.app_name)
            .setChannelDescriptionResourceId(R.string.app_name)
            .setSmallIconResourceId(R.drawable.ic_notification)
            .build()
            .also {
                it.setPlayer(player)
                it.setPriority(PlayerNotificationManager.PRIORITY_DEFAULT)
            }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlayerManager.player
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        notificationManager?.setPlayer(null)
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "playback"
    }
}
'''
write('player/PlaybackService.kt', playback)
print('PlaybackService rewritten')

# 2) MainActivity：PlaylistScreen 补 onOpenSong
p = 'MainActivity.kt'
s = read(p)
old = """                        PlaylistScreen(
                            playlistId = it.arguments?.getLong("id") ?: 0L,
                            onBack = { nav.popBackStack() },
                            onPlayQueue = { songs, start ->
                                PlayerManager.playQueue(songs, start)
                                PlayerManager.startService()
                            },
                        )"""
new = """                        PlaylistScreen(
                            playlistId = it.arguments?.getLong("id") ?: 0L,
                            onBack = { nav.popBackStack() },
                            onPlayQueue = { songs, start ->
                                PlayerManager.playQueue(songs, start)
                                PlayerManager.startService()
                            },
                            onOpenSong = { id -> nav.navigate("song/$id") },
                        )"""
if old in s:
    s = s.replace(old, new)
    print('MainActivity PlaylistScreen onOpenSong added')
else:
    print('!! MainActivity PlaylistScreen block not matched')
write(p, s)

# 3) AnnouncementScreen：加 background import
p = 'ui/announcement/AnnouncementScreen.kt'
s = read(p)
if 'import androidx.compose.foundation.background' not in s:
    s = s.replace('import androidx.compose.foundation.clickable',
                  'import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable')
    print('AnnouncementScreen background import added')
write(p, s)

# 4) GitHubSearchScreen：圆点块精确文本替换
p = 'ui/github/GitHubSearchScreen.kt'
s = read(p)
print('--- GitHub 176 上下文 ---')
lines = s.split('\n')
for i in range(170, 182):
    print(f'{i+1}: {lines[i]}')
write(p, s)

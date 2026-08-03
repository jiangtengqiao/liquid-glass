# -*- coding: utf-8 -*-
"""第二轮综合修复：代码级错误一次性处理"""
import io, os

BASE = r'E:\Kun\default_workspace\android\app\src\main\java\com\kun\glasssuite'

def read(p):
    return io.open(os.path.join(BASE, p), encoding='utf-8').read()

def write(p, s):
    io.open(os.path.join(BASE, p), 'w', encoding='utf-8', newline='').write(s)

# ---------- 1) App.kt：createNotificationChannel 拆行 ----------
p = 'App.kt'
s = read(p)
old = """        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            .createNotificationChannel(channel)
    }"""
new = """        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }"""
assert old in s, 'App.kt 块未匹配'
s = s.replace(old, new)
write(p, s)
print('App.kt ok')

# ---------- 2) PlaybackService.kt：去掉 Coil，改用简易 BitmapLoader ----------
p = 'player/PlaybackService.kt'
s = read(p)
s = s.replace("""import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.PlayerNotificationManager
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.kun.glasssuite.App
import com.kun.glasssuite.R
import io.coil.Coil
import io.coil.imageLoader
import io.coil.request.ImageRequest
import io.coil.size.Size
""",
"""import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.PlayerNotificationManager
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.kun.glasssuite.App
import com.kun.glasssuite.R
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
""")
old = """        notificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setChannelNameResourceId(R.string.app_name)
            .setChannelDescriptionResourceId(R.string.app_name)
            .setSmallIconResourceId(R.drawable.ic_notification)
            .setImageLoader(object : PlayerNotificationManager.BitmapLoader {
                override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
                    val future = SettableFuture.create<Bitmap>()
                    val request = ImageRequest.Builder(this@PlaybackService)
                        .data(uri)
                        .allowHardware(false)
                        .size(Size.ORIGINAL)
                        .target(
                            onSuccess = { result -> future.set(result) },
                            onError = { throwable ->
                                future.setException(throwable ?: RuntimeException("load failed"))
                            }
                        )
                        .build()
                    Coil.imageLoader(this@PlaybackService).enqueue(request)
                    return future
                }

                override fun clear() = Unit
            })
            .build()"""
new = """        val loader = object : PlayerNotificationManager.BitmapLoader {
            private val executor = Executors.newSingleThreadExecutor()

            override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
                val future = SettableFuture.create<Bitmap>()
                executor.execute {
                    val bmp = runCatching {
                        val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.setRequestProperty("User-Agent", "GlassSuite")
                        conn.inputStream.use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                    if (bmp != null) future.set(bmp) else future.setException(RuntimeException("load failed"))
                }
                return future
            }

            override fun clear() = Unit
        }
        notificationManager = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setChannelNameResourceId(R.string.app_name)
            .setChannelDescriptionResourceId(R.string.app_name)
            .setSmallIconResourceId(R.drawable.ic_notification)
            .setBitmapLoader(loader)
            .build()"""
assert old in s, 'PlaybackService 块未匹配'
s = s.replace(old, new)
write(p, s)
print('PlaybackService ok')

# ---------- 3) AnnouncementScreen.kt：删除错误的 Modifier.background 扩展 ----------
p = 'ui/announcement/AnnouncementScreen.kt'
s = read(p)
old = """
private fun androidx.compose.ui.Modifier.background(color: Color) =
    this.then(androidx.compose.foundation.background(color))
"""
if old in s:
    s = s.replace(old, '')
write(p, s)
print('AnnouncementScreen ok')

# ---------- 4) GitHubSearchScreen.kt：重写语言圆点 Box ----------
p = 'ui/github/GitHubSearchScreen.kt'
s = read(p)
old = """                    Box(
                        Modifier
                            .size(10.dp)
                            .padding(0.dp)
                        ) {
                            androidx.compose.foundation.background(
                                androidx.compose.foundation.shape.CircleShape,
                                color = Color(0xFF3D7FFF)
                            )
                        }"""
new = """                    Box(
                        Modifier
                            .size(10.dp)
                            .background(Color(0xFF3D7FFF), androidx.compose.foundation.shape.CircleShape)
                    )"""
assert old in s, 'GitHub 圆点块未匹配'
s = s.replace(old, new)
# 结尾 clickable 残留
old2 = """        androidx.compose.foundation.clickable {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.htmlUrl)))
            }
        }.let { }
    }"""
if old2 in s:
    s = s.replace(old2, """        val ctx = context
        Modifier.clickable {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.htmlUrl)))
            }
        }
    }""")
# 若上述未命中，检查是否有其它残留形式
if 'androidx.compose.foundation.clickable' in s:
    print('  !! 仍有 clickable 残留')
write(p, s)
print('GitHubSearchScreen ok')

# ---------- 5) DetailScreens.kt：onDispose 常规写法 ----------
p = 'ui/detail/DetailScreens.kt'
s = read(p)
s = s.replace("""    androidx.compose.runtime.DisposableEffect(player) {
        androidx.compose.runtime.onDispose { player.release() }
    }""",
"""    DisposableEffect(player) {
        onDispose { player.release() }
    }""")
# 确保 import
if 'import androidx.compose.runtime.DisposableEffect' not in s:
    s = s.replace('import androidx.compose.runtime.Composable',
                  'import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.DisposableEffect')
write(p, s)
print('DetailScreens ok')

# ---------- 6) PlayerManager.kt：Intent.ACTION_PLAY 不存在 ----------
p = 'player/PlayerManager.kt'
s = read(p)
old = """        val intent = Intent(appContext, PlaybackService::class.java).apply {
            action = Intent.ACTION_PLAY
        }
        runCatching {
            appContext.startForegroundService(intent)
        }"""
new = """        val intent = Intent(appContext, PlaybackService::class.java)
        runCatching {
            appContext.startForegroundService(intent)
        }"""
assert old in s, 'PlayerManager 块未匹配'
s = s.replace(old, new)
write(p, s)
print('PlayerManager ok')

print('FIX2 DONE')

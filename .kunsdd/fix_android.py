# -*- coding: utf-8 -*-
import io, os

BASE = r'E:\Kun\default_workspace\android\app\src\main\java\com\kun\glasssuite'
os.chdir(BASE)

def patch(p, pairs):
    s = io.open(p, encoding='utf-8').read()
    for old, new in pairs:
        if old not in s:
            print(f"!! MISS in {p}: {old[:70]!r}")
            continue
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)

# 1) Components.kt: MiniPlayer 需要 getValue
patch('ui/common/Components.kt', [
    ("import androidx.compose.runtime.collectAsState\n",
     "import androidx.compose.runtime.collectAsState\nimport androidx.compose.runtime.getValue\n"),
])

# 2) LoginScreen.kt: 用 rememberCoroutineScope
patch('ui/login/LoginScreen.kt', [
    ("import androidx.compose.runtime.remember\nimport androidx.compose.runtime.setValue",
     "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\nimport androidx.compose.runtime.setValue\nimport kotlinx.coroutines.launch"),
    ("    var busy by remember { mutableStateOf(false) }\n",
     "    var busy by remember { mutableStateOf(false) }\n    val scope = rememberCoroutineScope()\n"),
    ("kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {",
     "scope.launch {"),
])

# 3) PlaylistScreen.kt
patch('ui/playlist/PlaylistScreen.kt', [
    ("import androidx.compose.runtime.setValue",
     "import androidx.compose.runtime.rememberCoroutineScope\nimport androidx.compose.runtime.setValue\nimport kotlinx.coroutines.launch"),
    ("    var subscribed by remember { mutableStateOf(false) }\n",
     "    var subscribed by remember { mutableStateOf(false) }\n    val scope = rememberCoroutineScope()\n"),
    ("                            val resp = runCatching { Api.service.playlistSubscribe(playlistId, t) }.getOrNull()\n                            if (resp?.code == 200) subscribed = !subscribed",
     "                            scope.launch {\n                                val resp = runCatching { Api.service.playlistSubscribe(playlistId, t) }.getOrNull()\n                                if (resp?.code == 200) subscribed = !subscribed\n                            }"),
])
# SongRow 命名参数（PlaylistScreen + LikedScreen 两处相同块）
s = io.open('ui/playlist/PlaylistScreen.kt', encoding='utf-8').read()
s = s.replace("SongRow(song) {\n                        onPlayQueue(songs, songs.indexOf(song).coerceAtLeast(0))\n                    }",
              "SongRow(song = song, onClick = {\n                        onPlayQueue(songs, songs.indexOf(song).coerceAtLeast(0))\n                    })")
io.open('ui/playlist/PlaylistScreen.kt', 'w', encoding='utf-8', newline='').write(s)

# 4) SearchScreen.kt
patch('ui/search/SearchScreen.kt', [
    ("SongRow(s) {\n                        PlayerManager.playQueue(songs, songs.indexOf(s))\n                        PlayerManager.startService()\n                        actions.onOpenPlayer()\n                    }",
     "SongRow(song = s, onClick = {\n                        PlayerManager.playQueue(songs, songs.indexOf(s))\n                        PlayerManager.startService()\n                        actions.onOpenPlayer()\n                    })"),
])

# 5) DetailScreens.kt
patch('ui/detail/DetailScreens.kt', [
    ("SongRow(song, i) { onPlayQueue(hotSongs, i) }",
     "SongRow(song = song, index = i, onClick = { onPlayQueue(hotSongs, i) })"),
    ("SongRow(song, i) { onPlayQueue(songs, i) }",
     "SongRow(song = song, index = i, onClick = { onPlayQueue(songs, i) })"),
    ("    val player = remember {\n        ExoPlayer.Builder(androidx.compose.ui.platform.LocalContext.current)\n            .build()\n    }",
     "    val mvContext = androidx.compose.ui.platform.LocalContext.current\n    val player = remember(mvContext) {\n        ExoPlayer.Builder(mvContext)\n            .build()\n    }"),
    ("    DisposableEffectCompat {\n        onDispose { player.release() }\n    }",
     "    androidx.compose.runtime.DisposableEffect(player) {\n        androidx.compose.runtime.onDispose { player.release() }\n    }"),
    ("\n@Composable\nprivate fun DisposableEffectCompat(onDispose: () -> Unit) {\n    androidx.compose.runtime.DisposableEffect(Unit) {\n        androidx.compose.runtime.onDispose(onDispose)\n    }\n}\n",
     "\n"),
])

# 6) PlayerScreen.kt
patch('ui/player/PlayerScreen.kt', [
    ("                val isLiked = song?.let { song.id in liked } ?: false",
     "                val curSong = song\n                val isLiked = curSong?.let { s -> s.id in liked } ?: false"),
    ("private fun LyricList(",
     "private fun androidx.compose.foundation.layout.ColumnScope.LyricList("),
])

# 7) GitHubSearchScreen.kt
patch('ui/github/GitHubSearchScreen.kt', [
    ("import androidx.compose.foundation.layout.Arrangement",
     "import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Arrangement"),
])
s = io.open('ui/github/GitHubSearchScreen.kt', encoding='utf-8').read()
old_dot = """                    Box(
                        Modifier
                            .size(10.dp)
                            .padding(0.dp)
                        ) {
                            androidx.compose.foundation.background(
                                androidx.compose.foundation.shape.CircleShape,
                                color = Color(0xFF3D7FFF)
                            )
                        }"""
new_dot = """                    Box(
                        Modifier
                            .size(10.dp)
                            .background(Color(0xFF3D7FFF), androidx.compose.foundation.shape.CircleShape)
                    )"""
if old_dot in s:
    s = s.replace(old_dot, new_dot)
else:
    # 兜底：任意残留的旧式圆点
    import re
    s = re.sub(r"Box\(\s*Modifier\s*\.size\(10\.dp\)\s*\.padding\(0\.dp\)\s*\)\s*\{\s*androidx\.compose\.foundation\.background\([^)]*\)\s*\}", 
               "Box(Modifier.size(10.dp).background(Color(0xFF3D7FFF), androidx.compose.foundation.shape.CircleShape))", s)
old_click = """        androidx.compose.foundation.clickable {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.htmlUrl)))
            }
        }.let { }
    }"""
new_click = """        val ctx = context
        Modifier.clickable {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(repo.htmlUrl)))
            }
        }
    }"""
if old_click in s:
    s = s.replace(old_click, new_click)
io.open('ui/github/GitHubSearchScreen.kt', 'w', encoding='utf-8', newline='').write(s)

# 8) SettingsScreen.kt: AlertDialog import
patch('ui/settings/SettingsScreen.kt', [
    ("import androidx.compose.material3.OutlinedButton",
     "import androidx.compose.material3.AlertDialog\nimport androidx.compose.material3.OutlinedButton"),
])

# 9) AnnouncementScreen.kt
patch('ui/announcement/AnnouncementScreen.kt', [
    ("import androidx.compose.runtime.remember\n",
     "import androidx.compose.runtime.remember\nimport androidx.compose.runtime.rememberCoroutineScope\n"),
])

# 10) HomeScreen.kt
patch('ui/home/HomeScreen.kt', [
    ("import androidx.compose.foundation.layout.Arrangement",
     "import androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Arrangement"),
    (".androidx.compose.foundation.clickable { onOpenPlaylist(item.id) }",
     ".clickable { onOpenPlaylist(item.id) }"),
])

print("ALL PATCHED")

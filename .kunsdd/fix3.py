# -*- coding: utf-8 -*-
"""第三轮：剩余错误修复（GitHub 圆点 / Detail onDispose / PlayerManager ACTION_PLAY / MainActivity 核对）"""
import io, os, re

BASE = r'E:\Kun\default_workspace\android\app\src\main\java\com\kun\glasssuite'
def read(p): return io.open(os.path.join(BASE, p), encoding='utf-8').read()
def write(p, s): io.open(os.path.join(BASE, p), 'w', encoding='utf-8', newline='').write(s)

# 1) GitHubSearchScreen：圆点块正则替换 + import 检查
p = 'ui/github/GitHubSearchScreen.kt'
s = read(p)
pat = re.compile(r"Box\(\s*Modifier\s*\.size\(10\.dp\)\s*\.padding\(0\.dp\)\s*\)\s*\{\s*androidx\.compose\.foundation\.background\([^)]*\)\s*\}")
s2, n = pat.subn("Box(Modifier.size(10.dp).background(Color(0xFF3D7FFF), androidx.compose.foundation.shape.CircleShape))", s)
if n == 0:
    # 更宽松：任何含 androidx.compose.foundation.background( 的 Box 调用
    pat2 = re.compile(r"Box\(\s*Modifier\s*\.size\(10\.dp\)[\s\S]*?androidx\.compose\.foundation\.background\([\s\S]*?\}\)")
    s2, n = pat2.subn("Box(Modifier.size(10.dp).background(Color(0xFF3D7FFF), androidx.compose.foundation.shape.CircleShape))", s)
print('GitHub dot replaced:', n)
if not re.search(r'import androidx\.compose\.foundation\.background', s2):
    s2 = s2.replace('import androidx.compose.foundation.clickable', 'import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable')
if not re.search(r'import androidx\.compose\.foundation\.shape\.CircleShape', s2):
    s2 = s2.replace('import androidx.compose.foundation.layout.Arrangement',
                    'import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.shape.CircleShape')
write(p, s2)

# 2) DetailScreens：onDispose 常规写法
p = 'ui/detail/DetailScreens.kt'
s = read(p)
s = s.replace("import androidx.compose.runtime.LaunchedEffect",
              "import androidx.compose.runtime.DisposableEffect\nimport androidx.compose.runtime.LaunchedEffect")
s = s.replace("""    androidx.compose.runtime.DisposableEffect(player) {
        androidx.compose.runtime.onDispose { player.release() }
    }""",
              """    DisposableEffect(player) {
        onDispose { player.release() }
    }""")
write(p, s)
print('DetailScreens ok')

# 3) PlayerManager：ACTION_PLAY 常量不存在
p = 'player/PlayerManager.kt'
s = read(p)
s = s.replace("""        val intent = Intent(appContext, PlaybackService::class.java).apply {
            action = Intent.ACTION_PLAY
        }
        runCatching {
            appContext.startForegroundService(intent)
        }""",
              """        val intent = Intent(appContext, PlaybackService::class.java)
        runCatching {
            appContext.startForegroundService(intent)
        }""")
write(p, s)
print('PlayerManager ok')

# 4) MainActivity：确认 ArtistScreen 调用
p = 'MainActivity.kt'
s = read(p)
if 'onOpenSong = { id -> nav.navigate("song/$id") }' in s:
    # 只可能是 MainActions（Music 模块）里的 onOpenSong——检查是否在 ArtistScreen 调用块中
    idx = s.find('ArtistScreen(')
    seg = s[idx:idx+500] if idx >= 0 else ''
    if 'onOpenSong' in seg:
        s = s.replace("""                            onOpenAlbum = { id -> nav.navigate("album/$id") },
                            onOpenSong = { id -> nav.navigate("song/$id") },""",
                      """                            onOpenAlbum = { id -> nav.navigate("album/$id") },
                            onPlayQueue = { songs, start ->
                                PlayerManager.playQueue(songs, start)
                                PlayerManager.startService()
                            },""")
        print('MainActivity ArtistScreen fixed')
    else:
        print('MainActivity onOpenSong 仅在 MainActions 中（合法）')
else:
    print('MainActivity 无 onOpenSong')
write(p, s)

print('DONE')

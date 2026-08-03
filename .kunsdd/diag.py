# -*- coding: utf-8 -*-
"""诊断与修复脚本：重复文件、jar 缺失、残留调用"""
import os, io, glob

BASE = r'E:\Kun\default_workspace\android'

print("=== 1) 重复 MainActivity/App 文件 ===")
for f in glob.glob(BASE + r'\app\src\**\MainActivity.kt', recursive=True):
    print(' ', f)
for f in glob.glob(BASE + r'\app\src\**\App.kt', recursive=True):
    print(' ', f)

print("=== 2) coil / media3-ui jar 物理文件 ===")
coil_jars = glob.glob(os.path.expanduser(r'~\.gradle\caches\modules-2\files-2.1\io.coil-kt\coil\2.7.0\**\*.jar'), recursive=True)
print('  coil jars:', coil_jars if coil_jars else 'NONE!')
ui_jars = glob.glob(os.path.expanduser(r'~\.gradle\caches\modules-2\files-2.1\androidx.media3\media3-ui\1.4.1\**\*.jar'), recursive=True)
print('  media3-ui jars:', ui_jars if ui_jars else 'NONE!')

print("=== 3) MainActivity 138-146 行（ArtistScreen 调用） ===")
p = os.path.join(BASE, 'app', 'src', 'main', 'java', 'com', 'kun', 'glasssuite', 'MainActivity.kt')
lines = io.open(p, encoding='utf-8').read().split('\n')
for i in range(136, 147):
    print(f'  {i+1}: {lines[i]}')

print("=== 4) AnnouncementScreen 105-110 / GitHubSearchScreen 172-178 ===")
p2 = os.path.join(BASE, 'app', 'src', 'main', 'java', 'com', 'kun', 'glasssuite', 'ui', 'announcement', 'AnnouncementScreen.kt')
ls = io.open(p2, encoding='utf-8').read().split('\n')
for i in range(104, 111):
    print(f'  A{i+1}: {ls[i]}')
p3 = os.path.join(BASE, 'app', 'src', 'main', 'java', 'com', 'kun', 'glasssuite', 'ui', 'github', 'GitHubSearchScreen.kt')
ls = io.open(p3, encoding='utf-8').read().split('\n')
for i in range(171, 178):
    print(f'  G{i+1}: {ls[i]}')

print("=== 5) DetailScreens 388-395 ===")
p4 = os.path.join(BASE, 'app', 'src', 'main', 'java', 'com', 'kun', 'glasssuite', 'ui', 'detail', 'DetailScreens.kt')
ls = io.open(p4, encoding='utf-8').read().split('\n')
for i in range(387, 396):
    print(f'  D{i+1}: {ls[i]}')

print("=== 6) PlayerManager 340-346 (ACTION_PLAY) ===")
p5 = os.path.join(BASE, 'app', 'src', 'main', 'java', 'com', 'kun', 'glasssuite', 'player', 'PlayerManager.kt')
ls = io.open(p5, encoding='utf-8').read().split('\n')
for i in range(339, 346):
    print(f'  P{i+1}: {ls[i]}')

package com.liquidglass.app.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * 本地音乐扫描：读取设备 MediaStore.Audio 中的音乐文件。
 *
 * Android 13+ 需 READ_MEDIA_AUDIO；旧版本靠 READ_EXTERNAL_STORAGE。
 * 返回的 [Song] 用 contentUri 作为 streamUrl，封面用 albumart uri。
 */
object LocalMusicScanner {

    fun scan(context: Context): List<Song> {
        val result = mutableListOf<Song>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} = 1"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE LOCALIZED ASC"

        val cursor = try {
            context.contentResolver.query(collection, projection, selection, null, sortOrder)
        } catch (_: Exception) {
            return emptyList()
        } ?: return emptyList()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val contentUri = ContentUris.withAppendedId(collection, id)
                val albumId = c.getLong(albumIdCol)
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"), albumId
                ).toString()
                result.add(
                    Song(
                        id = "local_$id",
                        title = c.getString(titleCol) ?: "未知歌曲",
                        artist = c.getString(artistCol) ?: "未知艺人",
                        album = c.getString(albumCol) ?: "",
                        durationMs = c.getLong(durCol),
                        coverUri = albumArtUri,
                        source = Source.LOCAL,
                        streamUrl = contentUri.toString(),
                        fee = 0
                    )
                )
            }
        }
        return result
    }
}

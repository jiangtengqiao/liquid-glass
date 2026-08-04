package com.liquidglass.desktop.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 网易云音乐业务接口（桌面版，从安卓端移植）。
 *
 * 桌面版改动：
 * - 移除 Context 参数，SessionStore 直接调用（Preferences 全局单例）
 * - android.util.Log → java.util.logging.Logger
 *
 * 覆盖：搜索、歌曲播放URL、歌词(逐字yrc+逐行lrc+翻译)、用户歌单、歌单详情、VIP状态、
 *       排行榜、每日推荐、推荐歌单、歌单分类、私人FM、新歌速递、新碟、MV、DJ电台、相似歌曲。
 */
object NetEaseApi {

    private val logger = Logger.getLogger("NetEaseApi")

    private fun checkResponse(json: JSONObject, tag: String): Boolean {
        val code = json.optInt("code", -1)
        if (code != 200) {
            logger.log(Level.WARNING, "$tag: code=$code, msg=${json.optString("message")}")
            return false
        }
        return true
    }

    // ───────────────────────── 热搜词 ─────────────────────────

    /** 网易云热搜榜（无需登录） */
    suspend fun hotSearch(): List<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply { put("type", 1111) }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/search/hot", payload)
            if (!checkResponse(json, "hotSearch")) return@withContext emptyList()
            val hots = json.optJSONObject("result")?.optJSONArray("hots")
                ?: return@withContext emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until hots.length()) {
                val w = hots.getJSONObject(i).optString("first")
                if (w.isNotBlank()) list.add(w)
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "hotSearch failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 搜索 ─────────────────────────

    suspend fun search(keyword: String, limit: Int = 30, offset: Int = 0): SearchResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("s", keyword)
                    put("type", 1)
                    put("limit", limit)
                    put("offset", offset)
                    put("total", true)
                }.toString()
                val json = NetEaseApiClient.weapiPost("/weapi/cloudsearch/get/web", payload)
                if (!checkResponse(json, "search")) return@withContext SearchResult(emptyList(), 0)
                val result = json.optJSONObject("result") ?: return@withContext SearchResult(emptyList(), 0)
                val songs = result.optJSONArray("songs") ?: return@withContext SearchResult(emptyList(), 0)
                val list = mutableListOf<Song>()
                for (i in 0 until songs.length()) {
                    val s = songs.getJSONObject(i)
                    val artists = s.optJSONArray("ar")?.let { arr ->
                        buildString { for (j in 0 until arr.length()) { if (j > 0) append("/"); append(arr.getJSONObject(j).optString("name")) } }
                    } ?: ""
                    val album = s.optJSONObject("al")?.optString("name") ?: ""
                    val cover = s.optJSONObject("al")?.optString("picUrl") ?: ""
                    list.add(
                        Song(
                            id = s.optLong("id").toString(),
                            title = s.optString("name"),
                            artist = artists,
                            album = album,
                            durationMs = s.optLong("dt", 0L),
                            coverUrl = cover,
                            source = Source.NETEASE,
                            fee = s.optInt("fee", 0)
                        )
                    )
                }
                SearchResult(list, result.optInt("songCount", list.size))
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "search failed: ${e.message}")
                SearchResult(emptyList(), 0)
            }
        }

    // ───────────────────────── 歌曲播放URL ─────────────────────────

    /**
     * 取歌曲可播放 URL。
     * @param level "standard" 128kbps / "exhigh" 320kbps / "lossless" FLAC(VIP) / "hires" Hi-Res(VIP)
     */
    suspend fun songUrl(songId: String, level: String = "exhigh"): String = withContext(Dispatchers.IO) {
        try {
            val idsArr = JSONArray().put(songId.toLong())
            val payload = JSONObject().apply {
                put("ids", idsArr.toString())
                put("level", level)
                put("encodeType", if (level == "lossless" || level == "hires") "flac" else "aac")
                put("csrf_token", "")
            }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/song/enhance/player/url/v1", payload)
            if (!checkResponse(json, "songUrl")) return@withContext ""
            val data = json.optJSONArray("data") ?: return@withContext ""
            if (data.length() == 0) return@withContext ""
            val url = data.getJSONObject(0).optString("url")
            if (url.isBlank() && level == "lossless") {
                return@withContext songUrl(songId, "exhigh")
            }
            url ?: ""
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "songUrl failed for $songId: ${e.message}")
            ""
        }
    }

    // ───────────────────────── 歌词 ─────────────────────────

    /** 取歌词：优先逐字 yrc，回退逐行 lrc，附带翻译 */
    suspend fun lyrics(songId: String): Lyrics = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", songId.toLong())
                put("lv", -1)
                put("tv", -1)
                put("kv", -1)
            }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/song/lyric", payload)
            if (!checkResponse(json, "lyrics")) return@withContext Lyrics()

            val yrcText = json.optJSONObject("yrc")?.optString("lyric") ?: ""
            val yrcTrans = json.optJSONObject("ytlrc")?.optString("lyric") ?: ""
            val lrcText = json.optJSONObject("lrc")?.optString("lyric") ?: ""
            val lrcTrans = json.optJSONObject("tlyric")?.optString("lyric") ?: ""

            val yrcLines = if (yrcText.isNotBlank()) LyricsParser.parseYrc(yrcText, yrcTrans) else emptyList()
            val lrcLines = if (lrcText.isNotBlank()) LyricsParser.parseLrc(lrcText, lrcTrans) else emptyList()

            Lyrics(yrcLines = yrcLines, lrcLines = lrcLines)
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "lyrics failed for $songId: ${e.message}")
            Lyrics()
        }
    }

    // ───────────────────────── 用户歌单 ─────────────────────────

    suspend fun userPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        if (!SessionStore.isLoggedIn()) {
            logger.log(Level.WARNING, "userPlaylists: not logged in")
            return@withContext emptyList()
        }
        val uid = SessionStore.getUserId()
        if (uid <= 0L) {
            logger.log(Level.WARNING, "userPlaylists: uid=$uid <= 0, skipping")
            return@withContext emptyList()
        }
        try {
            val payload = JSONObject().apply {
                put("uid", uid)
                put("limit", 30)
                put("offset", 0)
                put("includeVideo", true)
                put("csrf_token", "")
            }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/user/playlist", payload)
            if (!checkResponse(json, "userPlaylists")) return@withContext emptyList()
            val arr = json.optJSONArray("playlist") ?: return@withContext emptyList()
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                list.add(
                    Playlist(
                        id = p.optLong("id").toString(),
                        name = p.optString("name"),
                        coverUrl = p.optString("coverImgUrl"),
                        trackCount = p.optInt("trackCount", 0),
                        creator = p.optJSONObject("creator")?.optString("nickname") ?: ""
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "userPlaylists failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 歌单曲目 ─────────────────────────

    suspend fun playlistTracks(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", playlistId.toLong())
                put("n", 1000)
                put("s", 8)
                put("csrf_token", "")
            }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/v6/playlist/detail", payload)
            if (!checkResponse(json, "playlistTracks")) return@withContext emptyList()
            val tracks = json.optJSONObject("playlist")?.optJSONArray("tracks")
                ?: return@withContext emptyList()
            val list = mutableListOf<Song>()
            for (i in 0 until tracks.length()) {
                val s = tracks.getJSONObject(i)
                val artists = s.optJSONArray("ar")?.let { arr ->
                    buildString { for (j in 0 until arr.length()) { if (j > 0) append("/"); append(arr.getJSONObject(j).optString("name")) } }
                } ?: ""
                list.add(
                    Song(
                        id = s.optLong("id").toString(),
                        title = s.optString("name"),
                        artist = artists,
                        album = s.optJSONObject("al")?.optString("name") ?: "",
                        durationMs = s.optLong("dt", 0L),
                        coverUrl = s.optJSONObject("al")?.optString("picUrl") ?: "",
                        source = Source.NETEASE,
                        fee = s.optInt("fee", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "playlistTracks failed for $playlistId: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 排行榜（榜单） ─────────────────────────

    /** 所有榜单列表（无需登录） */
    suspend fun toplist(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().toString()
            val json = NetEaseApiClient.weapiPost("/weapi/toplist/detail", payload)
            if (!checkResponse(json, "toplist")) return@withContext emptyList()
            val arr = json.optJSONArray("list") ?: return@withContext emptyList()
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                list.add(
                    Playlist(
                        id = p.optLong("id").toString(),
                        name = p.optString("name"),
                        coverUrl = p.optString("coverImgUrl"),
                        trackCount = p.optInt("trackCount", 0),
                        creator = p.optString("description")
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "toplist failed: ${e.message}")
            emptyList()
        }
    }

    /** 某榜单下的歌曲列表。复用 [playlistTracks] */
    suspend fun toplistTracks(id: String): List<Song> = playlistTracks(id)

    // ───────────────────────── 每日推荐歌曲 ─────────────────────────

    suspend fun recommendSongs(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("limit", 30)
                put("offset", 0)
                put("total", true)
                put("csrf_token", "")
            }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/v3/discovery/recommend/songs", payload)
            if (!checkResponse(json, "recommendSongs")) return@withContext emptyList()
            val arr = json.optJSONArray("recommend") ?: return@withContext emptyList()
            val list = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val artists = s.optJSONArray("ar")?.let { a ->
                    buildString { for (j in 0 until a.length()) { if (j > 0) append("/"); append(a.getJSONObject(j).optString("name")) } }
                } ?: ""
                val al = s.optJSONObject("al")
                list.add(
                    Song(
                        id = s.optLong("id").toString(),
                        title = s.optString("name"),
                        artist = artists,
                        album = al?.optString("name") ?: "",
                        durationMs = s.optLong("dt", 0L),
                        coverUrl = al?.optString("picUrl") ?: "",
                        source = Source.NETEASE,
                        fee = s.optInt("fee", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "recommendSongs failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 推荐歌单 ─────────────────────────

    suspend fun recommendPlaylists(): List<Playlist> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("limit", 12)
                put("offset", 0)
                put("total", true)
                put("csrf_token", "")
            }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/personalized/playlist", payload)
            if (!checkResponse(json, "recommendPlaylists")) return@withContext emptyList()
            val arr = json.optJSONArray("result") ?: return@withContext emptyList()
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                list.add(
                    Playlist(
                        id = p.optLong("id").toString(),
                        name = p.optString("name"),
                        coverUrl = p.optString("picUrl"),
                        trackCount = p.optInt("trackCount", 0),
                        creator = p.optJSONObject("creator")?.optString("nickname") ?: ""
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "recommendPlaylists failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 歌单分类 ─────────────────────────

    suspend fun playlistCategories(): List<PlaylistCategory> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().toString()
            val json = NetEaseApiClient.weapiPost("/weapi/playlist/catalogue", payload)
            if (!checkResponse(json, "playlistCategories")) return@withContext emptyList()
            val categoriesObj = json.optJSONObject("categories") ?: return@withContext emptyList()
            val subArr = json.optJSONArray("sub") ?: JSONArray()

            val subByCat = mutableMapOf<String, MutableList<Pair<String, String>>>()
            for (i in 0 until subArr.length()) {
                val sub = subArr.getJSONObject(i)
                val catId = sub.optInt("category").toString()
                val name = sub.optString("name")
                if (name.isNotBlank()) {
                    subByCat.getOrPut(catId) { mutableListOf() }.add(name to name)
                }
            }

            val list = mutableListOf<PlaylistCategory>()
            val keys = categoriesObj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val name = categoriesObj.optString(id)
                if (name.isNotBlank()) {
                    list.add(PlaylistCategory(id = id, name = name, subCategories = subByCat[id] ?: emptyList()))
                }
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "playlistCategories failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── VIP 状态刷新 ─────────────────────────

    suspend fun refreshVipStatus(): UserAccount? = NetEaseAuth.fetchAccount()

    // ───────────────────────── 私人 FM ─────────────────────────

    suspend fun personalFm(): List<Song> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply { put("csrf_token", "") }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/v1/radio/get", payload)
            if (!checkResponse(json, "personalFm")) return@withContext emptyList()
            val arr = json.optJSONArray("data") ?: return@withContext emptyList()
            val list = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val artists = s.optJSONArray("artists")?.let { a ->
                    buildString { for (j in 0 until a.length()) { if (j > 0) append("/"); append(a.getJSONObject(j).optString("name")) } }
                } ?: ""
                val al = s.optJSONObject("album")
                list.add(
                    Song(
                        id = s.optLong("id").toString(),
                        title = s.optString("name"),
                        artist = artists,
                        album = al?.optString("name") ?: "",
                        durationMs = s.optLong("duration", 0L),
                        coverUrl = al?.optString("picUrl") ?: "",
                        source = Source.NETEASE,
                        fee = s.optInt("fee", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "personalFm failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 新歌速递 ─────────────────────────

    /** @param areaId 0 全部 / 7 华语 / 96 欧美 / 8 日本 / 16 韩国 */
    suspend fun newSongs(areaId: Int = 0): List<Song> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("type", areaId)
                put("limit", 20)
                put("offset", 0)
                put("total", true)
                put("csrf_token", "")
            }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/v1/discovery/new/songs", payload)
            if (!checkResponse(json, "newSongs")) return@withContext emptyList()
            val arr = json.optJSONArray("data") ?: return@withContext emptyList()
            val list = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val artists = s.optJSONArray("artists")?.let { a ->
                    buildString { for (j in 0 until a.length()) { if (j > 0) append("/"); append(a.getJSONObject(j).optString("name")) } }
                } ?: ""
                val al = s.optJSONObject("album")
                list.add(
                    Song(
                        id = s.optLong("id").toString(),
                        title = s.optString("name"),
                        artist = artists,
                        album = al?.optString("name") ?: "",
                        durationMs = s.optLong("duration", 0L),
                        coverUrl = al?.optString("picUrl") ?: "",
                        source = Source.NETEASE,
                        fee = s.optInt("fee", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "newSongs failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 相似歌曲 ─────────────────────────

    suspend fun similarSongs(songId: String): List<Song> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("songid", songId.toLong())
                put("limit", 20)
                put("offset", 0)
                put("csrf_token", "")
            }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/v1/discovery/simiSong", payload)
            if (!checkResponse(json, "similarSongs")) return@withContext emptyList()
            val arr = json.optJSONArray("songs") ?: return@withContext emptyList()
            val list = mutableListOf<Song>()
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val artists = s.optJSONArray("artists")?.let { a ->
                    buildString { for (j in 0 until a.length()) { if (j > 0) append("/"); append(a.getJSONObject(j).optString("name")) } }
                } ?: ""
                val al = s.optJSONObject("album")
                list.add(
                    Song(
                        id = s.optLong("id").toString(),
                        title = s.optString("name"),
                        artist = artists,
                        album = al?.optString("name") ?: "",
                        durationMs = s.optLong("duration", 0L),
                        coverUrl = al?.optString("picUrl") ?: "",
                        source = Source.NETEASE,
                        fee = s.optInt("fee", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "similarSongs failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── DJ 电台 ─────────────────────────

    data class DjProgram(
        val id: String,
        val name: String,
        val djName: String,
        val coverUrl: String,
        val durationMs: Long,
        val listenerCount: Int
    )

    suspend fun recommendDj(): List<DjProgram> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply { put("csrf_token", "") }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/djradio/recommend/v1", payload)
            if (!checkResponse(json, "recommendDj")) return@withContext emptyList()
            val arr = json.optJSONArray("djRadios") ?: return@withContext emptyList()
            val list = mutableListOf<DjProgram>()
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                list.add(
                    DjProgram(
                        id = d.optLong("id").toString(),
                        name = d.optString("name"),
                        djName = d.optJSONObject("dj")?.optString("nickname") ?: "",
                        coverUrl = d.optString("picUrl"),
                        durationMs = 0L,
                        listenerCount = d.optInt("subCount", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "recommendDj failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── MV 推荐 ─────────────────────────

    data class MvInfo(
        val id: String,
        val name: String,
        val artist: String,
        val coverUrl: String,
        val playCount: Int
    )

    suspend fun recommendMv(): List<MvInfo> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply { put("csrf_token", "") }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/personalized/mv", payload)
            if (!checkResponse(json, "recommendMv")) return@withContext emptyList()
            val arr = json.optJSONArray("result") ?: return@withContext emptyList()
            val list = mutableListOf<MvInfo>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val artists = m.optJSONArray("artists")?.let { a ->
                    buildString { for (j in 0 until a.length()) { if (j > 0) append("/"); append(a.getJSONObject(j).optString("name")) } }
                } ?: ""
                list.add(
                    MvInfo(
                        id = m.optLong("id").toString(),
                        name = m.optString("name"),
                        artist = artists,
                        coverUrl = m.optString("picUrl"),
                        playCount = m.optInt("playCount", 0)
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "recommendMv failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 新碟上架 ─────────────────────────

    data class AlbumInfo(
        val id: String,
        val name: String,
        val artist: String,
        val coverUrl: String,
        val publishDate: String
    )

    suspend fun newAlbums(): List<AlbumInfo> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("limit", 20)
                put("offset", 0)
                put("total", true)
                put("area", "ALL")
                put("type", "new")
                put("csrf_token", "")
            }.toString()
            val json = NetEaseApiClient.weapiPost("/weapi/album/new", payload)
            if (!checkResponse(json, "newAlbums")) return@withContext emptyList()
            val arr = json.optJSONArray("albums") ?: return@withContext emptyList()
            val list = mutableListOf<AlbumInfo>()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val artist = a.optJSONArray("artists")?.let { arr2 ->
                    buildString { for (j in 0 until arr2.length()) { if (j > 0) append("/"); append(arr2.getJSONObject(j).optString("name")) } }
                } ?: ""
                list.add(
                    AlbumInfo(
                        id = a.optLong("id").toString(),
                        name = a.optString("name"),
                        artist = artist,
                        coverUrl = a.optString("picUrl"),
                        publishDate = a.optString("publishTime")
                    )
                )
            }
            list
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "newAlbums failed: ${e.message}")
            emptyList()
        }
    }
}

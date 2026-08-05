package com.liquidglass.app.music

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网易云音乐业务接口（纯 Kotlin 直连 weapi，无后端）。
 *
 * 覆盖：搜索、歌曲播放URL、歌词(逐字yrc+逐行lrc+翻译)、用户歌单、歌单详情、VIP状态。
 * 所有方法在 IO 线程执行，返回可直接用的 [Song]/[Lyrics] 等领域模型。
 */
object NetEaseApi {

    /**
     * 检查 weapi 响应的业务 code 是否为 200。
     * 不为 200 时记录日志，返回 false。
     */
    private fun checkResponse(json: JSONObject, tag: String): Boolean {
        val code = json.optInt("code", -1)
        if (code != 200) {
            Log.w("NetEaseApi", "$tag: code=$code, msg=${json.optString("message")}")
            return false
        }
        return true
    }

    // ───────────────────────── 热搜词 ─────────────────────────

    /**
     * 网易云热搜榜（无需登录）。
     * @return 热搜词列表，失败返回空
     */
    suspend fun hotSearch(): List<String> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("type", 1111)
            }.toString()
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
            Log.e("NetEaseApi", "hotSearch failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 搜索 ─────────────────────────

    suspend fun search(keyword: String, limit: Int = 30, offset: Int = 0): SearchResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().apply {
                    put("s", keyword)
                    put("type", 1)          // 1 单曲
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
                Log.e("NetEaseApi", "search failed: ${e.message}")
                SearchResult(emptyList(), 0)
            }
        }

    // ───────────────────────── 歌曲播放URL ─────────────────────────

    /**
     * 取歌曲可播放 URL。
     *
     * @param level 网易云音质档位，对应主流音乐软件音质：
     *  - "standard" 标准 128kbps
     *  - "exhigh"   极高 320kbps（默认，与大部分音乐软件默认档位一致）
     *  - "lossless" 无损 FLAC（VIP 专享，非 VIP 自动降级到 exhigh）
     *  - "hires"    Hi-Res（VIP 专享，部分曲目才有）
     * @return url；VIP且未登录 / 无版权时返回空串
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
            // v2.11.1: 多音质降级重试（对齐桌面端，修复"非VIP歌也播不了"）
            // lossless→exhigh→standard 三级降级，避免单档位空 url 导致无法播放
            val url = data.getJSONObject(0).optString("url")
            if (url.isBlank()) {
                return@withContext when (level) {
                    "lossless", "hires" -> songUrl(songId, "exhigh")
                    "exhigh" -> songUrl(songId, "standard")
                    else -> ""
                }
            }
            url ?: ""
        } catch (e: Exception) {
            Log.e("NetEaseApi", "songUrl failed for $songId: ${e.message}")
            ""
        }
    }

    // ───────────────────────── 歌词 ─────────────────────────

    /**
     * 取歌词：优先逐字 yrc，回退逐行 lrc，附带翻译 tlyric。
     */
    suspend fun lyrics(songId: String): Lyrics = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("id", songId.toLong())
                put("lv", -1)   // 逐行 lrc
                put("tv", -1)   // 翻译 tlyric
                put("kv", -1)   // 逐字 yrc
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
            Log.e("NetEaseApi", "lyrics failed for $songId: ${e.message}")
            Lyrics()
        }
    }

    // ───────────────────────── 用户歌单 ─────────────────────────

    suspend fun userPlaylists(context: Context): List<Playlist> = withContext(Dispatchers.IO) {
        if (!SessionStore.isLoggedIn(context)) {
            Log.w("NetEaseApi", "userPlaylists: not logged in")
            return@withContext emptyList()
        }
        // uid 校验：uid=0 时网易云返回空歌单，会导致"登录成功但啥歌单都没有"
        val uid = SessionStore.getUserId(context)
        if (uid <= 0L) {
            Log.w("NetEaseApi", "userPlaylists: uid=$uid <= 0, skipping")
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
            val arr = json.optJSONArray("playlist") ?: run {
                Log.w("NetEaseApi", "userPlaylists: no playlist array in response, keys=${json.keySet()}")
                return@withContext emptyList()
            }
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
            Log.e("NetEaseApi", "userPlaylists failed: ${e.message}")
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
            Log.e("NetEaseApi", "playlistTracks failed for $playlistId: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 排行榜（榜单） ─────────────────────────

    /**
     * 所有榜单列表（无需登录）。
     * @return 榜单 [Playlist] 列表，失败返回空
     */
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
            Log.e("NetEaseApi", "toplist failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 某榜单下的歌曲列表。复用 [playlistTracks]（同 `/weapi/v6/playlist/detail` 端点）。
     */
    suspend fun toplistTracks(id: String): List<Song> = playlistTracks(id)

    // ───────────────────────── 每日推荐歌曲 ─────────────────────────

    /**
     * 每日推荐歌曲（个性化推荐，建议登录后调用以获得个性化结果）。
     * @return 推荐 [Song] 列表，失败返回空
     */
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
            Log.e("NetEaseApi", "recommendSongs failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 推荐歌单 ─────────────────────────

    /**
     * 推荐歌单（无需登录）。
     * @return 推荐 [Playlist] 列表，失败返回空
     */
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
            Log.e("NetEaseApi", "recommendPlaylists failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 歌单分类 ─────────────────────────

    /**
     * 歌单分类目录（无需登录）。
     * @return 分类 [PlaylistCategory] 列表，失败返回空
     */
    suspend fun playlistCategories(): List<PlaylistCategory> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().toString()
            val json = NetEaseApiClient.weapiPost("/weapi/playlist/catalogue", payload)
            if (!checkResponse(json, "playlistCategories")) return@withContext emptyList()
            val categoriesObj = json.optJSONObject("categories") ?: return@withContext emptyList()
            val subArr = json.optJSONArray("sub") ?: JSONArray()

            // 按 category id 收集子分类
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
            // categories 是 id→name 的对象，遍历 key 保持插入顺序
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
            Log.e("NetEaseApi", "playlistCategories failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── VIP 状态刷新 ─────────────────────────

    suspend fun refreshVipStatus(context: Context): UserAccount? =
        NetEaseAuth.fetchAccount(context)

    // ───────────────────────── 私人 FM ─────────────────────────

    /**
     * 私人 FM（红心电台，建议登录后调用）。
     * @return FM 歌曲 [Song] 列表，失败返回空
     */
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
            Log.e("NetEaseApi", "personalFm failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 新歌速递 ─────────────────────────

    /**
     * 新歌速递（无需登录）。
     * @param areaId 地区：0 全部 / 7 华语 / 96 欧美 / 8 日本 / 16 韩国
     * @return 新歌 [Song] 列表
     */
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
            Log.e("NetEaseApi", "newSongs failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 相似歌曲 ─────────────────────────

    /**
     * 相似歌曲（喜欢这首歌的人也喜欢）。
     * @param songId 歌曲 ID
     * @return 相似 [Song] 列表
     */
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
            Log.e("NetEaseApi", "similarSongs failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── DJ 电台 ─────────────────────────

    /** DJ 电台节目数据 */
    data class DjProgram(
        val id: String,
        val name: String,
        val djName: String,
        val coverUrl: String,
        val durationMs: Long,
        val listenerCount: Int
    )

    /**
     * 推荐DJ电台节目（无需登录）。
     * @return DJ节目 [DjProgram] 列表
     */
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
            Log.e("NetEaseApi", "recommendDj failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── MV 推荐 ─────────────────────────

    /** MV 数据 */
    data class MvInfo(
        val id: String,
        val name: String,
        val artist: String,
        val coverUrl: String,
        val playCount: Int
    )

    /**
     * 推荐 MV（无需登录）。
     * @return MV [MvInfo] 列表
     */
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
            Log.e("NetEaseApi", "recommendMv failed: ${e.message}")
            emptyList()
        }
    }

    // ───────────────────────── 新碟上架 ─────────────────────────

    /** 专辑数据 */
    data class AlbumInfo(
        val id: String,
        val name: String,
        val artist: String,
        val coverUrl: String,
        val publishDate: String
    )

    /**
     * 新碟上架（无需登录）。
     * @return 专辑 [AlbumInfo] 列表
     */
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
            Log.e("NetEaseApi", "newAlbums failed: ${e.message}")
            emptyList()
        }
    }
}

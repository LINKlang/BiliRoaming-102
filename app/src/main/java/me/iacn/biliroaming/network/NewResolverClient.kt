package me.iacn.biliroaming.network

import kotlinx.coroutines.CancellationException
import me.iacn.biliroaming.utils.NativeRequestHooks
import org.json.JSONArray
import org.json.JSONObject

internal class NewResolverClient(
    private val config: ResolverConfig,
    private val credentials: (String) -> Pair<String, Long>,
    private val request: (String, String, Map<String, String>, Map<String, String>) -> String?,
) {
    data class Episode(val area: String, val cid: Long, val epId: Long, val aid: Long, val seasonId: Long)
    data class Play(val area: String, val video: JSONObject)
    private data class CachedSeason(val area: String, val json: String)
    private val searches = ResolverSearchSessions()
    private val seasons = bounded<String, CachedSeason>(16)
    private val episodes = bounded<Long, Episode>(256)
    private val searchAreas = bounded<Long, String>(64)

    private fun fetch(area: String, path: String, query: Map<String, String>, headers: Map<String, String> = emptyMap(),
                      accessKey: String = credentials(area).first): JSONObject {
        val text = request(area, path, query + ("access_key" to accessKey), headers)
            ?: throw IllegalStateException("服务器不可用")
        return JSONObject(text)
    }

    fun search(query: Map<String, String>, area: String, type: String): String {
        require(config.server(area) != null) { "未设置该地区新版解析服务器" }
        val intl = area == "th"
        require(!intl || type == "7") { "东南亚仅支持番剧搜索" }
        val key = "${config.scope}|$area|$type|${query["keyword"].orEmpty()}"
        val ticket = searches.begin(key, query["pn"].orEmpty().ifEmpty { "1" }, intl)
        val params = query.toMutableMap().apply {
            put("type", type); put("area", area); put("pn", ticket.page.toString())
            if (!intl) put("build", "6400000")
            else {
                put("search_type", "media_bangumi"); put("page", ticket.page.toString())
                put("page_size", query["ps"]?.takeIf { (it.toIntOrNull() ?: 0) > 0 } ?: "20")
                put("resolver_mode", "INTL")
                ticket.cursor?.let { put("resolver_cursor", it) }
            }
        }
        val credential = credentials(area)
        val headers = if (intl) IntlSearchMetadata.headers(credential.first, credential.second)
            else mapOf("resolver_mode" to ResolverConfig.mode(area))
        val body = fetch(area, "/x/v2/search/type", params, headers, credential.first)
        if (body.optInt("code", -1) != 0) body.put("message", "解析服务器错误码 ${body.optInt("code", -1)}")
        val normalized = searches.finish(ticket, body, intl)
        normalized.optJSONObject("data")?.optJSONArray("items")?.let { list -> synchronized(this) {
            for (i in 0 until list.length()) searchAreas[list.getJSONObject(i).optLong("season_id")] = area
        } }
        return normalized.toString()
    }

    fun season(info: Map<String, String?>, original: JSONObject?): String? {
        val sid = info["season_id"]?.toLongOrNull()?.takeIf { it > 0 } ?: original?.optLong("season_id")?.takeIf { it > 0 }
        val ep = info["ep_id"]?.toLongOrNull()?.takeIf { it > 0 }
        if (sid == null && ep == null) return null
        val preferred = info["area"] ?: synchronized(this) { searchAreas[sid] }
        val key = config.cacheKey((if (sid != null) "ss$sid" else "ep$ep") + ":${preferred.orEmpty()}")
        synchronized(this) { seasons[key]?.let {
            rememberSeason(it.area, JSONObject(it.json).getJSONObject("result"))
            return it.json
        } }
        val query = buildMap { sid?.let { put("season_id", it.toString()) }; ep?.let { put("ep_id", it.toString()) } }
        for (area in candidates(listOfNotNull(info["area"], preferred))) {
            try {
                val normalized = ResolverData.season(fetch(area, "/pgc/view/v2/app/season", query)) ?: continue
                val text = normalized.toString()
                synchronized(this) {
                    seasons[key] = CachedSeason(area, text)
                    rememberSeason(area, normalized.getJSONObject("result"))
                }
                return text
            } catch (e: NativeRequestHooks.SigningException) { throw e }
            catch (_: Exception) { /* Other configured App endpoints can still succeed. */ }
        }
        return null
    }

    fun play(query: Map<String, String>, priority: List<String>): Pair<Play?, Map<String, String>> {
        val errors = linkedMapOf<String, String>()
        for (area in candidates(priority)) {
            try {
                val body = fetch(area, "/pgc/player/api/playurl", query + ("area" to area))
                val video = ResolverData.play(body)
                if (video == null) {
                    errors[area] = if (body.optInt("code", -1) == 0) "未返回有效视频流"
                        else "错误码 ${body.optInt("code", -1)}"
                    continue
                }
                val cid = query["cid"]?.toLongOrNull() ?: 0
                val ep = query["ep_id"]?.toLongOrNull() ?: 0
                if (cid > 0 && ep > 0) synchronized(this) {
                    val previous = episodes[cid]?.takeIf { it.epId == ep }
                    episodes[cid] = Episode(area, cid, ep,
                        query["aid"]?.toLongOrNull()?.takeIf { it > 0 } ?: previous?.aid ?: 0,
                        query["season_id"]?.toLongOrNull()?.takeIf { it > 0 } ?: previous?.seasonId ?: 0)
                }
                return Play(area, video) to errors
            } catch (e: NativeRequestHooks.SigningException) { throw e }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors[area] = "请求失败（${e.javaClass.simpleName}）" }
        }
        return null to errors
    }

    fun subtitles(cid: Long, pid: Long): JSONArray {
        val episode = synchronized(this) { episodes[cid] } ?: return JSONArray()
        if (pid > 0 && episode.aid > 0 && pid != episode.aid) return JSONArray()
        val query = mapOf("type" to "1", "oid" to cid.toString(), "pid" to (episode.aid.takeIf { it > 0 } ?: pid).toString())
        val result = ResolverData.subtitles(fetch(episode.area, "/x/v2/dm/view", query))
        return synchronized(this) { if (episodes[cid] == episode) result else JSONArray() }
    }

    private fun candidates(priority: List<String>): List<String> =
        (priority + listOf("cn", "th", "hk", "tw")).distinct().filter { config.server(it) != null }

    private fun rememberSeason(area: String, season: JSONObject) {
        for (ep in ResolverData.episodes(season)) {
            val cid = ep.optLong("cid")
            val id = ep.optLong("id", ep.optLong("ep_id"))
            if (cid > 0 && id > 0) episodes[cid] = Episode(area, cid, id, ep.optLong("aid"), season.optLong("season_id"))
        }
    }

    private fun <K, V> bounded(limit: Int) = object : LinkedHashMap<K, V>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > limit
    }
}

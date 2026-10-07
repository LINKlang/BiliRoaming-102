package me.iacn.biliroaming.network

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object ResolverData {
    fun payload(body: JSONObject): JSONObject? = body.optJSONObject("result") ?: body.optJSONObject("data")

    fun episodes(data: JSONObject): List<JSONObject> {
        val lists = mutableListOf<JSONArray>()
        data.optJSONArray("episodes")?.let(lists::add)
        data.optJSONArray("modules")?.let { modules -> for (i in 0 until modules.length()) {
            modules.optJSONObject(i)?.optJSONObject("data")?.optJSONArray("episodes")?.let(lists::add)
        } }
        data.optJSONArray("section")?.let { sections -> for (i in 0 until sections.length()) {
            sections.optJSONObject(i)?.optJSONArray("episodes")?.let(lists::add)
        } }
        return lists.flatMap { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } }
    }

    fun season(body: JSONObject): JSONObject? {
        if (body.optInt("code", -1) != 0) return null
        val data = payload(body) ?: return null
        val modules = data.optJSONArray("modules")
        val main = data.optJSONArray("episodes")?.takeIf { it.length() > 0 } ?: (0 until (modules?.length() ?: 0))
            .mapNotNull { modules!!.optJSONObject(it)?.optJSONObject("data")?.optJSONArray("episodes") }
            .maxByOrNull { it.length() } ?: data.optJSONArray("section")?.let { sections ->
                JSONArray().apply { for (i in 0 until sections.length()) {
                    sections.optJSONObject(i)?.optJSONArray("episodes")?.let { list -> for (j in 0 until list.length()) put(list.get(j)) }
                } }
            } ?: return null
        val episodes = JSONArray()
        val seen = HashSet<Long>()
        for (i in 0 until main.length()) {
            val ep = main.optJSONObject(i) ?: continue
            val id = ep.optLong("id", ep.optLong("ep_id"))
            if (id <= 0 || !seen.add(id)) continue
            ep.put("id", id).put("ep_id", id)
            episodes.put(ep)
        }
        if (episodes.length() == 0) return null
        data.put("episodes", episodes)
        for (episode in ResolverData.episodes(data)) {
            val id = episode.optLong("id", episode.optLong("ep_id"))
            if (id > 0) {
                episode.put("id", id).put("ep_id", id)
                if (!episode.has("link")) episode.put("link", "https://www.bilibili.com/bangumi/play/ep$id")
            }
            if (!episode.has("index")) episode.put("index", episode.optString("title"))
            val rights = episode.optJSONObject("rights") ?: JSONObject().also { episode.put("rights", it) }
            rights.put("area_limit", 0)
            if (!rights.has("allow_dm")) rights.put("allow_dm", 1)
        }
        if (modules == null || modules.length() == 0) {
            fun module(content: JSONObject, style: String, title: String, id: Int) = JSONObject()
                .put("id", id).put("style", style).put("title", title).put("more", "查看更多")
                .put("module_style", JSONObject().put("hidden", 0).put("line", 1)).put("data", content)
            val rebuilt = JSONArray().put(module(JSONObject().put("episodes", episodes), "positive", "选集", 1))
            data.optJSONArray("section")?.let { sections -> for (i in 0 until sections.length()) {
                sections.optJSONObject(i)?.let { rebuilt.put(module(it, "section", it.optString("title"), i + 2)) }
            } }
            data.put("modules", rebuilt)
        }
        if (!data.has("season_title")) data.put("season_title", data.optString("title"))
        if (!data.has("season_status")) data.put("season_status", data.optInt("status"))
        if (!data.has("total_ep")) data.put("total_ep", data.optInt("total", episodes.length()))
        val series = data.optJSONObject("series") ?: JSONObject().also { data.put("series", it) }
        if (series.optJSONArray("seasons") == null) {
            val related = data.optJSONArray("seasons") ?: (0 until (modules?.length() ?: 0))
                .mapNotNull { modules!!.optJSONObject(it)?.optJSONObject("data")?.optJSONArray("seasons") }.firstOrNull()
            related?.let { list ->
                for (i in 0 until list.length()) list.optJSONObject(i)?.let {
                    if (!it.has("quarter_title")) it.put("quarter_title", it.optString("season_title", it.optString("title")))
                }
                series.put("seasons", list)
            }
        }
        val rights = data.optJSONObject("rights") ?: JSONObject().also { data.put("rights", it) }
        rights.put("area_limit", 0)
        return JSONObject().put("code", 0).put("result", data)
    }

    fun play(body: JSONObject): JSONObject? {
        if (body.optInt("code", -1) != 0) return null
        val wrapper = payload(body) ?: body
        val video = wrapper.optJSONObject("video_info") ?: wrapper
        val dash = video.optJSONObject("dash")
        fun normalizeUrls(list: JSONArray?) {
            if (list == null) return
            for (i in 0 until list.length()) list.optJSONObject(i)?.let {
                if (!it.has("base_url") && it.has("baseUrl")) it.put("base_url", it.optString("baseUrl"))
                if (!it.has("backup_url") && it.has("backupUrl")) it.put("backup_url", it.optJSONArray("backupUrl"))
                if (it.optString("base_url").startsWith("//")) it.put("base_url", "https:${it.optString("base_url")}")
            }
        }
        normalizeUrls(dash?.optJSONArray("video")); normalizeUrls(dash?.optJSONArray("audio"))
        val videos = dash?.optJSONArray("video")
        val segments = video.optJSONArray("durl")
        fun validUrl(url: String?): Boolean = url?.let {
            val uri = runCatching { URI(it) }.getOrNull()
            uri?.scheme in listOf("http", "https") && !uri?.host.isNullOrBlank()
        } == true
        val validDash = (0 until (videos?.length() ?: 0)).any { validUrl(videos!!.optJSONObject(it)?.optString("base_url")) }
        val validSegments = (0 until (segments?.length() ?: 0)).any { validUrl(segments!!.optJSONObject(it)?.optString("url")) }
        if (!validDash && !validSegments) return null
        video.put("code", 0)
        if (validDash) video.put("type", "DASH")
        else if (video.optString("type") !in listOf("MP4", "FLV")) video.put("type", "MP4")
        if (!video.has("accept_quality")) video.put("accept_quality", JSONArray().put(video.optInt("quality")))
        return video
    }

    fun searchItems(data: JSONObject): JSONArray {
        val raw = data.optJSONArray("items") ?: data.optJSONArray("result") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            if (item.optBoolean("resolver_injected")) continue
            val seasonId = item.optLong("season_id", item.optLong("pgc_season_id"))
            var link = sequenceOf("uri", "goto_url", "url").map { item.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            if (link.startsWith("//")) link = "https:$link"
            val uri = runCatching { URI(if (link.startsWith("//")) "https:$link" else link) }.getOrNull()
            val biliHost = uri?.host?.let { it == "bilibili.com" || it.endsWith(".bilibili.com") } == true ||
                (uri?.scheme == "bilibili" && uri.host == "bangumi")
            val pgcLink = biliHost && Regex("(?:/bangumi/play/(?:ss|ep)\\d+|bilibili://bangumi/season/\\d+)", RegexOption.IGNORE_CASE).containsMatchIn(link)
            if (seasonId <= 0 || (link.isNotBlank() && !pgcLink && item.optString("goto") != "bangumi")) continue
            item.put("season_id", seasonId).put("goto", "bangumi")
                .put("uri", if (pgcLink) link else "https://www.bilibili.com/bangumi/play/ss$seasonId")
            if (!item.has("param")) item.put("param", item.optString("media_id", seasonId.toString()))
            fun alias(target: String, vararg sources: String) {
                if (!item.has(target)) sources.firstOrNull { item.has(it) }?.let { item.put(target, item.opt(it)) }
            }
            alias("area", "areas"); alias("style", "styles"); alias("prompt", "desc", "description")
            alias("ptime", "pubtime"); alias("out_name", "all_net_name"); alias("track_id", "trackid")
            val score = item.optJSONObject("media_score")
            if (score != null) {
                if (!item.has("rating")) item.put("rating", score.opt("score"))
                if (!item.has("vote")) item.put("vote", score.opt("user_count") ?: score.opt("vote"))
            }
            // Web-shaped international fields must fit the App protobuf scalar types.
            if (item.opt("play_state") !is Number) item.put("play_state", 0)
            if (item.opt("rating") is String) item.put("rating", item.optString("rating").toDoubleOrNull() ?: 0.0)
            out.put(item)
        }
        return out
    }

    fun subtitles(body: JSONObject): JSONArray {
        if (body.optInt("code", -1) != 0) return JSONArray()
        val data = payload(body) ?: body
        val raw = data.optJSONObject("subtitle")?.optJSONArray("subtitles") ?: data.optJSONArray("subtitles") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until raw.length()) raw.optJSONObject(i)?.let { item ->
            var url = sequenceOf("subtitle_url_v2", "subtitle_url", "url").map { item.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
            if (url.startsWith("//")) url = "https:$url"
            if (url.isNotEmpty()) out.put(JSONObject().put("id", item.optLong("id", i + 1L))
                .put("url", url).put("key", item.optString("lan", item.optString("key")))
                .put("title", item.optString("lan_doc", item.optString("title"))).put("type", item.optInt("type")))
        }
        return out
    }
}

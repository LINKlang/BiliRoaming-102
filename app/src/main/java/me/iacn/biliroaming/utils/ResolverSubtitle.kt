package me.iacn.biliroaming.utils

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import me.iacn.biliroaming.DmViewReply
import me.iacn.biliroaming.SubtitleItem
import me.iacn.biliroaming.copy

internal object ResolverSubtitle {
    private val urls = object : LinkedHashMap<String, Boolean>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 256
    }
    @Synchronized fun register(url: String) { urls[originalUrl(url)] = true }
    @Synchronized fun recognizes(url: String): Boolean = urls.containsKey(originalUrl(url))
    fun merge(original: DmViewReply, extra: List<SubtitleItem>): DmViewReply = original.copy {
        val seen = original.subtitle.subtitlesList.map { "${it.lan}|${it.subtitleUrl}" }.toMutableSet()
        subtitle = original.subtitle.copy {
            subtitles += extra.filter { seen.add("${it.lan}|${it.subtitleUrl}") }
        }
    }
    private fun originalUrl(url: String): String {
        val uri = URI(url)
        val query = uri.rawQuery?.split('&')?.filterNot { it.substringBefore('=') == "zh_converter" }?.joinToString("&")
        return url.substringBefore('?') + if (query.isNullOrEmpty()) "" else "?$query"
    }

    fun toJson(text: String): String {
        val input = text.removePrefix("\uFEFF").trimStart()
        if (input.startsWith('{')) {
            require(JSONObject(input).optJSONArray("body") != null) { "字幕缺少 body" }
            return input
        }
        val body = JSONArray()
        fun cue(from: Double, to: Double, content: String) {
            if (to > from) body.put(JSONObject().put("from", from).put("to", to).put("location", 2)
                .put("content", content.replace(Regex("\\{[^}]*}"), "").replace(Regex("<[^>]+>"), "")
                    .replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")))
        }
        if (input.startsWith("WEBVTT")) {
            val lines = input.replace("\r\n", "\n").lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i++]
                if (line.trimStart().startsWith("NOTE") || line.trim() == "STYLE" || line.trim() == "REGION") {
                    while (i < lines.size && lines[i].isNotBlank()) i++
                    continue
                }
                if (!line.contains("-->")) continue
                val times = line.split("-->", limit = 2)
                val content = mutableListOf<String>()
                while (i < lines.size && lines[i].isNotBlank()) content += lines[i++]
                cue(time(times[0].trim()), time(times[1].trim().substringBefore(' ')), content.joinToString("\n"))
            }
        } else if (input.contains("[Events]", true)) {
            var inEvents = false
            var format = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
            for (line in input.lines()) {
                if (line.trim().startsWith('[')) inEvents = line.trim().equals("[Events]", true)
                if (!inEvents) continue
                if (line.startsWith("Format:", true)) format = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                if (line.startsWith("Dialogue:", true)) {
                    val values = line.substringAfter(':').trimStart().split(',', limit = format.size)
                    if (values.size != format.size || !format.containsAll(listOf("start", "end", "text"))) continue
                    cue(time(values[format.indexOf("start")].trim()), time(values[format.indexOf("end")].trim()), values[format.indexOf("text")])
                }
            }
        } else throw IllegalArgumentException("不支持的字幕格式")
        return JSONObject().put("body", body).toString()
    }

    private fun time(value: String): Double {
        val parts = value.split(':')
        require(parts.size in 2..3) { "字幕时间格式错误" }
        return parts.fold(0.0) { total, part -> total * 60 + part.replace(',', '.').toDouble() }
    }
}

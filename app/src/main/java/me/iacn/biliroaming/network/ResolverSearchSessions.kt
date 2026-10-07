package me.iacn.biliroaming.network

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** One cursor and deduplication history per region/type/keyword, reset by page one. */
internal class ResolverSearchSessions {
    data class Ticket(val key: String, val generation: Long, val request: Long, val page: Int, val cursor: String?)
    private class Session(val generation: Long) {
        val requests = HashMap<Int, Long>()
        val cursors = HashMap<Int, String>()
        val seen = HashMap<Int, Set<Long>>()
    }
    private var sequence = 0L
    private val sessions = object : LinkedHashMap<String, Session>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>?) = size > 32
    }

    @Synchronized
    fun begin(key: String, pageValue: String, intl: Boolean): Ticket {
        val token = pageValue.split(':')
        val page = if (token.size == 3 && token[0] == "resolver2") token[2].toIntOrNull() else pageValue.toIntOrNull()
        require(page != null && page > 0) { "搜索页码无效" }
        if (token.size == 3 && sessions[key]?.generation != token[1].toLongOrNull())
            throw CancellationException("Search session superseded")
        val session = if (page == 1) Session(++sequence).also { sessions[key] = it }
            else sessions[key] ?: Session(++sequence).also { sessions[key] = it }
        val cursor = session.cursors[page]
        require(!intl || page == 1 || !cursor.isNullOrEmpty()) { "搜索游标已失效，请刷新" }
        val request = ++sequence
        session.requests[page] = request
        return Ticket(key, session.generation, request, page, cursor)
    }

    @Synchronized
    fun finish(ticket: Ticket, body: JSONObject, intl: Boolean): JSONObject {
        val session = sessions[ticket.key]
        if (session == null || session.generation != ticket.generation || session.requests[ticket.page] != ticket.request)
            throw CancellationException("Search response superseded")
        if (body.optInt("code", -1) != 0) return body
        val data = body.optJSONObject("data") ?: throw IllegalArgumentException("搜索响应缺少 data")
        val items = ResolverData.searchItems(data)
        val seen = session.seen.filterKeys { it < ticket.page }.values.flatten().toMutableSet()
        val current = HashSet<Long>()
        val filtered = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val id = item.optLong("season_id")
            if (seen.add(id)) { current.add(id); filtered.put(item) }
        }
        session.seen[ticket.page] = current
        val nextCursor = data.optString("next", data.optString("next_cursor"))
        val pages = if (intl) if (nextCursor.isNotEmpty()) ticket.page + 1 else ticket.page
            else data.optInt("pages", data.optInt("numPages", ticket.page))
        if (intl && nextCursor.isNotEmpty()) session.cursors[ticket.page + 1] = nextCursor
        else session.cursors.remove(ticket.page + 1)
        data.put("items", filtered).put("pages", pages).put("page", ticket.page)
            .put("resolver_next_page", if (ticket.page < pages) "resolver2:${ticket.generation}:${ticket.page + 1}" else "")
        return body
    }
}

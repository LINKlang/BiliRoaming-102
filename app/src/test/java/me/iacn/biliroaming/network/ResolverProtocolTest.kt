package me.iacn.biliroaming.network

import kotlinx.coroutines.CancellationException
import me.iacn.biliroaming.DmViewReq
import me.iacn.biliroaming.dmViewReq
import me.iacn.biliroaming.resolver.ResolverMetadata
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ResolverProtocolTest {
    @Test fun addressesPreserveSchemePortAndPrefix() {
        assertEquals("https://example.invalid:8080/proxy", ResolverConfig.normalizeAddress(" example.invalid:8080/proxy/ "))
        val config = ResolverConfig(true, mapOf("cn" to "http://localhost:8080/proxy"))
        assertEquals("http://localhost:8080/proxy/x/v2/search/type", config.endpoint("cn", "/x/v2/search/type"))
        for (bad in listOf("ftp://example.invalid", "https://user:pass@example.invalid", "https://example.invalid/?token=x", "https://example.invalid/#fragment", "https://example.invalid:70000"))
            assertThrows(IllegalArgumentException::class.java) { ResolverConfig.normalizeAddress(bad) }
    }

    @Test fun oldAndNewConfigurationAndCacheAreIndependent() {
        assertEquals("cn_server", ResolverConfig.serverKey("cn", false))
        assertEquals("new_cn_server", ResolverConfig.serverKey("cn", true))
        assertTrue(ResolverConfig(true, emptyMap()).configured.isEmpty())
        val old = ResolverConfig(false, mapOf("cn" to "example.invalid"))
        val newer = ResolverConfig(true, mapOf("cn" to "example.invalid"))
        assertEquals("ss5526", old.cacheKey("ss5526"))
        assertNotEquals(old.cacheKey("ss5526"), newer.cacheKey("ss5526"))
        assertNotEquals(newer.scope, ResolverConfig(true, mapOf("cn" to "other.invalid")).scope)
        assertEquals(listOf("CN", "HK", "TW", "INTL"), ResolverConfig.AREAS.map(ResolverConfig::mode))
    }

    @Test fun internationalHeadersCarryAccountAndAppMetadata() {
        val headers = IntlSearchMetadata.headers("fixture-key", 42)
        val metadata = ResolverMetadata.Metadata.parseFrom(Base64.getDecoder().decode(headers["x-bili-metadata-bin"]))
        val device = ResolverMetadata.Device.parseFrom(Base64.getDecoder().decode(headers["x-bili-device-bin"]))
        assertEquals("fixture-key", metadata.accessKey)
        assertEquals("android_i", metadata.mobiApp)
        assertEquals(14, device.appId)
        assertEquals(9130300, device.build)
        assertEquals("INTL", headers["resolver_mode"])
        assertEquals("identify_v1 fixture-key", headers["authorization"])
        assertEquals("42", headers["x-bili-mid"])
        assertFalse(IntlSearchMetadata.headers("", 0).containsKey("authorization"))
    }

    @Test fun appAndWrappedSeasonsPreserveLargeEpisodeIdentifiers() {
        val ep = """{"id":5338220,"cid":40777680574,"aid":117070373062103,"bvid":"BV1mYud68Ews","status":2}"""
        val app = JSONObject("""{"code":0,"data":{"season_id":281544,"modules":[{"data":{"episodes":[$ep]}},{"data":{"episodes":[$ep,{"id":5338221,"cid":40777680575}]}},{"data":{"seasons":[{"season_id":275012,"season_title":"第二季"}]}}]}}""")
        val normalized = ResolverData.season(app)!!.getJSONObject("result")
        assertEquals(2, normalized.getJSONArray("episodes").length())
        val episode = normalized.getJSONArray("episodes").getJSONObject(0)
        assertEquals(40777680574L, episode.getLong("cid"))
        assertEquals(117070373062103L, episode.getLong("aid"))
        assertEquals(5338220L, episode.getLong("ep_id"))
        assertEquals("第二季", normalized.getJSONObject("series").getJSONArray("seasons").getJSONObject(0).getString("quarter_title"))
        val wrapped = ResolverData.season(JSONObject("""{"code":0,"result":{"season_id":29590,"episodes":[{"id":307068,"cid":469573312,"status":13}]}}"""))!!
        assertEquals(13, wrapped.getJSONObject("result").getJSONArray("episodes").getJSONObject(0).getInt("status"))
        assertEquals(1, wrapped.getJSONObject("result").getJSONArray("modules").length())
    }

    @Test fun flatAndNestedPlayInfoArePlayableWithoutChangingPreview() {
        val flat = ResolverData.play(JSONObject("""{"code":0,"is_preview":0,"quality":32,"dash":{"video":[{"id":32,"baseUrl":"https://media.invalid/video","backupUrl":["https://media.invalid/backup"]}],"audio":[]}}"""))!!
        assertEquals("DASH", flat.getString("type"))
        assertEquals("https://media.invalid/video", flat.getJSONObject("dash").getJSONArray("video").getJSONObject(0).getString("base_url"))
        val wrapped = ResolverData.play(JSONObject("""{"code":0,"result":{"video_info":{"is_preview":1,"quality":32,"durl":[{"url":"https://media.invalid/preview"}]}}}"""))!!
        assertEquals(1, wrapped.getInt("is_preview"))
        assertEquals("MP4", wrapped.getString("type"))
        assertNull(ResolverData.play(JSONObject("""{"code":0,"dash":{"audio":[{"base_url":"audio"}]}}""")))
        assertNull(ResolverData.play(JSONObject("""{"code":-400}""")))
    }

    @Test fun searchCardsFilterInjectedAndExternalNoticesAndMapFields() {
        val result = ResolverData.searchItems(JSONObject("""{"result":[
          {"season_id":1,"resolver_injected":true},
          {"season_id":2,"uri":"https://notice.invalid/landing","goto":""},
          {"season_id":3,"uri":"https://notice.invalid/bangumi/play/ss3","goto":""},
          {"season_id":281544,"title":"第四季","areas":"日本","desc":"简介","play_state":"更新中","media_score":{"score":9.5,"user_count":100},"goto_url":"https://www.bilibili.com/bangumi/play/ss281544"}
        ]}"""))
        assertEquals(1, result.length())
        val card = result.getJSONObject(0)
        assertEquals("bangumi", card.getString("goto"))
        assertEquals("日本", card.getString("area"))
        assertEquals("简介", card.getString("prompt"))
        assertEquals(9.5, card.getDouble("rating"), .001)
        assertEquals(0, card.getInt("play_state"))
    }

    @Test fun cursorPaginationDeduplicatesAndRefreshInvalidatesOldResponses() {
        val sessions = ResolverSearchSessions()
        val first = sessions.begin("query", "1", true)
        val page1 = sessions.finish(first, JSONObject("""{"code":0,"data":{"result":[{"season_id":1}],"next":"cursor-a"}}"""), true)
        val second = sessions.begin("query", page1.getJSONObject("data").getString("resolver_next_page"), true)
        assertEquals("cursor-a", second.cursor)
        val page2 = sessions.finish(second, JSONObject("""{"code":0,"data":{"result":[{"season_id":1},{"season_id":2}],"next":""}}"""), true).getJSONObject("data")
        assertEquals(1, page2.getJSONArray("items").length())
        assertEquals("", page2.getString("resolver_next_page"))
        val older = sessions.begin("query", "1", true)
        sessions.begin("query", "1", true)
        assertThrows(CancellationException::class.java) { sessions.finish(older, JSONObject("""{"code":0,"data":{"items":[]}}"""), true) }
        assertThrows(IllegalArgumentException::class.java) { sessions.begin("different-keyword", "2", true) }
    }

    @Test fun globalDmRequestRetains64BitPidAndCid() {
        val req = dmViewReq { pid = 117070373062103L; oid = 40777680574L; type = 1 }
        val decoded = DmViewReq.parseFrom(req.toByteArray())
        assertEquals(req.pid, decoded.pid)
        assertEquals(req.oid, decoded.oid)
    }
}

package me.iacn.biliroaming.network

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NewResolverClientTest {
    private val config = ResolverConfig(true, mapOf("cn" to "https://one.invalid", "th" to "https://two.invalid"))
    private val video = """{"code":0,"is_preview":0,"quality":32,"dash":{"video":[{"id":32,"base_url":"https://media.invalid/v"}],"audio":[]}}"""

    @Test fun allConfiguredRegionsUseOrdinaryAppPlayAndRetryInvalidStreams() {
        val paths = mutableListOf<String>()
        val client = NewResolverClient(config, { "key-$it" to 0L }) { area, path, query, headers ->
            paths += "$area$path"
            assertEquals("key-$area", query["access_key"])
            assertTrue(headers.isEmpty())
            if (area == "cn") """{"code":0,"dash":{}}""" else video
        }
        val (play, errors) = client.play(mapOf("ep_id" to "5338220", "cid" to "40777680574"), emptyList())
        assertEquals("th", play!!.area)
        assertEquals(listOf("cn/pgc/player/api/playurl", "th/pgc/player/api/playurl"), paths)
        assertEquals("未返回有效视频流", errors["cn"])
    }

    @Test fun explicitRegionPriorityAndAllErrorsArePreserved() {
        val areas = mutableListOf<String>()
        val client = NewResolverClient(config, { "" to 0L }) { area, _, _, _ ->
            areas += area; """{"code":-400}"""
        }
        val (play, errors) = client.play(mapOf("ep_id" to "96480", "cid" to "11198631"), listOf("th"))
        assertNull(play)
        assertEquals(listOf("th", "cn"), areas)
        assertEquals(2, errors.size)
    }

    @Test fun internationalSearchRetainsAppPathAndCarriesCursor() {
        val queries = mutableListOf<Map<String, String>>()
        val client = NewResolverClient(config, { "fixture" to 42L }) { _, path, query, headers ->
            assertEquals("/x/v2/search/type", path)
            assertEquals("INTL", headers["resolver_mode"])
            queries += query
            if (queries.size == 1) """{"code":0,"data":{"result":[{"season_id":281544}],"next":"cursor-one"}}"""
            else """{"code":0,"data":{"result":[{"season_id":281544},{"season_id":281545}],"next":""}}"""
        }
        val query = mapOf("keyword" to "从零开始", "pn" to "1", "ps" to "20")
        val first = JSONObject(client.search(query, "th", "7")).getJSONObject("data")
        val second = JSONObject(client.search(query + ("pn" to first.getString("resolver_next_page")), "th", "7")).getJSONObject("data")
        assertEquals("media_bangumi", queries[0]["search_type"])
        assertEquals("cursor-one", queries[1]["resolver_cursor"])
        assertEquals("2", queries[1]["page"])
        assertEquals(1, second.getJSONArray("items").length())
    }

    @Test fun epOnlyDetailBuildsModulesAndBindsSubtitlesToLargeIdentifiers() {
        val client = NewResolverClient(config, { "" to 0L }) { area, path, query, _ ->
            if (path.endsWith("season")) {
                assertEquals("5338220", query["ep_id"])
                """{"code":0,"result":{"season_id":281544,"episodes":[{"id":5338220,"cid":40777680574,"aid":117070373062103}]}}"""
            } else {
                assertEquals("cn", area)
                assertEquals("/x/v2/dm/view", path)
                assertEquals("40777680574", query["oid"])
                assertEquals("117070373062103", query["pid"])
                """{"code":0,"data":{"subtitle":{"subtitles":[{"id":1,"lan":"en","lan_doc":"English","subtitle_url":"//captions.invalid/a.json"}]}}}"""
            }
        }
        assertNotNull(client.season(mapOf("season_id" to "0", "ep_id" to "5338220"), null))
        assertEquals("https://captions.invalid/a.json", client.subtitles(40777680574L, 117070373062103L).getJSONObject(0).getString("url"))
        assertEquals(0, client.subtitles(40777680574L, 5L).length())
    }

    @Test fun lateSubtitleCannotAttachAfterEpisodeContextChanges() {
        lateinit var client: NewResolverClient
        client = NewResolverClient(config, { "" to 0L }) { _, path, _, _ ->
            if (path == "/x/v2/dm/view") {
                client.play(mapOf("cid" to "11198631", "ep_id" to "96481", "aid" to "99"), listOf("cn"))
                """{"code":0,"data":{"subtitle":{"subtitles":[{"lan":"en","subtitle_url":"https://captions.invalid/old.json"}]}}}"""
            } else video
        }
        client.play(mapOf("cid" to "11198631", "ep_id" to "96480", "aid" to "6873386"), listOf("cn"))
        assertEquals(0, client.subtitles(11198631L, 6873386L).length())
    }

    @Test fun detailsAreCachedOnlyWithinTheClientConfiguration() {
        var requests = 0
        fun client(host: String) = NewResolverClient(ResolverConfig(true, mapOf("cn" to host)), { "" to 0L }) { _, _, _, _ ->
            requests++
            """{"code":0,"data":{"season_id":5526,"episodes":[{"id":96480,"cid":11198631,"aid":6873386}]}}"""
        }
        val first = client("https://one.invalid")
        first.season(mapOf("season_id" to "5526"), null)
        first.season(mapOf("season_id" to "5526"), null)
        client("https://other.invalid").season(mapOf("season_id" to "5526"), null)
        assertEquals(2, requests)
    }

    @Test fun auxiliaryModuleEpisodesRemainAvailableToSubtitleRequests() {
        val client = NewResolverClient(config, { "" to 0L }) { _, path, query, _ ->
            if (path.endsWith("season")) """{"code":0,"data":{"season_id":1,"modules":[
                {"data":{"episodes":[{"id":10,"cid":100,"aid":1000},{"id":11,"cid":101,"aid":1001}]}},
                {"data":{"episodes":[{"ep_id":12,"cid":40777680574,"aid":117070373062103,"rights":{"area_limit":1}}]}}
            ]}}"""
            else {
                assertEquals("40777680574", query["oid"])
                """{"code":0,"data":{"subtitle":{"subtitles":[{"lan":"en","subtitle_url":"https://captions.invalid/extra.json"}]}}}"""
            }
        }
        val season = JSONObject(client.season(mapOf("season_id" to "1"), null)!!).getJSONObject("result")
        val extra = season.getJSONArray("modules").getJSONObject(1).getJSONObject("data").getJSONArray("episodes").getJSONObject(0)
        assertEquals(12L, extra.getLong("id"))
        assertEquals(0, extra.getJSONObject("rights").getInt("area_limit"))
        assertEquals(1, client.subtitles(40777680574L, 117070373062103L).length())
    }

    @Test fun searchAuthenticationUsesOneSnapshotForQueryAndMetadata() {
        var reads = 0
        val client = NewResolverClient(config, { "fixture-${++reads}" to 0L }) { _, _, query, headers ->
            assertEquals("identify_v1 ${query["access_key"]}", headers["authorization"])
            """{"code":0,"data":{"result":[],"next":""}}"""
        }
        client.search(mapOf("keyword" to "test", "pn" to "1"), "th", "7")
        assertEquals(1, reads)
    }
}

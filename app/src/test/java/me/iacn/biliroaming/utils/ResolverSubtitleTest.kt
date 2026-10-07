package me.iacn.biliroaming.utils

import me.iacn.biliroaming.dmViewReply
import me.iacn.biliroaming.DmViewReply
import me.iacn.biliroaming.subtitleItem
import me.iacn.biliroaming.videoSubtitle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResolverSubtitleTest {
    @Test fun jsonSubtitlesArePreserved() {
        val text = """{"body":[{"from":1,"to":2,"content":"字幕","location":2}]}"""
        assertEquals(text, ResolverSubtitle.toJson(text))
    }

    @Test fun assUsesDeclaredFormatAndPreservesTextCommasAndNewlines() {
        val text = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:02.30,0:01:04.50,Default,,0,0,0,,{\i1}Hello, world\N第二行
        """.trimIndent()
        val cue = JSONObject(ResolverSubtitle.toJson(text)).getJSONArray("body").getJSONObject(0)
        assertEquals(62.3, cue.getDouble("from"), .001)
        assertEquals("Hello, world\n第二行", cue.getString("content"))
    }

    @Test fun webVttSupportsCueIdentifiersAndSettings() {
        val text = "WEBVTT\n\nNOTE ignored\ncomment\n\ncue-one\n00:01.500 --> 00:03.000 align:start\n<b>Hello</b> &amp; world\n第二行\n"
        val cue = JSONObject(ResolverSubtitle.toJson(text)).getJSONArray("body").getJSONObject(0)
        assertEquals(1.5, cue.getDouble("from"), .001)
        assertEquals("Hello & world\n第二行", cue.getString("content"))
    }

    @Test fun registeredCaptionsAlsoRecognizeGeneratedChineseVariant() {
        ResolverSubtitle.register("https://captions.invalid/a.ass?signature=fixture")
        assertTrue(ResolverSubtitle.recognizes("https://captions.invalid/a.ass?signature=fixture&zh_converter=t2cn"))
        assertFalse(ResolverSubtitle.recognizes("https://captions.invalid/other.ass"))
    }

    @Test fun mergingCaptionsPreservesOfficialDanmakuStateAndDeduplicates() {
        val existing = subtitleItem { id = 1; lan = "en"; subtitleUrl = "https://captions.invalid/a.json" }
        val extra = subtitleItem { id = 2; lan = "zh-CN"; subtitleUrl = "https://captions.invalid/b.json" }
        val original = dmViewReply { d = false; inputPlaceHolder = "官方状态"; subtitle = videoSubtitle { subtitles += existing } }
        val merged = ResolverSubtitle.merge(original, listOf(existing, extra, extra))
        assertEquals(2, merged.subtitle.subtitlesCount)
        assertFalse(merged.d)
        assertEquals("官方状态", merged.inputPlaceHolder)
    }

    @Test fun captionsCanBuildAReplyWhenOfficialReplyIsAbsent() {
        val extra = subtitleItem { id = 1; lan = "en"; subtitleUrl = "https://captions.invalid/a.json" }
        val response = ResolverSubtitle.merge(dmViewReply {}, listOf(extra))
        val parsed = DmViewReply::class.java.getMethod("parseFrom", ByteArray::class.java)
            .invoke(null, response.toByteArray()) as DmViewReply
        assertEquals(1, parsed.subtitle.subtitlesCount)
        assertEquals(extra.subtitleUrl, parsed.subtitle.getSubtitles(0).subtitleUrl)
    }
}

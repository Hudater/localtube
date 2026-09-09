package dev.hudater.localtube.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * These tests double as documentation of the info.json shape this parser
 * assumes. If you paste in a REAL info.json from your YTDLnis/Pinchflat
 * output and a test here fails, that's the schema drift to fix in
 * [YtDlpInfoJson] — see docs/ARCHITECTURE.md.
 */
class YtDlpMetadataParserTest {

    private val parser = YtDlpMetadataParser()

    @Test
    fun `parses a standalone video with full metadata`() {
        val jsonText = """
            {
              "id": "dQw4w9WgXcQ",
              "title": "Sample Video Title",
              "duration": 212.5,
              "channel": "Example Channel",
              "channel_id": "UC1234567890",
              "uploader": "Example Channel",
              "uploader_id": "UC1234567890"
            }
        """.trimIndent()

        val result = parser.parse(jsonText)

        assertEquals("dQw4w9WgXcQ", result?.platformVideoId)
        assertEquals("Sample Video Title", result?.title)
        assertEquals(212L, result?.durationSeconds)
        assertEquals("Example Channel", result?.channelName)
        assertEquals("UC1234567890", result?.channelId)
        assertNull(result?.playlistId)
        assertNull(result?.playlistIndex)
    }

    @Test
    fun `parses a video that is part of a playlist`() {
        val jsonText = """
            {
              "id": "abc123",
              "title": "Episode 3",
              "duration": 600,
              "channel": "Example Channel",
              "channel_id": "UC1234567890",
              "playlist": "My Course",
              "playlist_id": "PL0987654321",
              "playlist_index": 3
            }
        """.trimIndent()

        val result = parser.parse(jsonText)

        assertEquals("My Course", result?.playlistName)
        assertEquals("PL0987654321", result?.playlistId)
        assertEquals(3, result?.playlistIndex)
    }

    @Test
    fun `falls back to uploader fields when channel fields are absent`() {
        val jsonText = """
            {
              "id": "xyz",
              "title": "Old-style extractor output",
              "uploader": "Some Uploader",
              "uploader_id": "UCabcdef"
            }
        """.trimIndent()

        val result = parser.parse(jsonText)

        assertEquals("Some Uploader", result?.channelName)
        assertEquals("UCabcdef", result?.channelId)
    }

    @Test
    fun `missing optional fields do not crash parsing`() {
        val jsonText = """{ "id": "onlyid" }"""

        val result = parser.parse(jsonText)

        assertEquals("onlyid", result?.platformVideoId)
        assertNull(result?.title)
        assertNull(result?.durationSeconds)
        assertNull(result?.channelId)
    }

    @Test
    fun `unknown extra fields are ignored, not fatal`() {
        val jsonText = """
            {
              "id": "abc",
              "title": "T",
              "some_future_yt_dlp_field": { "nested": true },
              "another_new_field": [1, 2, 3]
            }
        """.trimIndent()

        val result = parser.parse(jsonText)

        assertEquals("abc", result?.platformVideoId)
    }

    @Test
    fun `malformed json returns null instead of throwing`() {
        val result = parser.parse("{ this is not valid json")

        assertNull(result)
    }
}

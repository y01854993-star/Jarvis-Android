package com.jarvis.tablet.data.vision

import com.jarvis.tablet.domain.vision.Frame
import com.jarvis.tablet.domain.vision.FrameSource
import com.jarvis.tablet.domain.vision.VlmRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleVlmTest {
    private fun frame(t: Long) = Frame(t, FrameSource.SCREEN, 10, 10, byteArrayOf(9, 8, 7), IntArray(72) { it })

    @Test fun payloadHasOneTextPartAndOneImagePartPerFrame() {
        val json = JSONObject(OpenAiCompatibleVlm.buildPayload("smolvlm2", VlmRequest(listOf(frame(0), frame(1000)), "what changed?", "")))
        assertEquals("smolvlm2", json.getString("model"))
        val content = json.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals(3, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertTrue(content.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
    }

    @Test fun parsesTheAssistantReply() {
        assertEquals("hello", OpenAiCompatibleVlm.parseReply("{\"choices\":[{\"message\":{\"content\":\" hello \"}}]}"))
    }

    @Test(expected = IllegalStateException::class) fun emptyReplyIsAnError() { OpenAiCompatibleVlm.parseReply("{\"choices\":[]}") }

    @Test fun describeUsesTheInjectedTransportAndAuthHeader() = runBlocking {
        var seenUrl = ""
        var seenAuth = ""
        val vlm = OpenAiCompatibleVlm("http://192.168.1.5:8000/v1/", "m", "secret", 1000) { url, headers, _, _ ->
            seenUrl = url; seenAuth = headers["Authorization"].orEmpty()
            "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"
        }
        val r = vlm.describe(VlmRequest(listOf(frame(0)), "q", ""))
        assertEquals("ok", r.text)
        assertEquals("http://192.168.1.5:8000/v1/chat/completions", seenUrl)
        assertEquals("Bearer secret", seenAuth)
    }
}

package com.odesaplay.oko.beta

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackPayloadTest {

    private fun bundle() = JSONObject()
        .put("fileName", "x.json")
        .put("logs", JSONObject().put("decisions", "heavy"))

    @Test
    fun `attach keeps logs and adds feedback without shot`() {
        val out = FeedbackPayload.attach(bundle(), FeedbackCategory.BUG, "  boom  ", null, true, 1L)
        assertEquals("bug", out.getJSONObject("feedback").getString("category"))
        assertEquals("boom", out.getJSONObject("feedback").getString("message"))
        assertEquals(1L, out.getJSONObject("feedback").getLong("atMillis"))
        assertFalse(out.has("shot"))
        assertTrue(out.has("logs"))
    }

    @Test
    fun `attach strips logs and adds shot when opted`() {
        val shot = FeedbackShot(720, 360, "abc")
        val out = FeedbackPayload.attach(
            bundle(), FeedbackCategory.QUESTION, "why?", shot, false, 2L
        )
        assertFalse(out.has("logs"))
        assertEquals(720, out.getJSONObject("shot").getInt("width"))
        assertEquals("abc", out.getJSONObject("shot").getString("jpegBase64"))
        assertTrue(out.has("fileName"))
    }

    @Test
    fun `attach never mutates the input bundle`() {
        val input = bundle()
        FeedbackPayload.attach(input, FeedbackCategory.IMPROVEMENT, "m", null, false, 3L)
        assertTrue(input.has("logs"))
        assertFalse(input.has("feedback"))
    }
}

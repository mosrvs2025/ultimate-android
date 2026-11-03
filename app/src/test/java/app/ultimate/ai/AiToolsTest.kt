package app.ultimate.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Answers below are verbatim from Needle 3 run on this tool set (linux engine, Nov 2026). */
@RunWith(RobolectricTestRunner::class)
class AiToolsTest {
    private val flashOn = """{"type":"call","success":true,"error":null,"function_calls":[{"name":"flashlight","arguments":{"on":true}}],"confidence":0.8727}"""
    private val misfire = """{"type":"call","success":true,"function_calls":[{"name":"record_audio","arguments":{}}],"confidence":0.45}"""
    private val none = """{"type":"call","success":true,"function_calls":[],"reasoning":"No tool available","confidence":0.64}"""

    @Test fun readsConfidentCallsAndDropsGuesses() {
        val c = AiTools.parse(flashOn).single()
        assertEquals("flashlight", c.name); assertEquals(true, c.args["on"]); assertEquals("Turn the flashlight on", AiTools.describe(c))
        assertTrue("a 0.45 guess (it was wrong) is dropped", AiTools.parse(misfire).isEmpty())
        assertTrue(AiTools.parse(none).isEmpty())
        assertTrue(AiTools.parse("not json").isEmpty())
        assertTrue(AiTools.parse("""{"success":false,"function_calls":[{"name":"x"}],"confidence":0.99}""").isEmpty())
    }

    @Test fun rulesGoToTheRuleMakerNotTheModel() {
        assertTrue(AiTools.isRule("When I push toward the phone, turn on the flashlight"))
        assertTrue(AiTools.isRule("every time I plug in, vibrate"))
        assertTrue(!AiTools.isRule("turn the volume down"))
    }

    @Test fun theToolListIsValidJsonWithAnEntryPerAction() {
        val arr = org.json.JSONArray(AiTools.TOOLS)
        assertEquals(10, arr.length())
        (0 until arr.length()).forEach { assertTrue(AiTools.describe(AiCall(arr.getJSONObject(it).getString("name"), emptyMap(), 1.0)) != arr.getJSONObject(it).getString("name")) }
        java.io.File("build/ai-tools.json").writeText(AiTools.TOOLS)
    }

    @Test fun volumeIsClampedInWords() {
        assertEquals("Set the media volume to 100%", AiTools.describe(AiCall("set_volume", mapOf("percent" to 140), 0.9)))
    }
}

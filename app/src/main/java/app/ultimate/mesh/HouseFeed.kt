package app.ultimate.mesh

import org.json.JSONArray
import org.json.JSONObject

/**
 * The device map as JSON for the headset view (served by the phone's own Wi-Fi server at /house). The mesh registers
 * [source] when it exists; without a map the page says how to make one.
 */
object HouseFeed {
    @Volatile var source: () -> String? = { null }

    fun json(map: HouseLayout.Result.Map, names: Map<String, String>, measured: List<HouseLayout.Measure>, you: WhereAmI.Estimate?): String {
        val bad = map.doubtful.map { setOf(it.a, it.b) }.toSet()
        return JSONObject().apply {
            put("devices", JSONArray().apply {
                map.at.forEach { (id, p) -> put(JSONObject().put("id", id).put("name", names[id] ?: id).put("x", p.x).put("y", p.y).put("nearest", id == you?.nearest)) }
            })
            put("links", JSONArray().apply {
                measured.filter { it.a in map.at && it.b in map.at }.forEach { m -> put(JSONObject().put("a", m.a).put("b", m.b).put("m", m.metres).put("bad", setOf(m.a, m.b) in bad)) }
            })
            put("you", you?.at?.let { JSONObject().put("x", it.x).put("y", it.y).put("r", you.radius).put("sure", you.sure) } ?: JSONObject.NULL)
            put("rms", map.rms)
        }.toString()
    }
}

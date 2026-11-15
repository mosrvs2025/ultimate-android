package app.ultimate.movement

import android.content.Context
import app.ultimate.fusion.Blueprints
import app.ultimate.fusion.MovementReport
import java.io.File

/** The places you go back to (see [Blueprints]), kept in one small private file next to your recordings. Nothing leaves the phone. */
class BlueprintStore(context: Context) {
    private val file = File(File(context.applicationContext.filesDir, "movement").also { it.mkdirs() }, "places.json")

    private fun load() = Blueprints.decode(runCatching { file.readText() }.getOrNull())
    private fun save(places: List<Blueprints.Place>) { runCatching { file.writeText(Blueprints.encode(places)) } }

    /** Notes that recording [id] happened, and says what it means: a visit to a named place, an offer to name one, or nothing. */
    @Synchronized fun see(id: String, r: MovementReport): Blueprints.Outcome {
        val path = r.path ?: return Blueprints.Outcome.Nothing
        val metres = r.distance?.value ?: return Blueprints.Outcome.Nothing
        val f = Blueprints.footprint(path, r.turns.size, metres) ?: return Blueprints.Outcome.Nothing
        val (next, outcome) = Blueprints.see(load(), id, f, metres, r.durationS)
        save(next)
        return outcome
    }

    /** Names a place and returns the outcome for [id] as a visit to it. */
    @Synchronized fun name(placeId: String, name: String, id: String): Blueprints.Outcome {
        val next = Blueprints.name(load(), placeId, name); save(next)
        val p = next.firstOrNull { it.id == placeId } ?: return Blueprints.Outcome.Nothing
        val i = p.runs.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: p.runs.lastIndex
        return Blueprints.Outcome.Visit(p, i + 1, null)
    }

    @Synchronized fun decline(placeId: String) = save(Blueprints.decline(load(), placeId))
}

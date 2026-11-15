package app.ultimate.social

import kotlin.math.roundToLong

/**
 * What a friend is allowed to see of a walk. Everything is off until the owner switches it on, and an exact position never
 * leaves the phone: if "where" is on, only the neighbourhood does (rounded to about 100 m).
 */
data class Share(val steps: Boolean = false, val distance: Boolean = false, val pace: Boolean = false, val heart: Boolean = false, val where: Boolean = false)

/** One moment of a walk as the phone knows it. */
data class WalkBeat(
    val startedAt: Long, val state: String, val steps: Long? = null, val distanceM: Double? = null, val paceSecPerKm: Double? = null,
    val heartBpm: Int? = null, val lat: Double? = null, val lon: Double? = null,
)

object WalkPrivacy {
    /** [beat] with everything the owner has not chosen to share removed (never mutated: a new beat is returned). */
    fun redact(beat: WalkBeat, share: Share): WalkBeat = WalkBeat(
        beat.startedAt, if (beat.state == "running") "running" else "walking",
        steps = beat.steps.takeIf { share.steps }, distanceM = beat.distanceM?.takeIf { share.distance }?.let { (it / 10).roundToLong() * 10.0 },
        paceSecPerKm = beat.paceSecPerKm?.takeIf { share.pace }?.let { (it / 5).roundToLong() * 5.0 },
        heartBpm = beat.heartBpm?.takeIf { share.heart && it in 30..230 },
        lat = beat.lat?.takeIf { share.where }?.let(::coarse), lon = beat.lon?.takeIf { share.where }?.let(::coarse),
    )

    /** A coordinate rounded to three decimals: about 100 m, the neighbourhood and not the doorstep. */
    fun coarse(degrees: Double) = (degrees * 1000).roundToLong() / 1000.0

    /** The line a friend's notification shows. The name is cleaned (no control characters, no web addresses, short). */
    fun announcement(name: String, state: String): String {
        val n = cleanName(name).ifBlank { "A friend" }
        return if (state == "running") "$n is going for a run. Join them?" else "$n is going for a walk. Join them?"
    }

    fun joined(name: String) = "${cleanName(name).ifBlank { "A friend" }} is joining your walk"

    fun cleanName(name: String): String = name.filter { it >= ' ' && it != '\u007f' }
        .replace(Regex("""(?i)(https?://|www\.)\S+"""), "").trim().take(24)
}

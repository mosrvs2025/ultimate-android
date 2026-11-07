package app.ultimate.spatial

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pure parts of Spatial: smoothing radio signals, turning them into proximity zones with hysteresis, walking a
 * path from steps and heading, building a signal heatmap, and recognising places from Wi-Fi fingerprints. Nothing
 * here pretends to know distances in metres: Bluetooth and Wi-Fi strength depend on bodies, walls and pockets far more
 * than on distance, so the outputs are zones and similarities with an honest confidence. All tested.
 */

/** A one-dimensional Kalman filter for a noisy signal (dBm): [estimate] and how unsure it is ([variance]). */
class RssiFilter(private val processNoise: Double = 0.8, private val measureNoise: Double = 16.0) {
    var estimate = Double.NaN; private set
    var variance = 100.0; private set
    var lastAt = 0L; private set
    fun feed(rssi: Double, t: Long): Double {
        if (estimate.isNaN()) { estimate = rssi; variance = measureNoise; lastAt = t; return estimate }
        // Uncertainty grows with time since the last reading.
        variance += processNoise * ((t - lastAt).coerceAtLeast(0) / 1000.0).coerceIn(0.1, 30.0)
        val k = variance / (variance + measureNoise)
        estimate += k * (rssi - estimate); variance *= (1 - k); lastAt = t
        return estimate
    }
    /** 0..1: high when readings are fresh and steady. */
    fun confidence(now: Long): Double {
        if (estimate.isNaN()) return 0.0
        val age = (now - lastAt) / 1000.0
        return (1.0 / (1.0 + variance / 8.0)) * exp(-age / 6.0)
    }
}

/**
 * How close the watch is to the phone, from Bluetooth signal strength, in three zones with a dead band between them
 * so a wobbling signal doesn't flicker. Calibrated on your own phone and watch: [touch] is the strength with the
 * watch held right at the phone, [arm] at arm's length.
 */
class ProximityZones(var touch: Double = -45.0, var arm: Double = -70.0, private val band: Double = 4.0, private val dwellMs: Long = 1_200) {
    enum class Zone { Near, Mid, Far, Gone }
    var zone = Zone.Gone; private set
    private var pending: Zone? = null
    private var since = 0L

    private fun raw(rssi: Double, current: Zone): Zone {
        val nearLine = touch + (arm - touch) * 0.35; val farLine = arm - 4
        return when (current) {
            Zone.Near -> if (rssi < nearLine - band) (if (rssi < farLine - band) Zone.Far else Zone.Mid) else Zone.Near
            Zone.Far, Zone.Gone -> if (rssi > farLine + band) (if (rssi > nearLine + band) Zone.Near else Zone.Mid) else Zone.Far
            Zone.Mid -> when { rssi > nearLine + band -> Zone.Near; rssi < farLine - band -> Zone.Far; else -> Zone.Mid }
        }
    }

    /** Feeds a smoothed strength (or null when the watch hasn't been heard for a while); returns the zone it moved to, once it has held. */
    fun feed(rssi: Double?, t: Long): Zone? {
        val want = if (rssi == null) Zone.Gone else raw(rssi, zone)
        if (want == zone) { pending = null; return null }
        if (want != pending) { pending = want; since = t; return null }
        if (t - since < dwellMs) return null
        zone = want; pending = null
        return zone
    }

    companion object {
        /** A calibration from readings taken at the phone and at arm's length; null if they don't differ enough to tell apart. */
        fun calibrate(atPhone: List<Double>, atArm: List<Double>): Pair<Double, Double>? {
            if (atPhone.size < 5 || atArm.size < 5) return null
            val a = atPhone.sorted()[atPhone.size / 2]; val b = atArm.sorted()[atArm.size / 2]
            return if (a - b >= 8) a to b else null
        }
    }
}

/** Walking a path: each step moves [stepM] metres along the phone's heading (radians, 0 = north, clockwise). */
class StepPath(private val stepM: Double = 0.7) {
    var x = 0.0; private set
    var y = 0.0; private set
    val points = ArrayList<Pair<Double, Double>>().apply { add(0.0 to 0.0) }
    fun step(heading: Double) { x += stepM * sin(heading); y -= stepM * cos(heading); points += x to y }
    fun reset() { x = 0.0; y = 0.0; points.clear(); points += 0.0 to 0.0 }
}

/** Signal strength laid out where you walked, in cells of [cellM] metres; weak cells are dead zones. */
class Heatmap(private val cellM: Double = 0.75, private val deadBelow: Double = -75.0) {
    data class Cell(val cx: Int, val cy: Int, val meanDbm: Double, val samples: Int) { val dead get() = meanDbm < -75.0 }
    private val sums = HashMap<Pair<Int, Int>, Pair<Double, Int>>()
    fun add(x: Double, y: Double, dbm: Double) {
        val k = floor(x / cellM).toInt() to floor(y / cellM).toInt()
        val (s, n) = sums[k] ?: (0.0 to 0); sums[k] = (s + dbm) to (n + 1)
    }
    fun cells(): List<Cell> = sums.map { (k, v) -> Cell(k.first, k.second, v.first / v.second, v.second) }
    fun deadZones() = cells().filter { it.meanDbm < deadBelow }
    fun clear() = sums.clear()
    val cellSize get() = cellM
}

/**
 * Places, recognised by the Wi-Fi networks you can hear and how strongly (a "fingerprint"). Similar fingerprints mean
 * you're probably in the same place; it says nothing about the room's shape or size.
 */
object Fingerprints {
    /** 0..1: how alike two fingerprints are, weighing the networks both hear by how close their strengths are. */
    fun similarity(a: Map<String, Int>, b: Map<String, Int>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.keys intersect b.keys
        if (shared.isEmpty()) return 0.0
        val diff = shared.map { abs(a.getValue(it) - b.getValue(it)) }.average()
        val overlap = shared.size.toDouble() / (a.keys union b.keys).size
        return overlap * (1.0 / (1.0 + diff / 8.0))
    }

    data class Match(val place: String, val score: Double, val confidence: Double)
    /** The best matching saved place, with confidence from how far it beats the runner-up; null when nothing is close. */
    fun recognise(now: Map<String, Int>, places: Map<String, List<Map<String, Int>>>, min: Double = 0.25): Match? {
        val scored = places.mapValues { (_, prints) -> prints.maxOfOrNull { similarity(now, it) } ?: 0.0 }.entries.sortedByDescending { it.value }
        val best = scored.firstOrNull() ?: return null
        if (best.value < min) return null
        val second = scored.getOrNull(1)?.value ?: 0.0
        return Match(best.key, best.value, ((best.value - second) / best.value).coerceIn(0.0, 1.0) * (best.value / 0.6).coerceAtMost(1.0))
    }
}

/**
 * The shared spatial model: what each sense says right now, how sure it is, and when it last spoke. A sense that
 * hasn't been heard from fades out instead of holding a stale value, and a missing one is simply absent.
 */
data class Reading(val value: String, val confidence: Double, val at: Long)
class SpatialModel(private val staleMs: Long = 15_000) {
    private val readings = HashMap<String, Reading>()
    fun put(sense: String, value: String, confidence: Double, at: Long) { readings[sense] = Reading(value, confidence.coerceIn(0.0, 1.0), at) }
    /** Every sense with its confidence faded by age; stale ones are dropped. */
    fun now(t: Long): Map<String, Reading> = readings.filterValues { t - it.at <= staleMs }.mapValues { (_, r) -> r.copy(confidence = r.confidence * (1.0 - (t - r.at).toDouble() / staleMs).coerceAtLeast(0.0)) }
    /** Overall confidence: the senses agree when several are fresh. */
    fun overall(t: Long) = now(t).values.map { it.confidence }.let { if (it.isEmpty()) 0.0 else 1 - it.fold(1.0) { acc, c -> acc * (1 - c) } }
}

/** Distance of a 2D point from the origin, for the map. */
fun dist(x: Double, y: Double) = sqrt(x * x + y * y)

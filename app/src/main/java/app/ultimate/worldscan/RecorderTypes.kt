package app.ultimate.worldscan

import java.io.File

/** What the recorder wrote, for the walk's record and for honest reporting (a truncated log must say so). */
class RecorderSummary(val file: File, val bytes: Long, val truncated: Boolean, val counts: Map<String, Int>, val sensorCounts: Map<Int, Int>, val fixes: Int, val medianAccuracyM: Double?, val sensorsAvailable: List<Int>)

/** The latest GPS state for the screen: how good the last fix is and how many fixes have arrived. */
class GnssLive(val accuracyM: Float? = null, val fixes: Int = 0, val lastFixAgeMs: Long = Long.MAX_VALUE, val provider: String? = null, val enabled: Boolean = true)

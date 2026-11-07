package app.ultimate.health

/**
 * Turns the watch's heart rate into moments rules can react to: "heart_high" once your heart rate has stayed at or
 * above [high] for [holdMs] (a stress spike, or exercise), and "heart_calm" once it has come back down below
 * [high] - 10 for as long. Brief jumps (standing up, a sneeze) don't count. Pure, so it is tested.
 */
class HeartWatch(var high: Int = 110, private val holdMs: Long = 60_000) {
    private var aboveSince = 0L
    private var belowSince = 0L
    var raised = false; private set

    fun feed(bpm: Int, t: Long): String? {
        if (bpm >= high) { belowSince = 0; if (aboveSince == 0L) aboveSince = t } else aboveSince = 0
        if (bpm < high - 10) { if (belowSince == 0L) belowSince = t } else belowSince = 0
        if (!raised && aboveSince != 0L && t - aboveSince >= holdMs) { raised = true; return "heart_high" }
        if (raised && belowSince != 0L && t - belowSince >= holdMs) { raised = false; return "heart_calm" }
        return null
    }
}

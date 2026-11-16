package app.ultimate.wear

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Tells "still", "walking" and "running" apart from the watch's step counter alone, the cheapest sensor it has: the hardware
 * counts steps and hands them over in batches, so the watch sleeps in between. A state only changes after it has held (about
 * a minute to start walking, two minutes of near-stillness to stop), so a few steps across a room do not start anything.
 * Pure, so it is tested.
 */
class StepActivity(
    private val startWalkSpm: Double = 60.0,
    private val runSpm: Double = 140.0,
    private val stillSpm: Double = 20.0,
    private val holdStartMs: Long = 60_000,
    private val holdStopMs: Long = 120_000,
) {
    enum class State(val key: String) { Still("still"), Walking("walking"), Running("running") }

    /** [since] is roughly when the steady movement began (epoch ms): when the watch first saw the pace, less the half minute it needs to measure one. */
    data class Change(val state: State, val spm: Int, val steps: Long, val since: Long = 0L)

    var state = State.Still; private set
    /** Steps a minute over the last minute, or 0 until there is a minute of data. */
    var spm = 0.0; private set
    private val samples = ArrayDeque<Pair<Long, Long>>() // (time ms, cumulative count)
    private var candidate: State? = null
    private var candidateSince = 0L
    private var first: Long? = null
    val stepsSinceStart: Long get() = (samples.lastOrNull()?.second ?: 0L) - (first ?: 0L)

    /** Feeds one step-counter reading (it only ever grows until the watch restarts). Returns a change of state, or null. */
    fun feed(count: Long, now: Long): Change? {
        val last = samples.lastOrNull()
        if (last != null && count < last.second) samples.clear()      // the counter reset (watch rebooted)
        if (first == null) first = count
        samples.addLast(now to count)
        while (samples.size > 2 && now - samples[1].first >= 60_000) samples.removeFirst()
        val a = samples.first()
        val dt = now - a.first
        spm = if (dt >= 30_000) (count - a.second) * 60_000.0 / dt else 0.0
        val target = when {
            spm >= runSpm -> State.Running
            spm >= startWalkSpm -> State.Walking
            spm <= stillSpm -> State.Still
            else -> state      // in between: keep what it was
        }
        if (target == state) { candidate = null; return null }
        if (candidate != target) { candidate = target; candidateSince = now; return null }
        val hold = if (target == State.Still) holdStopMs else holdStartMs
        if (now - candidateSince < hold) return null
        val since = (candidateSince - 30_000L).coerceAtLeast(0L)
        state = target; candidate = null
        return Change(target, spm.toInt(), stepsSinceStart, since)
    }

    companion object {
        /** What the phone last said about a movement recording, for the watch screen: null when it is not recording. */
        val phoneRecording = MutableStateFlow<PhoneRecording?>(null)

        /** Reads a "movement:..." message from the phone into [phoneRecording]. Returns true if it was one. */
        fun fromPhone(text: String, now: Long = System.currentTimeMillis()): Boolean {
            when {
                text == "movement:start" -> phoneRecording.value = PhoneRecording(0.0, 0, now)
                text == "movement:stop" -> phoneRecording.value = null
                text.startsWith("movement:status:") -> {
                    val p = text.removePrefix("movement:status:").split(':')
                    phoneRecording.value = PhoneRecording(p.getOrNull(0)?.toDoubleOrNull() ?: 0.0, p.getOrNull(1)?.toIntOrNull() ?: 0, now)
                }
                else -> return false
            }
            return true
        }
    }
}

/** A recording running on the phone, as last reported: metres, seconds, and when the report arrived. */
data class PhoneRecording(val metres: Double, val seconds: Int, val at: Long)

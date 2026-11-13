package app.ultimate.mesh

import android.content.Context
import android.os.Build
import app.ultimate.UltimateApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * What this phone does when another of the owner's devices asks. Every request except a ping is refused unless the
 * owner has switched on "Lend this phone"; a recording shows in Live now with a Stop button and in the mesh
 * notification for as long as it runs, so nothing is ever recorded or played here without being visible.
 */
class MeshHost(context: Context, private val lends: () -> Boolean, private val onChange: () -> Unit, private val notifyPing: (String) -> Unit) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val recorder = MotionRecorder(app)
    private val ranger = PeerRanger(app)

    /** Who is recording here, for the banner; null when nothing is. */
    @Volatile var recordingFor: String? = null; private set
    @Volatile private var rangeResult: PeerRanger.Outcome? = null
    @Volatile private var rangeBusy = false

    private var cache: Pair<Int, RepCounter.Summary>? = null

    fun handle(from: MeshNode.Peer, cmd: String, args: Map<String, String>): Map<String, String> {
        if (cmd == Cmd.PING) { notifyPing(from.name); return mapOf("model" to Build.MODEL) }
        if (!lends()) throw IllegalStateException("this phone is not lending its sensors: turn on \"Lend this phone\" in Octopus Mesh there")
        return when (cmd) {
            Cmd.REC_START -> {
                if (recorder.running) throw IllegalStateException("this phone is already recording")
                recorder.start(args["rate"]?.toIntOrNull() ?: 50)?.let { throw IllegalStateException(it) }
                recordingFor = from.name
                senses().begin(SESSION, "mesh", "Recording motion for ${from.name}", sensitive = true) { stopRecording() }
                onChange()
                mapOf("state" to "recording", "gyro" to if (recorder.hasGyroscope) "1" else "0")
            }
            Cmd.REC_STOP -> { stopRecording(); status(final = true) }
            Cmd.REC_STATUS -> status(final = false)
            Cmd.REC_FETCH -> {
                val from0 = args["from"]?.toIntOrNull() ?: 0
                val log = recorder.log
                mapOf("rows" to log.rows.toString(), "from" to from0.toString(), "data" to log.encode(from0, MotionLog.CHUNK_ROWS))
            }
            Cmd.RANGE_ARM -> {
                if (rangeBusy) throw IllegalStateException("this phone is already measuring")
                if (!MeshProfiles.canRange(app)) throw IllegalStateException("this phone cannot play or hear the sound used")
                rangeBusy = true; rangeResult = null
                senses().begin(RANGE, "mesh", "Measuring distance for ${from.name}", sensitive = true) { }
                scope.launch {
                    try { rangeResult = ranger.run(PeerRange.Role.Callee) } finally { rangeBusy = false; senses().end(RANGE) }
                }
                mapOf("state" to "armed")
            }
            Cmd.RANGE_RESULT -> when (val r = rangeResult) {
                null -> mapOf("state" to if (rangeBusy) "pending" else "none")
                is PeerRanger.Outcome.Failed -> mapOf("state" to "failed", "why" to r.why)
                is PeerRanger.Outcome.Done -> mapOf("state" to "done") + encode(r.side)
            }
            else -> throw IllegalStateException("unknown command")
        }
    }

    fun stopRecording() {
        recorder.stop(); recordingFor = null; senses().end(SESSION); onChange()
    }

    private fun senses() = (app as UltimateApp).senses

    /** Where the recording stands, with the repetition estimate over everything so far (cached, since it costs a little). */
    private fun status(final: Boolean): Map<String, String> {
        val log = recorder.log
        val summary = cache?.takeIf { !final && log.rows - it.first < 100 }?.second ?: run {
            val (t, a) = log.axes()
            RepCounter.analyze(t, a).also { cache = log.rows to it }
        }
        return buildMap {
            put("state", if (recorder.running) "recording" else "stopped")
            put("rows", log.rows.toString()); put("ms", log.durationMs.toString())
            put("reps", summary.reps.toString()); put("sets", summary.sets.size.toString())
            put("active", "%.1f".format(java.util.Locale.US, summary.activeSeconds)); put("rms", "%.2f".format(java.util.Locale.US, summary.rms))
            put("setsDetail", summary.sets.joinToString(";") { "%.1f:%.1f:%d:%.1f:%.2f".format(java.util.Locale.US, it.startS, it.endS, it.reps, it.perMinute, it.intensity) })
        }
    }

    private fun encode(s: PeerRange.Side) = mapOf(
        "ownPos" to s.own.position.toString(), "ownStr" to s.own.strength.toString(), "ownNoise" to s.own.noise.toString(),
        "otherPos" to s.other.position.toString(), "otherStr" to s.other.strength.toString(), "otherNoise" to s.other.noise.toString(),
    )

    companion object {
        const val SESSION = "mesh.record"
        const val RANGE = "mesh.range"
        fun decodeSide(p: Map<String, String>): PeerRange.Side? = runCatching {
            PeerRange.Side(
                PeerRange.Detection(p.getValue("ownPos").toDouble(), p.getValue("ownStr").toDouble(), p.getValue("ownNoise").toDouble()),
                PeerRange.Detection(p.getValue("otherPos").toDouble(), p.getValue("otherStr").toDouble(), p.getValue("otherNoise").toDouble()),
            )
        }.getOrNull()
    }
}

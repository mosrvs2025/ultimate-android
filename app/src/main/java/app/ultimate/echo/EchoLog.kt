package app.ultimate.echo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * A recording of what Echo measured, frame by frame, to share for diagnosis: no audio, only numbers. Markers you tap
 * ("hand at 20 cm", "hand away") are written in line with the frames, so a session can be replayed against the truth.
 */
class EchoLog {
    private val lines = ArrayList<String>()
    private var startedAt = 0L
    var recording = false; private set
    var marker: String? = null

    fun start() { lines.clear(); startedAt = System.currentTimeMillis(); recording = true; lines += HEADER }

    fun mark(label: String) { marker = label }

    fun add(f: SonarFrame) {
        if (!recording) return
        val m = marker; marker = null
        lines += row(System.currentTimeMillis() - startedAt, f, m)
    }

    val frames get() = (lines.size - 1).coerceAtLeast(0)

    /** Stops and writes the file; returns a shareable address. */
    fun finish(context: Context): Uri? {
        recording = false
        if (lines.size <= 1) return null
        val dir = File(context.cacheDir, "echo").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val file = File(dir, "echo-session-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.csv")
        file.writeText(lines.joinToString("\n"))
        return FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }

    companion object {
        const val HEADER = "t_ms,range_cm,confidence,velocity_m_s,link_snr,phone_moving,stereo,process_ms,marker,peaks_cm_score"

        /** One frame as a CSV line. The five strongest local peaks of the motion profile go last, as distance:score pairs. */
        fun row(t: Long, f: SonarFrame, marker: String?): String {
            val m = f.fix.motion
            val peaks = (1 until m.size - 1).filter { m[it] >= m[it - 1] && m[it] >= m[it + 1] && m[it] > 1.5f }
                .sortedByDescending { m[it] }.take(5)
                .joinToString(";") { "%.1f:%.1f".format(java.util.Locale.US, it * Sonar.M_PER_SAMPLE * 100, m[it]) }
            fun d(x: Double, fmt: String) = if (x.isNaN()) "" else fmt.format(java.util.Locale.US, x)
            return listOf(
                t.toString(), d(f.fix.rangeM * 100, "%.1f"), d(f.fix.confidence, "%.2f"), d(f.fix.velocity, "%.3f"), d(f.fix.linkSnr, "%.0f"),
                if (f.phoneMoving) "1" else "0", if (f.stereo) "1" else "0", "%.2f".format(java.util.Locale.US, f.processMs), marker.orEmpty().replace(",", " "), peaks,
            ).joinToString(",")
        }
    }
}

package app.ultimate.air

import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.Locale
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Records what Air mode's senses saw and what you did, for training your own model later: hand points (never camera
 * pictures), the pinch gap, the recognised shape, the watch's motion, and events (taps, drags, shortcuts, holds you
 * abandoned). Only while you turn it on; kept on the phone, compressed, the oldest dropped past [capBytes]; you can
 * export them as one zip to send to whoever trains the model. Line formats are in [Lines].
 */
class SenseRecorder(private val dir: File, private val capBytes: Long = 50L * 1024 * 1024) {
    private var out: Writer? = null
    var file: File? = null; private set

    /** Lines, one per reading, comma-separated, times in milliseconds. Pure, so it is tested. */
    object Lines {
        fun hand(t: Long, sign: String?, score: Float, gap: Float, points: List<Pair<Float, Float>>) =
            buildString {
                append("h,").append(t).append(',').append(sign ?: "").append(',').append(f(score)).append(',').append(f(gap))
                points.forEach { (x, y) -> append(',').append(f(x)).append(',').append(f(y)) }
            }
        fun watch(t: Long, gx: Float, gy: Float, gz: Float, ax: Float, ay: Float, az: Float) =
            "w,$t,${f(gx)},${f(gy)},${f(gz)},${f(ax)},${f(ay)},${f(az)}"
        fun event(t: Long, kind: String, detail: String = "") = "e,$t,$kind,${detail.replace(',', ';').replace('\n', ' ')}"
        const val HEADER = "# Ultimate sense recording v1. h,t,sign,score,gap,x0,y0..x20,y20 | w,t,gx,gy,gz,ax,ay,az | e,t,kind,detail"
        private fun f(v: Float) = String.format(Locale.ROOT, "%.4f", v)
    }

    @Synchronized fun start(now: Long) {
        if (out != null) return
        dir.mkdirs(); trim()
        val f = File(dir, "session-$now.csv.gz"); file = f
        out = OutputStreamWriter(GZIPOutputStream(f.outputStream()), Charsets.UTF_8).also { it.write(Lines.HEADER + "\n") }
    }
    @Synchronized fun write(line: String) { runCatching { out?.write(line); out?.write("\n") } }
    @Synchronized fun stop() { runCatching { out?.close() }; out = null }
    val recording get() = out != null

    /** Total size of what's kept. */
    fun bytes() = dir.listFiles().orEmpty().sumOf { it.length() }
    fun sessions() = dir.listFiles().orEmpty().filter { it.name.endsWith(".csv.gz") }.sortedBy { it.name }

    /** Drops the oldest sessions until everything fits under the cap. */
    fun trim() { val s = sessions().toMutableList(); var total = s.sumOf { it.length() }; while (total > capBytes && s.size > 1) { total -= s.first().length(); s.removeAt(0).delete() } }

    /** Every session in one zip, for sending; returns null when there's nothing yet. */
    fun export(to: File): File? {
        val s = sessions().filter { it != file || out == null }
        if (s.isEmpty()) return null
        ZipOutputStream(to.outputStream()).use { z -> s.forEach { f -> z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
        return to
    }
    fun deleteAll() { stop(); dir.listFiles()?.forEach { it.delete() } }
}

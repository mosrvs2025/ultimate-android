package app.ultimate.movement

import app.ultimate.fusion.MovementAnalyzer
import app.ultimate.mesh.MotionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.math.PI
import kotlin.math.sin

/** The store keeps raw recordings re-readable, tolerates a log cut short, and puts a recording from another device through the same analysis. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MovementStoreTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    /** A SIMULATED 60 s walk's accelerometer and gyroscope, as a mesh recording would carry. */
    private fun fakeMotion(seconds: Int = 60): MotionLog {
        val log = MotionLog()
        for (i in 0 until seconds * 50) {
            val t = i / 50.0
            val bounce = if (t > 6) 3.0 * sin(2 * PI * 1.8 * t) else 0.0
            log.add(i * 20, 0f, 0f, (9.81 + bounce).toFloat(), 0f, 0f, 0f)
        }
        return log
    }

    @Test fun aRecordingFromAnotherDeviceIsKeptAndAnalysedLikeAnyOther() {
        val store = MovementStore(ctx)
        val e = store.importMotion(fakeMotion(), "Z Flip6", 1_000L)
        assertNotNull(e)
        assertEquals(MovementStore.Origin.Mesh, e!!.origin)
        assertTrue(store.list().any { it.id == e.id })
        val report = MovementAnalyzer.analyze(store.read(e))
        assertTrue("steps ${report.steps?.value}", (report.steps?.value ?: 0.0) in 80.0..110.0)   // 54 s at 1.8 Hz is about 97 steps
        assertEquals(60.0, report.durationS, 1.5)
        // Only inertial sensors: it says so rather than pretending to have checked the distance against anything.
        assertEquals(listOf("inertial"), report.sources)
        assertEquals(1, report.fusion.sources.size)
        store.delete(e)
        assertTrue(store.list().none { it.id == e.id })
    }

    @Test fun aLogCutShortStillGivesWhatWasWritten() {
        val store = MovementStore(ctx)
        val file = File(ctx.cacheDir, "cut.jsonl.gz")
        GZIPOutputStream(file.outputStream()).bufferedWriter().use { w -> repeat(500) { w.write("{\"type\":\"sensor\",\"s\":1,\"t\":${1_000_000_000L + it * 20_000_000L},\"a\":3,\"v\":[0,0,9.8]}\n") } }
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size - 12))        // the phone died mid-write
        val n = store.lines(file).count()
        assertTrue("read $n lines", n in 100..500)
    }

    @Test fun aTooShortRecordingIsNotKept() {
        assertNull(MovementStore(ctx).importMotion(MotionLog().also { l -> repeat(20) { l.add(it, 0f, 0f, 9.8f, 0f, 0f, 0f) } }, "x"))
    }

    @Test fun calibrationSurvivesBeingSavedAndReset() {
        val store = MovementStore(ctx)
        val cal = store.calibrator()
        cal.learn("d|walk|stride", 400.0, app.ultimate.fusion.Truth(448.0, app.ultimate.fusion.TruthKind.KnownCourse))
        store.saveCalibrator(cal)
        assertEquals(1, store.calibrator().runs("d|walk|stride"))
        store.clearCalibration()
        assertEquals(0, store.calibrator().runs("d|walk|stride"))
    }
}

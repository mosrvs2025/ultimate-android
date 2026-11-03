package app.ultimate.echo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoBenchTest {
    private fun frame(rangeCm: Double?, moving: Boolean = false) = SonarFrame(
        SonarTracker.Fix(0.0, rangeCm?.div(100) ?: Double.NaN, 6.0, Double.NaN, 50.0, FloatArray(0), FloatArray(0)),
        null, null, null, FloatArray(0), moving, 2.5f, false,
    )

    @Test fun reportsAccuracyFalseAlarmsAndIgnoresFramesWhileThePhoneMoves() {
        val still = List(18) { frame(null) } + List(2) { frame(40.0) }
        val hold = List(10) { frame(21.0 + (it % 3) - 1) } + listOf(frame(90.0, moving = true)) + List(2) { frame(null) }
        val r = BenchMath.report("Test phone", still, listOf(20 to hold), List(30) { 2.5f }, listOf(300.0, 310.0), listOf(380.0, 390.0), false)
        assertEquals(0.1, r.falseRate, 1e-9)
        val row = r.rows.single()
        assertEquals(21.0, row.medianCm!!, 1e-9)
        assertEquals(10.0 / 13, row.detectRate, 1e-9)
        assertTrue(r.text, r.text.contains("Hand at 20 cm: read 21.0 cm (error +1.0)"))
        assertTrue(r.text.contains("+80 mA"))
    }

    @Test fun phonesThatDontReportCurrentSaySo() {
        val r = BenchMath.report("x", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
        assertTrue(r.text.contains("doesn't report its current"))
    }
}

package app.ultimate.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Things a real 37-minute backyard walk showed (900 m ± 24 m from a GPS that saw half the time and steps that saw the rest). */
class RealWalkLessonsTest {
    private class Fake(override val name: String, override val relSigma: Double, override val absSigma: Double, private val seen: ClosedFloatingPointRange<Double>, private val speed: Double) : DistanceSource {
        override val basis = Basis.Derived
        override val blind = emptyList<Span>()
        override val usable = true
        override fun length(span: Span): Double? = if (span.from >= seen.start && span.to <= seen.endInclusive) speed * (span.to - span.from) else null
        override fun position(t: Double): DoubleArray? = null
    }

    @Test fun `a precise sensor that saw half the walk does not vouch for the half it missed`() {
        // Steps (±24% on what they measured) saw the first 850 s; GPS (±24 m offset, 2% scale) saw the later 1140 s. Little overlap.
        val steps = Fake("inertial steps", 0.24, 5.0, 0.0..850.0, 1.03)
        val gps = Fake("GPS", 0.02, 24.0, 700.0..1840.0, 0.25)
        val r = Fusion.run(listOf(steps, gps), 2238.0).distance!!
        // About 700 m of the total came from steps alone: its ±24% is about ±170 m, not ±24 m.
        assertTrue("sigma ${r.sigma} for ${r.value}", r.sigma > 120.0)
        assertTrue("sigma ${r.sigma} should still be a sensible size", r.sigma < 400.0)
    }

    @Test fun `two sensors that both saw everything are still sharper than either alone`() {
        val steps = Fake("inertial steps", 0.10, 3.0, 0.0..600.0, 1.2)
        val gps = Fake("GPS", 0.02, 8.0, 0.0..600.0, 1.2)
        val r = Fusion.run(listOf(steps, gps), 600.0).distance!!
        assertEquals(720.0, r.value, 1.0)
        assertTrue("sigma ${r.sigma}", r.sigma < 0.10 * r.value)          // better than the steps' 10%
        assertTrue("sigma ${r.sigma} must still cover GPS's own error", r.sigma >= 8.0)
    }

    private fun seg(a: Double, b: Double, g: Gait) = GaitSegment(Span(a, b), g, null, 1.0)

    @Test fun `unsteady blips at every turn do not split a walk into pieces`() {
        val raw = listOf(seg(0.0, 88.0, Gait.Walking), seg(88.0, 94.0, Gait.Other), seg(94.0, 150.0, Gait.Walking), seg(148.0, 152.0, Gait.Other), seg(152.0, 220.0, Gait.Walking))
        val s = Events.smooth(raw)
        assertEquals(1, s.size); assertEquals(Gait.Walking, s[0].gait); assertEquals(0.0, s[0].span.from, 0.0); assertEquals(220.0, s[0].span.to, 0.0)
    }

    @Test fun `a few seconds of running between walking is not running but half a minute is`() {
        val brief = Events.smooth(listOf(seg(0.0, 86.0, Gait.Walking), seg(86.0, 90.0, Gait.Running), seg(88.0, 120.0, Gait.Walking)))
        assertEquals(listOf(Gait.Walking), brief.map { it.gait })
        val long = Events.smooth(listOf(seg(0.0, 118.0, Gait.Walking), seg(118.0, 152.0, Gait.Running), seg(150.0, 200.0, Gait.Walking)))
        assertEquals(listOf(Gait.Walking, Gait.Running, Gait.Walking), long.map { it.gait })
    }

    @Test fun `a real pause or a long unsteady stretch is kept`() {
        val raw = listOf(seg(0.0, 100.0, Gait.Walking), seg(100.0, 130.0, Gait.Still), seg(130.0, 200.0, Gait.Walking), seg(200.0, 230.0, Gait.Other), seg(230.0, 300.0, Gait.Walking))
        assertEquals(raw.map { it.gait }, Events.smooth(raw).map { it.gait })     // 30 s each: neither is a blip
    }

    @Test fun `standing still with fidgeting stays one still stretch`() {
        val raw = listOf(seg(1510.0, 1534.0, Gait.Still), seg(1532.0, 1538.0, Gait.Other), seg(1536.0, 1574.0, Gait.Still), seg(1572.0, 1576.0, Gait.Other), seg(1574.0, 1594.0, Gait.Still))
        val s = Events.smooth(raw); assertEquals(1, s.size); assertEquals(Gait.Still, s[0].gait); assertEquals(1594.0, s[0].span.to, 0.0)
    }

    @Test fun `nothing to smooth is returned unchanged`() {
        assertEquals(emptyList<GaitSegment>(), Events.smooth(emptyList()))
        val one = listOf(seg(0.0, 10.0, Gait.Running)); assertEquals(one, Events.smooth(one))   // alone, running is all there is
    }
}

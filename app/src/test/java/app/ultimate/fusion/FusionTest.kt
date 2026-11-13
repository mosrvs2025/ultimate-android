package app.ultimate.fusion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Every scenario below is a SIMULATION of a walk on a known course (see [WalkSim]); it checks the analysis against the truth the simulation knows. */
class FusionTest {
    private fun run(c: SimConfig, cal: Calibrator? = null, device: String = "test", motion: String = "walk"): Pair<MovementReport, SimOut> {
        val sim = WalkSim.run(c)
        val trace = Trace.read(sim.lines.asSequence())
        return MovementAnalyzer.analyze(trace, cal, device, motion) to sim
    }
    private fun pct(a: Double, truth: Double) = 100 * (a - truth) / truth

    // ---- reading the real log format ----

    @Test fun theRealLogFormatIsReadWithAllSensorsOnOneClock() {
        val sim = WalkSim.run(SimConfig(laps = 1))
        val t = Trace.read(sim.lines.asSequence())
        assertTrue(t.hasImu && t.hasPoses && t.hasFixes)
        assertEquals(50.0, t.imu.rate, 1.0)
        assertTrue("clock note: ${t.poseClock}", t.poseClock.startsWith("frame clock mapped"))
        // The camera runs on a clock 5 s away from the sensors'; after mapping, its first and last poses land within the recording.
        assertTrue(t.poses.first().t in -0.2..0.5); assertEquals(sim.durationS, t.poses.last().t, 0.5)
        assertEquals(sim.durationS, t.durationS, 1.0)
    }

    @Test fun garbageLinesAreSkippedNotFatal() {
        val t = Trace.read(sequenceOf("", "not json", "{\"type\":\"sensor\"}", "{\"type\":\"sensor\",\"s\":1,\"t\":5,\"a\":3,\"v\":[1,2]}"))
        assertFalse(t.hasImu); assertEquals(0.0, t.durationS, 0.0)
    }

    // ---- distance and agreement ----

    @Test fun threeIndependentSensorsAgreeOnADistanceAndItIsCloseToTheTruth() {
        val (r, sim) = run(SimConfig(laps = 2))
        val d = r.distance!!
        assertEquals("fused ${d.value} vs truth ${sim.truthMetres}", sim.truthMetres, d.value, sim.truthMetres * 0.04)
        assertTrue("no contradiction expected: ${r.fusion.contradictions.map { it.text }}", r.fusion.contradictions.isEmpty())
        assertEquals(3, r.fusion.sources.size)
        assertTrue(abs(d.value - sim.truthMetres) <= 2.5 * d.sigma)   // the stated uncertainty covers the real error
    }

    @Test fun aSensorWithAWrongScaleIsFoundAndLeftOut() {
        val (r, sim) = run(SimConfig(laps = 2, camScale = 1.5))
        assertTrue(r.fusion.contradictions.isNotEmpty())
        assertEquals("camera tracking", r.fusion.contradictions.mapNotNull { it.outlier }.firstOrNull())
        assertTrue("camera tracking" in r.fusion.excluded)
        assertEquals("the total must not be dragged by the bad sensor", sim.truthMetres, r.distance!!.value, sim.truthMetres * 0.05)
        assertTrue(MotionQA.answer("which sensor disagreed?", r).text.contains("camera tracking"))
    }

    @Test fun whenOnlyTwoSensorsDisagreeNoneIsBlamedAndTheDoubtIsShown() {
        val (r, sim) = run(SimConfig(laps = 2, camScale = 1.5, gps = false, imu = false))
        // Camera alone: nothing to compare with.
        assertTrue(r.fusion.contradictions.isEmpty())
        val (r2, _) = run(SimConfig(laps = 2, camScale = 1.5, gps = false))
        assertTrue(r2.fusion.contradictions.isNotEmpty())
        assertNull(r2.fusion.contradictions.first().outlier)
        assertTrue("both kept", r2.fusion.excluded.isEmpty())
        // Doubt widened: the uncertainty now spans the disagreement, so the truth is inside the band.
        val d = r2.distance!!
        assertTrue("truth ${sim.truthMetres} inside ${d.value} ± ${2 * d.sigma}", abs(d.value - sim.truthMetres) <= 2 * d.sigma)
    }

    @Test fun aRhythmCountedTwicePerStepIsRecognisedAsDouble() {
        val (r, sim) = run(SimConfig(laps = 2, cadenceHz = 1.5, bouncesPerStep = 2))
        val c = r.fusion.contradictions
        assertTrue("expected the steps to disagree: ${r.fusion.sources.map { it.name + " " + it.total.value }}", c.isNotEmpty())
        assertTrue(c.map { it.text }.toString(), c.any { it.text.contains("twice") })
        assertEquals("inertial steps", c.mapNotNull { it.outlier }.firstOrNull())
        assertEquals(sim.truthMetres, r.distance!!.value, sim.truthMetres * 0.06)
    }

    @Test fun stepCountingAloneStillGivesADistanceWithItsWideUncertainty() {
        val (r, sim) = run(SimConfig(laps = 2, camera = false, gps = false))
        val d = r.distance!!
        assertEquals(1, r.fusion.sources.size)
        assertTrue("uncalibrated steps are only good to roughly 12%: ${pct(d.value, sim.truthMetres)}%", abs(pct(d.value, sim.truthMetres)) < 12)
        assertTrue("uncertainty must be at least several percent, not false precision", 2 * d.sigma / d.value > 0.12)
    }

    @Test fun lostCameraTrackingIsReportedAndTheOtherSensorsCarryTheDistance() {
        val (r, sim) = run(SimConfig(laps = 3, camLoss = listOf(60.0..110.0)))
        assertTrue(r.failures.any { it.kind == "tracking" && it.text.contains("lost") })
        assertEquals("the walk continues through the gap", sim.truthMetres, r.distance!!.value, sim.truthMetres * 0.05)
        assertTrue("seen by the other sensors, so nothing unobserved: ${r.fusion.unobservedS}", r.fusion.unobservedS < 15)
        assertTrue(MotionQA.answer("where did tracking fail?", r).text.contains("lost"))
        // The relocalisation jump after the loss must not be walked.
        val cam = r.fusion.sources.first { it.name == "camera tracking" }
        assertTrue("camera total ${cam.total.value} vs ${sim.truthMetres}", cam.total.value < sim.truthMetres * 1.05)
    }

    @Test fun whenNothingSawAStretchItIsNotCounted() {
        val (r, sim) = run(SimConfig(laps = 3, camLoss = listOf(60.0..110.0), gps = false, imu = false))
        assertTrue("unobserved ${r.fusion.unobservedS}", r.fusion.unobservedS >= 40)
        assertTrue("distance must be a lower bound: ${r.distance!!.value} vs ${sim.truthMetres}", r.distance!!.value < sim.truthMetres * 0.97)
        assertTrue(MotionQA.answer("how far did I travel?", r).text.contains("not counted"))
    }

    @Test fun aPhoneLyingStillDoesNotWalkWithGpsNoise() {
        val sim = WalkSim.run(SimConfig(stillOnly = true, camera = false, gpsSigma = 5.0, gpsRho = 0.5, stillSeconds = 180.0))
        val t = Trace.read(sim.lines.asSequence())
        val raw = t.fixes.zipWithNext().sumOf { (a, b) -> kotlin.math.hypot((b.lat - a.lat) * 111_000.0, (b.lon - a.lon) * 85_000.0) }
        val smooth = GpsSource.of(t)!!.total().value
        assertTrue("raw chain $raw m, smoothed $smooth m", smooth < raw * 0.5)
        val r = MovementAnalyzer.analyze(t)
        assertTrue("a still phone moved ${r.distance?.value}", (r.distance?.value ?: 0.0) < 40.0)
    }

    // ---- events ----

    @Test fun lapsTurnsAndGaitAreRead() {
        val (r, sim) = run(SimConfig(laps = 3, camera = true))
        assertEquals("laps ${r.laps.map { it.metres }}", 3, r.laps.size)
        r.laps.forEach { assertEquals(400.0, it.metres, 40.0) }
        assertTrue("lap consistency ${r.lapConsistency}", (r.lapConsistency ?: 1.0) < 0.1)
        assertTrue("turns ${r.turns.size}", r.turns.size in 10..13)  // 4 corners a lap, 90 degrees each, clockwise
        assertTrue("the course goes anticlockwise, so every turn is to the left: ${r.turns.map { it.degrees }}", r.turns.all { it.left })
        val walking = r.gait.filter { it.gait == Gait.Walking }.sumOf { it.span.length }
        assertTrue("walking ${walking}s of ${sim.durationS}", walking > 0.8 * (sim.durationS - 10))
        assertTrue(MotionQA.answer("how many laps", r).text.contains("3 lap"))
    }

    @Test fun runningIsToldFromWalkingAndStillByRhythm() {
        val (run, _) = run(SimConfig(laps = 2, cadenceHz = 2.8, bounceAmp = 7.0), motion = "run")
        assertTrue(run.gait.any { it.gait == Gait.Running && it.span.length > 20 })
        assertFalse(run.gait.any { it.gait == Gait.Walking && it.span.length > 20 })
        assertTrue(MotionQA.answer("was I running?", run).basis == Basis.Inferred)  // a judgement, labelled as one
        val still = WalkSim.run(SimConfig(stillOnly = true, camera = false, gps = false, stillSeconds = 30.0))
        val rs = MovementAnalyzer.analyze(Trace.read(still.lines.asSequence()))
        assertTrue(rs.gait.all { it.gait == Gait.Still })
    }

    @Test fun speedIsReportedFromTheFusedPathAndAskingForPaceWorks() {
        val (r, sim) = run(SimConfig(laps = 2))
        val v = StrideModel(1.0).length(1.8) * 1.8
        assertEquals(v, r.averageSpeed!!.value, 0.25)
        assertTrue(MotionQA.answer("how fast was I?", r).text.contains("km/h"))
        assertTrue(r.topSpeed!!.value < v * 1.6)
        assertEquals(sim.durationS, r.durationS, 1.0)
    }

    // ---- calibration ----

    @Test fun calibrationLearnsAPersonalStrideFromAKnownCourseAndNotBefore() {
        val cal = Calibrator()
        val device = "SM-F956U1"
        val truth = 800.0   // two laps of the 400 m course, known
        fun err(c: Calibrator?): Double {
            val (r, _) = run(SimConfig(laps = 2, strideScale = 1.12, camera = false, gps = false, seed = 7), c, device)
            return pct(r.distance!!.value, truth)
        }
        val before = err(null)
        assertTrue("an uncalibrated model of someone with long strides under-measures: $before%", before < -6)
        // One run is not enough to claim a bias.
        val obs1 = MovementAnalyzer.calibrationObservations(Trace.read(WalkSim.run(SimConfig(laps = 2, strideScale = 1.12, camera = false, gps = false, seed = 11)).lines.asSequence()))
        assertTrue(cal.learn(Calibrator.key(device, "walk", "stride"), obs1.getValue("stride"), Truth(truth, TruthKind.KnownCourse)) is Learned.Accepted)
        assertFalse(cal.correction(Calibrator.key(device, "walk", "stride"), 0.12).active)
        // Further verified runs make it real.
        for (s in 12L..14L) {
            val o = MovementAnalyzer.calibrationObservations(Trace.read(WalkSim.run(SimConfig(laps = 2, strideScale = 1.12, camera = false, gps = false, seed = s)).lines.asSequence()))
            cal.learn(Calibrator.key(device, "walk", "stride"), o.getValue("stride"), Truth(truth, TruthKind.KnownCourse))
        }
        val c = cal.correction(Calibrator.key(device, "walk", "stride"), 0.12)
        assertTrue(c.active); assertEquals(1.12, c.factor, 0.03)
        val after = err(cal)
        assertTrue("calibrated error $after% should be within a few percent (was $before%)", abs(after) < 4)
        // It did not claim to be perfect: some run-to-run uncertainty remains.
        assertTrue(c.relSigma >= 0.02)
        // Another phone's profile is separate.
        assertFalse(cal.correction(Calibrator.key("other-phone", "walk", "stride"), 0.12).active)
        // Round trip through storage.
        assertEquals(c.factor, Calibrator.fromJson(cal.toJson()).correction(Calibrator.key(device, "walk", "stride"), 0.12).factor, 1e-9)
    }

    @Test fun calibrationRefusesWhatIsNotGroundTruth() {
        val cal = Calibrator(); val k = Calibrator.key("d", "walk", "stride")
        val inferred = cal.learn(k, 400.0, Truth(420.0, TruthKind.KnownCourse, Basis.Inferred))
        assertTrue(inferred is Learned.Rejected && inferred.why.contains("not ground truth"))
        assertTrue(cal.learn(k, 400.0, Truth(30.0, TruthKind.KnownCourse)) is Learned.Rejected)       // too short to mean anything
        assertTrue(cal.learn(k, 400.0, Truth(2_000.0, TruthKind.KnownCourse)) is Learned.Rejected)    // a failure, not a bias
        assertEquals(0, cal.runs(k))
    }

    @Test fun aWeakReferenceCannotMakeTheSystemConfident() {
        val strong = Calibrator(); val weak = Calibrator(); val k = Calibrator.key("d", "walk", "stride")
        repeat(4) { strong.learn(k, 400.0, Truth(448.0, TruthKind.Surveyed)); weak.learn(k, 400.0, Truth(448.0, TruthKind.OpenSkyGps)) }
        assertTrue(strong.correction(k, 0.12).relSigma <= weak.correction(k, 0.12).relSigma)
    }

    @Test fun aSensorThatSawTooLittleDoesNotTeachABias() {
        // The camera is lost for a third of the walk: its distance would teach a false 30% bias, so it is not offered.
        val sim = WalkSim.run(SimConfig(laps = 3, camLoss = listOf(100.0..450.0), gps = false, imu = false))
        val obs = MovementAnalyzer.calibrationObservations(Trace.read(sim.lines.asSequence()))
        assertNull(obs["camera scale"])
    }

    @Test fun theStatedUncertaintyIsHonestAcrossManyRandomWalks() {
        var covered = 0; val n = 24
        for (s in 1..n) {
            val cfg = SimConfig(seed = s.toLong(), laps = 2, camScale = 1.0 + (s % 5 - 2) * 0.01, strideScale = 1.0 + (s % 7 - 3) * 0.02, gpsSigma = 3.0 + s % 4)
            val (r, sim) = run(cfg)
            val d = r.distance!!
            if (abs(d.value - sim.truthMetres) <= 2 * d.sigma) covered++
        }
        assertTrue("only $covered of $n runs were inside their own 95% band", covered >= n * 0.85)
    }

    // ---- the smaller parts ----

    @Test fun theGpsSmootherRemovesJitterAndKeepsTheTrend() {
        val rnd = java.util.Random(4)
        val t = DoubleArray(120) { it.toDouble() }
        val truth = DoubleArray(120) { 1.4 * it }
        val z = DoubleArray(120) { truth[it] + rnd.nextGaussian() * 5 }
        val s = GpsSource.smooth(t, z, DoubleArray(120) { 5.0 })
        val rawErr = Math.sqrt(z.indices.sumOf { (z[it] - truth[it]) * (z[it] - truth[it]) } / 120)
        val smErr = Math.sqrt(s.indices.sumOf { (s[it] - truth[it]) * (s[it] - truth[it]) } / 120)
        assertTrue("smoothed $smErr vs raw $rawErr", smErr < rawErr * 0.6)
    }

    @Test fun twoTracksInDifferentFramesAreFittedOntoEachOther() {
        val a = (0 until 40).map { doubleArrayOf(it * 1.0, Math.sin(it / 5.0) * 10) }
        val th = Math.toRadians(63.0)
        val b = a.map { doubleArrayOf(Math.cos(th) * it[0] - Math.sin(th) * it[1] + 100, Math.sin(th) * it[0] + Math.cos(th) * it[1] - 40) }
        val f = Alignment2D.fit(a, b)!!
        assertEquals(th, f.theta, 1e-6); assertTrue(f.rms < 1e-6)
        assertNull(Alignment2D.fit(a.take(3), b.take(3)))
    }

    @Test fun questionsThatAreNotAboutMovementAreNotGuessed() {
        val (r, _) = run(SimConfig(laps = 1))
        val a = MotionQA.answer("what is the meaning of life", r)
        assertFalse(a.understood); assertTrue(a.measurements.isEmpty())
        assertTrue(MotionQA.factsBlock(r).contains("MEASURED FACTS"))
        assertNotNull(MotionQA.answer("how accurate is this", r).text)
    }
}

class ScanQATest {
    private fun walk(id: String, start: Long, tracked: Double = 0.97, merge: app.ultimate.worldscan.MergeRecord? = null, why: String? = null) = app.ultimate.worldscan.WalkMeta(
        id = id, name = "Walk $id", startedAt = start, endedAt = start + 100, lengthM = 120.0, durationS = 100.0, trackedShare = tracked, longestLossS = if (tracked < 0.9) 12.0 else 0.0, jumps = 0, speedP95 = 1.6,
        extentX = 40.0, extentZ = 30.0, cubes = 5000, keyFrames = 80, diag = "", geo = null, fixes = 0, gpsMedianAccM = null, clock = null, logBytes = 0, logTruncated = false, sensorCounts = emptyMap(),
        pieces = emptyList(), merge = merge, unmergedReason = why)
    private val m = app.ultimate.worldscan.MergeRecord(0, app.ultimate.room.Alignment.IDENTITY, 0f, 0.05f, 0.82f, 0.3f, "walk", listOf(
        app.ultimate.worldscan.PieceRecord(0, 0, 1, app.ultimate.room.Alignment.IDENTITY, "feature", 0.05f, 0.8f, 120, 880, 15)))
    private val place = app.ultimate.worldscan.Place("p", "Kitchen", 0, 3, "a", listOf(walk("a", 1), walk("b", 2, merge = m), walk("c", 3, tracked = 0.7, why = "the two walks did not overlap enough")), listOf("a", "b"))

    @Test fun accuracyReportsTrackingAndHonestlyDeclinesToClaimCentimetres() {
        val a = ScanQA.answer("How accurate was the previous scan?", place)
        assertTrue(a.text, a.text.contains("70%") && a.text.contains("12 s"))
        assertTrue(a.text.contains("no accuracy in centimetres is claimed"))
    }

    @Test fun changesAreCountedFromTheMergeRecordAndCaveatedBecauseCauseIsNotKnown() {
        val a = ScanQA.answer("What changed in this room?", place)
        assertTrue(a.text, a.text.contains("added 120") && a.text.contains("removed 15") && a.text.contains("cannot tell those apart"))
    }

    @Test fun mergeAnswerNamesWhyAWalkWasNotMerged() {
        val a = ScanQA.answer("Can these two spatial recordings be merged?", place)
        assertTrue(a.text, a.text.contains("NOT merged") && a.text.contains("did not overlap enough") && a.text.contains("first walk"))
    }

    @Test fun unknownQuestionsAreNotGuessed() {
        assertFalse(ScanQA.answer("what's for lunch", place).understood)
        assertEquals("This place has no walks yet.", ScanQA.answer("accuracy", app.ultimate.worldscan.Place("e", "Empty", 0, 0, "", emptyList(), emptyList())).text)
    }
}

class MovementSignalsTest {
    private fun report(c: SimConfig) = MovementAnalyzer.analyze(Trace.read(WalkSim.run(c).lines.asSequence()))

    @Test fun eachLapAndKilometreIsAnnouncedOnceAsTheRecordingGrows() {
        val one = report(SimConfig(laps = 1)); val three = report(SimConfig(laps = 3))
        val first = MovementSignals.between(null, one)
        assertEquals(1, first.count { it.kind == "lap" })
        val later = MovementSignals.between(one, three)
        assertEquals(listOf("2", "3"), later.filter { it.kind == "lap" }.map { it.data["lap"] })
        assertEquals(1, later.count { it.kind == "kilometre" })           // 400 m -> 1200 m crosses one kilometre
        assertTrue("nothing new, nothing announced", MovementSignals.between(three, three).isEmpty())
    }

    @Test fun aSensorStartingToDisagreeIsAnnouncedOnce() {
        val ok = report(SimConfig(laps = 2)); val bad = report(SimConfig(laps = 2, camScale = 1.5))
        val s = MovementSignals.between(ok, bad).filter { it.kind == "disagree" }
        assertEquals(1, s.size); assertTrue(s.first().label.contains("camera tracking"))
        assertTrue(MovementSignals.between(bad, bad).none { it.kind == "disagree" })
    }

    @Test fun everySignalHasATriggerPeopleCanPick() {
        val keys = app.ultimate.rules.SenseNames.choices.map { it.first }.toSet()
        for (k in listOf("lap", "running", "walking", "stopped", "kilometre", "disagree")) assertTrue("movement:$k", "movement:$k" in keys)
        for (k in listOf("device_joined", "device_back", "device_lost", "device_near", "device_away")) assertTrue("mesh:$k", "mesh:$k" in keys)
    }
}

/** Measures the analysis itself, so its cost is a recorded number and a regression shows up. Not a phone measurement: this is the build machine. */
class FusionBenchmarkTest {
    @Test fun readingAndAnalysingAHalfHourRecording() {
        val sim = WalkSim.run(SimConfig(laps = 6, course = SimConfig.square(100.0), camera = true))   // 2.4 km, about 31 minutes
        val lines = sim.lines
        val t0 = System.nanoTime()
        val trace = Trace.read(lines.asSequence())
        val t1 = System.nanoTime()
        val report = MovementAnalyzer.analyze(trace)
        val t2 = System.nanoTime()
        // The live screen: the same recording arriving in 6 s slices. Before: parse everything each time. Now: parse only what is new.
        val step = (lines.size + 39) / 40
        var full = 0L; var incremental = 0L
        val b = TraceBuilder()
        var upto = 0
        var passes = 0
        while (upto < lines.size) {                              // 40 refreshes spread over the recording are enough to measure
            val next = minOf(lines.size, upto + step)
            var s = System.nanoTime(); Trace.read(lines.subList(0, next).asSequence()); full += System.nanoTime() - s
            s = System.nanoTime(); for (i in upto until next) b.add(lines[i]); b.build(); incremental += System.nanoTime() - s
            upto = next; passes++
        }
        val text = "Fusion benchmark on the build machine (simulated ${"%.0f".format(sim.durationS / 60)} min recording, ${lines.size} log lines)\n" +
            "read whole log: ${(t1 - t0) / 1_000_000} ms\nanalyse: ${(t2 - t1) / 1_000_000} ms\n" +
            "live refresh, $passes passes: re-reading everything ${full / 1_000_000} ms total, incremental ${incremental / 1_000_000} ms total (${"%.1f".format(full.toDouble() / incremental.coerceAtLeast(1))}x less)\n"
        println(text)
        java.io.File("build/fusion-benchmark.txt").apply { parentFile.mkdirs(); writeText(text) }
        assertEquals(sim.truthMetres, report.distance!!.value, sim.truthMetres * 0.04)
        assertEquals("the incremental reader must give the same recording", trace.imu.size, b.build().imu.size)
        assertTrue("incremental must be cheaper than re-reading", incremental < full)
        assertTrue("analysis of half an hour took ${(t2 - t1) / 1_000_000} ms", (t2 - t1) < 20_000_000_000L)
    }
}

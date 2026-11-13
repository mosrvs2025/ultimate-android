package app.ultimate.mesh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything here runs on synthesised signals and loopback sockets. It checks the arithmetic and the protocol, not
 * any real phone's speaker, microphone or sensors; that is only learned by using two phones.
 */
class MeshRemoteTest {
    private val key = MeshWire.keyFromCode("remote-test")
    private val nodes = mutableListOf<MeshNode>()
    @After fun tearDown() { nodes.forEach { it.stop() } }

    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) fail("timed out"); Thread.sleep(20) }
    }

    private fun node(name: String, p: DeviceProfile, lends: () -> Boolean = { true }) =
        MeshNode(name, key, bindHost = "127.0.0.1", capacity = 5.0, heartbeatMs = 120, profile = { p.copy(lends = lends()) }).start().also { nodes += it }

    // ---- profiles and events ----

    @Test fun aProfileSurvivesTheWireAndSaysWhatItMayBeAskedToDo() {
        val p = DeviceProfile("SM-F741U1", Kind.Foldable, setOf(Cap.Motion, Cap.Mic, Cap.Speaker, Cap.NearUltrasound), lends = true, app = "2.10.0")
        val back = DeviceProfile.fromPayload(p.toPayload())
        assertEquals(p, back)
        assertTrue(back.canRecordMotion); assertTrue(back.canRange)
        assertFalse("a device that is not lending is never asked", back.copy(lends = false).canRecordMotion)
        assertFalse(back.copy(caps = setOf(Cap.Motion, Cap.Mic, Cap.Speaker)).canRange) // no near-ultrasound
        assertEquals(DeviceProfile.Unknown.kind, DeviceProfile.fromPayload(emptyMap()).kind)
        assertTrue(p.describe().startsWith("foldable"))
    }

    @Test fun anAnnouncementIsMadeOnceWhenADeviceJoinsAndOnceWhenItIsLost() {
        val events = CopyOnWriteArrayList<MeshNode.PeerEvent>()
        val a = node("a", DeviceProfile("Fold", Kind.Foldable, setOf(Cap.Motion), false))
        a.onEvent = { events += it }
        val b = node("b", DeviceProfile("Flip", Kind.Foldable, setOf(Cap.Motion, Cap.Mic), true))
        b.addSeed("127.0.0.1", a.boundPort)
        waitFor { events.any { it is MeshNode.PeerEvent.Joined } }
        Thread.sleep(500)
        assertEquals("one Joined however many heartbeats follow", 1, events.count { it is MeshNode.PeerEvent.Joined })
        val joined = events.filterIsInstance<MeshNode.PeerEvent.Joined>().single().peer
        assertEquals("b", joined.name)
        assertEquals(Kind.Foldable, joined.profile.kind); assertTrue(joined.profile.lends); assertTrue(Cap.Mic in joined.profile.caps)

        b.stop()
        waitFor { events.any { it is MeshNode.PeerEvent.Lost } }
        Thread.sleep(500)
        assertEquals(1, events.count { it is MeshNode.PeerEvent.Lost })
    }

    @Test fun aDeviceThatComesBackIsAnnouncedAsReturned() {
        val events = CopyOnWriteArrayList<MeshNode.PeerEvent>()
        val a = node("a", DeviceProfile("A", Kind.Phone, emptySet(), false))
        a.onEvent = { events += it }
        val b = node("b", DeviceProfile("B", Kind.Phone, emptySet(), false))
        b.addSeed("127.0.0.1", a.boundPort)
        waitFor { events.any { it is MeshNode.PeerEvent.Joined } }
        // B goes quiet without a goodbye (as when a phone leaves Wi-Fi), then resumes.
        b.stop(); waitFor { events.any { it is MeshNode.PeerEvent.Lost } }
        val b2 = MeshNode("b", key, id = b.id, bindHost = "127.0.0.1", capacity = 5.0, heartbeatMs = 120).start().also { nodes += it }
        b2.addSeed("127.0.0.1", a.boundPort)
        waitFor { events.any { it is MeshNode.PeerEvent.Returned } }
        assertEquals(1, events.count { it is MeshNode.PeerEvent.Joined })
    }

    // ---- commands ----

    @Test fun aCommandReachesTheHandlerAndTheAnswerComesBack() {
        val a = node("a", DeviceProfile("A", Kind.Phone, emptySet(), false))
        val b = node("b", DeviceProfile("B", Kind.Phone, setOf(Cap.Motion), true))
        val seen = CopyOnWriteArrayList<String>()
        b.commands = MeshNode.CommandHandler { from, cmd, args -> seen += "$cmd from ${from.name} ${args["rate"]}"; mapOf("state" to "recording", "rows" to "0") }
        a.addSeed("127.0.0.1", b.boundPort)
        waitFor { a.peers().any { a.alive(it) } && b.peers().isNotEmpty() }
        val answer = a.command(b.id, Cmd.REC_START, mapOf("rate" to "50"))
        assertEquals("recording", answer["state"])
        assertEquals(listOf("rec.start from a 50"), seen)
    }

    @Test fun aDeviceThatIsNotLendingRefusesAndTheReasonIsReported() {
        val a = node("a", DeviceProfile("A", Kind.Phone, emptySet(), false))
        val lending = booleanArrayOf(false)
        val b = node("b", DeviceProfile("B", Kind.Phone, setOf(Cap.Motion), false)) { lending[0] }
        b.commands = MeshNode.CommandHandler { _, _, _ -> if (!lending[0]) throw IllegalStateException("this phone is not lending its sensors") else mapOf("ok" to "1") }
        a.addSeed("127.0.0.1", b.boundPort)
        waitFor { a.peers().isNotEmpty() && b.peers().isNotEmpty() }
        try { a.command(b.id, Cmd.REC_START); fail("must refuse") } catch (e: IllegalStateException) { assertTrue(e.message!!, "not lending" in e.message!!) }
        lending[0] = true
        assertEquals("1", a.command(b.id, Cmd.REC_START)["ok"])
    }

    @Test fun aDeviceWithNoHandlerAcceptsNoCommandsAtAll() {
        val a = node("a", DeviceProfile.Unknown); val b = node("b", DeviceProfile.Unknown)
        a.addSeed("127.0.0.1", b.boundPort)
        waitFor { a.peers().isNotEmpty() && b.peers().isNotEmpty() }
        try { a.command(b.id, Cmd.PING); fail("must refuse") } catch (e: IllegalStateException) { assertTrue("not accepting" in e.message!!) }
    }

    @Test fun aStrangerWithTheWrongCodeCannotSendCommands() {
        val b = node("b", DeviceProfile.Unknown)
        b.commands = MeshNode.CommandHandler { _, _, _ -> fail("a stranger's command must never reach the handler"); emptyMap() }
        val stranger = MeshNode("s", MeshWire.keyFromCode("not-the-code"), bindHost = "127.0.0.1", capacity = 1.0, heartbeatMs = 120).start().also { nodes += it }
        stranger.addSeed("127.0.0.1", b.boundPort)
        Thread.sleep(500)
        assertTrue(b.peers().isEmpty())
        assertTrue(b.stats().rejected > 0)
    }

    // ---- roster ----

    @Test fun theRosterAnnouncesNewDevicesOnceAndQuietlyIgnoresWobbles() {
        val r = Roster(backAfterMs = 600_000)
        assertTrue(r.seen("z", "Flip", 1_000) is Roster.Sighting.New)
        assertEquals(Roster.Sighting.Same, r.seen("z", "Flip", 5_000))
        assertEquals("a brief dropout is not news", Roster.Sighting.Same, r.seen("z", "Flip", 60_000))
        val back = r.seen("z", "Flip", 60_000 + 700_000)
        assertTrue(back is Roster.Sighting.Back && back.awayMs >= 600_000)
        // Survives being saved and loaded, including awkward names.
        val loaded = Roster.decode(Roster(listOf(Roster.Known("a", "Dad's\tphone\nX", 1, 2))).encode())
        assertEquals(1, loaded.known.size); assertEquals(1L, loaded.known.single().firstSeen)
        assertEquals(Roster.Sighting.Same, loaded.seen("a", "Dad's phone X", 3))
    }

    // ---- motion log ----

    @Test fun aMotionChunkRoundTripsWithinItsQuantisationAndFitsAFrame() {
        val log = MotionLog()
        val rnd = Random(3)
        repeat(1_000) { i -> log.add(i * 20, rnd.nextFloat() * 30 - 15, rnd.nextFloat() * 30 - 15, 9.81f, rnd.nextFloat() * 6 - 3, 0f, 0f) }
        val chunk = log.encode(0, MotionLog.CHUNK_ROWS)
        val frame = MeshWire.encode(MeshWire.Frame(MeshWire.Type.ACK, "x", 1, 1L, mapOf("rows" to "375", "data" to chunk)), key)
        assertTrue("frame is ${frame.length} bytes", frame.length < MeshWire.MAX_LINE)
        val copy = MotionLog()
        assertEquals(MotionLog.CHUNK_ROWS, MotionLog.decodeInto(copy, chunk))
        for (i in 0 until copy.rows) {
            assertEquals(log.timeMs(i), copy.timeMs(i))
            for (k in 0..2) { assertEquals(log.accel(i, k).toDouble(), copy.accel(i, k).toDouble(), 0.0051); assertEquals(log.gyro(i, k).toDouble(), copy.gyro(i, k).toDouble(), 0.0011) }
        }
        try { MotionLog.decodeInto(MotionLog(), "AAA"); fail() } catch (_: Exception) {}
    }

    @Test fun aRecordingStopsGrowingAtItsCap() {
        val log = MotionLog(maxRows = 10)
        repeat(25) { log.add(it, 0f, 0f, 0f, 0f, 0f, 0f) }
        assertEquals(10, log.rows); assertTrue(log.full)
    }

    // ---- repetition counting (synthesised signals) ----

    private fun synth(seconds: Double, rate: Double = 50.0, noise: Double = 0.05, seed: Long = 1,
                      move: (Double) -> DoubleArray): Pair<DoubleArray, Array<DoubleArray>> {
        val rnd = Random(seed); val n = (seconds * rate).toInt()
        val t = DoubleArray(n) { it / rate }
        val ax = DoubleArray(n); val ay = DoubleArray(n); val az = DoubleArray(n)
        for (i in 0 until n) { val m = move(t[i]); ax[i] = m[0] + rnd.nextGaussian() * noise; ay[i] = m[1] + rnd.nextGaussian() * noise; az[i] = 9.81 + m[2] + rnd.nextGaussian() * noise }
        return t to arrayOf(ax, ay, az)
    }

    private fun bursts(vararg spans: Triple<Double, Double, Double>, amp: Double = 3.0) = { t: Double ->
        var x = 0.0
        for ((from, to, hz) in spans) if (t in from..to) x += amp * sin(2 * PI * hz * (t - from))
        x
    }

    @Test fun twoSetsOfRepetitionsAreFoundAndCounted() {
        val f = bursts(Triple(5.0, 25.0, 0.5), Triple(40.0, 52.0, 0.4))
        val (t, a) = synth(60.0) { doubleArrayOf(0.0, 0.0, f(it)) }
        val s = RepCounter.analyze(t, a)
        assertEquals(s.toString(), 2, s.sets.size)
        assertEquals(10.0, s.sets[0].reps.toDouble(), 1.0); assertEquals(30.0, s.sets[0].perMinute, 3.0)
        assertEquals(5.0, s.sets[1].reps.toDouble(), 1.0); assertEquals(24.0, s.sets[1].perMinute, 4.0)
    }

    @Test fun theCountDoesNotDependOnHowThePhoneIsHeld() {
        // The same 12 repetitions along three different directions in the phone's own frame, and a tilted one.
        val f = bursts(Triple(3.0, 27.0, 0.5))
        for (dir in listOf(doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.6, 0.6, 0.53))) {
            val len = sqrt(dir.sumOf { it * it })
            val (t, a) = synth(30.0) { val x = f(it); doubleArrayOf(x * dir[0] / len, x * dir[1] / len, x * dir[2] / len) }
            val s = RepCounter.analyze(t, a)
            assertEquals("direction ${dir.toList()} gave ${s.sets}", 12.0, s.reps.toDouble(), 1.0)
        }
    }

    @Test fun aPhoneLyingStillCountsNothing() {
        val (t, a) = synth(90.0) { doubleArrayOf(0.0, 0.0, 0.0) }
        val s = RepCounter.analyze(t, a)
        assertEquals(0, s.reps); assertTrue(s.sets.isEmpty()); assertEquals(0.0, s.activeSeconds, 0.6)
    }

    @Test fun aFastRhythmLikeWalkingGivesItsCadence() {
        val f = bursts(Triple(2.0, 32.0, 2.0), amp = 2.5)
        val (t, a) = synth(35.0) { doubleArrayOf(0.0, 0.0, f(it)) }
        val s = RepCounter.analyze(t, a)
        assertEquals(1, s.sets.size)
        assertEquals(120.0, s.sets[0].perMinute, 6.0)
        assertEquals(60.0, s.reps.toDouble(), 4.0)
    }

    @Test fun anUnevenSampleRateIsHandled() {
        // Android delivers sensor events at irregular intervals. Jitter the timestamps by up to +-30%.
        val rnd = Random(9)
        val f = bursts(Triple(2.0, 22.0, 0.5))
        var tt = 0.0; val ts = ArrayList<Double>(); val z = ArrayList<Double>()
        while (tt < 26) { ts += tt; z += 9.81 + f(tt) + rnd.nextGaussian() * 0.05; tt += 0.02 * (0.7 + 0.6 * rnd.nextDouble()) }
        val zero = DoubleArray(ts.size)
        val s = RepCounter.analyze(ts.toDoubleArray(), arrayOf(zero, zero, z.toDoubleArray()))
        assertEquals(10.0, s.reps.toDouble(), 1.0)
    }

    // ---- ranging between two phones (synthesised recordings with known geometry) ----

    private fun chirpAt(role: PeerRange.Role, x: Double): Double {
        if (x < 0 || x > PeerRange.CHIRP - 1) return 0.0
        val (f0, f1) = if (role == PeerRange.Role.Caller) PeerRange.F_LOW to PeerRange.F_HIGH else PeerRange.F_HIGH to PeerRange.F_LOW
        val k = (f1 - f0) / (PeerRange.CHIRP.toDouble() / PeerRange.RATE)
        val tt = x / PeerRange.RATE; val u = x / (PeerRange.CHIRP - 1)
        val taper = when { u < 0.1 -> 0.5 * (1 - cos(2 * PI * u / 0.2)); u > 0.9 -> 0.5 * (1 - cos(2 * PI * (1 - u) / 0.2)); else -> 1.0 }
        return sin(2 * PI * (f0 * tt + k * tt * tt / 2)) * taper * 0.6
    }

    private class Arrival(val role: PeerRange.Role, val atSample: Double, val gain: Double)

    private fun record(arrivals: List<Arrival>, noise: Double, seed: Long, samples: Int = PeerRange.Plan.samples(PeerRange.Plan.RECORD_MS)): FloatArray {
        val rnd = Random(seed)
        val out = FloatArray(samples) { (rnd.nextGaussian() * noise).toFloat() }
        for (a in arrivals) {
            val from = a.atSample.toInt().coerceAtLeast(0); val to = (a.atSample + PeerRange.CHIRP + 1).toInt().coerceAtMost(samples)
            for (i in from until to) out[i] += (a.gain * chirpAt(a.role, i - a.atSample)).toFloat()
        }
        return out
    }

    /**
     * Two phones D metres apart. A started recording [aHead] seconds before B (the network's start-up difference).
     * Each phone's own speaker is heard 7 cm from its mic and loud; the other's is quiet and falls with distance.
     */
    private fun twoPhones(d: Double, aHead: Double = 0.12, echoOfPeer: Double = 0.0, clockPpm: Double = 0.0, noise: Double = 0.004, seed: Long = 5): Pair<PeerRange.Side?, PeerRange.Side?> {
        val r = PeerRange.RATE.toDouble(); val c = PeerRange.SOUND; val sm = 0.07
        val aRec0 = 0.0; val bRec0 = aHead
        val emitA = aRec0 + PeerRange.Plan.CALLER_AT_MS / 1000.0 + 0.031      // each phone's real start differs a little from the plan
        val emitB = bRec0 + PeerRange.Plan.CALLEE_AT_MS / 1000.0 + 0.017
        val peerGain = 0.04 / maxOf(d, 0.3)
        // Phone B's sample clock runs fast by clockPpm, so it counts more samples per true second.
        val bRate = r * (1 + clockPpm * 1e-6)
        val aHears = mutableListOf(Arrival(PeerRange.Role.Caller, (emitA - aRec0) * r + sm / c * r, 0.5), Arrival(PeerRange.Role.Callee, (emitB - aRec0) * r + d / c * r, peerGain))
        val bHears = mutableListOf(Arrival(PeerRange.Role.Callee, (emitB - bRec0) * bRate + sm / c * bRate, 0.5), Arrival(PeerRange.Role.Caller, (emitA - bRec0) * bRate + d / c * bRate, peerGain))
        if (echoOfPeer > 0) { aHears += Arrival(PeerRange.Role.Callee, aHears[1].atSample + 0.004 * r, peerGain * echoOfPeer); bHears += Arrival(PeerRange.Role.Caller, bHears[1].atSample + 0.004 * r, peerGain * echoOfPeer) }
        return PeerRange.measure(record(aHears, noise, seed), PeerRange.Role.Caller) to PeerRange.measure(record(bHears, noise, seed + 1), PeerRange.Role.Callee)
    }

    private fun measured(d: Double, aHead: Double = 0.12, echo: Double = 0.0, ppm: Double = 0.0, noise: Double = 0.004, seed: Long = 5): PeerRange.Result {
        val (a, b) = twoPhones(d, aHead, echo, ppm, noise, seed)
        assertNotNull(a); assertNotNull(b)
        return PeerRange.distance(a!!, b!!)
    }

    @Test fun distanceIsRecoveredToAboutAnInchAcrossTheRoomWithoutAnyClockAgreement() {
        for (d in listOf(0.3, 0.5, 1.0, 2.5, 4.0, 7.0)) {
            for (head in listOf(0.0, 0.12, 0.31, -0.2)) {
                val r = measured(d, aHead = head)
                // Far away the other phone's chirp is faint, and the result then says so rather than claiming to be sure.
                if (d <= 4.0) assertEquals("d=$d head=$head: $r", PeerRange.Confidence.Good, r.confidence)
                assertTrue("d=$d head=$head: $r", r.confidence != PeerRange.Confidence.None)
                assertEquals("d=$d head=$head", d, r.metres, 0.02)
            }
        }
    }

    @Test fun aLoudEchoArrivingAfterTheDirectSoundDoesNotShiftTheAnswer() {
        val r = measured(2.0, echo = 1.3)
        assertEquals(2.0, r.metres, 0.03)
    }

    @Test fun twoPhonesSamplingAtSlightlyDifferentRatesStillAgreeToACoupleOfCentimetres() {
        val r = measured(3.0, ppm = 40.0)
        assertEquals(3.0, r.metres, 0.04)
    }

    @Test fun aPhoneBarelyHeardIsReportedAsDoubtfulNotSure() {
        val (a, b) = twoPhones(9.0, noise = 0.03)
        val r = PeerRange.distance(a!!, b!!)
        assertTrue("faint chirps must not be called good: $r", r.confidence != PeerRange.Confidence.Good || abs(r.metres - 9.0) < 0.05)
    }

    @Test fun silenceFromTheOtherPhoneGivesNoNumberAndTheReason() {
        // The other phone never plays: nothing but noise where its chirp should be.
        val ownOnly = record(listOf(Arrival(PeerRange.Role.Caller, 17_000.0, 0.5)), 0.004, 1)
        val a = PeerRange.measure(ownOnly, PeerRange.Role.Caller)!!
        val b = PeerRange.measure(record(listOf(Arrival(PeerRange.Role.Callee, 43_000.0, 0.5)), 0.004, 2), PeerRange.Role.Callee)!!
        val r = PeerRange.distance(a, b)
        assertEquals(PeerRange.Confidence.None, r.confidence); assertTrue(r.metres.isNaN()); assertTrue("did not hear" in r.note)
    }

    @Test fun itsOwnLoudSpeakerIsNotMistakenForTheOtherPhone() {
        // Only this phone's own chirp is in the recording, very loud. The other phone's must be reported as not heard.
        val rec = record(listOf(Arrival(PeerRange.Role.Caller, 17_000.0, 5.0)), 0.004, 3)
        val side = PeerRange.measure(rec, PeerRange.Role.Caller)!!
        assertTrue(side.own.heard); assertFalse("a loud own chirp leaked into the search for the other's", side.other.heard)
    }

    @Test fun absurdTimingsAreDiscarded() {
        val own = PeerRange.Detection(1_000.0, 100.0, 1.0)
        val far = PeerRange.Detection(1_000.0 + 48_000 * 0.5, 100.0, 1.0)  // half a second later in both recordings: 85 m
        val r = PeerRange.distance(PeerRange.Side(own, far), PeerRange.Side(own, far))
        assertEquals(PeerRange.Confidence.None, r.confidence)
    }

    @Test fun oneSampleOfTimingIsAboutThreeAndAHalfMillimetres() {
        assertEquals(0.00357, PeerRange.M_PER_SAMPLE, 0.0001)
    }
}

class MeshBleCodecTest {
    private val k1 = MeshWire.keyFromCode("code-one"); private val k2 = MeshWire.keyFromCode("code-two")

    @Test fun onlyPhonesWithTheSameCodeComputeTheSameServiceIdentifier() {
        assertEquals(MeshBleCodec.serviceUuid(k1), MeshBleCodec.serviceUuid(MeshWire.keyFromCode("code-one")))
        assertTrue(MeshBleCodec.serviceUuid(k1) != MeshBleCodec.serviceUuid(k2))
        assertEquals(4, MeshBleCodec.serviceUuid(k1).version())
    }

    @Test fun aTagIdentifiesADeviceWithoutRevealingItsId() {
        assertEquals(4, MeshBleCodec.tag(k1, "abc").size)
        assertEquals(MeshBleCodec.tagHex(k1, "abc"), MeshBleCodec.tagHex(k1, "abc"))
        assertTrue(MeshBleCodec.tagHex(k1, "abc") != MeshBleCodec.tagHex(k1, "abd"))
        assertTrue("another mesh must not match", MeshBleCodec.tagHex(k1, "abc") != MeshBleCodec.tagHex(k2, "abc"))
    }

    @Test fun signalStrengthOnlyEverSortsIntoCoarseBands() {
        assertEquals(MeshBleCodec.Band.Touching, MeshBleCodec.band(-40.0))
        assertEquals(MeshBleCodec.Band.Near, MeshBleCodec.band(-55.0))
        assertEquals(MeshBleCodec.Band.Room, MeshBleCodec.band(-70.0))
        assertEquals(MeshBleCodec.Band.Far, MeshBleCodec.band(-92.0))
        val s = MeshBleCodec.Smoother()
        s.add(-50); s.add(-50); s.add(-50)
        assertEquals("one wild reading must not jump a band", MeshBleCodec.Band.Near, MeshBleCodec.band(s.add(-85)))
    }
}

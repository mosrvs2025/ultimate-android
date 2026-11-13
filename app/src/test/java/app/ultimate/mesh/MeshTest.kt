package app.ultimate.mesh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class MeshTest {
    private val key = MeshWire.keyFromCode("471-902")
    private val nodes = mutableListOf<MeshNode>()

    @After fun tearDown() { nodes.forEach { it.stop() } }

    private fun node(name: String, cap: Double, k: ByteArray = key, slow: Double = 0.0) =
        MeshNode(name, k, bindHost = "127.0.0.1", capacity = cap, heartbeatMs = 150, slowdownMsPerUnit = slow).start().also { nodes += it }

    private fun waitFor(ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) { if (System.currentTimeMillis() > end) fail("timed out"); Thread.sleep(25) }
    }

    @Test fun framesRoundTripAndRefuseTamperingStaleAndWrongKeys() {
        val f = MeshWire.Frame(MeshWire.Type.TASK, "abc", 1, 1_000_000, mapOf("n" to "10", "odd" to "a=b&c|d"))
        val line = MeshWire.encode(f, key)
        assertEquals(f, MeshWire.decode(line, key, now = 1_000_000))
        // Sealed: nothing about the frame is readable on the wire, and two sealings of the same frame differ.
        assertFalse(line.contains("TASK")); assertFalse(line.contains("abc")); assertFalse(line.contains("odd"))
        assertNotEquals(line, MeshWire.encode(f, key))
        // Any changed character anywhere is refused.
        for (i in listOf(5, line.length / 2, line.length - 2)) {
            val tampered = line.substring(0, i) + (if (line[i] == 'A') 'B' else 'A') + line.substring(i + 1)
            assertThrows { MeshWire.decode(tampered, key, now = 1_000_000) }
        }
        assertThrows { MeshWire.decode("om1|TASK|abc|1|1000000||00", key, now = 1_000_000) }   // the old readable format is not accepted
        assertTrue(MeshWire.codeBits("471-902") < MeshWire.codeBits("violet-otter-471-lamp"))
        assertThrows { MeshWire.decode(line, key, now = 1_000_000 + MeshWire.MAX_SKEW_MS + 1) }
        assertThrows { MeshWire.decode(line, MeshWire.keyFromCode("000-000"), now = 1_000_000) }
        val guard = ReplayGuard()
        assertTrue(guard.firstTime("abc", 1)); assertTrue(!guard.firstTime("abc", 1))
        assertNotEquals(MeshWire.fingerprint(key), MeshWire.fingerprint(MeshWire.keyFromCode("000-000")))
    }

    @Test fun reflexKeepsSmallTasksLocalAndShipsBigOnesToTheCheapestArm() {
        val self = PeerView("me", 1.0, 0.0, 0.0, true)
        val fast = PeerView("fast", 10.0, 0.0, 6.0, true)
        val busy = PeerView("busy", 20.0, 5_000.0, 6.0, true)
        val dying = PeerView("dying", 50.0, 0.0, 6.0, true, battery = 0.1)
        assertEquals("me", Routing.choose(10.0, self, listOf(fast, busy, dying)))
        assertEquals("fast", Routing.choose(400.0, self, listOf(fast, busy, dying)))
        assertEquals("me", Routing.choose(400.0, self, listOf(fast.copy(alive = false))))
        assertEquals("busy", Routing.choose(400.0, self, listOf(fast, busy), exclude = setOf("fast")))
        assertEquals(9592L, Kernels.primes(100_000))
    }

    @Test fun realNodesFormAMeshFromOneSeedShareWorkAndSurviveAnArmBeingCut() {
        val phone = node("phone", 1.0, slow = 2.0)   // slow on purpose
        val laptop = node("laptop", 20.0)
        val desktop = node("desktop", 40.0)
        laptop.addSeed("127.0.0.1", desktop.boundPort)
        phone.addSeed("127.0.0.1", laptop.boundPort)
        // The phone only knew the laptop; gossip introduces the desktop.
        waitFor { phone.peers().size == 2 && phone.peers().all(phone::alive) }

        val small = phone.submit(10_000)
        assertEquals(phone.id, small.executor)            // reflex: not worth the network
        assertEquals(1229L, small.value)

        val big = phone.submit(1_000_000)
        assertEquals(desktop.id, big.executor)            // the cheapest arm
        assertEquals(78498L, big.value)

        // Cut the desktop without warning; the phone still thinks it is alive, so the next task fails over.
        desktop.stop()
        val after = phone.submit(1_000_000)
        assertEquals(78498L, after.value)
        assertTrue("expected a failover, got ${after.attempts} attempt(s) on ${after.executor}", after.attempts >= 2)
        assertEquals(laptop.id, after.executor)
        assertTrue(phone.stats().failovers >= 1)

        // Once heartbeats lapse the desktop is no longer chosen first.
        waitFor { phone.peers().none { it.id == desktop.id && phone.alive(it) } }
        assertEquals(1, phone.submit(1_000_000).attempts)
    }

    @Test fun aDeviceWithTheWrongPairingCodeIsRefusedAndLearnsNothing() {
        val a = node("a", 10.0)
        val stranger = node("stranger", 10.0, k = MeshWire.keyFromCode("999-999"))
        stranger.addSeed("127.0.0.1", a.boundPort)
        Thread.sleep(600)
        assertTrue(a.peers().isEmpty()); assertTrue(stranger.peers().isEmpty())
        assertTrue(a.stats().rejected > 0)
    }

    @Test fun stopRevokesImmediately() {
        val a = node("a", 10.0); val b = node("b", 10.0)
        b.addSeed("127.0.0.1", a.boundPort)
        waitFor { a.peers().isNotEmpty() }
        a.stop()
        assertTrue(a.peers().isEmpty())
        assertFalse(a.running)
        // Checking the port itself is unreliable in the full suite: another test's server can take the freed port at once.
    }

    @Test fun benchmarkStrategiesOnTheSameWorkloadAndFailures() {
        val sc = MeshSim.homeMesh()
        val results = Strategy.entries.map { MeshSim.run(sc, it) }
        val calm = Strategy.entries.map { MeshSim.run(sc.copy(outages = emptyList()), it) }
        val byName = results.associateBy { it.strategy }
        fun fmt(rs: List<MeshSim.Result>) = rs.joinToString("\n") { r ->
            "%-24s ok=%.3f mean=%7.1fms p95=%7.1fms msgs=%6d retries=%4d energy=%8.0f"
                .format(r.strategy.label, r.successRate, r.meanMs, r.p95Ms, r.messages, r.retries, r.energy)
        }
        val table = "With outages (coordinator asleep 8-16 s, phone-b drops twice):\n" + fmt(results) + "\n\nNo failures (control):\n" + fmt(calm)
        println(table)
        File("build/mesh-benchmark.txt").apply { parentFile.mkdirs(); writeText("Simulated (not hardware). Scenario: MeshSim.homeMesh(), seed 7\n$table\n") }
        // The coordinator outage is what distinguishes the designs.
        assertTrue(byName.getValue(Strategy.Octopus).successRate > byName.getValue(Strategy.Central).successRate)
        assertTrue(byName.getValue(Strategy.Hybrid).successRate >= byName.getValue(Strategy.Central).successRate)
        // Deterministic for a seed.
        assertEquals(results, Strategy.entries.map { MeshSim.run(sc, it) })
    }

    private fun assertThrows(block: () -> Unit) { try { block(); fail("expected an exception") } catch (_: Exception) {} }
}

package app.ultimate.mesh

/** What one node believes about another (possibly stale: it came from the last heartbeat). */
data class PeerView(
    val id: String,
    /** Work units per millisecond, measured on that device by [Kernels.calibrate]. */
    val capacity: Double,
    /** Work units queued there at the last heartbeat. */
    val queuedWork: Double,
    /** Smoothed round trip to it, ms. */
    val rttMs: Double,
    val alive: Boolean,
    /** 0..1, or null when the device has no battery (a computer). */
    val battery: Double? = null,
)

enum class Strategy(val label: String) {
    /** Every task goes through one coordinator that sees every queue. Classic, accurate, single point of failure. */
    Central("Centralized"),
    /** Each node decides alone from gossip: small tasks run where they start ("reflex"), big ones go to the cheapest arm. */
    Octopus("Octopus (decentralized)"),
    /** Octopus decisions, but a coordinator's fresh view is used while one is reachable. */
    Hybrid("Hybrid"),
}

/**
 * Pure routing math shared by the real node and the simulator, so the benchmark measures the same decisions the
 * app makes.
 */
object Routing {
    /** Tasks whose local run time is below this are never shipped: the network round trip costs more than it saves. */
    const val REFLEX_MS = 15.0
    /** Battery devices under this level are only used when nothing else is alive. */
    const val LOW_BATTERY = 0.2

    fun expectedMs(work: Double, p: PeerView, isSelf: Boolean): Double {
        val compute = (p.queuedWork + work) / p.capacity
        val net = if (isSelf) 0.0 else p.rttMs
        val batteryPenalty = if (p.battery != null && p.battery < LOW_BATTERY) 1_000.0 else 0.0
        return compute + net + batteryPenalty
    }

    /** Picks where a task of [work] units should run. Returns [self]'s id for local execution. */
    fun choose(work: Double, self: PeerView, peers: Collection<PeerView>, exclude: Set<String> = emptySet()): String {
        val localMs = work / self.capacity
        if (localMs <= REFLEX_MS && self.id !in exclude) return self.id
        val candidates = peers.filter { it.alive && it.id != self.id && it.id !in exclude }
        var best = self.id; var bestMs = if (self.id in exclude) Double.MAX_VALUE else expectedMs(work, self, true)
        for (p in candidates) {
            val ms = expectedMs(work, p, false)
            if (ms < bestMs) { best = p.id; bestMs = ms }
        }
        return best
    }
}

/** Real work for the mesh to share. Deterministic, so any node can verify another's answer. */
object Kernels {
    /** Counts primes below [n] with a sieve. Work units = n / 1000. */
    fun primes(n: Int): Long {
        require(n in 2..50_000_000)
        val composite = BooleanArray(n)
        var count = 0L
        for (i in 2 until n) {
            if (!composite[i]) { count++; var j = i.toLong() * i; while (j < n) { composite[j.toInt()] = true; j += i } }
        }
        return count
    }

    fun workOf(n: Int): Double = n / 1000.0

    /** Measures this device: work units per ms for the primes kernel (best of three after a warm-up). */
    fun calibrate(n: Int = 400_000): Double {
        primes(n)
        var best = Long.MAX_VALUE
        repeat(3) { val t = System.nanoTime(); primes(n); best = minOf(best, System.nanoTime() - t) }
        return workOf(n) / (best / 1e6).coerceAtLeast(0.01)
    }
}

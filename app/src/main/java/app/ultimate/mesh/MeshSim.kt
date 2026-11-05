package app.ultimate.mesh

import kotlin.random.Random

/**
 * Seeded simulation of a small personal mesh (phones, a laptop, a desktop) used to compare [Strategy]s under the same
 * workload and the same failures. Each node is a FIFO worker. Messages cost one link latency each way.
 *
 * Model limits, stated so nobody mistakes this for hardware data: tasks are processed in arrival order; gossip is a
 * periodic snapshot; energy is a proxy (compute-ms on battery devices plus a fixed cost per radio message).
 */
object MeshSim {
    data class NodeSpec(val name: String, val capacity: Double, val onBattery: Boolean, val latencyMs: Double)
    data class Outage(val node: Int, val fromMs: Double, val toMs: Double)
    data class Scenario(
        val nodes: List<NodeSpec>,
        val outages: List<Outage>,
        val tasks: Int = 2_000,
        val meanGapMs: Double = 12.0,
        val gossipMs: Double = 250.0,
        val timeoutMs: Double = 1_500.0,
        val coordinator: Int = 0,
        val seed: Int = 7,
    )
    data class Result(
        val strategy: Strategy, val completed: Int, val failed: Int, val meanMs: Double, val p95Ms: Double,
        val messages: Int, val energy: Double, val retries: Int,
    ) {
        val successRate get() = completed.toDouble() / (completed + failed)
    }

    /** A home: two phones (one with a weak battery story), a laptop, a desktop, a tablet. */
    fun homeMesh() = Scenario(
        nodes = listOf(
            NodeSpec("desktop", 9.0, false, 3.0),
            NodeSpec("laptop", 5.0, true, 4.0),
            NodeSpec("phone-a", 2.5, true, 8.0),
            NodeSpec("phone-b", 2.0, true, 8.0),
            NodeSpec("tablet", 1.5, true, 10.0),
        ),
        // The desktop (also the central coordinator) sleeps for a while; a phone drops off Wi-Fi twice.
        outages = listOf(Outage(0, 8_000.0, 16_000.0), Outage(3, 3_000.0, 5_000.0), Outage(3, 20_000.0, 21_000.0)),
    )

    fun run(sc: Scenario, strategy: Strategy, maxRetries: Int = 3): Result {
        val rnd = Random(sc.seed)
        val n = sc.nodes.size
        val busyUntil = DoubleArray(n)
        val snapshot = DoubleArray(n)            // queued ms as last gossiped
        var lastGossip = -1e9
        var t = 0.0
        val latencies = ArrayList<Double>(sc.tasks)
        var failed = 0; var messages = 0; var energy = 0.0; var retries = 0
        fun up(i: Int, at: Double) = sc.outages.none { it.node == i && at >= it.fromMs && at < it.toMs }
        fun link(a: Int, b: Int) = if (a == b) 0.0 else sc.nodes[a].latencyMs + sc.nodes[b].latencyMs

        repeat(sc.tasks) {
            t += -sc.meanGapMs * kotlin.math.ln(1 - rnd.nextDouble())
            // Mostly small interactive jobs with occasional heavy ones.
            val work = if (rnd.nextDouble() < 0.7) 5.0 + rnd.nextDouble() * 20 else 150.0 + rnd.nextDouble() * 350
            var origin = rnd.nextInt(n); while (!up(origin, t)) origin = (origin + 1) % n
            if (t - lastGossip >= sc.gossipMs) {
                for (i in 0 until n) snapshot[i] = maxOf(0.0, busyUntil[i] - t)
                messages += n * (n - 1); lastGossip = t
            }
            var now = t; var attempt = 0; val excluded = mutableSetOf<Int>(); var done = false
            while (!done && attempt <= maxRetries) {
                attempt++
                val coordUp = up(sc.coordinator, now)
                val useCentral = strategy == Strategy.Central || (strategy == Strategy.Hybrid && coordUp && origin != sc.coordinator)
                val target: Int
                var overhead = 0.0
                if (useCentral) {
                    if (!coordUp) { now += sc.timeoutMs; retries++; messages++; continue }
                    // Coordinator sees exact queues; costs a round trip to ask it.
                    overhead = 2 * link(origin, sc.coordinator); messages += 2
                    target = (0 until n).filter { it !in excluded }.minByOrNull { i ->
                        maxOf(0.0, busyUntil[i] - now) + work / sc.nodes[i].capacity + link(origin, i)
                    } ?: origin
                } else {
                    val views = (0 until n).map { i ->
                        PeerView(i.toString(), sc.nodes[i].capacity, snapshot[i] * sc.nodes[i].capacity, link(origin, i), true)
                    }
                    val self = views[origin].copy(queuedWork = maxOf(0.0, busyUntil[origin] - now) * sc.nodes[origin].capacity)
                    target = Routing.choose(work, self, views, excluded.map { it.toString() }.toSet()).toInt()
                }
                val arrive = now + overhead + link(origin, target) / 2
                val start = maxOf(arrive, busyUntil[target])
                val finish = start + work / sc.nodes[target].capacity
                if (target != origin) messages += 2
                val survives = up(target, arrive) && up(target, finish) && sc.outages.none { it.node == target && it.fromMs in arrive..finish }
                if (survives) {
                    busyUntil[target] = finish
                    if (sc.nodes[target].onBattery) energy += work / sc.nodes[target].capacity
                    latencies += finish + link(origin, target) / 2 - t
                    done = true
                } else {
                    // Lost with the node; the origin notices on timeout and routes around it.
                    if (up(target, arrive)) busyUntil[target] = maxOf(busyUntil[target], arrive)
                    excluded += target; retries++
                    now = arrive + sc.timeoutMs
                }
            }
            if (!done) failed++
        }
        energy += messages * 0.05
        latencies.sort()
        val p95 = if (latencies.isEmpty()) 0.0 else latencies[(latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)]
        return Result(strategy, latencies.size, failed, latencies.average().takeIf { !it.isNaN() } ?: 0.0, p95, messages, energy, retries)
    }
}

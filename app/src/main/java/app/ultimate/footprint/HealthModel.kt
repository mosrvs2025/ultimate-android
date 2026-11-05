package app.ultimate.footprint

/** One night's sleep and the resting figures for a day, ready to say out loud. */
data class HealthDay(val day: Long, val steps: Long, val sleepMs: Long, val avgBpm: Int?, val minBpm: Int?, val maxBpm: Int?)

object HealthMath {
    fun avgSteps(days: List<HealthDay>): Long = days.filter { it.steps > 0 }.let { d -> if (d.isEmpty()) 0 else d.sumOf { it.steps } / d.size }
    fun avgSleepMs(days: List<HealthDay>): Long = days.filter { it.sleepMs > 0 }.let { d -> if (d.isEmpty()) 0 else d.sumOf { it.sleepMs } / d.size }
    fun restingBpm(days: List<HealthDay>): Int? = days.mapNotNull { it.minBpm }.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }

    fun sentences(days: List<HealthDay>): List<String> = buildList {
        val s = avgSteps(days); if (s > 0) add("You walk about %,d steps a day.".format(s))
        days.filter { it.steps > 0 }.maxByOrNull { it.steps }?.let { add("Your biggest day had %,d steps.".format(it.steps)) }
        val sl = avgSleepMs(days); if (sl > 0) add("You sleep about ${sl / 3_600_000}h ${sl / 60_000 % 60}m a night.")
        restingBpm(days)?.let { add("Your resting heart rate is around $it beats a minute.") }
        val n = days.count { it.steps > 0 || it.sleepMs > 0 || it.avgBpm != null }; if (n > 0) add("That is $n days of your body, written down by apps you already use.")
    }
}

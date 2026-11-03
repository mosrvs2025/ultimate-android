package app.ultimate.timeline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray

/** One time on the charger: when, for how long, and the battery level going in and coming out. */
data class Charge(val start: Long, val end: Long, val from: Int, val to: Int)

/**
 * Remembers when the phone is plugged in and unplugged, with the battery level at each, so Timeline can show charging
 * habits. Nothing else: no location, no network. Keeps the last 400 sessions, on the phone only.
 */
object ChargeLog {
    private fun prefs(c: Context) = c.getSharedPreferences("charging", Context.MODE_PRIVATE)
    private fun level(c: Context) = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1

    fun plugged(c: Context, at: Long = System.currentTimeMillis()) { prefs(c).edit().putLong("since", at).putInt("from", level(c)).apply() }

    fun unplugged(c: Context, at: Long = System.currentTimeMillis()) {
        val p = prefs(c); val since = p.getLong("since", 0L); if (since == 0L || at <= since) return
        val list = sessions(c) + Charge(since, at, p.getInt("from", -1), level(c))
        p.edit().putString("log", JSONArray(list.takeLast(400).map { JSONArray().put(it.start).put(it.end).put(it.from).put(it.to) }).toString()).remove("since").apply()
    }

    fun sessions(c: Context): List<Charge> = runCatching {
        val a = JSONArray(prefs(c).getString("log", "[]")); (0 until a.length()).map { i -> a.getJSONArray(i).let { Charge(it.getLong(0), it.getLong(1), it.getInt(2), it.getInt(3)) } }
    }.getOrDefault(emptyList())

    /** The session going on right now, if the phone is on the charger. */
    fun current(c: Context, now: Long = System.currentTimeMillis()): Charge? = prefs(c).getLong("since", 0L).takeIf { it > 0 }?.let { Charge(it, now, prefs(c).getInt("from", -1), level(c)) }
}

/** Android tells this about plugging in and out even while Ultimate is closed; it only writes down the time and level. */
class ChargeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> ChargeLog.plugged(context)
            Intent.ACTION_POWER_DISCONNECTED -> ChargeLog.unplugged(context)
        }
    }
}

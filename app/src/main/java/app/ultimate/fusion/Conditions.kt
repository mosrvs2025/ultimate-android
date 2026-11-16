package app.ultimate.fusion

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the air was like during a walk: temperature, humidity, wind, pressure and air quality. Phones and watches have no sensor for
 * most of this (no gas or particle sensors), so it comes from the free Open-Meteo weather service for the place and hour of the
 * walk, and only after the owner allows it. The place is rounded to about a kilometre before it is sent. Pure parsing and wording.
 */
object Conditions {
    data class Snapshot(val tempC: Double?, val feelsC: Double?, val humidity: Int?, val windKmh: Double?, val pressureHpa: Double?, val aqi: Int?, val pm25: Double?)

    /** A coordinate rounded to two decimals (about a kilometre), which is all weather needs and all that leaves the phone. */
    fun roundForSending(deg: Double): String = "%.2f".format(Locale.US, deg)

    private fun hourKey(epochMs: Long): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH':00'", Locale.US); f.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return f.format(java.util.Date(epochMs))
    }

    fun weatherUrl(lat: Double, lon: Double) = "https://api.open-meteo.com/v1/forecast?latitude=${roundForSending(lat)}&longitude=${roundForSending(lon)}" +
        "&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,pressure_msl&past_days=14&forecast_days=1&timezone=GMT"
    fun airUrl(lat: Double, lon: Double) = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${roundForSending(lat)}&longitude=${roundForSending(lon)}" +
        "&hourly=us_aqi,pm2_5&past_days=7&forecast_days=1&timezone=GMT"

    private fun at(json: String?, field: String, key: String): Double? = runCatching {
        val h = JSONObject(json ?: return null).getJSONObject("hourly")
        val times = h.getJSONArray("time"); val values = h.getJSONArray(field)
        val i = (0 until times.length()).firstOrNull { times.getString(it) == key } ?: return null
        if (values.isNull(i)) null else values.getDouble(i)
    }.getOrNull()

    /** The conditions at [startMs] from the two services' answers (either may be null); null when neither knew that hour. */
    fun parse(weatherJson: String?, airJson: String?, startMs: Long): Snapshot? {
        val k = hourKey(startMs)
        val s = Snapshot(at(weatherJson, "temperature_2m", k), at(weatherJson, "apparent_temperature", k), at(weatherJson, "relative_humidity_2m", k)?.roundToInt(),
            at(weatherJson, "wind_speed_10m", k), at(weatherJson, "pressure_msl", k), at(airJson, "us_aqi", k)?.roundToInt(), at(airJson, "pm2_5", k))
        return if (s.tempC == null && s.aqi == null && s.pressureHpa == null) null else s
    }

    fun aqiWord(aqi: Int): String = when { aqi <= 50 -> "good"; aqi <= 100 -> "moderate"; aqi <= 150 -> "poor for sensitive people"; aqi <= 200 -> "unhealthy"; else -> "very unhealthy" }

    /** "14°C (feels 12°), humidity 68%, wind 11 km/h, pressure 1016 hPa · air quality 42 (good)". */
    fun words(s: Snapshot): String {
        val a = ArrayList<String>()
        s.tempC?.let { a += "%.0f°C".format(Locale.US, it) + (s.feelsC?.takeIf { f -> Math.abs(f - it) >= 2 }?.let { f -> " (feels %.0f°)".format(Locale.US, f) } ?: "") }
        s.humidity?.let { a += "humidity $it%" }; s.windKmh?.let { a += "wind %.0f km/h".format(Locale.US, it) }; s.pressureHpa?.let { a += "pressure %.0f hPa".format(Locale.US, it) }
        val air = s.aqi?.let { "air quality $it (${aqiWord(it)})" }
        return a.joinToString(", ") + (if (a.isNotEmpty() && air != null) " · " else "") + (air ?: "")
    }

    fun encode(s: Snapshot) = JSONObject().apply { s.tempC?.let { put("t", it) }; s.feelsC?.let { put("f", it) }; s.humidity?.let { put("h", it) }; s.windKmh?.let { put("w", it) }; s.pressureHpa?.let { put("p", it) }; s.aqi?.let { put("a", it) }; s.pm25?.let { put("m", it) } }.toString()
    fun decode(text: String?): Snapshot? = runCatching { JSONObject(text ?: return null).let { o -> fun d(k: String) = if (o.has(k)) o.getDouble(k) else null
        Snapshot(d("t"), d("f"), d("h")?.toInt(), d("w"), d("p"), d("a")?.toInt(), d("m")) } }.getOrNull()
}

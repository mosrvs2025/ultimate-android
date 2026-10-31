package app.ultimate.footprint

import java.util.TimeZone

/** What an app can see or do, in the words a person would use. */
enum class Access(val label: String, val sensitive: Boolean, val note: String) {
    BackgroundLocation("Your location, all the time", true, "Even when you are not using the app."),
    Location("Your location", true, "Where you are, while the app is in use."),
    Microphone("Your microphone", true, "It can record sound."),
    Camera("Your camera", true, "It can take photos and video."),
    Contacts("Your contacts", true, "Names, numbers and who you know."),
    Calls("Your calls and phone", true, "Call history, your number, call state."),
    Messages("Your text messages", true, "It can read or send SMS."),
    Calendar("Your calendar", true, "Your events and who is in them."),
    Body("Your body and activity", true, "Steps, heart rate and movement."),
    Photos("Your photos and files", true, "Pictures, videos and the files on your phone, including where photos were taken."),
    Nearby("Devices near you", true, "Bluetooth and Wi-Fi devices around you."),
    AppList("The list of your apps", false, "Which apps you have installed."),
    ScreenTime("What you do in other apps", true, "Which apps you open, and for how long."),
    Internet("The internet", false, "It can send data off your phone."),
}

object PermissionMap {
    private const val GRANTED = 2 // PackageInfo.REQUESTED_PERMISSION_GRANTED

    private val by: Map<String, Access> = buildMap {
        put("android.permission.ACCESS_BACKGROUND_LOCATION", Access.BackgroundLocation)
        listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION").forEach { put("android.permission.$it", Access.Location) }
        put("android.permission.RECORD_AUDIO", Access.Microphone)
        put("android.permission.CAMERA", Access.Camera)
        listOf("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS").forEach { put("android.permission.$it", Access.Contacts) }
        listOf("READ_PHONE_STATE", "READ_CALL_LOG", "WRITE_CALL_LOG", "CALL_PHONE", "READ_PHONE_NUMBERS", "ANSWER_PHONE_CALLS").forEach { put("android.permission.$it", Access.Calls) }
        listOf("READ_SMS", "RECEIVE_SMS", "SEND_SMS", "RECEIVE_MMS").forEach { put("android.permission.$it", Access.Messages) }
        listOf("READ_CALENDAR", "WRITE_CALENDAR").forEach { put("android.permission.$it", Access.Calendar) }
        listOf("BODY_SENSORS", "BODY_SENSORS_BACKGROUND", "ACTIVITY_RECOGNITION").forEach { put("android.permission.$it", Access.Body) }
        listOf("READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE", "ACCESS_MEDIA_LOCATION").forEach { put("android.permission.$it", Access.Photos) }
        listOf("BLUETOOTH_SCAN", "BLUETOOTH_CONNECT", "NEARBY_WIFI_DEVICES").forEach { put("android.permission.$it", Access.Nearby) }
        put("android.permission.QUERY_ALL_PACKAGES", Access.AppList)
        put("android.permission.PACKAGE_USAGE_STATS", Access.ScreenTime)
        put("android.permission.INTERNET", Access.Internet)
    }

    /** What the app can really do now: only permissions that were granted count. Background location replaces plain location. */
    fun granted(requested: List<String>?, flags: IntArray?): Set<Access> {
        if (requested == null) return emptySet()
        val out = HashSet<Access>()
        requested.forEachIndexed { i, p -> if ((flags?.getOrNull(i) ?: 0) and GRANTED != 0) by[p]?.let(out::add) }
        if (Access.BackgroundLocation in out) out.remove(Access.Location)
        return out
    }
}

/** Bytes an app sent and received. */
data class NetUse(val pkg: String, val received: Long, val sent: Long) { val total get() = received + sent }

object NetMath {
    data class Bucket(val uid: Int, val received: Long, val sent: Long)

    /** Adds up buckets by app (an app can have several), leaving out uids that belong to no installed app, biggest first. */
    fun byApp(buckets: List<Bucket>, pkgOf: (Int) -> String?): List<NetUse> =
        buckets.mapNotNull { b -> pkgOf(b.uid)?.let { it to b } }.groupBy({ it.first }, { it.second })
            .map { (pkg, bs) -> NetUse(pkg, bs.sumOf { it.received }, bs.sumOf { it.sent }) }.filter { it.total > 0 }.sortedByDescending { it.total }
}

/** A reading of the phone's step counter, which counts steps since the phone last started. */
data class StepSample(val at: Long, val counter: Long)

object StepMath {
    /** Steps per local day. A counter that goes down means the phone restarted, so the new reading is steps since the restart. */
    fun daily(samples: List<StepSample>, zone: TimeZone = TimeZone.getDefault()): Map<Long, Long> {
        val out = java.util.TreeMap<Long, Long>()
        samples.sortedBy { it.at }.zipWithNext().forEach { (a, b) ->
            val delta = if (b.counter >= a.counter) b.counter - a.counter else b.counter
            val day = Math.floorDiv(b.at + zone.getOffset(b.at), 86_400_000L)
            out.merge(day, delta, Long::plus)
        }
        return out
    }

    fun dayStartLocal(day: Long) = day * 86_400_000L
}

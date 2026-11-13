package app.ultimate.mesh

/** What a device in the mesh is able to do for the others. Reported by the device itself; never guessed by its peers. */
enum class Cap(val key: String, val label: String) {
    Compute("compute", "compute"),
    Motion("motion", "motion sensors"),
    Mic("mic", "microphone"),
    Speaker("spk", "speaker"),
    NearUltrasound("nus", "near-ultrasound"),
    Camera("cam", "camera"),
    Bluetooth("ble", "Bluetooth"),
}

enum class Kind(val key: String, val label: String) {
    Phone("phone", "phone"), Foldable("fold", "foldable"), Tablet("tab", "tablet"), Computer("pc", "computer"), Other("other", "device");
    companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Other }
}

/**
 * Who a device says it is and what it can do. [lends] is the owner's separate choice to let their other devices
 * use this one's sensors and speaker; it is off until they switch it on, and a device that is not lending is
 * never asked to record or play a sound however capable it is.
 */
data class DeviceProfile(val model: String, val kind: Kind, val caps: Set<Cap>, val lends: Boolean, val app: String = "") {
    val canRecordMotion get() = lends && Cap.Motion in caps
    /** Distance by sound needs a speaker, a microphone, and both able to reach near-ultrasound (the phone's own report). */
    val canRange get() = lends && Cap.Mic in caps && Cap.Speaker in caps && Cap.NearUltrasound in caps

    fun toPayload(): Map<String, String> = buildMap {
        put("model", model); put("kind", kind.key); put("caps", caps.joinToString(",") { it.key }); put("lends", if (lends) "1" else "0")
        if (app.isNotEmpty()) put("app", app)
    }

    /** One line for people: "foldable · motion sensors, microphone, speaker". */
    fun describe(): String = kind.label + (if (caps.isEmpty()) "" else " · " + caps.sortedBy { it.ordinal }.joinToString(", ") { it.label })

    companion object {
        val Unknown = DeviceProfile("?", Kind.Other, emptySet(), false)
        fun fromPayload(p: Map<String, String>) = DeviceProfile(
            model = p["model"] ?: "?", kind = Kind.of(p["kind"]),
            caps = (p["caps"] ?: "").split(',').mapNotNull { k -> Cap.entries.firstOrNull { it.key == k } }.toSet(),
            lends = p["lends"] == "1", app = p["app"] ?: "",
        )
    }
}

/** Commands one device can send another, over the signed mesh channel. A device refuses any of these it has not been told to lend. */
object Cmd {
    const val PING = "ping"
    const val REC_START = "rec.start"
    const val REC_STOP = "rec.stop"
    const val REC_STATUS = "rec.status"
    const val REC_FETCH = "rec.fetch"
    const val RANGE_ARM = "range.arm"
    const val RANGE_RESULT = "range.result"
}

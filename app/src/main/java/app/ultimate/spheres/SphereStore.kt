package app.ultimate.spheres

import java.io.File

/**
 * Where captured places live: files/spheres/<place>/tour.json and sphere/<stop>.jpg. Only on this phone; the Wi-Fi page
 * (Send to computer) can show them to a headset or computer on the same network when it is switched on.
 */
class SphereStore(filesDir: File) {
    val root = File(filesDir, "spheres").apply { mkdirs() }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64)
    fun dir(tourId: String) = File(root, safe(tourId))
    fun image(tourId: String, stopId: String) = File(File(dir(tourId), "sphere").apply { mkdirs() }, safe(stopId) + ".jpg")

    fun list(): List<SphereTour> = root.listFiles().orEmpty().mapNotNull { d -> File(d, "tour.json").takeIf { it.exists() }?.let { SphereTour.fromJson(it.readText()) } }
        .sortedByDescending { it.createdAt }

    fun get(id: String): SphereTour? = File(dir(id), "tour.json").takeIf { it.exists() }?.let { SphereTour.fromJson(it.readText()) }

    fun save(t: SphereTour) { val d = dir(t.id).apply { mkdirs() }; File(d, "tour.json.tmp").apply { writeText(t.toJson()); renameTo(File(d, "tour.json")) } }

    fun delete(id: String) = dir(id).deleteRecursively()

    /**
     * A file the viewer page asks for, relative to a place ("tour.json", "sphere/<id>.jpg"); null for anything else, so a
     * request can never reach outside the place's own folder.
     */
    fun serve(tourId: String, path: String): Pair<ByteArray, String>? {
        val t = get(tourId) ?: return null
        return when {
            path == "tour.json" -> t.toViewerJson().toByteArray() to "application/json"
            path.startsWith("sphere/") && path.endsWith(".jpg") -> {
                val stop = path.removePrefix("sphere/").removeSuffix(".jpg")
                if (t.stops.none { it.id == stop }) null else image(tourId, stop).takeIf { it.exists() }?.readBytes()?.let { it to "image/jpeg" }
            }
            else -> null
        }
    }
}

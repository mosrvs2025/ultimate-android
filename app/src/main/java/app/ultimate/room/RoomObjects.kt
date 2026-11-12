package app.ultimate.room

/**
 * A thing found in a space. [id] stays the same across scans, so "the couch" is one couch however many times the room is rescanned.
 * [state] is "here", or "missing" when a later scan looked at where it was and did not find it.
 */
data class RoomObject(val label: String, val at: P3, val seen: Int, val best: Float, val id: String = "", val firstAt: Long = 0, val lastAt: Long = 0, val state: String = "here", val missed: Int = 0)

/** The household things worth remembering, out of what the detector knows (people and vehicles are left out on purpose). */
object RoomLabels {
    val KEEP = setOf("chair", "couch", "bed", "dining table", "tv", "laptop", "potted plant", "refrigerator", "microwave", "oven", "toaster", "sink", "toilet", "book", "clock", "vase",
        "bottle", "cup", "bowl", "keyboard", "mouse", "remote", "cell phone", "backpack", "handbag", "suitcase", "bench", "teddy bear", "umbrella", "scissors", "hair drier")
    /** Not something the detector knows: a chest of drawers, wardrobe or cupboard, which it tends to call a suitcase. Produced by [fix]. */
    const val CABINET = "cabinet"
    fun emoji(label: String) = when (label) { "chair", "bench" -> "🪑"; "couch" -> "🛋️"; "bed" -> "🛏️"; "dining table" -> "🍽️"; "tv" -> "📺"; "laptop", "keyboard", "mouse" -> "💻"; "potted plant", "vase" -> "🪴"
        "refrigerator" -> "🧊"; CABINET -> "🗄️"; "microwave", "oven", "toaster" -> "🍳"; "sink" -> "🚰"; "toilet" -> "🚽"; "book" -> "📚"; "clock" -> "🕒"; "bottle", "cup", "bowl" -> "🥤"; "remote", "cell phone" -> "📱"; "backpack", "handbag", "suitcase" -> "🎒"; else -> "📦" }

    /**
     * How far apart two sightings of the same kind of thing can be and still be one thing: about the thing's own size. One fixed
     * distance (0.7 m, as before) splits a two-metre bed into several "beds" and glues separate bottles on a shelf into one.
     */
    fun radius(label: String): Float = when (label) {
        "bed" -> 1.6f; "couch" -> 1.4f; CABINET -> 0.8f; "dining table" -> 1.1f; "refrigerator", "bench" -> 0.9f; "tv", "oven", "toilet", "sink" -> 0.7f
        "chair", "suitcase", "potted plant", "microwave" -> 0.55f; "laptop", "backpack", "keyboard", "umbrella", "teddy bear" -> 0.4f
        else -> 0.25f
    }
    /**
     * Checks a detection against the thing's real size ([widthM] and [heightM] from the box and its depth) and returns the label to use,
     * or null when it cannot be that. The detector has no class for a dresser or wardrobe and calls a large wooden box a suitcase; a
     * "bottle" a metre wide is a pile of something. These are plain size rules, not guesses about what the thing is.
     */
    fun fix(label: String, widthM: Float, heightM: Float): String? {
        val big = maxOf(widthM, heightM)
        return when (label) {
            "suitcase" -> if (widthM > 0.9f || heightM > 0.85f) CABINET else label
            "bed" -> if (big < 0.9f) null else label
            "couch" -> if (widthM < 0.8f) "chair" else label
            "chair" -> if (widthM > 1.3f) null else label
            "bottle" -> if (widthM > 0.5f || heightM > 0.5f) null else label
            "book" -> if (widthM > 0.6f) null else label
            "laptop" -> if (widthM > 0.75f) null else label
            "backpack", "handbag" -> if (big > 0.95f) null else label
            "tv" -> if (big < 0.3f) null else label
            else -> label
        }
    }

    /**
     * Whether a thing can really be where it was placed. A small thing (a bottle, a cup, a book) has to rest on something; the detector
     * "finds" them on blank walls, and the depth there is the wall itself, so the position lies flat against a wall surface well above the
     * floor. That is rejected. Bigger things (a wardrobe, a bed) can stand against a wall and are left alone.
     */
    fun plausible(o: RoomObject, s: RoomStats?): Boolean {
        if (s == null || s.walls.isEmpty() || radius(o.label) >= 0.4f) return true
        val floorY = s.floorY ?: return true
        val a = Math.toRadians(s.headingDeg.toDouble()); val c = kotlin.math.cos(a).toFloat(); val sn = kotlin.math.sin(a).toFloat()
        val pu = o.at.x * c + o.at.z * sn; val pv = -o.at.x * sn + o.at.z * c
        if (o.at.y - floorY < 0.9f) return true
        return s.walls.none { w -> val on = if (w.axis == 0) pu else pv; val along = if (w.axis == 0) pv else pu; kotlin.math.abs(on - w.pos) < 0.08f && along in (w.from - 0.2f)..(w.to + 0.2f) }
    }

    /** Big things can be told from one good look; small ones get mislabelled more, so they need more sightings and a surer detector. */
    fun minScore(label: String): Float = if (radius(label) >= 0.55f) 0.45f else 0.5f
}

/**
 * Remembers objects across many camera frames within one scan. One frame's detection is not trusted: the same label seen again within
 * the thing's own size is the same object (its position settles to the running average), and it only counts after [confirmAt]
 * sightings. Pure, so it is tested.
 */
class ObjectMemory(private val confirmAt: Int = 4) {
    private val all = ArrayList<RoomObject>()

    fun see(label: String, at: P3, score: Float) {
        if (score < RoomLabels.minScore(label)) return
        val r = RoomLabels.radius(label)
        val i = all.indices.filter { all[it].label == label && all[it].at.dist(at) <= r }.minByOrNull { all[it].at.dist(at) }
        if (i == null) { all += RoomObject(label, at, 1, score); return }
        val o = all[i]; val n = o.seen + 1; val k = minOf(o.seen, 20)   // after 20 sightings, new ones still nudge it (so drift doesn't freeze an early error)
        all[i] = o.copy(at = P3((o.at.x * k + at.x) / (k + 1), (o.at.y * k + at.y) / (k + 1), (o.at.z * k + at.z) / (k + 1)), seen = n, best = maxOf(o.best, score))
    }

    fun confirmed(): List<RoomObject> = all.filter { it.seen >= confirmAt }.sortedByDescending { it.seen }
    fun clear() = all.clear()
}

/**
 * Keeping things' identities when a room is scanned again. [fresh] must already be in the saved room's coordinates. A fresh thing of
 * the same kind near a saved one *is* that saved one (it keeps its id and first-seen time, and takes the new position, so a chair that
 * was nudged stays the same chair). A fresh thing with no match is new. A saved thing with no match is marked "missing" only if this
 * scan actually looked at the place where it was ([looked]); otherwise it is left exactly as it was. Pure, so it is tested.
 */
object ObjectIdentity {
    fun merge(saved: List<RoomObject>, fresh: List<RoomObject>, now: Long, looked: (P3) -> Boolean, newId: () -> String): List<RoomObject> {
        val out = ArrayList<RoomObject>(); val used = HashSet<Int>()
        // Closest pairs first, so two chairs side by side keep their own identities.
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for (s in saved.indices) for (f in fresh.indices) if (saved[s].label == fresh[f].label) { val d = saved[s].at.dist(fresh[f].at); if (d <= 2f * RoomLabels.radius(saved[s].label) + 0.3f) pairs += Triple(d, s, f) }
        val matchOf = HashMap<Int, Int>()
        for ((_, s, f) in pairs.sortedBy { it.first }) if (s !in matchOf && f !in used) { matchOf[s] = f; used += f }
        for (s in saved.indices) {
            val o = saved[s]; val f = matchOf[s]?.let { fresh[it] }
            val r = when {
                f != null -> o.copy(at = f.at, seen = o.seen + f.seen, best = maxOf(o.best, f.best), lastAt = now, state = "here", missed = 0, id = o.id.ifEmpty { newId() })
                looked(o.at) -> o.copy(state = "missing", missed = o.missed + 1, id = o.id.ifEmpty { newId() })
                else -> o.copy(id = o.id.ifEmpty { newId() })
            }
            // Not found again after two scans that looked at the place: it is gone, so it stops cluttering the list.
            if (r.missed < 2) out += r
        }
        for (f in fresh.indices) if (f !in used) out += fresh[f].copy(id = newId(), firstAt = now, lastAt = now, state = "here", missed = 0)
        return tidy(out)
    }

    /** One thing, one entry: same-kind entries closer than about the thing's own size are the same thing seen twice (the older identity stays). */
    fun tidy(all: List<RoomObject>): List<RoomObject> {
        val sorted = all.sortedWith(compareBy<RoomObject>({ if (it.firstAt == 0L) Long.MAX_VALUE else it.firstAt }).thenByDescending { it.seen })
        val out = ArrayList<RoomObject>()
        for (o in sorted) {
            val i = out.indexOfFirst { it.label == o.label && it.at.dist(o.at) < 1.1f * RoomLabels.radius(o.label) + 0.1f }
            if (i < 0) { out += o; continue }
            val k = out[i]; val n = k.seen + o.seen
            out[i] = k.copy(at = P3((k.at.x * k.seen + o.at.x * o.seen) / n, (k.at.y * k.seen + o.at.y * o.seen) / n, (k.at.z * k.seen + o.at.z * o.seen) / n), seen = n, best = maxOf(k.best, o.best),
                lastAt = maxOf(k.lastAt, o.lastAt), state = if (k.state == "here" || o.state == "here") "here" else "missing", missed = minOf(k.missed, o.missed))
        }
        return out
    }
}

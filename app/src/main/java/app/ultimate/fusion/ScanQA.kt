package app.ultimate.fusion

import app.ultimate.worldscan.Place
import app.ultimate.worldscan.WalkMeta

/**
 * Answers questions about a mapped place from what World Scan recorded about each walk (how much of it was tracked, how it was placed on
 * the map, how it was merged), with the same rule as [MotionQA]: only stored measurements, in fixed sentences, never a guess. It can say how
 * well a scan was tracked and merged; it cannot say how close the map is to the real building, because nothing in the app has measured
 * that against a known size, and it says so.
 */
object ScanQA {
    fun answer(question: String, place: Place): Answer {
        val q = question.lowercase()
        fun has(vararg w: String) = w.any { q.contains(it) }
        if (place.walks.isEmpty()) return Answer("This place has no walks yet.", emptyList(), Basis.Observed)
        return when {
            has("merge", "combine", "together", "line up", "align") -> merges(place)
            has("chang", "differ", "new", "moved", "removed", "added") -> changes(place)
            has("where", "gps", "on the map", "earth", "located") -> where(place)
            has("big", "size", "large", "how long", "extent") -> size(place)
            has("accura", "good", "quality", "reliab", "trust", "how well") -> accuracy(place)
            else -> Answer("I can answer about a place's scan quality, whether walks could be merged, what changed between walks, where it is on the map, and how big it is. I only use what was recorded.", emptyList(), Basis.Derived, understood = false)
        }
    }

    private fun latest(p: Place) = p.walks.maxByOrNull { it.startedAt }!!

    private fun accuracy(p: Place): Answer {
        val w = latest(p)
        val lines = ArrayList<String>()
        lines += "Latest walk (${w.name}): the camera tracked it ${"%.0f".format(w.trackedShare * 100)}% of the time" + (if (w.longestLossS > 1) ", longest loss ${"%.0f".format(w.longestLossS)} s" else "") + (if (w.jumps > 0) ", ${w.jumps} sudden position jump(s)" else "") + "."
        val geo = w.geo
        lines += if (geo != null) geo.summary() + "." else "It was not placed on the Earth (no usable GPS)."
        w.clock?.let { lines += "Camera and sensor clocks were matched to ±${"%.0f".format(it.jitterMs)} ms." }
        w.drift?.let { lines += "Drift check: ${it.summary()}" + if (it.applied) " (the correction is applied)." else "" }
        w.merge?.let { lines += "It joined the map with ${"%.0f".format(it.globalInlier * 100)}% of its points agreeing with what was already there." }
        lines += "These describe how well the scan was tracked and merged. How close the map is to the true building size has not been measured: the app has no known-size reference for it, so no accuracy in centimetres is claimed."
        return Answer("OBSERVED/DERIVED: " + lines.joinToString(" "), listOf(Quantity(w.trackedShare * 100, 0.0, "% tracked", Basis.Observed, "camera tracking")), Basis.Derived)
    }

    private fun changes(p: Place): Answer {
        val merged = p.walks.filter { it.merge != null }.sortedBy { it.startedAt }
        if (merged.isEmpty()) return Answer("No walk has been merged into this map yet, so there is nothing to compare: change between scans is measured when a later walk is merged over an earlier one.", emptyList(), Basis.Derived)
        val w = merged.last(); val m = w.merge!!
        val added = m.pieces.sumOf { it.added }; val re = m.pieces.sumOf { it.reinforced }; val rem = m.pieces.sumOf { it.removed }
        val share = if (added + re > 0) 100.0 * added / (added + re) else 0.0
        return Answer("DERIVED from the last merge (${w.name}): it added $added new 10 cm cells, reinforced $re that were already mapped, and removed $rem that it no longer saw. " +
            "About ${"%.0f".format(share)}% of what it saw was new. New cells can be a place that was not walked before as well as something that changed, and removed cells can be a place not walked this time; the app cannot tell those apart yet.",
            listOf(Quantity(added.toDouble(), 0.0, "cells added", Basis.Derived, "merge record"), Quantity(rem.toDouble(), 0.0, "cells removed", Basis.Derived, "merge record")), Basis.Derived)
    }

    private fun merges(p: Place): Answer {
        val parts = p.walks.sortedBy { it.startedAt }.map { w ->
            val m = w.merge
            when {
                w.id == p.baseWalkId -> "${w.name}: the first walk, which defines the map."
                m != null -> "${w.name}: merged (${"%.0f".format(m.globalInlier * 100)}% of its points agreed with the map, ${m.pieces.count { it.alignment != null }} of ${m.pieces.size} pieces placed, " + (if (m.margin > 0) "the best placement beat the next by ${"%.2f".format(m.margin)})." else "no close second placement).")
                else -> "${w.name}: NOT merged" + (w.unmergedReason?.let { ", because $it" } ?: ".")
            }
        }
        return Answer("DERIVED from the stored merge records. " + parts.joinToString(" "), emptyList(), Basis.Derived)
    }

    private fun where(p: Place): Answer {
        val g = p.bestGeo ?: return Answer("This place has not been placed on the Earth: none of its walks had usable GPS.", emptyList(), Basis.Observed)
        return Answer("DERIVED: " + g.summary() + ". GPS places the whole map to within a few metres; it does not make the map more accurate, only locates it.", emptyList(), Basis.Derived)
    }

    private fun size(p: Place): Answer {
        val w: WalkMeta = p.walks.maxByOrNull { it.lengthM }!!
        return Answer("OBSERVED by camera tracking: the longest walk (${w.name}) went ${"%.0f".format(w.lengthM)} m and spans ${"%.0f".format(w.extentX)} × ${"%.0f".format(w.extentZ)} m. Camera-tracking scale drifts by a few percent over long walks and has not been checked against a known length here.",
            listOf(Quantity(w.lengthM, 0.02 * w.lengthM + 0.5, "m", Basis.Derived, "camera tracking")), Basis.Derived)
    }
}

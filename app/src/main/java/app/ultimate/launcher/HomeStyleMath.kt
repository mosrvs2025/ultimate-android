package app.ultimate.launcher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How the home screen is arranged, which is a different question from how the favourites are laid out: the original is a list of favourites with a
 * letter rail for everything else, and these are whole other ways of holding your apps. Each one gets every app, not just the favourites.
 */
enum class HomeStyle(val label: String, val blurb: String) {
    Lists("Lists", "Your favourites as a list, and a liquid letter rail for everything else"),
    Pages("Pages", "Every app in a grid of pages you swipe between, with your favourites in a dock"),
    Hive("Hive", "Hexagon icons packed like a honeycomb, scrolling down"),
    Dial("Dial", "A ring of apps round the corner your thumb rests in: spin it"),
    Tiles("Tiles", "Big coloured tiles for your favourites, wide and small, with All apps at the end"),
    Prompt("Prompt", "A command line: type a few letters and press Enter"),
}

/** The sums behind the home styles, apart from drawing, so they are tested without a phone. */
object StyleMath {
    // ---- Pages

    /** How many pages [count] apps need at [perPage] to a page (at least one, so an empty phone still has a page). */
    fun pages(count: Int, perPage: Int): Int = if (perPage <= 0) 1 else max(1, ceil(count / perPage.toDouble()).toInt())

    /** The apps on [page] (0-based); a page past the end is empty. */
    fun <T> pageSlice(items: List<T>, page: Int, perPage: Int): List<T> = if (perPage <= 0 || page < 0) emptyList() else items.drop(page * perPage).take(perPage)

    /** Columns and rows of cells that fit a [widthDp] by [heightDp] area: whole cells only, at least 3 columns and 2 rows, at most 6 columns. */
    fun gridFit(widthDp: Float, heightDp: Float, cellW: Float, cellH: Float): Pair<Int, Int> =
        floor(widthDp / cellW).toInt().coerceIn(3, 6) to floor(heightDp / cellH).toInt().coerceAtLeast(2)

    // ---- Hive

    /** Hexagons per row: the row has to fit with room for the half-cell the odd rows are pushed across. */
    fun hiveCols(widthDp: Float, cellDp: Float): Int = floor((widthDp - cellDp / 2f) / cellDp).toInt().coerceIn(2, 7)

    /** The apps in rows of [cols]; every other row is pushed half a cell across by the drawing, which is what makes it a honeycomb. */
    fun <T> hiveRows(items: List<T>, cols: Int): List<List<T>> = if (cols <= 0) emptyList() else items.chunked(cols)

    // ---- Dial

    /** The dial is a ring seen from its corner: the apps sit on an arc from [START] round to [END] degrees, measured the way a screen is (0 to the right, 90 down). */
    const val START = 180f      // left of the corner
    const val END = 270f        // above the corner
    const val STEP = 12f        // degrees between neighbouring apps

    /** Where app [index] is on the ring when the ring has been turned by [offsetDeg]. */
    fun dialAngle(index: Int, offsetDeg: Float, stepDeg: Float = STEP): Float = START + index * stepDeg - offsetDeg

    fun dialVisible(angle: Float): Boolean = angle >= START - 0.01f && angle <= END + 0.01f

    /** The ring can't be turned past its first or last app. */
    fun dialClamp(offsetDeg: Float, count: Int, stepDeg: Float = STEP): Float = offsetDeg.coerceIn(0f, max(0, count - 1) * stepDeg)

    /** The app sitting in the middle of the visible arc. */
    fun dialSelected(offsetDeg: Float, count: Int, stepDeg: Float = STEP): Int {
        if (count <= 0) return -1
        val mid = (START + END) / 2f
        return (((mid - START) + offsetDeg) / stepDeg).roundToInt().coerceIn(0, count - 1)
    }

    /** Turning by a finger: the angle (degrees) of [x],[y] seen from the corner ([cx],[cy]). */
    fun angleOf(x: Float, y: Float, cx: Float, cy: Float): Float = (atan2(y - cy, x - cx) * 180f / PI.toFloat()).let { if (it < 0) it + 360f else it }

    /** How far the finger went round the corner between two angles, taking the short way across the 0/360 seam. */
    fun turn(from: Float, to: Float): Float { var d = to - from; while (d > 180f) d -= 360f; while (d < -180f) d += 360f; return d }

    // ---- Tiles

    enum class Tile(val span: Int, val tallDp: Int) { Large(2, 150), Wide(2, 76), Small(1, 76) }

    /** One large tile, then two small and a wide, over and over: a rhythm, so the wall never looks like a plain grid. */
    fun tilePlan(count: Int): List<Tile> = List(count) { i -> if (i == 0) Tile.Large else when ((i - 1) % 3) { 0, 1 -> Tile.Small; else -> Tile.Wide } }

    // ---- Prompt

    /** What the prompt lists: your favourites until something is typed (and while a bare number is typed, so the numbers are in view), then the matches. */
    fun promptList(apps: List<HomeApp>, favourites: List<HomeApp>, query: String, limit: Int = 8): List<HomeApp> =
        (if (query.isBlank() || query.trim().toIntOrNull() != null) favourites else LauncherMath.search(apps, query)).take(limit)

    /** Typing a bare number picks that line of the list (1 is the first), as the numbered lists do. Null when [query] isn't a number or is out of range. */
    fun promptPick(query: String, listed: List<HomeApp>): HomeApp? = query.trim().toIntOrNull()?.let { n -> listed.getOrNull(n - 1) }

    /** Which app Enter opens: a picked number, else the first match. */
    fun promptEnter(apps: List<HomeApp>, favourites: List<HomeApp>, query: String): HomeApp? {
        val listed = promptList(apps, favourites, query)
        return promptPick(query, listed) ?: if (query.isBlank()) null else listed.firstOrNull()
    }
}

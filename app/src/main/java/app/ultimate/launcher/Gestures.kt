package app.ultimate.launcher

/** The small sums behind trying a gesture on the settings page's pad, apart from the touch handling, so they are tested without a phone. */
object Gestures {
    enum class Swipe { Up, Down }

    /** A vertical drag that ended [totalDy] pixels from where it began (negative is up): a swipe only when it went at least [threshold] either way. */
    fun swipe(totalDy: Float, threshold: Float): Swipe? = when {
        totalDy <= -threshold -> Swipe.Up
        totalDy >= threshold -> Swipe.Down
        else -> null
    }
}

package app.ultimate.air

/**
 * What the watch sends, and what it means on the phone. Messages are "gesture:<kind>" for wrist gestures, whose action
 * the person chooses, and "button:<action>" for the watch's buttons, which name their action directly.
 */
object WatchMessages {
    /** Must match the watch's PhoneLink.PATH. */
    const val PATH = "/ultimate/watch"

    data class Message(val isButton: Boolean, val kind: String)

    val GESTURES = listOf(
        "flick_left" to "Flick left", "flick_right" to "Flick right", "flick_up" to "Flick up", "flick_down" to "Flick down",
        "twist" to "Twist", "shake" to "Shake",
    )
    /** Starting actions for each wrist gesture; each can be changed on the phone. */
    val DEFAULTS = mapOf("flick_left" to "previous", "flick_right" to "next", "twist" to "playpause", "flick_up" to "volume_up", "flick_down" to "volume_down", "shake" to "none")

    /** Parses a message; null for anything unknown, so a newer watch can't make an older phone do something odd. */
    fun parse(text: String): Message? {
        val (type, kind) = text.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
        return when (type) {
            "gesture" -> kind.takeIf { k -> GESTURES.any { it.first == k } }?.let { Message(false, it) }
            "button" -> kind.takeIf { k -> AirActions.CHOICES.any { it.first == k } && k != "none" }?.let { Message(true, it) }
            else -> null
        }
    }

    fun label(m: Message) = if (m.isButton) AirActions.CHOICES.firstOrNull { it.first == m.kind }?.second ?: m.kind
        else GESTURES.firstOrNull { it.first == m.kind }?.second ?: m.kind
}

package app.ultimate.recipes

/** What a step works on and what it hands to the next step. */
enum class Kind { Video, Audio, Image }

/** One thing a recipe does, done by the same engine as the tool it comes from. */
enum class Step(val label: String, val takes: Set<Kind>, val gives: Kind?, val toolId: String) {
    Shrink("Make smaller", setOf(Kind.Video), Kind.Video, "squeeze"),
    Mp3("Pull out the sound as MP3", setOf(Kind.Video, Kind.Audio), Kind.Audio, "audio"),
    Podcast("Podcast-style MP3 (mono, small)", setOf(Kind.Video, Kind.Audio), Kind.Audio, "audio"),
    Transcribe("Write down what's said", setOf(Kind.Video, Kind.Audio), null, ""),
    PhotoPost("Crop 4:5 for posting", setOf(Kind.Image), Kind.Image, "photos"),
    PhotoSmall("Small photo for sending", setOf(Kind.Image), Kind.Image, "photos"),
}

data class Recipe(val id: String, val name: String, val steps: List<Step>, val builtIn: Boolean = false) {
    /** The kind of file the recipe starts from. */
    val starts: Set<Kind> get() = steps.firstOrNull()?.takes.orEmpty()
}

object RecipeRules {
    val builtIns = listOf(
        Recipe("smaller-mp3", "Smaller video + MP3", listOf(Step.Shrink, Step.Mp3), true),
        Recipe("podcast", "Video to podcast", listOf(Step.Podcast, Step.Transcribe), true),
        Recipe("notes", "Notes from a recording", listOf(Step.Transcribe), true),
        Recipe("post", "Ready to post", listOf(Step.PhotoPost), true),
    )

    fun kindOf(mime: String): Kind? = when {
        mime.startsWith("video/") -> Kind.Video
        mime.startsWith("audio/") -> Kind.Audio
        mime.startsWith("image/") -> Kind.Image
        else -> null
    }

    /** Null when every step can take what the step before hands it; otherwise why not, in plain words. */
    fun problem(steps: List<Step>): String? {
        if (steps.isEmpty()) return "Add at least one step."
        var flowing: Set<Kind> = steps.first().takes
        steps.forEachIndexed { i, s ->
            if (flowing.none { it in s.takes }) return "\"${s.label}\" can't follow \"${steps[i - 1].label}\"."
            s.gives?.let { flowing = setOf(it) }
        }
        return null
    }

    /** Recipes that can start from this kind of file and whose tools this edition has. */
    fun forKind(all: List<Recipe>, kind: Kind, toolIds: Set<String>) = all.filter { r -> kind in r.starts && r.steps.all { it.toolId.isEmpty() || it.toolId in toolIds } }

    /** Folders phones put screen recordings in (Samsung, Pixel and others). */
    fun isScreenRecording(relativePath: String): Boolean {
        val p = relativePath.lowercase()
        return "screen recording" in p || "screenrecord" in p || "screen_record" in p || "screencast" in p
    }
}

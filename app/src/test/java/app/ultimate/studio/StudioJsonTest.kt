package app.ultimate.studio

import org.junit.Assert.*
import org.junit.Test

class StudioJsonTest {
    @Test fun roundTripPreservesEditableProjectNotJustExportRecipe() {
        val p = StudioProject(id = "p", name = "Mo’s film 🎬", clips = listOf(StudioClip(id = "c", uri = "content://clip/1", name = "Clip",
            sourceMs = 10000, startMs = 500, endMs = 7000, speed = 0.5f, volume = 0f, look = Look.Noir, brightness = 0.2f, rotation = 90,
            title = "Two little moons\nfind each other", titlePosition = 2)), shape = CanvasShape.Square, fillCanvas = true,
            music = StudioMusic("content://song/2", "Song", 60000, 3000, 0.25f), bpm = 126, beatOffsetMs = 500, shortSide = 1080)
        assertEquals(p, StudioJson.decode(StudioJson.encode(p)))
    }
    @Test(expected = IllegalArgumentException::class) fun futureProjectSchemasDoNotGetSilentlyMisread() { StudioJson.decode("{\"schema\":99}") }
}

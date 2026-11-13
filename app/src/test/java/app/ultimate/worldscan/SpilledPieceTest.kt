package app.ultimate.worldscan

import app.ultimate.room.RoomMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream

class SpilledPieceTest {
    private fun wall(x0: Float, n: Int): RoomMap { val m = WalkSettings.newMap(); for (i in 0 until n) for (y in 0..10) for (v in 1..3) m.hit(x0 + i * 0.1f, y * 0.1f, 3f, v, 1f); return m }
    private fun track() = WalkTrack().also { t -> for (i in 0..300) t.add(PosePoint(i * 100_000_000L, i * 0.14f, 0f, 0f, 0f, 0f, 0f, 1f, TrackingQuality.TRACKING)) }

    @Test fun piecesWrittenOutDuringAWalkAreStoredLikeThoseKeptInMemory() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wsspill" + System.nanoTime()); dir.mkdirs()
        val first = wall(0f, 150); val second = wall(15f, 150)
        val file = File(dir, "w.piece0.bin.gz"); GZIPOutputStream(file.outputStream()).use { first.write(it) }
        // The same walk, once with its first stretch spilled to a file and once with both in memory.
        val spilled = WalkStore(File(dir, "a")).save(WalkInput("w", "Walk", 0L, 1L, track(), listOf(SpilledPiece(file, 0L, 14_999_999_999L), WalkChunk(second, 15_000_000_000L, 29_999_999_999L)), null, emptyList(), null, null, 1, ""), null)
        val inMemory = WalkStore(File(dir, "b")).save(WalkInput("w", "Walk", 0L, 1L, track(), listOf(WalkChunk(first, 0L, 14_999_999_999L), WalkChunk(second, 15_000_000_000L, 29_999_999_999L)), null, emptyList(), null, null, 1, ""), null)
        assertEquals(inMemory.walk.cubes, spilled.walk.cubes)
        assertEquals(2, spilled.walk.pieces.size)
        val a = WalkStore(File(dir, "a")).baseMap(spilled.place.id)!!.solid; val b = WalkStore(File(dir, "b")).baseMap(inMemory.place.id)!!.solid
        assertEquals(b, a)
        assertEquals(2, WalkStore(File(dir, "a")).pieces(spilled.place.id, "w").size)
        assertFalse("the spill file is removed once the walk is stored", file.exists())
        dir.deleteRecursively()
    }
}

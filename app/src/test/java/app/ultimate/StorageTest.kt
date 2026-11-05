package app.ultimate

import app.ultimate.storage.Folder
import app.ultimate.storage.Treemap
import app.ultimate.storage.Trend
import app.ultimate.storage.biggestFolders
import app.ultimate.storage.chainTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageTest {
    @Test fun treemapAreasMatchSizesAndStayInside() {
        val items = listOf(600L, 300L, 60L, 30L, 10L).mapIndexed { i, b -> Triple("k$i", "n$i", b) }
        val tiles = Treemap.layout(items)
        assertEquals(5, tiles.size)
        tiles.forEach { t ->
            assertEquals(t.bytes / 1000.0, (t.w * t.h).toDouble(), 0.001)
            assertTrue(t.x >= -1e-4f && t.y >= -1e-4f && t.x + t.w <= 1.0001f && t.y + t.h <= 1.0001f)
        }
        assertEquals(1.0, tiles.sumOf { (it.w * it.h).toDouble() }, 0.001)
    }

    @Test fun treemapSkipsEmptyAndHandlesNothing() {
        assertTrue(Treemap.layout(emptyList()).isEmpty())
        assertEquals(1, Treemap.layout(listOf(Triple("a", "a", 5L), Triple("b", "b", 0L))).size)
    }

    @Test fun treemapTilesAreNotSlivers() {
        val tiles = Treemap.layout((1..12).map { Triple("$it", "$it", 100L) })
        tiles.forEach { assertTrue(maxOf(it.w / it.h, it.h / it.w) < 3f) }
    }

    @Test fun trendComparesWithAWeekAgo() {
        val day = 86_400_000L
        val now = 100 * day
        var records = emptyList<Pair<Long, Long>>()
        records = Trend.add(records, now - 8 * day, 50)
        records = Trend.add(records, now - 7 * day, 60)
        records = Trend.add(records, now - day, 70)
        records = Trend.add(records, now, 90)
        assertEquals(30L, Trend.change(records, now))
        assertNull(Trend.change(listOf(now to 5L), now))
    }

    @Test fun trendKeepsOneRecordPerDay() {
        val r = Trend.add(Trend.add(emptyList(), 1000, 1), 2000, 2)
        assertEquals(listOf(2000L to 2L), r)
    }

    @Test fun biggestFoldersSkipWrappersAndChainFindsPath() {
        val root = Folder("Internal storage", "/s")
        val dcim = Folder("DCIM", "/s/DCIM").also { it.bytes = 100 }
        val cam = Folder("Camera", "/s/DCIM/Camera").also { it.bytes = 95 }
        val dl = Folder("Download", "/s/Download").also { it.bytes = 40 }
        val apks = Folder("apks", "/s/Download/apks").also { it.bytes = 20 }
        dcim.children["Camera"] = cam; dl.children["apks"] = apks
        root.children["DCIM"] = dcim; root.children["Download"] = dl
        val names = biggestFolders(root).map { it.name }
        assertEquals(listOf("Camera", "Download", "apks"), names)
        assertEquals(listOf("DCIM", "Camera"), chainTo(root, cam).map { it.name })
    }
}

class MapItemsTest {
    private val gb = 1_000_000_000L
    private fun file(folder: app.ultimate.storage.Folder, name: String, bytes: Long) =
        app.ultimate.storage.BigFile(folder.path + "/" + name, name, bytes, 0, app.ultimate.files.FileKind.Video).also { folder.addFile(it); folder.own += bytes; folder.ownFiles++; folder.bytes += bytes }

    @Test fun aFolderOfOnlyFilesShowsEachFile() {
        val sora = app.ultimate.storage.Folder("Sora", "/Movies/Sora")
        file(sora, "a.mp4", 600_000_000); file(sora, "b.mp4", 500_000_000); file(sora, "c.mp4", 400_000_000); file(sora, "d.mp4", 170_000_000)
        val items = app.ultimate.storage.MapItems.of(sora)
        assertEquals(listOf("a.mp4", "b.mp4", "c.mp4", "d.mp4"), items.map { it.label })
        assertTrue(items.all { it.file != null && it.folder == null })
        assertEquals(sora.bytes, items.sumOf { it.bytes })
    }

    @Test fun foldersAndFilesShareTheMapBiggestFirst() {
        val root = app.ultimate.storage.Folder("DCIM", "/DCIM")
        val sub = app.ultimate.storage.Folder("Camera", "/DCIM/Camera").also { it.bytes = 3 * gb; it.files = 30; root.children["Camera"] = it; root.bytes += it.bytes }
        file(root, "big.mp4", 5 * gb); file(root, "small.jpg", gb / 100)
        val items = app.ultimate.storage.MapItems.of(root)
        assertEquals(listOf("big.mp4", "Camera", "small.jpg"), items.map { it.label })
        assertEquals(sub, items[1].folder)
    }

    @Test fun manySmallFilesBecomeOneHonestTile() {
        val f = app.ultimate.storage.Folder("Cache", "/Cache")
        repeat(100) { file(f, "f$it", 1_000_000L + it) }
        val items = app.ultimate.storage.MapItems.of(f, cap = 10)
        assertEquals(10, items.size)
        val rest = items.last()
        assertEquals(app.ultimate.storage.MapItems.REST, rest.key)
        assertEquals("91 smaller items", rest.label)
        assertEquals(f.bytes, items.sumOf { it.bytes }) // the tiles always add up to the folder
    }

    @Test fun filesBeyondTheOnesKeptAreStillCounted() {
        val f = app.ultimate.storage.Folder("Photos", "/Photos")
        repeat(60) { file(f, "p$it", 2_000_000L + it) }
        assertEquals(40, f.filesBySize().size)
        val items = app.ultimate.storage.MapItems.of(f)
        assertTrue(items.last().hidden >= 20 && items.sumOf { it.bytes } == f.bytes)
        assertEquals(emptyList<app.ultimate.storage.MapItem>(), app.ultimate.storage.MapItems.of(app.ultimate.storage.Folder("Empty", "/Empty")))
    }
}

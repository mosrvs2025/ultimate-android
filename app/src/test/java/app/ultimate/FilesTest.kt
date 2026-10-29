package app.ultimate

import app.ultimate.files.ApkBundle
import app.ultimate.files.CleanupRules
import app.ultimate.files.Entry
import app.ultimate.files.FileKind
import app.ultimate.files.FileModel
import app.ultimate.files.FileOps
import app.ultimate.files.FileSort
import app.ultimate.files.Item
import app.ultimate.files.Trash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun kindsAndSorting() {
        assertEquals(FileKind.Apk, FileModel.kindOf(File("x.APKS")))
        assertEquals(FileKind.Image, FileModel.kindOf(File("a.heic")))
        val d = tmp.newFolder("z")
        val e = listOf(Entry(File("b.txt"), FileKind.Document, 5, 2, 0), Entry(d, FileKind.Folder, 0, 1, 0), Entry(File(".hidden"), FileKind.Other, 9, 3, 0), Entry(File("a.mp4"), FileKind.Video, 50, 1, 0))
        assertEquals(listOf("z", "a.mp4", "b.txt"), FileModel.sorted(e, FileSort.Name, false).map { it.file.name })
        assertEquals(listOf("z", "a.mp4", ".hidden", "b.txt"), FileModel.sorted(e, FileSort.Largest, true).map { it.file.name })
    }

    @Test fun namesNeverOverwriteOrEscape() {
        val d = tmp.newFolder("d"); File(d, "p.jpg").writeText("x"); File(d, "p (2).jpg").writeText("x")
        assertEquals("p (3).jpg", FileModel.freeName(d, "p.jpg"))
        assertEquals("new.txt", FileModel.freeName(d, "new.txt"))
        assertEquals("ab", FileModel.validName(" a/b "))
        assertNull(FileModel.validName(".."))
        assertTrue(FileModel.isInside(File(d, "x"), d)); assertFalse(FileModel.isInside(tmp.root, d))
    }

    @Test fun copyMoveAndRefuseMovingIntoItself() {
        val a = tmp.newFolder("a"); val b = tmp.newFolder("b"); File(a, "f.txt").writeText("hi")
        FileOps.transfer(listOf(File(a, "f.txt")), b, move = false)
        assertTrue(File(a, "f.txt").exists()); assertEquals("hi", File(b, "f.txt").readText())
        FileOps.transfer(listOf(File(a, "f.txt")), b, move = true)
        assertFalse(File(a, "f.txt").exists()); assertTrue(File(b, "f (2).txt").exists())
        assertTrue(runCatching { FileOps.transfer(listOf(a), File(a, "inner").apply { mkdirs() }, move = true) }.isFailure)
    }

    @Test fun binRestoresToWhereItWasAndPurgesOldItems() {
        val root = tmp.newFolder("root"); val pics = File(root, "Pictures").apply { mkdirs() }; val f = File(pics, "cat.jpg").apply { writeText("meow") }
        val trash = Trash(root)
        val item = trash.put(f)
        assertFalse(f.exists()); assertEquals(1, trash.items().size); assertEquals(f.absolutePath, trash.items()[0].originalPath)
        val back = trash.restore(trash.items()[0]); assertEquals(f.absolutePath, back.absolutePath); assertTrue(trash.items().isEmpty())
        trash.put(back)
        assertEquals(0L, trash.purgeOlderThan(30, now = item.deletedAt + 86_400_000L))
        assertEquals(4L, trash.purgeOlderThan(30, now = System.currentTimeMillis() + 31 * 86_400_000L))
        assertTrue(trash.items().isEmpty())
    }

    @Test fun duplicatesKeepTheCameraOriginal() {
        fun it(path: String, bytes: Long = 100_000, at: Long = 1) = Item(File(path), bytes, at, FileKind.Image)
        val cam = it("/s/DCIM/Camera/a.jpg", at = 5); val wa = it("/s/WhatsApp/Media/a.jpg", at = 1); val other = it("/s/Download/b.jpg")
        val groups = CleanupRules.duplicates(listOf(cam, wa, other, it("/s/x/tiny", 10))) { if (it.file.name == "a.jpg") "same" else it.file.path }
        assertEquals(1, groups.size); assertEquals(cam, groups[0].keep); assertEquals(listOf(wa), groups[0].spare)
    }

    @Test fun similarPhotosGroupByTimeAndLookAndKeepTheSharpest() {
        fun p(at: Long, hash: Long, sharp: Double) = Triple(Item(File("/p$at.jpg"), 1000, at, FileKind.Image), hash, sharp)
        val g = CleanupRules.similar(listOf(p(0, 0b1111L, 10.0), p(60_000, 0b1110L, 50.0), p(120_000, 0b1100L, 20.0), p(3_600_000, 0b1111L, 99.0)))
        assertEquals(1, g.size); assertEquals(3, g[0].items.size); assertEquals(60_000L, g[0].keep.modified)
        val pixels = IntArray(72) { it % 9 }
        assertEquals(-1L, CleanupRules.dHash(pixels))
        assertEquals(0.0, CleanupRules.sharpness(IntArray(25) { 7 }, 5, 5), 0.0)
        assertTrue(CleanupRules.sharpness(IntArray(25) { if (it % 2 == 0) 0 else 255 }, 5, 5) > 1000)
    }

    @Test fun suggestionsRecogniseTheRightFolders() {
        assertTrue(CleanupRules.isScreenshot("/storage/emulated/0/DCIM/Screenshots/a.png"))
        assertTrue(CleanupRules.isMessengerSent("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/x.jpg"))
        assertFalse(CleanupRules.isMessengerSent("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/x.jpg"))
        assertTrue(ApkBundle.isApkEntry("splits/config.arm64_v8a.apk")); assertFalse(ApkBundle.isApkEntry("icon.png"))
        assertTrue(ApkBundle.isArchive("App.XAPK")); assertFalse(ApkBundle.isArchive("app.apk"))
    }
}

class UpdateRulesTest {
    @org.junit.Test fun onlyNewerSameAppSameSigner() {
        val r = app.ultimate.files.UpdateRules
        org.junit.Assert.assertTrue(r.isUpdate("app.ultimate.plus", 24, setOf("k"), "app.ultimate.plus", 23, setOf("k")))
        org.junit.Assert.assertFalse(r.isUpdate("app.ultimate.plus", 23, setOf("k"), "app.ultimate.plus", 23, setOf("k")))
        org.junit.Assert.assertFalse(r.isUpdate("app.ultimate", 99, setOf("k"), "app.ultimate.plus", 23, setOf("k")))
        org.junit.Assert.assertFalse(r.isUpdate("app.ultimate.plus", 99, setOf("other"), "app.ultimate.plus", 23, setOf("k")))
        org.junit.Assert.assertFalse(r.isUpdate("app.ultimate.plus", 99, emptySet(), "app.ultimate.plus", 23, emptySet()))
    }
}

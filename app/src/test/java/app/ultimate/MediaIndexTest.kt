package app.ultimate

import app.ultimate.files.FileKind
import app.ultimate.files.FileModel
import app.ultimate.files.MediaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaIndexTest {
    @Test fun leavesOutTheBinAndAppPrivateFolders() {
        assertTrue(MediaRules.skip("/storage/emulated/0/.UltimateTrash/1~a.jpg"))
        assertTrue(MediaRules.skip("/storage/emulated/0/Android/data/com.x/cache/a.bin"))
        assertTrue(MediaRules.skip("/storage/emulated/0/Android/obb/com.game/main.obb"))
        assertFalse(MediaRules.skip("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/a.jpg"))
        assertFalse(MediaRules.skip("/storage/emulated/0/DCIM/Camera/a.jpg"))
    }

    @Test fun kindFromNameAlone() {
        assertEquals(FileKind.Image, FileModel.kindOfName("IMG_1.HEIC"))
        assertEquals(FileKind.Video, FileModel.kindOfName("clip.mp4"))
        assertEquals(FileKind.Apk, FileModel.kindOfName("app.apk"))
        assertEquals(FileKind.Other, FileModel.kindOfName("README"))
    }
}

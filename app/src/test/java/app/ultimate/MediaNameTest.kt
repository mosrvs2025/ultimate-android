package app.ultimate

import app.ultimate.core.MediaName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaNameTest {
    private val orig = "YTDown.com_Shorts_Media__qkjQSOAy0k_Villain_001_1080p.mp3"

    @Test fun keepsTheExtensionWhateverIsTyped() {
        assertEquals("Villain theme.mp3", MediaName.clean("Villain theme", orig))
        assertEquals("Villain theme.mp3", MediaName.clean("Villain theme.mp3", orig))
        assertEquals("Villain theme.mp3", MediaName.clean("Villain theme.MP3", orig))
        assertEquals("Villain theme.wav.mp3", MediaName.clean("Villain theme.wav", orig))
    }

    @Test fun removesWhatAFileNameCannotHold() {
        assertEquals("a b c.mp3", MediaName.clean("a/b\\c", orig))
        assertEquals("Why is this.mp3", MediaName.clean("Why: is *this*?", orig).let { it?.replace("  ", " ") })
        assertEquals("tabs and spaces.mp3", MediaName.clean("  tabs\tand   spaces  ", orig))
    }

    @Test fun refusesEmptyOrDotOnlyNames() {
        assertNull(MediaName.clean("", orig)); assertNull(MediaName.clean("   ", orig)); assertNull(MediaName.clean("...", orig)); assertNull(MediaName.clean("/:*", orig))
        assertNull(MediaName.clean(".mp3", orig))
    }

    @Test fun trailingDotsAndLongNamesAreTrimmed() {
        assertEquals("song.mp3", MediaName.clean("song...", orig))
        assertEquals(MediaName.MAX_STEM + 4, MediaName.clean("x".repeat(300), orig)!!.length)
    }

    @Test fun stemAndExtension() {
        assertEquals("a.tar", MediaName.stem("a.tar.gz")); assertEquals(".gz", MediaName.extension("a.tar.gz"))
        assertEquals("noext", MediaName.stem("noext")); assertEquals("", MediaName.extension("noext"))
        assertEquals("", MediaName.extension(".hidden")); assertEquals("", MediaName.extension("weird.thisisverylong"))
        assertEquals("a b.mp4", MediaName.clean("a b", "x.mp4")); assertEquals("a b", MediaName.clean("a b", "noext"))
    }
}

package app.ultimate.sound

import org.junit.Assert.*
import org.junit.Test

class SoundExportTest {
    private fun session(offset: Double? = null) = SoundSession("test-id", "=test, \"room\"", 1000, 1000,
        -30.0, -40.0, -20.0, -15.0, 2, offset, "Microphone", "App left the foreground",
        listOf(SoundPoint(1000, -30.0, -15.0, 2, 48000)))
    @Test fun uncalibratedExportDoesNotInventSpl() {
        val csv = session().csv()
        assertTrue(csv.contains("-30.000000,-15.000000,,,2,48000"))
        assertTrue(csv.contains("App left the foreground"))
    }
    @Test fun calibratedExportKeepsDigitalValuesAndSeparateOffset() {
        assertTrue(session(100.0).csv().contains("-30.000000,-15.000000,100.000000,70.000000,2,48000"))
    }
    @Test fun namesAreQuotedAndSpreadsheetFormulasAreNeutralized() {
        assertTrue(session().csv().contains("\"'=test, \"\"room\"\"\""))
    }
}

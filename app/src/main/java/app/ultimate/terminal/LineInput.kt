package app.ultimate.terminal

/**
 * Reads one typed line inside the terminal, for the questions a connection asks before the remote shell exists: "trust this
 * server?" and "password:". The terminal's own keyboard bytes go in; what to show (the typed characters, or nothing for a
 * password) and the finished line come out. Pure, so it is tested.
 */
class LineInput(private val secret: Boolean) {
    class Feed(val echo: String, val line: String? = null, val cancelled: Boolean = false)

    private val text = StringBuilder()

    fun feed(bytes: ByteArray, length: Int = bytes.size): Feed {
        val s = String(bytes, 0, length, Charsets.UTF_8)
        val echo = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\u0003' -> return Feed(echo.append("^C\r\n").toString(), cancelled = true)
                c == '\r' || c == '\n' -> return Feed(echo.append("\r\n").toString(), line = text.toString())
                c == '\u007f' || c == '\b' -> if (text.isNotEmpty()) { text.setLength(text.offsetByCodePoints(text.length, -1).coerceAtLeast(0)); if (!secret) echo.append("\b \b") }
                c == '\u001b' -> { // an arrow or function key: skip it and its letter
                    i++
                    if (i < s.length && s[i] == '[') { i++; while (i < s.length && !s[i].isLetter() && s[i] != '~') i++ }
                    else if (i < s.length && s[i] == 'O') i++
                }
                c < ' ' -> Unit
                else -> { text.append(c); if (!secret) echo.append(c) }
            }
            i++
        }
        return Feed(echo.toString())
    }
}

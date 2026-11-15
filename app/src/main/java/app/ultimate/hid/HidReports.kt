package app.ultimate.hid

import kotlin.math.roundToInt

/**
 * The phone as an ordinary Bluetooth keyboard and mouse. Every computer, tablet, TV and headset already understands
 * those, so nothing has to be installed on them: they see a keyboard and a mouse called "Ultimate".
 *
 * This file is the part that does not need a phone: the description the phone hands over when it pairs (what reports it
 * sends), and turning text, cursor movement and media keys into those reports. Keyboard: report 1, 8 bytes. Mouse:
 * report 2, buttons + x + y + wheel. Media keys: report 3, one byte of flags.
 */
object HidReports {
    const val KEYBOARD: Byte = 1
    const val MOUSE: Byte = 2
    const val MEDIA: Byte = 3

    /** The standard USB HID report descriptor for a keyboard, a three-button wheel mouse, and media keys. */
    val DESCRIPTOR: ByteArray = intArrayOf(
        // Keyboard (report 1): modifiers, reserved byte, six keys.
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
        0xC0,
        // Mouse (report 2): three buttons, then x, y and wheel as signed bytes, relative.
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, 0x02, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
        // Media keys (report 3): next, previous, play/pause, mute, volume up, volume down, then two spare bits.
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x03, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x06,
        0x09, 0xB5, 0x09, 0xB6, 0x09, 0xCD, 0x09, 0xE2, 0x09, 0xE9, 0x09, 0xEA, 0x81, 0x02,
        0x95, 0x02, 0x81, 0x01,
        0xC0,
    ).map { it.toByte() }.toByteArray()

    // ---- keyboard ----

    const val CTRL = 0x01; const val SHIFT = 0x02; const val ALT = 0x04; const val META = 0x08

    /** Named keys, as USB usage codes. */
    enum class Key(val code: Int) { Enter(0x28), Escape(0x29), Backspace(0x2A), Tab(0x2B), Space(0x2C), Delete(0x4C),
        Right(0x4F), Left(0x50), Down(0x51), Up(0x52), Home(0x4A), End(0x4D), PageUp(0x4B), PageDown(0x4E) }

    data class Stroke(val modifiers: Int, val code: Int)

    private val plain = "1234567890-=[]\\;'`,./"
    private val shifted = "!@#$%^&*()_+{}|:\"~<>?"
    private val codes = intArrayOf(0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)

    /** One character as a key press on a US layout, or null if a US keyboard has no key for it. */
    fun stroke(c: Char): Stroke? = when (c) {
        in 'a'..'z' -> Stroke(0, 0x04 + (c - 'a'))
        in 'A'..'Z' -> Stroke(SHIFT, 0x04 + (c - 'A'))
        ' ' -> Stroke(0, Key.Space.code)
        '\n' -> Stroke(0, Key.Enter.code)
        '\t' -> Stroke(0, Key.Tab.code)
        else -> plain.indexOf(c).takeIf { it >= 0 }?.let { Stroke(0, codes[it]) }
            ?: shifted.indexOf(c).takeIf { it >= 0 }?.let { Stroke(SHIFT, codes[it]) }
    }

    fun keyDown(s: Stroke) = byteArrayOf(s.modifiers.toByte(), 0, s.code.toByte(), 0, 0, 0, 0, 0)
    val KEYS_UP = ByteArray(8)

    /**
     * Text as keyboard reports: each character pressed then released (a release between them, so double letters type
     * twice). Characters a US keyboard cannot type are skipped and counted in [Typed.skipped].
     */
    data class Typed(val reports: List<ByteArray>, val skipped: Int)

    fun type(text: String): Typed {
        val out = ArrayList<ByteArray>(); var skipped = 0
        for (c in text.replace("\r\n", "\n")) {
            val s = stroke(c) ?: run { skipped++; null } ?: continue
            out += keyDown(s); out += KEYS_UP
        }
        return Typed(out, skipped)
    }

    // ---- mouse ----

    const val LEFT = 1; const val RIGHT = 2; const val MIDDLE = 4

    /**
     * Turns cursor movement into mouse reports. Fractions of a pixel are carried over to the next move (so slow, smooth
     * movement still arrives), and a move bigger than one report allows is split into several.
     */
    class Mouse {
        private var fx = 0f; private var fy = 0f
        var buttons = 0

        fun move(dx: Float, dy: Float, wheel: Int = 0): List<ByteArray> {
            fx += dx; fy += dy
            var x = fx.roundToInt(); var y = fy.roundToInt(); var w = wheel
            fx -= x; fy -= y
            val out = ArrayList<ByteArray>()
            while (x != 0 || y != 0 || w != 0 || out.isEmpty()) {
                val sx = x.coerceIn(-127, 127); val sy = y.coerceIn(-127, 127); val sw = w.coerceIn(-127, 127)
                out += report(sx, sy, sw)
                x -= sx; y -= sy; w -= sw
                if (x == 0 && y == 0 && w == 0) break
            }
            return out
        }

        fun press(button: Int): ByteArray { buttons = buttons or button; return report(0, 0, 0) }
        fun release(button: Int): ByteArray { buttons = buttons and button.inv(); return report(0, 0, 0) }
        /** A click: the button down, then up. */
        fun click(button: Int = LEFT) = listOf(press(button), release(button))

        private fun report(x: Int, y: Int, w: Int) = byteArrayOf(buttons.toByte(), x.toByte(), y.toByte(), w.toByte())
    }

    // ---- media keys ----

    enum class Media(val bit: Int) { Next(0), Previous(1), PlayPause(2), Mute(3), VolumeUp(4), VolumeDown(5) }

    fun media(m: Media) = listOf(byteArrayOf((1 shl m.bit).toByte()), byteArrayOf(0))
}

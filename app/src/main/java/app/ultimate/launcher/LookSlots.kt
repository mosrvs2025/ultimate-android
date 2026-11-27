package app.ultimate.launcher

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/** A look you saved under a name: the name, and the whole look as a `ULOOK1:` code (so it survives any change to the presets). */
data class LookSlot(val name: String, val code: String)

/**
 * Saved looks. Up to [MAX], each a name and a look code; saving under a name you already used replaces it. Stored as one string,
 * one slot per line (`name<TAB>code`), and reading drops any line that isn't a name and a readable look, so a damaged or future
 * entry never breaks the rest. Plain logic, tested without a phone.
 */
object LookSlots {
    const val MAX = 8
    const val MAX_NAME = 24

    fun cleanName(raw: String): String = raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(MAX_NAME).trim()

    fun encode(slots: List<LookSlot>): String = slots.take(MAX).joinToString("\n") { "${cleanName(it.name)}\t${it.code.trim()}" }

    fun decode(text: String?): List<LookSlot> = text.orEmpty().lines().mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val name = cleanName(line.substring(0, tab)); val code = line.substring(tab + 1).trim()
        if (name.isBlank() || LookCode.apply(code, LauncherSettings()) == null) null else LookSlot(name, code)
    }.take(MAX)

    sealed interface Saved { data class Ok(val slots: List<LookSlot>) : Saved; data object Full : Saved; data object NoName : Saved }

    /** [slots] with the look in [code] saved as [name]: replaces a slot of the same name (ignoring case), else adds one, unless full. */
    fun save(slots: List<LookSlot>, name: String, code: String): Saved {
        val n = cleanName(name)
        if (n.isBlank()) return Saved.NoName
        val at = slots.indexOfFirst { it.name.equals(n, ignoreCase = true) }
        if (at >= 0) return Saved.Ok(slots.toMutableList().also { it[at] = LookSlot(n, code) })
        if (slots.size >= MAX) return Saved.Full
        return Saved.Ok(slots + LookSlot(n, code))
    }

    fun delete(slots: List<LookSlot>, index: Int): List<LookSlot> = slots.filterIndexed { i, _ -> i != index }

    /** A name to offer for the look being worn: the preset's name with "mix" if you changed it, numbered until it is free. */
    fun suggestName(s: LauncherSettings, existing: List<LookSlot>): String {
        val base = Looks.matching(s)?.name ?: "My look"
        val taken = existing.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var i = 2
        while ("$base $i".lowercase() in taken) i++
        return "$base $i"
    }
}

/** A look as a QR code and back, so a friend can scan it from your screen or a screenshot. Only the `ULOOK1:` text is in it. */
object LookQr {
    /** The code's QR as rows of dark/light cells, or null if it can't be drawn. */
    fun cells(code: String): Array<BooleanArray>? = runCatching {
        val m = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        Array(m.height) { y -> BooleanArray(m.width) { x -> m[x, y] } }
    }.getOrNull()

    /** Reads a look code out of a picture's pixels ([argb], row by row). Null if there is no QR, or it isn't a look. */
    fun decode(argb: IntArray, width: Int, height: Int): String? = runCatching {
        val text = MultiFormatReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, argb))),
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true),
        ).text.trim()
        text.takeIf { LookCode.apply(it, LauncherSettings()) != null }
    }.getOrNull()

    /** The QR drawn into pixels (for tests and for sharing as a picture): [scale] pixels per cell, black on white. */
    fun pixels(code: String, scale: Int = 8): Triple<IntArray, Int, Int>? {
        val c = cells(code) ?: return null
        val w = c[0].size * scale; val h = c.size * scale
        val px = IntArray(w * h) { i -> if (c[(i / w) / scale][(i % w) / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return Triple(px, w, h)
    }
}

package app.ultimate.audio.model

/**
 * The "Xing" header a variable-bitrate MP3 needs. A fixed-rate file can be timed from its size, but a variable-rate one can't:
 * without this header a player guesses the length from the first frame (the encoder library writes none), so the length shown
 * is wrong, by minutes on a long recording, and seeking lands in the wrong place.
 *
 * It is one extra frame at the very start of the file holding the number of audio frames, the number of bytes and a 100-entry
 * table of where each percent of the sound begins. The encoder's frames are followed as they are written ([Tracker]); a blank
 * frame with the right header is written first ([Tracker.placeholder]) and overwritten with the real one at the end
 * ([Tracker.tag]). Pure arithmetic on bytes, so it is tested against real encoder output.
 */
object Mp3VbrTag {
    private val RATES = arrayOf(intArrayOf(11025, 12000, 8000), intArrayOf(0, 0, 0), intArrayOf(22050, 24000, 16000), intArrayOf(44100, 48000, 32000))
    private val KBPS_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val KBPS_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private const val TOC = 100
    /** "Xing", flags, frame count, byte count, then the table. */
    private const val TAG_BYTES = 4 + 4 + 4 + 4 + TOC
    /** Every this-many frames the byte position is remembered, which is plenty for a 100-entry table (0.1 s at most). */
    private const val STRIDE = 4

    /** What one frame header says. [frameBytes] is the length of the frame it starts, [samples] how much sound it holds. */
    class Header(val version: Int, val rate: Int, val kbps: Int, val padding: Int, val mono: Boolean, val protection: Boolean, val raw: Int) {
        val mpeg1 get() = version == 3
        val samples get() = if (mpeg1) 1152 else 576
        val frameBytes get() = (if (mpeg1) 144 else 72) * kbps * 1000 / rate + padding
        /** Where the tag starts inside a frame: after the header, the optional checksum and the side information. */
        val tagOffset get() = 4 + (if (protection) 0 else 2) + (if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17))
    }

    /** The header at [at] in [b], or null if there is no Layer III frame there. */
    fun parse(b: ByteArray, at: Int): Header? {
        if (at + 4 > b.size) return null
        val h = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        if ((h ushr 21) and 0x7FF != 0x7FF) return null
        val version = (h ushr 19) and 3
        val layer = (h ushr 17) and 3
        if (version == 1 || layer != 1) return null // reserved version, or not Layer III
        val kbpsIndex = (h ushr 12) and 0xF
        val rateIndex = (h ushr 10) and 3
        if (kbpsIndex == 0 || kbpsIndex == 15 || rateIndex == 3) return null
        val kbps = (if (version == 3) KBPS_V1 else KBPS_V2)[kbpsIndex]
        return Header(version, RATES[version][rateIndex], kbps, (h ushr 9) and 1, ((h ushr 6) and 3) == 3, (h ushr 16) and 1 == 1, h)
    }

    /** Follows the frames of an encoder's output as it arrives, in pieces of any size. */
    class Tracker {
        private var buf = ByteArray(1 shl 16)
        private var start = 0
        private var end = 0
        private var consumed = 0L
        private var marks = IntArray(1 shl 12)
        private var frames = 0L
        /** The first real frame's header, once seen. */
        var first: Header? = null
            private set
        /** Bytes of audio seen so far, not counting the tag frame that is written in front. */
        val audioBytes get() = consumed + (end - start)
        val frameCount get() = frames

        fun feed(data: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (end + len > buf.size) {
                // Slide what's unparsed to the front, and grow if the piece is still too big.
                System.arraycopy(buf, start, buf, 0, end - start); end -= start; start = 0
                if (end + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, end + len))
            }
            System.arraycopy(data, off, buf, end, len); end += len
            while (end - start >= 4) {
                val h = parse(buf, start)
                if (h == null) { start++; consumed++; continue } // not on a frame: skip a byte (never happens with a clean encoder)
                if (end - start < h.frameBytes) break
                if (first == null) first = h
                if (frames % STRIDE == 0L) {
                    val i = (frames / STRIDE).toInt()
                    if (i >= marks.size) marks = marks.copyOf(marks.size * 2)
                    marks[i] = consumed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                }
                frames++; start += h.frameBytes; consumed += h.frameBytes
            }
        }

        /** Sound in the audio frames, in seconds. */
        fun seconds(): Double = first?.let { frames * it.samples.toDouble() / it.rate } ?: 0.0

        /** The blank frame to write before the first audio frame, with room for the tag. Needs [first] to be known. */
        fun placeholder(): ByteArray = frame(0L, 0L, null)

        /** The finished tag frame, to overwrite the placeholder with. [fileBytes] is the whole file, tag frame included. */
        fun tag(fileBytes: Long): ByteArray = frame(frames, fileBytes, marks)

        private fun frame(frameCount: Long, fileBytes: Long, table: IntArray?): ByteArray {
            val f = first ?: error("no audio frame yet")
            // The smallest bit rate whose frame holds the tag: the tag frame costs the file a few hundred bytes.
            val kbpsTable = if (f.mpeg1) KBPS_V1 else KBPS_V2
            val need = f.tagOffset + TAG_BYTES
            var index = 1
            while (index < 14 && (if (f.mpeg1) 144 else 72) * kbpsTable[index] * 1000 / f.rate < need) index++
            // Same header as the audio, except the bit rate (and no padding), so every decoder treats it as just another frame.
            val raw = (f.raw and (0xF shl 12).inv() and (1 shl 9).inv()) or (index shl 12)
            val size = (if (f.mpeg1) 144 else 72) * kbpsTable[index] * 1000 / f.rate
            val out = ByteArray(size)
            out[0] = (raw ushr 24).toByte(); out[1] = (raw ushr 16).toByte(); out[2] = (raw ushr 8).toByte(); out[3] = raw.toByte()
            if (table == null) return out
            var p = f.tagOffset
            "Xing".forEach { out[p++] = it.code.toByte() }
            p = put32(out, p, 1L or 2L or 4L)
            p = put32(out, p, frameCount)
            p = put32(out, p, fileBytes)
            val audio = audioBytes.coerceAtLeast(1)
            for (i in 0 until TOC) {
                val frame = (i * frameCount / TOC).let { it - it % STRIDE }
                val pos = if (frameCount == 0L) 0L else table[(frame / STRIDE).toInt()].toLong()
                out[p++] = (256 * pos / audio).coerceIn(0L, 255L).toByte()
            }
            return out
        }

        private fun put32(o: ByteArray, at: Int, v: Long): Int {
            o[at] = (v ushr 24).toByte(); o[at + 1] = (v ushr 16).toByte(); o[at + 2] = (v ushr 8).toByte(); o[at + 3] = v.toByte()
            return at + 4
        }
    }

    /** What a finished tag says, for checking one. Null if the frame has no Xing tag. */
    class Read(val frames: Long, val bytes: Long, val toc: IntArray)

    fun read(frame: ByteArray): Read? {
        val h = parse(frame, 0) ?: return null
        val p = h.tagOffset
        if (p + TAG_BYTES > frame.size) return null
        if (String(frame, p, 4, Charsets.US_ASCII) != "Xing") return null
        fun u32(at: Int) = ((frame[at].toLong() and 0xFF) shl 24) or ((frame[at + 1].toLong() and 0xFF) shl 16) or ((frame[at + 2].toLong() and 0xFF) shl 8) or (frame[at + 3].toLong() and 0xFF)
        return Read(u32(p + 8), u32(p + 12), IntArray(TOC) { frame[p + 16 + it].toInt() and 0xFF })
    }
}

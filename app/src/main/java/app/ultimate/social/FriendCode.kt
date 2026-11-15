package app.ultimate.social

import java.security.SecureRandom

/**
 * The short code a person gives a friend to be added ("K7M2-X9QD-F"). Eight letters and digits that avoid look-alikes
 * (Crockford's alphabet: no I, L, O or U) plus a ninth character that catches most typing slips, so a mistyped code is
 * refused on the spot instead of sending a request to a stranger. A code is a way to ask, never a way to look someone up:
 * it shows nothing about the person until they accept.
 */
object FriendCode {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val DATA_CHARS = 8

    fun generate(random: java.util.Random = SecureRandom()): String {
        val data = CharArray(DATA_CHARS) { ALPHABET[random.nextInt(32)] }
        return pretty(String(data) + check(String(data)))
    }

    /** The code as people read it: K7M2-X9QD-F. */
    fun pretty(raw: String) = raw.chunked(4).joinToString("-")

    /** What was typed, tidied up: upper case, dashes and spaces dropped, look-alike letters read as the digit they resemble. */
    fun normalize(input: String): String = input.uppercase().filter { it != '-' && it != ' ' }
        .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }.joinToString("")

    /** The tidy 9-character form of [input] if it is a valid code (right length, right letters, check character matches), else null. */
    fun parse(input: String): String? {
        val s = normalize(input)
        if (s.length != DATA_CHARS + 1 || s.any { it !in ALPHABET }) return null
        return s.takeIf { check(it.take(DATA_CHARS)) == it.last() }
    }

    private fun check(data: String): Char {
        var sum = 0
        data.forEachIndexed { i, c -> sum += (2 * i + 1) * ALPHABET.indexOf(c) } // odd weights: one wrong character always changes the sum
        return ALPHABET[sum % 32]
    }
}

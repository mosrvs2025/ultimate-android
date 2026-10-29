package app.ultimate.core

import android.text.format.DateUtils
import java.util.Locale

fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    return when {
        b >= 1e9 -> String.format(Locale.US, "%.2f GB", b / 1e9)
        b >= 1e8 -> String.format(Locale.US, "%.0f MB", b / 1e6)
        b >= 1e6 -> String.format(Locale.US, "%.1f MB", b / 1e6)
        b >= 1e3 -> String.format(Locale.US, "%.0f KB", b / 1e3)
        else -> "${b.toLong()} B"
    }
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

fun formatEta(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return if (s >= 60) "${s / 60} min ${s % 60} s left" else "$s s left"
}

fun formatMbps(bps: Int): String = String.format(Locale.US, "%.1f Mbps", bps / 1e6)

fun percentSaved(before: Long, after: Long): Int = if (before <= 0) 0 else ((1 - after.toDouble() / before) * 100).toInt().coerceIn(0, 100)

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

fun androidVersionName(api: Int): String? = when (api) {
    21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"; 28 -> "9"
    29 -> "10"; 30 -> "11"; 31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
    else -> null
}

fun sdkText(api: Int): String = androidVersionName(api)?.let { "Android $it (API $api)" } ?: "API $api"

/** "AB12…" as "AB:12:…" so a fingerprint reads and compares like the ones tools print. */
fun colonHex(hex: String): String = hex.chunked(2).joinToString(":")

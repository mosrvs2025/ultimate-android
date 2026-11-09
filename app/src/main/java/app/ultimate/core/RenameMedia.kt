package app.ultimate.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turning what someone typed into a safe file name. Pure, so it is tested. */
object MediaName {
    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F]""")
    const val MAX_STEM = 100

    /** The name without its extension ("Song.mp3" gives "Song"; "archive.tar.gz" gives "archive.tar"). */
    fun stem(name: String): String = name.substringBeforeLast('.', name).ifEmpty { name }

    /** The extension including the dot, or empty. */
    fun extension(name: String): String = name.substringAfterLast('.', "").let { if (it.isEmpty() || it.length > 6 || name.startsWith('.') && !name.drop(1).contains('.')) "" else ".$it" }

    /**
     * The full new file name, or null when nothing usable was typed. The original extension is always kept, so a file can't
     * lose what it is by being renamed; typing the extension again does not double it.
     */
    fun clean(typed: String, original: String): String? {
        val ext = extension(original)
        var s = typed.replace(illegal, " ").replace(Regex("\\s+"), " ").trim()
        if (ext.isNotEmpty() && s.endsWith(ext, ignoreCase = true)) s = s.dropLast(ext.length)
        s = s.trim().trimEnd('.', ' ').take(MAX_STEM).trim()
        if (s.isEmpty() || s.all { it == '.' }) return null
        return s + ext
    }
}

/**
 * Renames a media file Ultimate made, through MediaStore (no storage permission needed for our own files). Returns the name it
 * ended up with: MediaStore adds " (1)" itself when the name is taken. Failure is a message a person can read.
 */
suspend fun renameMedia(context: Context, uri: Uri, newName: String): Result<String> = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val rows = resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null)
        check(rows > 0) { "Android didn't find that file any more" }
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: newName
    }.recoverCatching { e ->
        throw IllegalStateException(if (e is SecurityException) "Android won't let Ultimate rename this file" else e.message ?: "Couldn't rename it")
    }
}

/** Asks for a new name, showing the old one without its extension, selected for typing. Calls [onRename] with the typed text. */
@Composable
fun RenameDialog(current: String, error: String? = null, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(MediaName.stem(current)) }
    val ext = MediaName.extension(current)
    val valid = MediaName.clean(text, current) != null && MediaName.clean(text, current) != current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, isError = error != null,
                label = { Text("Name") }, suffix = { if (ext.isNotEmpty()) Text(ext) },
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (valid) onRename(text) }),
            )
        },
        confirmButton = { TextButton({ onRename(text) }, enabled = valid) { Text("Rename") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

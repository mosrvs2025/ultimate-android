package app.ultimate.core

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** A small JSON array in app-private storage, written atomically so a crash never leaves a half-written file. */
class JsonFile(private val file: File) {
    private val lock = Mutex()

    fun read(): JSONArray = runCatching { if (file.exists()) JSONArray(file.readText()) else JSONArray() }.getOrDefault(JSONArray())

    suspend fun write(array: JSONArray) = lock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(array.toString())
            tmp.renameTo(file)
        }
    }
}

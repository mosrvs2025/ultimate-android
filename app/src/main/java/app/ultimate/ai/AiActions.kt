package app.ultimate.ai

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import app.ultimate.UltimateApp
import app.ultimate.core.ToolLaunch

/** Carries out a proposal the person confirmed. Returns what happened, in words, for a toast. */
object AiActions {
    fun run(app: UltimateApp, c: AiCall): String {
        fun open(id: String, query: String? = null): String {
            val t = app.tools.firstOrNull { it.id == id } ?: return "That tool isn't in this edition"
            app.shell.open(t, ToolLaunch(query = query)); return "Opened ${t.title}"
        }
        return when (c.name) {
            "flashlight" -> runCatching {
                val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                val on = c.args["on"] != false
                cm.setTorchMode(id, on); if (on) "Flashlight on" else "Flashlight off"
            }.getOrDefault("This phone's flashlight isn't available")
            "set_volume" -> {
                val am = app.getSystemService(AudioManager::class.java)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val pct = (c.args["percent"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 50
                am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * pct / 100f), AudioManager.FLAG_SHOW_UI); "Volume $pct%"
            }
            "compress_video" -> open("squeeze")
            "edit_video" -> open("studio")
            "record_audio" -> open("recorder")
            "find_notification" -> open("inbox", c.args["query"]?.toString())
            "find_copied" -> open("clips", c.args["query"]?.toString())
            "storage_status" -> open("storage")
            "clean_up" -> open("cleanup")
            "start_echo" -> open("echo")
            else -> "I don't know how to do that yet"
        }
    }
}

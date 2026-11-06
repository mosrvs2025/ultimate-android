package app.ultimate.ai

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import app.ultimate.UltimateApp
import app.ultimate.core.ToolLaunch

/** Carries out a proposal the person confirmed. Returns what happened, in words, for a toast. */
object AiActions {
    /** Whether Ultimate last turned the flashlight on, for the follow-up button. */
    @Volatile var torchOn = false
    /** The media volume before the last change, so "Undo" can put it back. */
    @Volatile private var volumeBefore: Int? = null

    /**
     * What to offer right after [c] ran: a button that undoes or flips it ("Turn off" after turning the flashlight on,
     * "Undo" after a volume change), or null for actions that just opened a tool.
     */
    fun followUp(c: AiCall): Pair<String, AiCall>? = when (c.name) {
        "flashlight" -> if (c.args["on"] != false) "Turn off" to AiCall("flashlight", mapOf("on" to false), 1.0) else "Turn on" to AiCall("flashlight", mapOf("on" to true), 1.0)
        "set_volume" -> volumeBefore?.let { "Undo" to AiCall("set_volume", mapOf("percent" to it), 1.0) }
        else -> null
    }

    /** A control you kept on Home: a short name, and what tapping it does now (the flashlight flips each time). */
    fun controlLabel(c: AiCall): String = when (c.name) {
        "flashlight" -> if (torchOn) "Flashlight: on" else "Flashlight: off"
        "set_volume" -> "Volume ${(c.args["percent"] as? Number)?.toInt() ?: 50}%"
        else -> AiTools.describe(c)
    }
    fun controlCall(c: AiCall): AiCall = if (c.name == "flashlight") AiCall("flashlight", mapOf("on" to !torchOn), 1.0) else c

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
                cm.setTorchMode(id, on); torchOn = on; if (on) "Flashlight on" else "Flashlight off"
            }.getOrDefault("This phone's flashlight isn't available")
            "set_volume" -> {
                val am = app.getSystemService(AudioManager::class.java)
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val pct = (c.args["percent"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 50
                volumeBefore = Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max.coerceAtLeast(1))
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

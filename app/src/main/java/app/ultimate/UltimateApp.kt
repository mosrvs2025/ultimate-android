package app.ultimate

import kotlinx.coroutines.launch
import android.app.Application
import app.ultimate.studio.StudioManager
import app.ultimate.studio.StudioTool
import app.ultimate.sound.SoundTool
import app.ultimate.audio.AudioTool
import app.ultimate.audio.engine.AudioManager
import app.ultimate.audio.engine.AudioStore
import app.ultimate.core.Tool
import app.ultimate.core.WorkHub
import app.ultimate.home.Shell
import app.ultimate.pluck.PluckTool
import app.ultimate.pluck.engine.AppRepository
import app.ultimate.pluck.engine.ExtractManager
import app.ultimate.pluck.engine.HistoryStore
import app.ultimate.pluck.engine.ReplacementStore
import app.ultimate.pluck.engine.ScanStore
import app.ultimate.squeeze.SqueezeTool
import app.ultimate.traffic.TrafficTool
import app.ultimate.traffic.engine.TrafficManager
import app.ultimate.squeeze.engine.CompressionManager
import app.ultimate.squeeze.engine.DeviceCodecs
import app.ultimate.squeeze.engine.LibraryStore

/**
 * Wires the tools together. To add a tool: give it a package, implement [Tool], build its services here,
 * register any background work with [work], and add it to [tools].
 */
class UltimateApp : Application() {
    val work: WorkHub by lazy { WorkHub(this) }
    val insights: app.ultimate.insights.InsightsStore by lazy { app.ultimate.insights.InsightsStore(this, BuildConfig.VERSION_NAME) }
    val shell: Shell by lazy { Shell(tools, insights) }

    // Squeeze
    val capabilities by lazy { DeviceCodecs.detect() }
    val squeezeLibrary: LibraryStore by lazy { LibraryStore(this) }
    val squeezeManager: CompressionManager by lazy { CompressionManager(this, squeezeLibrary, work) }

    // Pluck
    val pluckRepository: AppRepository by lazy { AppRepository(this) }
    val pluckHistory: HistoryStore by lazy { HistoryStore(this) }
    val pluckScans: ScanStore by lazy { ScanStore(this) }
    val lanManager by lazy { app.ultimate.lan.LanManager(this) }
    val checkupManager by lazy { app.ultimate.checkup.engine.CheckupManager(this) }
    val pluckReplacements: ReplacementStore by lazy { ReplacementStore(this) }
    val pluckManager: ExtractManager by lazy { ExtractManager(this, pluckHistory, work) }

    // Video to audio
    val audioStore: AudioStore by lazy { AudioStore(this) }
    val audioManager: AudioManager by lazy { AudioManager(this, audioStore, work) }

    /** The tools this edition ships, in Home order. The flags are compile-time constants, so R8 removes tools that are off. */
    // Traffic inspector (only in editions that include it and the internet permission it needs)
    val trafficManager: TrafficManager by lazy { TrafficManager(this) }

    val studioManager: StudioManager by lazy { StudioManager(this, work) }
    val recorder by lazy { app.ultimate.recorder.RecorderManager(this) }
    val photos by lazy { app.ultimate.photos.PhotoManager(this, work) }
    val automations by lazy { app.ultimate.recipes.Automations(this) }
    val recipes by lazy { app.ultimate.recipes.RecipeRunner(this) }
    val notificationLog by lazy { app.ultimate.inbox.NotificationLog(this) }
    val clips by lazy { app.ultimate.clips.ClipStore(this) }
    val sendServer by lazy { app.ultimate.send.SendServer(this) }
    val transcripts by lazy { app.ultimate.speech.TranscriptStore(this) }
    val prompter by lazy { app.ultimate.prompter.PrompterStore(this) }

    val tools: List<Tool> by lazy {
        buildList {
            if (BuildConfig.FLAVOR == "ultimateplus") add(Class.forName("app.ultimate.ssh.SshTool").getConstructor(android.content.Context::class.java).newInstance(this@UltimateApp) as Tool)
            if (BuildConfig.HAS_CHECKUP) add(app.ultimate.checkup.CheckupTool(checkupManager))
            if (BuildConfig.HAS_STUDIO) add(StudioTool(studioManager))
            if (BuildConfig.HAS_RECORDER) add(app.ultimate.recorder.RecorderTool(recorder))
            if (BuildConfig.HAS_PHOTOS) add(app.ultimate.photos.PhotosTool(photos))
            if (BuildConfig.HAS_PROMPTER) add(app.ultimate.prompter.PrompterTool(prompter))
            if (BuildConfig.HAS_RECORDER) add(app.ultimate.recipes.RecipesTool(this@UltimateApp))
            if (BuildConfig.HAS_SQUEEZE) add(SqueezeTool(this@UltimateApp))
            if (BuildConfig.HAS_AUDIO) add(AudioTool(this@UltimateApp))
            if (BuildConfig.HAS_PLUCK) add(PluckTool(this@UltimateApp))
            if (BuildConfig.HAS_SOUND) add(SoundTool(this@UltimateApp))
            if (BuildConfig.HAS_TRAFFIC) add(TrafficTool(this@UltimateApp))
            if (BuildConfig.HAS_NETWORK) add(app.ultimate.lan.LanTool(lanManager))
            if (BuildConfig.HAS_SEND) add(app.ultimate.send.SendTool(sendServer))
            if (BuildConfig.HAS_FILES) add(app.ultimate.files.FilesTool(this@UltimateApp))
            if (BuildConfig.HAS_INBOX) add(app.ultimate.inbox.InboxTool(this@UltimateApp))
            if (BuildConfig.HAS_CLIPS) add(app.ultimate.clips.ClipsTool(clips))
            if (BuildConfig.HAS_FILES) add(app.ultimate.files.CleanupTool(this@UltimateApp))
            if (BuildConfig.HAS_FILES) add(app.ultimate.storage.StorageTool(this@UltimateApp))
            if (BuildConfig.HAS_SCREENTIME) add(app.ultimate.screentime.ScreenTimeTool(this@UltimateApp))
            if (BuildConfig.HAS_SENSORS) add(app.ultimate.sensors.SensorsTool((getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager).getSensorList(android.hardware.Sensor.TYPE_ALL).size))
        }
    }

    override fun onCreate() {
        super.onCreate()
        insights.installCrashCatcher()
        insights.since
        // Errors the tools show on screen go into the usage report too, so "it didn't work" comes with a reason.
        val watch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        fun follow(tool: String, flow: kotlinx.coroutines.flow.Flow<String?>) = watch.launch { flow.collect { if (!it.isNullOrBlank()) insights.problem(tool, it) } }
        if (BuildConfig.HAS_STUDIO) follow("Studio", studioManager.error)
        if (BuildConfig.HAS_RECORDER) follow("Recorder", recorder.error)
        if (BuildConfig.HAS_SEND) follow("Send to computer", kotlinx.coroutines.flow.flow { sendServer.state.collect { emit(it.error) } })
        // A content trigger fires once, so it is re-armed at every start in case the last one was used up while the app was away.
        automations.watchScreenRecordings()
        if (BuildConfig.HAS_FILES) app.ultimate.files.UpdateWatcher.arm(this)
        // Every manager must be registered before the first background job starts, so the one service watches them all.
        if (BuildConfig.HAS_SQUEEZE) work.register(squeezeManager)
        if (BuildConfig.HAS_PLUCK) work.register(pluckManager)
        if (BuildConfig.HAS_AUDIO) work.register(audioManager)
        if (BuildConfig.HAS_TRAFFIC) work.register(trafficManager)
        if (BuildConfig.HAS_STUDIO) work.register(studioManager)
        if (BuildConfig.HAS_CHECKUP) work.register(checkupManager)
        if (BuildConfig.HAS_PHOTOS) work.register(photos)
    }
}

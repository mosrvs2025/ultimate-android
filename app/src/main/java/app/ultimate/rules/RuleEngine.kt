package app.ultimate.rules

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ultimate.BuildConfig
import app.ultimate.MainActivity
import app.ultimate.R
import app.ultimate.UltimateApp
import app.ultimate.core.JsonFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Runs the user's rules. Events arrive from live sources (the watching service), from the notification and clipboard tools,
 * and from scheduled times; each is matched against the rules, throttled so nothing can loop, and every run, and every
 * "put back", is written to a short history so the person can see what happened and why.
 */
class RuleEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val file = JsonFile(File(context.filesDir, "rules.json"))
    private val logFile = JsonFile(File(context.filesDir, "rules-log.json"))
    private val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)
    private val limiter = RateLimiter()

    private val _rules = MutableStateFlow(loadRules())
    val rules: StateFlow<List<Rule>> = _rules.asStateFlow()
    private val _log = MutableStateFlow(loadLog())
    val log: StateFlow<List<RunRecord>> = _log.asStateFlow()

    // ---- Editing ----

    fun add(rule: Rule) { _rules.update { it + rule }; saveRules(); sync() }
    fun update(rule: Rule) { _rules.update { list -> list.map { if (it.id == rule.id) rule else it } }; saveRules(); sync() }
    fun setEnabled(id: String, on: Boolean) {
        val before = _rules.value.firstOrNull { it.id == id } ?: return
        _rules.value = _rules.value.map { if (it.id == id) it.copy(enabled = on) else it }
        // Turning off a rule that is holding changes (for example Do Not Disturb) puts them back.
        if (!on) scope.launch { restore(before, "you turned the rule off") }
        saveRules(); sync()
    }
    fun delete(id: String) {
        val before = _rules.value.firstOrNull { it.id == id }
        _rules.value = _rules.value.filterNot { it.id == id }
        if (before != null) scope.launch { restore(before, "you deleted the rule") }
        saveRules(); sync()
    }
    fun clearLog() { _log.value = emptyList(); saveLog() }

    // ---- Events ----

    /** Offers an event to every rule. Cheap when nothing matches, which is nearly always. */
    fun fire(event: Event) {
        val rules = _rules.value
        if (rules.none { RuleMatcher.starts(it, event) || RuleMatcher.ends(it, event) }) return
        scope.launch {
            rules.forEach { r ->
                when {
                    RuleMatcher.ends(r, event) -> restore(r, because(event))
                    RuleMatcher.starts(r, event) -> if (limiter.allow(r.id, System.currentTimeMillis())) run(r, because(event), vars(event)) else record(r, because(event), "Skipped: it just ran")
                }
            }
        }
    }

    /** A time rule's moment has come. */
    fun fireTime(ruleId: String) {
        val r = _rules.value.firstOrNull { it.id == ruleId && it.enabled && it.trigger.type == TriggerType.AtTime } ?: return
        scope.launch {
            if (limiter.allow(r.id, System.currentTimeMillis())) run(r, "it is ${Describe.time(r.trigger.minutes)}", vars(null))
            schedule(r)
        }
    }

    /** Runs a rule right now, so you can see what it does; not throttled. */
    fun test(rule: Rule) { scope.launch { run(rule, "you tested it", vars(null)) } }

    private fun because(e: Event) = when (e) {
        is Event.State -> Describe.trigger(Trigger(e.type, e.on)).replaceFirstChar { it.uppercase() }
        is Event.Battery -> "the battery dropped to ${e.now}%"
        is Event.Time -> "it is ${Describe.time(e.minutes)}"
        is Event.Notification -> "${e.app}: ${e.title}".take(80)
        is Event.Copied -> "you copied: ${e.text.lines().first().take(50)}"
    }

    private fun vars(e: Event?): Map<String, String> {
        val now = Calendar.getInstance()
        return buildMap {
            put("battery", batteryPercent().toString())
            put("time", "%d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE)))
            put("date", java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(now.time))
            when (e) {
                is Event.Notification -> { put("title", e.title); put("text", e.text); put("app", e.app) }
                is Event.Copied -> { put("clip", e.text); put("text", e.text) }
                else -> Unit
            }
            put("clip", this["clip"] ?: (context.applicationContext as? UltimateApp)?.takeIf { BuildConfig.HAS_CLIPS }?.clips?.clips?.value?.firstOrNull()?.text.orEmpty())
        }
    }

    // ---- Doing things ----

    private suspend fun run(rule: Rule, why: String, vars: Map<String, String>) {
        if (rule.undoWhenEnds && rule.trigger.type.stateful) remember(rule)
        val results = rule.actions.map { a -> runCatching { apply(a, vars) }.getOrElse { "${a.type.label}: ${it.message ?: "failed"}" } }
        record(rule, why, results.joinToString("; ").ifEmpty { "Nothing to do" })
    }

    // VIBRATE is declared by the editions that include Rules (ultimate, ultimateplus); the others never reach this code.
    @android.annotation.SuppressLint("MissingPermission")
    private fun apply(a: Action, vars: Map<String, String>): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val text = Vars.fill(a.text, vars)
        return when (a.type) {
            ActionType.Notify -> { notify(text); "Showed \"${text.take(40)}\"" }
            ActionType.OpenLink -> open(Intent(Intent.ACTION_VIEW, Uri.parse(text)), "Open link", text)
            ActionType.OpenApp -> {
                val i = context.packageManager.getLaunchIntentForPackage(a.text) ?: error("app not installed")
                open(i, "Open app", AppNames.label(context, a.text))
            }
            ActionType.Volume -> {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * a.value / 100f).toInt().coerceIn(0, max), 0)
                "Volume ${a.value}%"
            }
            ActionType.Ringer -> {
                audio.ringerMode = a.value.coerceIn(0, 2)
                listOf("Silenced", "Vibrate only", "Ringer on")[a.value.coerceIn(0, 2)]
            }
            ActionType.Dnd -> {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) error("allow Do Not Disturb access first")
                nm.setInterruptionFilter(if (a.value == 1) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                if (a.value == 1) "Do Not Disturb on" else "Do Not Disturb off"
            }
            ActionType.Torch -> { torch(a.value == 1); if (a.value == 1) "Flashlight on" else "Flashlight off" }
            ActionType.Vibrate -> {
                context.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)); "Vibrated"
            }
            ActionType.Copy -> {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Rule", text)); "Copied"
            }
            ActionType.Speak -> { speak(text); "Said it" }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun torch(on: Boolean) {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: error("no flashlight")
        cm.setTorchMode(id, on)
    }

    private fun speak(text: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rule")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ tts?.shutdown() }, 20_000)
        }
    }

    /**
     * Android stops apps starting screens from the background, unless the app may draw over other apps (the same permission
     * as the orb). Without it the action becomes a notification that opens the thing with one tap.
     */
    private fun open(intent: Intent, title: String, what: String): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(context)) { context.startActivity(intent); return "Opened $what" }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rules", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(context, what.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(what.hashCode(), NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(what).setAutoCancel(true).setContentIntent(pi).build())
        return "Tap the notification to open $what"
    }

    private fun notify(text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rules", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, "rules").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify((System.currentTimeMillis() % 100_000).toInt() + 7000, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Rule").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(open).build())
    }

    // ---- Putting things back ----

    /** Notes what the rule's undoable actions are about to change, once, so the other side of the trigger can restore it. */
    private fun remember(rule: Rule) {
        if (prefs.contains("undo.${rule.id}")) return
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val o = JSONObject()
        rule.actions.forEach { a ->
            when (a.type) {
                ActionType.Volume -> o.put("volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                ActionType.Ringer -> o.put("ringer", audio.ringerMode)
                ActionType.Dnd -> if (nm.isNotificationPolicyAccessGranted) o.put("dnd", nm.currentInterruptionFilter)
                ActionType.Torch -> o.put("torch", a.value == 1)
                else -> Unit
            }
        }
        prefs.edit().putString("undo.${rule.id}", o.toString()).apply()
    }

    private suspend fun restore(rule: Rule, why: String) {
        val saved = prefs.getString("undo.${rule.id}", null) ?: return
        prefs.edit().remove("undo.${rule.id}").apply()
        val o = runCatching { JSONObject(saved) }.getOrNull() ?: return
        val audio = context.getSystemService(AudioManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        val did = ArrayList<String>()
        runCatching { if (o.has("volume")) { audio.setStreamVolume(AudioManager.STREAM_MUSIC, o.getInt("volume"), 0); did += "volume" } }
        runCatching { if (o.has("ringer")) { audio.ringerMode = o.getInt("ringer"); did += "ringer" } }
        runCatching { if (o.has("dnd") && nm.isNotificationPolicyAccessGranted) { nm.setInterruptionFilter(o.getInt("dnd")); did += "Do Not Disturb" } }
        runCatching { if (o.has("torch")) { torch(!o.getBoolean("torch")); did += "flashlight" } }
        record(rule, why, "Put back: " + did.joinToString(", ").ifEmpty { "nothing needed changing" })
    }

    // ---- History ----

    private fun record(rule: Rule, why: String, result: String) {
        // Several events can arrive at once; an atomic update keeps every record.
        _log.update { (listOf(RunRecord(System.currentTimeMillis(), rule.id, rule.name, why, result)) + it).take(100) }
        saveLog()
    }

    fun lastRun(ruleId: String): RunRecord? = _log.value.firstOrNull { it.ruleId == ruleId }

    // ---- Keeping the right things running ----

    /** Starts or stops the watching service, and lines up each time rule's next run. Call after any change and at app start. */
    fun sync() {
        val live = _rules.value.any { it.enabled && it.trigger.type in RuleKinds.LIVE }
        runCatching {
            if (live) ContextCompat.startForegroundService(context, Intent(context, RulesService::class.java))
            else context.stopService(Intent(context, RulesService::class.java))
        }
        val timed = _rules.value.filter { it.trigger.type == TriggerType.AtTime }
        val wm = WorkManager.getInstance(context)
        val before = prefs.getStringSet("timed", emptySet()).orEmpty()
        (before - timed.filter { it.enabled }.map { it.id }.toSet()).forEach { wm.cancelUniqueWork("rule-time-$it") }
        timed.filter { it.enabled }.forEach(::schedule)
        prefs.edit().putStringSet("timed", timed.filter { it.enabled }.map { it.id }.toSet()).apply()
    }

    private fun schedule(rule: Rule) {
        if (!rule.enabled || rule.trigger.type != TriggerType.AtTime) return
        val delay = Schedule.next(System.currentTimeMillis(), rule.trigger.minutes, rule.trigger.days) - System.currentTimeMillis()
        val req = OneTimeWorkRequestBuilder<RuleTimeWorker>().setInitialDelay(delay.coerceAtLeast(1_000), TimeUnit.MILLISECONDS).setInputData(androidx.work.workDataOf("rule" to rule.id)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("rule-time-${rule.id}", ExistingWorkPolicy.REPLACE, req)
    }

    private fun batteryPercent(): Int {
        val i = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0) -1 else level * 100 / scale
    }

    // ---- Saving ----

    private fun loadRules(): List<Rule> = runCatching { file.read().let { a -> List(a.length()) { a.getJSONObject(it) }.mapNotNull(RuleJson::fromJson) } }.getOrDefault(emptyList())
    private fun saveRules() { val snap = _rules.value; scope.launch { file.write(JSONArray().apply { snap.forEach { put(RuleJson.toJson(it)) } }) } }
    private fun loadLog(): List<RunRecord> = runCatching {
        logFile.read().let { a -> List(a.length()) { a.getJSONObject(it) }.map { RunRecord(it.getLong("at"), it.getString("id"), it.getString("name"), it.getString("why"), it.getString("result")) } }
    }.getOrDefault(emptyList())
    private fun saveLog() { val snap = _log.value; scope.launch { logFile.write(JSONArray().apply { snap.forEach { put(JSONObject().put("at", it.at).put("id", it.ruleId).put("name", it.ruleName).put("why", it.because).put("result", it.result)) } }) } }

    companion object { const val CHANNEL = "rules" }
}

/** Fires a time rule at its moment, then lines up the next one. */
class RuleTimeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as UltimateApp
        inputData.getString("rule")?.let { app.rules.fireTime(it) }
        return Result.success()
    }
}

/** Installed apps by the name people use for them. */
object AppNames {
    fun label(context: Context, pkg: String) = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    fun find(context: Context, name: String): String? {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }
        val q = name.trim()
        return (apps.firstOrNull { it.second.equals(q, true) } ?: apps.firstOrNull { it.second.contains(q, true) } ?: apps.firstOrNull { q.contains(it.second, true) && it.second.length > 3 })?.first
    }
}

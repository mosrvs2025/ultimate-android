package app.ultimate.launcher

import android.app.usage.UsageStatsManager
import app.ultimate.UltimateApp
import app.ultimate.health.ActivityGlance
import app.ultimate.health.ActivityRepo
import app.ultimate.health.HeartGlance
import app.ultimate.mesh.CamNodes
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The installed apps the home screen can open, their icons (drawn once and kept, so scrolling the letters never stutters), and
 * the favourites. Everything is read on a background thread; the screen only ever shows what is already loaded.
 */
class LauncherRepo(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val icons = object : LruCache<String, ImageBitmap>(160) {}
    /** Learns which apps you open when, from launches made here, to offer them at the right moment. */
    val habits = LauncherHabits(app)

    private val _apps = MutableStateFlow<List<HomeApp>>(emptyList())
    val apps: StateFlow<List<HomeApp>> = _apps
    private fun readSettings() = SettingsCodec.withExtras(SettingsCodec.decode(prefs.getString("accent", null), prefs.getString("backdrop", null), prefs.getString("clock", null),
        prefs.getBoolean("showDate", true), prefs.getBoolean("railTicks", true), prefs.getInt("favsShown", 5), prefs.getBoolean("suggestions", true), prefs.getString("hidden", null))) { key -> prefs.getString("x.$key", null) }
    private val _settings = MutableStateFlow(readSettings())
    /** The owner's choices about how the launcher looks and behaves (the base look, before any living-look rule). */
    val settings: StateFlow<LauncherSettings> = _settings

    private var signals = Signals(minuteOfDay = minuteOfDay())
    private val _living = MutableStateFlow(LivingLook.effective(_settings.value, signals))
    /** What is drawn: [settings] with the look a rule is wearing on top, and that rule. Same as [settings] when no rule applies. */
    val living: StateFlow<Living> = _living

    private fun minuteOfDay(): Int = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE) }

    /** Reads what the rules look at (the time, a running focus session, today's step goal) and redraws if the look changes. Cheap; call it every minute. */
    fun tick() {
        val focus = (app as? UltimateApp)?.focus?.current?.value?.let { it.running && !it.paused } ?: false
        val goal = _settings.value.rules.any { it.whenever == When.GoalReached } && runCatching { ActivityGlance.goalReached(app) }.getOrDefault(false)
        signals = Signals(minuteOfDay(), focus, goal)
        _living.value = LivingLook.effective(_settings.value, signals)
    }

    /** [tick], after freshening the step count when a rule needs it. Off the main thread. */
    suspend fun refreshSignals() {
        if (_settings.value.rules.any { it.whenever == When.GoalReached }) runCatching { ActivityGlance.refresh(app) }
        tick()
    }

    private val _cards = MutableStateFlow(CardData())
    /** What the home cards show. Filled by [refreshCards] for the cards that are switched on. */
    val cards: StateFlow<CardData> = _cards

    /** Reads what the switched-on cards need (activity, heart, shared cameras). Each read is throttled by its own source; call about every minute. */
    suspend fun refreshCards() {
        if (cardsFixed) return
        val on = _settings.value.cards
        if (on.isEmpty()) { if (_cards.value != CardData()) _cards.value = CardData(); return }
        val activity = if (HomeCard.Activity in on) runCatching { ActivityRepo.load(app, history = false) }.getOrNull() else null
        val heart = if (HomeCard.Heart in on) runCatching { HeartGlance.read(app) }.getOrNull() else null
        val cams = if (HomeCard.Cameras in on) runCatching { CamNodes.list() }.getOrDefault(emptyList()) else emptyList()
        _cards.value = CardData(activity, heart, cams, System.currentTimeMillis())
    }

    /** For previews and tests: cards with fixed contents. */
    internal fun seedCards(data: CardData) { cardsFixed = true; _cards.value = data }
    private var cardsFixed = false

    private var inkWatcher: Any? = null
    /** Keeps [WallpaperInk] in step with the phone's wallpaper colours, for the "match my wallpaper" accent. */
    fun watchWallpaper() {
        runCatching {
            val wm = android.app.WallpaperManager.getInstance(app)
            fun read() { WallpaperInk.set(wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)?.let { c -> listOfNotNull(c.primaryColor, c.secondaryColor, c.tertiaryColor).map { it.toArgb() } }.orEmpty()) }
            read()
            if (inkWatcher == null) {
                val l = android.app.WallpaperManager.OnColorsChangedListener { _, _ -> read() }
                wm.addOnColorsChangedListener(l, android.os.Handler(android.os.Looper.getMainLooper())); inkWatcher = l
            }
        }
    }

    fun update(change: (LauncherSettings) -> LauncherSettings) {
        val s = change(_settings.value).clamped(); _settings.value = s
        _living.value = LivingLook.effective(s, signals)
        prefs.edit().putString("accent", s.accent.name).putString("backdrop", s.backdrop.name).putString("clock", s.clock.name).putBoolean("showDate", s.showDate)
            .putBoolean("railTicks", s.railTicks).putInt("favsShown", s.favouritesShown).putBoolean("suggestions", s.suggestions).putString("hidden", SettingsCodec.encodeHidden(s.hidden))
            .also { e -> SettingsCodec.extras(s).forEach { (k, v) -> e.putString("x.$k", v) } }.apply()
    }
    /** Starts the favourites over from what the phone would suggest. */
    suspend fun resetFavourites() { prefs.edit().remove("favs").apply(); load() }

    private val _favourites = MutableStateFlow<List<String>>(emptyList())
    val favourites: StateFlow<List<String>> = _favourites

    suspend fun load() = withContext(Dispatchers.IO) {
        val pm = app.packageManager
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { HomeApp(it.activityInfo.packageName, it.loadLabel(pm).toString(), runCatching { pm.getPackageInfo(it.activityInfo.packageName, 0).firstInstallTime }.getOrDefault(0L)) }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        _apps.value = list
        withContext(Dispatchers.Main) { watchWallpaper() }
        val saved = prefs.getString("favs", null)
        _favourites.value = if (saved != null) saved.split(',').filter { it.isNotBlank() && list.any { a -> a.pkg == it } }
            else LauncherMath.defaultFavourites(usage(), phoneDefaults(), list.map { it.pkg }.toSet(), app.packageName)
    }

    /** What is worth offering right now (see [NowSuggest]); read off the main thread. */
    suspend fun now(): List<Now> = withContext(Dispatchers.IO) {
        val list = _apps.value
        val installed = list.map { it.pkg }.toSet()
        val labels = list.associate { it.pkg to it.label }
        runCatching { NowSuggest.suggest(NowReader.read(app, installed, habits), labels::get) }.getOrDefault(emptyList())
    }

    fun act(id: String) = NowReader.act(app, id)

    /** For previews and tests: a fixed set of apps instead of the phone's. */
    internal fun seed(apps: List<HomeApp>, favourites: List<String>) { _apps.value = apps; _favourites.value = favourites }

    fun setFavourites(pkgs: List<String>) { _favourites.value = pkgs; prefs.edit().putString("favs", pkgs.joinToString(",")).apply() }
    fun toggleFavourite(pkg: String) = setFavourites(if (pkg in _favourites.value) _favourites.value - pkg else (_favourites.value + pkg).take(8))

    /** The icon, drawn at [px] once and cached. Null while it has not been drawn yet (call [loadIcon] off the main thread). */
    fun icon(pkg: String): ImageBitmap? = icons.get(pkg)
    suspend fun loadIcon(pkg: String, px: Int = 108): ImageBitmap? = icons.get(pkg) ?: withContext(Dispatchers.IO) {
        runCatching { app.packageManager.getApplicationIcon(pkg).toBitmap(px, px).asImageBitmap() }.getOrNull()?.also { icons.put(pkg, it) }
    }

    fun launch(pkg: String): Boolean {
        val i = app.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        val ok = runCatching { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)); true }.getOrDefault(false)
        if (ok) runCatching { habits.record(pkg) }
        return ok
    }

    private fun start(i: Intent): Boolean = runCatching { app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)

    /** The system's own picker for a new wallpaper (photos, live wallpapers, the phone's gallery). */
    fun changeWallpaper(): Boolean = start(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Change wallpaper"))

    /** Android's own settings app. */
    fun openSystemSettings(): Boolean = start(Intent(android.provider.Settings.ACTION_SETTINGS))

    /** Hands [query] to the phone's browser or search app. */
    fun webSearch(query: String): Boolean = start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query))

    /**
     * Pulls down the notification shade or quick settings, the way other launchers do: there is no public call for it, so this asks the
     * status bar service directly and does nothing if the phone's version doesn't allow it. Returns whether it worked.
     */
    fun pullShade(quickSettings: Boolean): Boolean = runCatching {
        val bar = app.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod(if (quickSettings) "expandSettingsPanel" else "expandNotificationsPanel").invoke(bar)
        true
    }.getOrDefault(false)

    fun appInfo(pkg: String) { runCatching { app.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    /** Minutes of use over the last 14 days, when Usage access is allowed; empty otherwise (it is never asked for here). */
    private fun usage(): List<Pair<String, Long>> = runCatching {
        val um = app.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        um.queryAndAggregateUsageStats(now - 14L * 86_400_000, now).map { (k, v) -> k to v.totalTimeInForeground / 60_000 }
    }.getOrDefault(emptyList())

    /** The phone's own dialer, messages, camera and browser, whichever apps those are on this phone. */
    private fun phoneDefaults(): List<String> {
        val pm = app.packageManager
        fun of(i: Intent) = runCatching { pm.resolveActivity(i, 0)?.activityInfo?.packageName }.getOrNull()?.takeIf { it != "android" }
        return listOfNotNull(
            of(Intent(Intent.ACTION_DIAL)),
            runCatching { Telephony.Sms.getDefaultSmsPackage(app) }.getOrNull(),
            of(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
            of(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))),
            of(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)),
        )
    }
}

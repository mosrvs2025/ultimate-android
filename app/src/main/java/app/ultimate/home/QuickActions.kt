package app.ultimate.home

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.widget.RemoteViews
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.ultimate.MainActivity
import app.ultimate.R

/** The few things worth one tap from outside the app, with the tool each opens. Pure, so the choice is tested. */
object QuickActions {
    data class Item(val toolId: String, val label: String, val longLabel: String, val icon: Int)

    val ALL = listOf(
        Item("echo", "Echo", "Feel distance with sound", R.drawable.ic_act_echo),
        Item("air", "Air control", "Hand gestures from across the room", R.drawable.ic_act_echo),
        Item("ruler", "Ruler", "True-size ruler", R.drawable.ic_act_ruler),
        Item("recorder", "Record", "Record your voice", R.drawable.ic_act_mic),
        Item("cleanup", "Clean up", "Free up space", R.drawable.ic_act_clean),
    )

    /** Only actions for tools this edition has, at most [max] (launchers show about four). */
    fun available(tools: Set<String>, max: Int = 4) = ALL.filter { it.toolId in tools }.take(max)

    fun open(context: Context, toolId: String): Intent =
        Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(MainActivity.EXTRA_TOOL, toolId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The menu that appears when the app icon is long-pressed on the home screen. */
    fun publishShortcuts(context: Context, tools: Set<String>) = runCatching {
        val items = available(tools, ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(1, 4))
        ShortcutManagerCompat.setDynamicShortcuts(context, items.map { a ->
            ShortcutInfoCompat.Builder(context, "tool-${a.toolId}").setShortLabel(a.label).setLongLabel(a.longLabel)
                .setIcon(IconCompat.createWithResource(context, a.icon)).setIntent(open(context, a.toolId)).build()
        })
    }

    /** Turns the flashlight on or off from the widget, without opening the app. */
    fun toggleTorch(context: Context) = runCatching {
        val cm = context.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        torchOn = !torchOn
        cm.setTorchMode(id, torchOn)
    }

    @Volatile var torchOn = false
}

/**
 * The home screen widget: Echo, Ruler, Record and the flashlight in one tap, and a line with the last thing the phone
 * sensed. Nothing runs in the background for it; the app refreshes the line when something is sensed.
 */
class QuickActionsWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = ids.forEach { manager.updateAppWidget(it, views(context, null)) }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TORCH) { QuickActions.toggleTorch(context); return }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TORCH = "app.ultimate.widget.TORCH"

        fun views(context: Context, status: String?): RemoteViews = RemoteViews(context.packageName, R.layout.widget_actions).apply {
            setTextViewText(R.id.widget_status, status ?: context.getString(R.string.widget_idle))
            fun open(id: Int, tool: String, code: Int) = setOnClickPendingIntent(id, PendingIntent.getActivity(context, code, QuickActions.open(context, tool), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            open(R.id.widget_echo, "echo", 401); open(R.id.widget_ruler, "ruler", 402); open(R.id.widget_record, "recorder", 403)
            open(R.id.widget_root, "home", 404)
            setOnClickPendingIntent(R.id.widget_torch, PendingIntent.getBroadcast(context, 405, Intent(context, QuickActionsWidget::class.java).setAction(ACTION_TORCH), PendingIntent.FLAG_IMMUTABLE))
        }

        /** Shows [status] on every placed widget; cheap when none are placed. */
        fun show(context: Context, status: String) = runCatching {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, QuickActionsWidget::class.java))
            if (ids.isNotEmpty()) m.updateAppWidget(ids, views(context, status))
        }
    }
}

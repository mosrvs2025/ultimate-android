package app.ultimate.inbox

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.ultimate.UltimateApp

/**
 * Android hands every posted notification to this service once the user turns on notification access for Ultimate. It keeps
 * the words, never acts on notifications, and stores nothing outside the phone.
 */
class NotificationCatcher : NotificationListenerService() {
    private val log get() = (application as UltimateApp).notificationLog
    private var lastTrim = 0L

    // When Android (re)connects the listener, whatever is already in the shade is kept too, so nothing shown while it was
    // paused is lost.
    override fun onListenerConnected() {
        log.connected(true)
        runCatching { activeNotifications?.forEach { onNotificationPosted(it) } }
    }

    // Samsung and Android can drop the listener to save battery; asking to be bound again brings it back without the user.
    override fun onListenerDisconnected() {
        log.connected(false)
        runCatching { requestRebind(android.content.ComponentName(this, NotificationCatcher::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            val log = log
            if (log.paused) return
            val n = sbn.notification
            val e = n.extras
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val title = (e.getCharSequence(Notification.EXTRA_TITLE) ?: e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString().orEmpty()
            val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
            val messages = runCatching {
                @Suppress("DEPRECATION")
                val raw = if (Build.VERSION.SDK_INT >= 33) e.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java) else e.getParcelableArray(Notification.EXTRA_MESSAGES)
                if (Build.VERSION.SDK_INT >= 30) Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw).map { m -> listOfNotNull(m.senderPerson?.name ?: m.sender, m.text).joinToString(": ") }
                // Android 10 has no parser for these, so read the bundle keys MessagingStyle writes.
                else raw.orEmpty().mapNotNull { (it as? android.os.Bundle)?.let { b -> listOfNotNull(b.getCharSequence("sender"), b.getCharSequence("text")).joinToString(": ").ifBlank { null } } }
            }.getOrDefault(emptyList())
            // Chat apps re-post the whole conversation with each message; keep only lines not stored before for this chat.
            val fresh = log.newMessages(sbn.key, messages)
            if (messages.isNotEmpty() && fresh.isEmpty()) return
            val body = LogRules.body(e.getCharSequence(Notification.EXTRA_TEXT)?.toString(), e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(), lines, fresh)
            val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.category == Notification.CATEGORY_PROGRESS || n.category == Notification.CATEGORY_TRANSPORT
            if (LogRules.skip(sbn.packageName, packageName, ongoing, title, body, log.excluded)) return
            val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            log.add(sbn.packageName, app, sbn.postTime, title, body, e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(), sbn.key)
            if (System.currentTimeMillis() - lastTrim > 3_600_000L) { lastTrim = System.currentTimeMillis(); log.trim() }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // REASON_APP_CANCEL: the app itself took it back (for example the sender unsent the message).
        if (reason == REASON_APP_CANCEL) runCatching { log.markRemovedByApp(sbn.key) }
    }
}

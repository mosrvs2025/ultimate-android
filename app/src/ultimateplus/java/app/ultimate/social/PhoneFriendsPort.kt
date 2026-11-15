package app.ultimate.social

import android.content.Context

/** The dashboard's view of this phone: the same store and sender the app screen uses, so the two always agree. */
class PhoneFriendsPort(context: Context) : FriendsPort {
    private val app = context.applicationContext
    private val store get() = FriendsStore(app)

    override fun device(): String = android.os.Build.MODEL ?: "Phone"
    override var name: String get() = store.name; set(v) { store.name = v }
    override var settings: AnnounceSettings get() = store.settings; set(v) { store.settings = v }
    override var summary: Boolean get() = store.summary; set(v) { store.summary = v }
    override var templateStart: String get() = store.templateStart; set(v) { store.templateStart = v }
    override var templateFinish: String get() = store.templateFinish; set(v) { store.templateFinish = v }
    override fun circles() = store.circles()
    override fun told() = store.told
    override fun add(t: Target) = store.add(t)
    override fun remove(id: String) = store.remove(id)
    override fun send(circle: Circle, message: String, what: String) = FriendsHub.deliver(store, circle, message, what)
    override fun log() = store.log()
}

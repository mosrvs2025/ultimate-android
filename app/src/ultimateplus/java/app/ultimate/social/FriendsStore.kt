package app.ultimate.social

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the friends feature remembers: the owner's circles (their links and tokens sealed with a key that lives in the phone's
 * hardware-backed Keystore and never leaves it), the display name, and the choices. Everything is off until switched on.
 */
class FriendsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("friends", Context.MODE_PRIVATE)

    var name: String
        get() = prefs.getString("name", "") ?: ""
        set(v) { prefs.edit().putString("name", Delivery.safe(v, 24)).apply() }

    var summary: Boolean
        get() = prefs.getBoolean("summary", true)
        set(v) { prefs.edit().putBoolean("summary", v).apply() }

    var summaryDistance: Boolean
        get() = prefs.getBoolean("summaryDistance", false)
        set(v) { prefs.edit().putBoolean("summaryDistance", v).apply() }

    /** The owner's own wording for the two messages; blank means the usual wording (see [Templates]). */
    var templateStart: String
        get() = prefs.getString("tplStart", "") ?: ""
        set(v) { prefs.edit().putString("tplStart", v.take(Templates.MAX)).apply() }
    var templateFinish: String
        get() = prefs.getString("tplFinish", "") ?: ""
        set(v) { prefs.edit().putString("tplFinish", v.take(Templates.MAX)).apply() }

    fun log(): List<LogEntry> = ActivityLog.decode(prefs.getString("log", null))
    fun record(e: LogEntry) = synchronized(LOCK) { prefs.edit().putString("log", ActivityLog.encode(ActivityLog.add(log(), e))).apply() }

    var settings: AnnounceSettings
        get() = AnnounceSettings(
            enabled = prefs.getBoolean("enabled", false), notify = (prefs.getStringSet("notify", emptySet()) ?: emptySet()).toSet(),
            askFirst = prefs.getBoolean("askFirst", true), steadyMinutes = prefs.getInt("steady", 4), cooldownMinutes = prefs.getInt("cooldown", 180),
            quietFromMin = prefs.getInt("quietFrom", 22 * 60), quietToMin = prefs.getInt("quietTo", 7 * 60), privateUntil = prefs.getLong("privateUntil", 0L),
        )
        set(s) { prefs.edit().putBoolean("enabled", s.enabled).putStringSet("notify", s.notify).putBoolean("askFirst", s.askFirst).putInt("steady", s.steadyMinutes)
            .putInt("cooldown", s.cooldownMinutes).putInt("quietFrom", s.quietFromMin).putInt("quietTo", s.quietToMin).putLong("privateUntil", s.privateUntil).apply() }

    var told: Map<String, Long>
        get() = runCatching { JSONObject(prefs.getString("told", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } }.getOrDefault(emptyMap())
        set(v) { prefs.edit().putString("told", JSONObject(v as Map<*, *>).toString()).apply() }

    fun circles(): List<Circle> = runCatching {
        val a = JSONArray(unseal(prefs.getString("circles", null) ?: return emptyList()))
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            when (o.getString("kind")) {
                "discord" -> Circle(o.getString("id"), Target.Discord(o.getString("url"), o.optString("label", "Discord")))
                "ntfy" -> Circle(o.getString("id"), Target.Ntfy(o.getString("url"), o.optString("label", "ntfy")))
                "telegram" -> Circle(o.getString("id"), Target.Telegram(o.getString("token"), o.getString("chat"), o.optString("label", "Telegram")))
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    private fun saveCircles(list: List<Circle>) {
        val a = JSONArray()
        list.forEach { c ->
            a.put(JSONObject().put("id", c.id).apply {
                when (val t = c.target) {
                    is Target.Discord -> put("kind", "discord").put("url", t.url).put("label", t.label)
                    is Target.Ntfy -> put("kind", "ntfy").put("url", t.url).put("label", t.label)
                    is Target.Telegram -> put("kind", "telegram").put("token", t.token).put("chat", t.chatId).put("label", t.label)
                }
            })
        }
        prefs.edit().putString("circles", seal(a.toString())).apply()
    }

    fun add(t: Target): Circle { val c = Circle("c" + System.currentTimeMillis().toString(36), t); saveCircles(circles() + c); return c }
    fun remove(id: String) { saveCircles(circles().filterNot { it.id == id }); settings = settings.let { it.copy(notify = it.notify - id) } }

    private fun seal(text: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun unseal(text: String): String {
        val (iv, ct) = text.split(':')
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        return String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object { val LOCK = Any(); const val ALIAS = "ultimate-friends-circles"; const val TRANSFORM = "AES/GCM/NoPadding" }
}

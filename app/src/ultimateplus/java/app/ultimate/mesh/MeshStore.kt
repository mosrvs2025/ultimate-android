package app.ultimate.mesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * What the mesh remembers between runs: whether this phone lends its sensors, the devices it has met, and the pairing
 * code. The code is sealed with a key that lives in the phone's hardware-backed Keystore and never leaves it, so a copy
 * of the app's data on its own cannot reveal it. It is kept only so the mesh can come back without retyping and so this
 * phone can show it for pairing the next device; "Forget" removes it.
 */
class MeshStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mesh", Context.MODE_PRIVATE)

    /** Off until the owner turns it on: other devices may only record or play sound here while this is true. */
    var lends: Boolean
        get() = prefs.getBoolean("lends", false)
        set(v) { prefs.edit().putBoolean("lends", v).apply() }

    /** Whether the mesh was running when the app last closed, so it can resume when the app is opened again. */
    var wasOn: Boolean
        get() = prefs.getBoolean("wasOn", false)
        set(v) { prefs.edit().putBoolean("wasOn", v).apply() }

    var roster: String
        get() = prefs.getString("roster", "") ?: ""
        set(v) { prefs.edit().putString("roster", v).apply() }

    fun saveCode(code: String) {
        runCatching {
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = c.doFinal(code.toByteArray())
            prefs.edit().putString("code", Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        }
    }

    fun loadCode(): String? = runCatching {
        val (iv, ct) = (prefs.getString("code", null) ?: return null).split(':')
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
    }.getOrNull()

    /** Removes the code and the list of known devices. */
    fun forgetAll() { prefs.edit().remove("code").remove("roster").putBoolean("wasOn", false).apply() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object { const val ALIAS = "ultimate-mesh-code"; const val TRANSFORM = "AES/GCM/NoPadding" }
}

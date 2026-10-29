package app.ultimate.checkup.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the user's API key encrypted with a key that never leaves the phone's Keystore. */
/** One vault per provider; Claude keeps the original slot so a key saved before is still found. */
class KeyVault(context: Context, provider: String = "anthropic") {
    private val KEY = if (provider == "anthropic") "anthropicKey" else "${provider}Key"
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)

    fun load(): String? = runCatching {
        val stored = prefs.getString(KEY, null) ?: return null
        val (iv, data) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(data))
    }.getOrNull()

    fun save(apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val data = cipher.doFinal(apiKey.trim().toByteArray())
        prefs.edit().putString(KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun forget() = prefs.edit().remove(KEY).apply()

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "ultimate-ai-key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

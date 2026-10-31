package app.ultimate.sshclient

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.util.security.SecurityUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Locks and unlocks small secrets (private keys, saved passwords). The real one uses a key that never leaves the phone's Keystore. */
interface Sealer {
    fun seal(data: ByteArray): String
    fun open(sealed: String): ByteArray?
}

class KeystoreSealer(private val alias: String = "ultimate-ssh-vault") : Sealer {
    override fun seal(data: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secret()) }
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    override fun open(sealed: String): ByteArray? = runCatching {
        val (iv, data) = sealed.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv)) }.doFinal(data)
    }.getOrNull()

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private companion object { const val TRANSFORM = "AES/GCM/NoPadding" }
}

/** Key pairs, as the files and lines other SSH tools use. */
object Keys {
    /** An ECDSA P-256 pair: short, fast, accepted by every current server. */
    fun generate(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()

    /** The private key as an OpenSSH key file (it carries the public half too, which Java's own export leaves out). */
    fun pem(pair: KeyPair): String {
        val out = java.io.ByteArrayOutputStream()
        org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(pair, "ultimate", null, out)
        return String(out.toByteArray(), Charsets.UTF_8) // not ByteArrayOutputStream.toString(Charset), which needs Android 13
    }

    /** Reads a private key in OpenSSH or PEM form, with its passphrase when it has one; null when it can't be read. */
    fun load(pem: String, passphrase: String? = null): KeyPair? = runCatching {
        SecurityUtils.loadKeyPairIdentities(null, NamedResource.ofName("key"), ByteArrayInputStream(pem.toByteArray()),
            if (passphrase.isNullOrEmpty()) null else FilePasswordProvider.of(passphrase))?.firstOrNull()
    }.getOrNull()

    /** The line you paste into a server's authorized_keys. */
    fun publicLine(pair: KeyPair, comment: String) = PublicKeyEntry.toString(pair.public) + if (comment.isBlank()) "" else " " + comment.trim().replace(' ', '-')

    fun fingerprint(pair: KeyPair): String = KeyUtils.getFingerPrint(pair.public)
}

/**
 * Hosts, snippets, keys and the servers already trusted. Everything lives in one small file; private keys and saved passwords
 * are stored sealed and never written to the file in the clear, nor to logs.
 */
class VaultStore(private val dir: File, private val sealer: Sealer, private val now: () -> Long = System::currentTimeMillis, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val file = File(dir, "ssh-vault.json")
    private val lock = Any()
    private val _hosts = MutableStateFlow<List<Host>>(emptyList())
    private val _identities = MutableStateFlow<List<Identity>>(emptyList())
    private val _snippets = MutableStateFlow<List<Snippet>>(emptyList())
    private var known = HashMap<String, String>()
    private var secrets = HashMap<String, String>()

    val hosts: StateFlow<List<Host>> = _hosts.asStateFlow()
    val identities: StateFlow<List<Identity>> = _identities.asStateFlow()
    val snippets: StateFlow<List<Snippet>> = _snippets.asStateFlow()

    init { load() }

    // ---- Hosts ----

    fun saveHost(h: Host) { _hosts.update { l -> if (l.any { it.id == h.id }) l.map { if (it.id == h.id) h else it } else l + h }; persist() }
    fun deleteHost(id: String) { _hosts.update { l -> l.filterNot { it.id == id } }; synchronized(lock) { secrets.remove("pw.$id") }; persist() }
    fun touch(id: String) { _hosts.update { l -> l.map { if (it.id == id) it.copy(lastConnected = now()) else it } }; persist() }
    fun newHostId() = newId()

    fun savePassword(hostId: String, password: String) { synchronized(lock) { secrets["pw.$hostId"] = sealer.seal(password.toByteArray()) }; persist() }
    fun forgetPassword(hostId: String) { synchronized(lock) { secrets.remove("pw.$hostId") }; persist() }
    fun hasPassword(hostId: String) = synchronized(lock) { secrets.containsKey("pw.$hostId") }
    fun password(hostId: String): String? = synchronized(lock) { secrets["pw.$hostId"] }?.let { sealer.open(it) }?.let { String(it) }

    // ---- Keys ----

    fun generateIdentity(name: String): Identity {
        val pair = Keys.generate()
        return store(name, pair, Keys.pem(pair), null)
    }

    /** Null when the text isn't a key this app can read, or the passphrase is wrong. */
    fun importIdentity(name: String, pem: String, passphrase: String?): Identity? {
        val pair = Keys.load(pem, passphrase) ?: return null
        return store(name, pair, pem, passphrase)
    }

    private fun store(name: String, pair: KeyPair, pem: String, passphrase: String?): Identity {
        val id = newId()
        val identity = Identity(id, name.ifBlank { "Key" }, Keys.publicLine(pair, ""), Keys.fingerprint(pair), now())
        val blob = JSONObject().put("pem", pem).put("pass", passphrase ?: "").toString().toByteArray()
        synchronized(lock) { secrets["key.$id"] = sealer.seal(blob) }
        _identities.update { it + identity }; persist()
        return identity
    }

    fun deleteIdentity(id: String) {
        _identities.update { l -> l.filterNot { it.id == id } }
        _hosts.update { l -> l.map { if (it.identityId == id) it.copy(identityId = "", auth = Auth.Ask) else it } }
        synchronized(lock) { secrets.remove("key.$id") }; persist()
    }

    fun keyPair(id: String): KeyPair? {
        val blob = synchronized(lock) { secrets["key.$id"] }?.let { sealer.open(it) } ?: return null
        val o = runCatching { JSONObject(String(blob)) }.getOrNull() ?: return null
        return Keys.load(o.optString("pem"), o.optString("pass").ifEmpty { null })
    }

    // ---- Snippets ----

    fun saveSnippet(s: Snippet) { _snippets.update { l -> if (l.any { it.id == s.id }) l.map { if (it.id == s.id) s else it } else l + s }; persist() }
    fun deleteSnippet(id: String) { _snippets.update { l -> l.filterNot { it.id == id } }; persist() }

    // ---- Servers already trusted ----

    fun knownFingerprints(): Map<String, String> = synchronized(lock) { HashMap(known) }
    fun trust(address: String, port: Int, fingerprint: String) { synchronized(lock) { known[KnownHosts.key(address, port)] = fingerprint }; persist() }

    // ---- Saving ----

    private fun persist() = synchronized(lock) {
        dir.mkdirs()
        val o = JSONObject().put("hosts", JSONArray().apply { _hosts.value.forEach { put(VaultJson.host(it)) } })
            .put("identities", JSONArray().apply { _identities.value.forEach { put(VaultJson.identity(it)) } })
            .put("snippets", JSONArray().apply { _snippets.value.forEach { put(VaultJson.snippet(it)) } })
            .put("known", JSONObject().apply { known.forEach { (k, v) -> put(k, v) } })
            .put("secrets", JSONObject().apply { secrets.forEach { (k, v) -> put(k, v) } })
        val tmp = File(dir, "ssh-vault.json.tmp")
        tmp.writeText(o.toString()); tmp.renameTo(file)
    }

    private fun load() = synchronized(lock) {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull()
        if (o == null) { _snippets.value = Snippets.starters; return@synchronized }
        fun <T : Any> list(key: String, read: (JSONObject) -> T?): List<T> = o.optJSONArray(key)?.let { a -> List(a.length()) { a.optJSONObject(it) }.filterNotNull().mapNotNull(read) }.orEmpty()
        _hosts.value = list("hosts", VaultJson::host)
        _identities.value = list("identities", VaultJson::identity)
        _snippets.value = if (o.has("snippets")) list("snippets", VaultJson::snippet) else Snippets.starters
        o.optJSONObject("known")?.let { k -> k.keys().forEach { key -> known[key] = k.getString(key) } }
        o.optJSONObject("secrets")?.let { s -> s.keys().forEach { key -> secrets[key] = s.getString(key) } }
    }
}

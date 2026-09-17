package chat.central.widget

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray

/**
 * Where the chat's session token and this device's encryption keys live.
 *
 * Keystore-wrapped AES-GCM over ordinary [SharedPreferences], rather than the
 * WebView's own storage — and that is the entire point of it. WebView data is
 * the app's to delete (a "clear app data", an OEM's storage reclaim, the app's
 * own cache button), and history is encrypted to devices, so dropping the keys
 * does not merely sign the visitor out, it makes the thread unreadable. This
 * survives all three.
 *
 * Written against the Keystore directly rather than through
 * `androidx.security:security-crypto`. That wrapper does the same AES-GCM under
 * the same Keystore master key, but it is the only dependency this library
 * would not otherwise need, its `EncryptedSharedPreferences` is deprecated, and
 * its one usable release is an alpha. Eighty lines of platform API cost an
 * integrator nothing; a fourth transitive dependency costs them a version to
 * reconcile forever.
 *
 * Runs on the WebView's JavaScript-bridge thread, never the main one — which is
 * where it belongs, because the first touch generates a Keystore key.
 */
internal object SecureStorage {

    fun get(context: Context, key: String): String? = decrypt(prefs(context).getString(value(key), null))

    fun getMany(context: Context, keys: List<String>): List<String?> {
        val store = prefs(context)
        // One SharedPreferences read, one Keystore key, N decryptions — the
        // batch the bridge exists to make worth sending.
        return keys.map { decrypt(store.getString(value(it), null)) }
    }

    fun set(context: Context, key: String, value: String) = setMany(context, listOf(key to value))

    fun setMany(context: Context, entries: List<Pair<String, String>>) {
        val store = prefs(context)
        val edit = store.edit()
        for ((key, plain) in entries) edit.putString(value(key), encrypt(plain))
        val keys = manifest(store)
        val changed = entries.map { it.first }.fold(false) { acc, key -> keys.add(key) || acc }
        if (changed) edit.putString(INDEX_KEY, JSONArray(keys.toList()).toString())
        // commit(), not apply(): the caller answers the page as soon as this
        // returns, and a session token the widget believes is saved must be on
        // disk before a process death can prove otherwise.
        edit.commit()
    }

    fun remove(context: Context, key: String) {
        val store = prefs(context)
        val keys = manifest(store)
        val edit = store.edit().remove(value(key))
        if (keys.remove(key)) edit.putString(INDEX_KEY, JSONArray(keys.toList()).toString())
        edit.commit()
    }

    /**
     * Neither Keystore nor Keychain lists by prefix, hence the manifest this
     * keeps beside the values. The adapter's contract is FULL keys, so what
     * goes in is what comes back out.
     */
    fun list(context: Context, prefix: String): List<String> =
        manifest(prefs(context)).filter { it.startsWith(prefix) }

    /**
     * Forget every value, and the Keystore key that could read them.
     *
     * Dropping the key is what makes this final: any ciphertext that outlives
     * the prefs file — a backup, an undeleted page — is unreadable rather than
     * merely unreferenced.
     */
    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
        synchronized(this) { cachedKey = null }
    }

    // ---- crypto -------------------------------------------------------------

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "chat.central.widget.storage"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user authentication requirement: the chat syncs and
                // notifies while the phone is locked, and a key that needs a
                // fingerprint first cannot decrypt the session that does it.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    // Resolved once and cached: Keystore calls cross into keymaster, and a boot
    // reads sixty values. Not `by lazy` — clear() deletes the key, and a lazy
    // one would hand out the dead handle forever after.
    @Volatile
    private var cachedKey: SecretKey? = null

    private val key: SecretKey
        get() = cachedKey ?: synchronized(this) { cachedKey ?: secretKey().also { cachedKey = it } }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        // iv || ciphertext, in one value: GCM's IV is not a secret and it has to
        // travel with what it encrypted.
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String? {
        if (stored == null) return null
        return runCatching {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            }
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull() // a value this key can no longer read is a value that is not there
    }

    // ---- prefs --------------------------------------------------------------

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: context.applicationContext
            .getSharedPreferences("chat.central.widget.storage", Context.MODE_PRIVATE)
            .also { cached = it }
    }

    private fun manifest(store: SharedPreferences): LinkedHashSet<String> {
        val raw = store.getString(INDEX_KEY, null) ?: return LinkedHashSet()
        val parsed = runCatching { JSONArray(raw) }.getOrNull() ?: return LinkedHashSet()
        return (0 until parsed.length()).mapNotNullTo(LinkedHashSet()) { parsed.optString(it).ifEmpty { null } }
    }

    // Values are namespaced apart from the manifest so a raw dump of the prefs
    // file can never mistake one for the other.
    private fun value(key: String) = "v.$key"

    private const val INDEX_KEY = "__index__"
}

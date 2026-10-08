package de.shansen.liblogicalaccessnfc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import de.shansen.rfcard.DesfireKey
import de.shansen.rfcard.DesfireKeyType
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedCardKey(val aid: Int?, val label: String, val key: DesfireKey)

/** Only encrypted key material reaches disk; the wrapping key stays in Android Keystore. */
class SavedKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("saved-card-keys", Context.MODE_PRIVATE)
    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): List<SavedCardKey> {
        val encoded = prefs.getString("ciphertext", null) ?: return emptyList()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128,
            Base64.decode(prefs.getString("iv", null), Base64.NO_WRAP)))
        val plaintext = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
        try {
            val rows = JSONArray(plaintext.toString(Charsets.UTF_8))
            return List(rows.length()) { i ->
                val row = rows.getJSONObject(i)
                SavedCardKey(if (row.isNull("aid")) null else row.getInt("aid"), row.getString("label"),
                    DesfireKey(Base64.decode(row.getString("bytes"), Base64.NO_WRAP),
                        DesfireKeyType.valueOf(row.getString("type")), row.getInt("number"),
                        if (row.isNull("version")) null else row.getInt("version")))
            }
        } finally { plaintext.fill(0) }
    }
    fun save(aid: Int?, label: String, key: DesfireKey) {
        val existing = load()
        try {
            val entries = existing.filterNot { it.aid == aid && (aid == null || (it.key.number == key.number && it.key.type == key.type)) } + SavedCardKey(aid, label, key)
            write(entries)
        } finally { existing.forEach { it.key.clear() } }
    }
    fun removeApplicationKeys() = removeWhere { it.aid != null }
    fun removePiccKey() = removeWhere { it.aid == null }
    private fun removeWhere(predicate: (SavedCardKey) -> Boolean) {
        val entries = load()
        try { write(entries.filterNot(predicate)) } finally { entries.forEach { it.key.clear() } }
    }
    private fun write(entries: List<SavedCardKey>) {
        if (entries.isEmpty()) { check(prefs.edit().clear().commit()); return }
        val rows = JSONArray()
        entries.forEach { entry -> rows.put(JSONObject().apply {
            put("aid", entry.aid ?: JSONObject.NULL); put("label", entry.label)
            put("bytes", Base64.encodeToString(entry.key.bytes, Base64.NO_WRAP))
            put("type", entry.key.type.name); put("number", entry.key.number)
            put("version", entry.key.version ?: JSONObject.NULL)
        }) }
        val plaintext = rows.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
            val ciphertext = cipher.doFinal(plaintext)
            check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit())
        } finally { plaintext.fill(0) }
    }
    companion object { private const val ALIAS = "rfidgear.saved-card-keys.v1" }
}

package com.example.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface VaultCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertextWithIv: ByteArray): ByteArray
    fun deleteKey()
}

class AndroidKeyStoreVaultCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS
) : VaultCipher {

    private val random = SecureRandom()

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = ks.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun decrypt(ciphertextWithIv: ByteArray): ByteArray {
        require(ciphertextWithIv.size > GCM_IV_LENGTH) { "Invalid encrypted payload" }
        val iv = ciphertextWithIv.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = ciphertextWithIv.copyOfRange(GCM_IV_LENGTH, ciphertextWithIv.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(keyAlias)) {
                ks.deleteEntry(keyAlias)
            }
        }
    }

    companion object {
        const val DEFAULT_KEY_ALIAS = "veluna_secret_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
    }
}

/**
 * Software AES-256-GCM cipher used strictly for unit/Robolectric tests where AndroidKeyStore
 * provider is absent on the JVM.
 */
class InMemoryAesGcmVaultCipher(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
) : VaultCipher {
    private var keySpec: SecretKeySpec? = SecretKeySpec(keyBytes.copyOf(32), "AES")
    private val random = SecureRandom()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val key = keySpec ?: throw IllegalStateException("Vault key was wiped")
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertextWithIv: ByteArray): ByteArray {
        val key = keySpec ?: throw IllegalStateException("Vault key was wiped")
        require(ciphertextWithIv.size > 12) { "Invalid encrypted payload" }
        val iv = ciphertextWithIv.copyOfRange(0, 12)
        val ciphertext = ciphertextWithIv.copyOfRange(12, ciphertextWithIv.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        keySpec = null
    }
}

class SecretVault(
    private val storageFile: File,
    private val cipher: VaultCipher
) {
    constructor(context: Context) : this(
        storageFile = File(context.applicationContext.noBackupFilesDir, VAULT_FILENAME),
        cipher = AndroidKeyStoreVaultCipher()
    )

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun put(name: String, value: String) {
        synchronized(lock) {
            val map = readMapLocked().toMutableMap()
            if (value.isEmpty()) {
                map.remove(name)
            } else {
                map[name] = value
            }
            writeMapLocked(map)
        }
    }

    fun putAll(entries: Map<String, String>) {
        synchronized(lock) {
            val map = readMapLocked().toMutableMap()
            entries.forEach { (k, v) ->
                if (v.isEmpty()) map.remove(k) else map[k] = v
            }
            writeMapLocked(map)
        }
    }

    fun get(name: String): String? = synchronized(lock) {
        readMapLocked()[name]
    }

    fun getAll(): Map<String, String> = synchronized(lock) {
        readMapLocked().toMap()
    }

    fun delete(name: String) {
        synchronized(lock) {
            val map = readMapLocked().toMutableMap()
            if (map.remove(name) != null) {
                writeMapLocked(map)
            }
        }
    }

    fun wipeAll() {
        synchronized(lock) {
            runCatching {
                if (storageFile.exists()) {
                    storageFile.delete()
                }
                val tmp = File(storageFile.parentFile, "$VAULT_FILENAME.tmp")
                if (tmp.exists()) {
                    tmp.delete()
                }
            }
            cipher.deleteKey()
        }
    }

    private fun readMapLocked(): Map<String, String> {
        if (!storageFile.exists()) return emptyMap()
        return runCatching {
            val encoded = storageFile.readText(Charsets.UTF_8).trim()
            if (encoded.isEmpty()) return emptyMap()
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val plainJson = cipher.decrypt(payload).toString(Charsets.UTF_8)
            json.decodeFromString<Map<String, String>>(plainJson)
        }.getOrElse {
            emptyMap()
        }
    }

    private fun writeMapLocked(map: Map<String, String>) {
        if (map.isEmpty()) {
            if (storageFile.exists()) storageFile.delete()
            return
        }
        storageFile.parentFile?.mkdirs()
        val plainBytes = json.encodeToString(map).toByteArray(Charsets.UTF_8)
        val encrypted = cipher.encrypt(plainBytes)
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val tmp = File(storageFile.parentFile, "$VAULT_FILENAME.tmp")
        tmp.writeText(encoded, Charsets.UTF_8)
        if (!tmp.renameTo(storageFile)) {
            storageFile.writeText(encoded, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val VAULT_FILENAME = "secrets.bin"

        const val KEY_LASTFM_KEY = "lastfm_key"
        const val KEY_LASTFM_SECRET = "lastfm_secret"
        const val KEY_LASTFM_SESSION = "lastfm_session"
        const val KEY_LISTENBRAINZ_TOKEN = "listenbrainz_token"
    }
}

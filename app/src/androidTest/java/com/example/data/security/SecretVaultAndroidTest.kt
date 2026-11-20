package com.example.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecretVaultAndroidTest {

    private lateinit var testAlias: String
    private lateinit var tempDir: File
    private lateinit var cipher: AndroidKeyStoreVaultCipher

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        testAlias = "veluna_test_key_$suffix"
        tempDir = File(context.cacheDir, "vault_android_test_$suffix").apply { mkdirs() }
        cipher = AndroidKeyStoreVaultCipher(testAlias)
    }

    @After
    fun tearDown() {
        cipher.deleteKey()
        tempDir.deleteRecursively()
    }

    @Test
    fun androidKeyStoreCipher_usesUniqueRandomIvAndRoundTripsPlaintext() {
        val payload = "sensitive-lastfm-session-key".toByteArray(Charsets.UTF_8)
        val first = cipher.encrypt(payload)
        val second = cipher.encrypt(payload)

        // Fresh 12-byte GCM IV + 16-byte tag ensures distinct ciphertexts for same plaintext
        assertThat(first.size).isGreaterThan(12 + 16)
        assertThat(first.toList()).isNotEqualTo(second.toList())
        assertThat(cipher.decrypt(first)).isEqualTo(payload)
        assertThat(cipher.decrypt(second)).isEqualTo(payload)
    }

    @Test
    fun androidKeyStoreCipher_reportsActualKeyInfoSecurityLevel() {
        val ciphertext = cipher.encrypt("probe".toByteArray(Charsets.UTF_8))
        assertThat(ciphertext).isNotEmpty()

        val report = cipher.inspectKeySecurity()
        android.util.Log.i("SecretVaultAndroidTest", "KeyInfo report: $report")

        assertThat(report.keyAlias).isEqualTo(testAlias)
        assertThat(report.securityLevel).isNotEqualTo(KeySecurityLevel.UNAVAILABLE)
        // Do not claim hardware/StrongBox unless KeyInfo itself reports hardware backing.
        val expectedHardware = report.securityLevel == KeySecurityLevel.STRONGBOX ||
            report.securityLevel == KeySecurityLevel.TRUSTED_ENVIRONMENT ||
            report.securityLevel == KeySecurityLevel.UNKNOWN_HARDWARE
        assertThat(report.isInsideSecureHardware).isEqualTo(expectedHardware)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            assertThat(report.securityLevel).isNotEqualTo(KeySecurityLevel.STRONGBOX)
        }
    }

    @Test
    fun secretVault_withAndroidKeyStore_persistsWipesAndDetectsTampering() {
        val storageFile = File(tempDir, SecretVault.VAULT_FILENAME)
        val vault = SecretVault(storageFile, cipher)

        vault.putAll(
            mapOf(
                SecretVault.KEY_LASTFM_KEY to "k_prod_123",
                SecretVault.KEY_LASTFM_SESSION to "s_prod_456"
            )
        )
        assertThat(vault.get(SecretVault.KEY_LASTFM_KEY)).isEqualTo("k_prod_123")
        assertThat(vault.get(SecretVault.KEY_LASTFM_SESSION)).isEqualTo("s_prod_456")

        // Tamper ciphertext byte -> must raise SecretVaultCorruptionException rather than returning partial/spoofed data
        val corrupted = storageFile.readText(Charsets.UTF_8).toCharArray()
        corrupted[corrupted.size / 2] = if (corrupted[corrupted.size / 2] == 'A') 'B' else 'A'
        storageFile.writeText(String(corrupted), Charsets.UTF_8)

        var threwCorruption = false
        try {
            vault.getAll()
        } catch (_: SecretVaultCorruptionException) {
            threwCorruption = true
        }
        assertThat(threwCorruption).isTrue()

        vault.wipeAll()
        assertThat(storageFile.exists()).isFalse()
        assertThat(vault.getAll()).isEmpty()
    }
}

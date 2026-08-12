package com.transdot.transferassistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredSession(
    val serverAddress: String,
    val deviceId: String,
    val masterToken: String,
)

interface SessionStore {
    fun load(): StoredSession?
    fun prepare()
    fun save(session: ClaimedSession)
}

class SecureSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun prepare() {
        val plaintext = ByteArray(STORAGE_PROBE_BYTES).also(SecureRandom()::nextBytes)
        val encryptCipher = Cipher.getInstance(TRANSFORMATION)
        encryptCipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = encryptCipher.doFinal(plaintext)

        val decryptCipher = Cipher.getInstance(TRANSFORMATION)
        decryptCipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, encryptCipher.iv),
        )
        check(decryptCipher.doFinal(encrypted).contentEquals(plaintext)) {
            "Android Keystore self-check failed."
        }

        val probeSaved = preferences.edit().putBoolean(KEY_STORAGE_PROBE, true).commit()
        check(probeSaved) {
            "App-private storage is not writable."
        }
        val probeRemoved = preferences.edit().remove(KEY_STORAGE_PROBE).commit()
        check(probeRemoved) {
            "App-private storage probe could not be removed."
        }
    }

    @Synchronized
    override fun load(): StoredSession? {
        val serverAddress = preferences.getString(KEY_SERVER_ADDRESS, null) ?: return null
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return null
        val encrypted = preferences.getString(KEY_MASTER_TOKEN, null) ?: return null
        val iv = preferences.getString(KEY_MASTER_TOKEN_IV, null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            StoredSession(serverAddress, deviceId, String(plaintext, StandardCharsets.UTF_8))
        }.getOrElse {
            clearStoredValues()
            null
        }
    }

    @Synchronized
    override fun save(session: ClaimedSession) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(session.masterToken.toByteArray(StandardCharsets.UTF_8))

        val saved = preferences.edit()
            .putString(KEY_SERVER_ADDRESS, session.serverAddress)
            .putString(KEY_DEVICE_ID, session.deviceId)
            .putString(KEY_MASTER_TOKEN, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_MASTER_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        check(saved) { "Unable to persist the Android master session." }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private fun clearStoredValues() {
        preferences.edit {
            remove(KEY_SERVER_ADDRESS)
            remove(KEY_DEVICE_ID)
            remove(KEY_MASTER_TOKEN)
            remove(KEY_MASTER_TOKEN_IV)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "secure_master_session"
        const val KEY_ALIAS = "transfer_assistant_master_token_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val STORAGE_PROBE_BYTES = 32

        const val KEY_SERVER_ADDRESS = "server_address"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_MASTER_TOKEN = "master_token_ciphertext"
        const val KEY_MASTER_TOKEN_IV = "master_token_iv"
        const val KEY_STORAGE_PROBE = "storage_probe"
    }
}

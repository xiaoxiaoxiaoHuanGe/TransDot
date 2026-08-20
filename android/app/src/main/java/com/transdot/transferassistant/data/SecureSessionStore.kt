package com.transdot.transferassistant.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.UUID

data class StoredSession(
    val serverAddress: String,
    val deviceId: String,
    val masterToken: String,
    val profileId: String = "",
    val profileName: String = "",
    val instanceId: String = "",
    val instanceFingerprint: String = "",
)

interface SessionStore {
    fun load(): StoredSession?
    fun prepare()
    fun save(session: ClaimedSession)
    fun profiles(): List<ServerProfileSummary> = load()?.let {
        listOf(ServerProfileSummary(it.profileId, it.profileName.ifBlank { defaultProfileName(it.serverAddress) }, it.serverAddress))
    }.orEmpty()
    fun activeProfileId(): String? = load()?.profileId
    fun loadProfile(profileId: String): StoredSession? = load()?.takeIf { it.profileId == profileId }
    fun switchProfile(profileId: String): Boolean = false
    fun renameProfile(profileId: String, name: String): Boolean = false
    fun deleteProfile(profileId: String): Boolean = false
    fun updateInstance(profileId: String, instanceId: String): Boolean = false
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
        val profiles = loadStoredProfiles()
        if (profiles.isEmpty()) return null
        val activeId = preferences.getString(KEY_ACTIVE_PROFILE_ID, null)
        val profile = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
        if (activeId != profile.id) preferences.edit().putString(KEY_ACTIVE_PROFILE_ID, profile.id).apply()
        return decrypt(profile).getOrElse {
            if (deleteProfile(profile.id)) load() else null
        }
    }

    @Synchronized
    override fun save(session: ClaimedSession) {
        val profiles = loadStoredProfiles().toMutableList()
        val existing = profiles.firstOrNull {
            it.serverAddress == session.serverAddress && it.deviceId == session.deviceId && it.instanceId == session.instanceId
        }
        val encrypted = encryptToken(session.masterToken)
        val profile = StoredProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = existing?.name ?: defaultProfileName(session.serverAddress),
            serverAddress = session.serverAddress,
            deviceId = session.deviceId,
            encryptedToken = encrypted.first,
            tokenIv = encrypted.second,
            instanceId = session.instanceId,
            instanceFingerprint = session.instanceFingerprint,
        )
        if (existing == null) profiles += profile else profiles[profiles.indexOf(existing)] = profile
        check(writeProfiles(profiles, profile.id)) { "Unable to persist the Android master session." }
        clearLegacyValues()
    }

    @Synchronized
    override fun profiles(): List<ServerProfileSummary> = loadStoredProfiles().map {
        ServerProfileSummary(it.id, it.name, it.serverAddress, it.instanceId)
    }

    @Synchronized
    override fun activeProfileId(): String? = preferences.getString(KEY_ACTIVE_PROFILE_ID, null)

    @Synchronized
    override fun loadProfile(profileId: String): StoredSession? =
        loadStoredProfiles().firstOrNull { it.id == profileId }?.let { decrypt(it).getOrNull() }

    @Synchronized
    override fun switchProfile(profileId: String): Boolean {
        if (loadStoredProfiles().none { it.id == profileId }) return false
        return preferences.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).commit()
    }

    @Synchronized
    override fun renameProfile(profileId: String, name: String): Boolean {
        val normalizedName = name.trim().takeIf(String::isNotBlank) ?: return false
        val profiles = loadStoredProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index < 0) return false
        profiles[index] = profiles[index].copy(name = normalizedName)
        return writeProfiles(profiles, activeProfileId())
    }

    @Synchronized
    override fun deleteProfile(profileId: String): Boolean {
        val stored = loadStoredProfiles()
        if (stored.none { it.id == profileId }) return false
        val summaries = stored.map { ServerProfileSummary(it.id, it.name, it.serverAddress) }
        val removal = removeProfile(summaries, activeProfileId(), profileId)
        val remaining = stored.filterNot { it.id == profileId }
        return writeProfiles(remaining, removal.activeProfileId)
    }

    @Synchronized
    override fun updateInstance(profileId: String, instanceId: String): Boolean {
        if (instanceId.isBlank()) return false
        val profiles = loadStoredProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index < 0) return false
        profiles[index] = profiles[index].copy(instanceId = instanceId)
        return writeProfiles(profiles, activeProfileId())
    }

    private fun encryptToken(token: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    private fun decrypt(profile: StoredProfile): Result<StoredSession> = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(profile.tokenIv, Base64.NO_WRAP)),
        )
        val plaintext = cipher.doFinal(Base64.decode(profile.encryptedToken, Base64.NO_WRAP))
        StoredSession(
            profile.serverAddress,
            profile.deviceId,
            String(plaintext, StandardCharsets.UTF_8),
            profile.id,
            profile.name,
            profile.instanceId,
            profile.instanceFingerprint,
        )
    }

    private fun loadStoredProfiles(): List<StoredProfile> {
        preferences.getString(KEY_PROFILES_JSON, null)?.let { encoded ->
            return runCatching {
                val array = JSONArray(encoded)
                buildList {
                    repeat(array.length()) { index ->
                        val value = array.getJSONObject(index)
                        add(StoredProfile(
                            value.getString("id"), value.getString("name"), value.getString("server_address"),
                            value.getString("device_id"), value.getString("master_token_ciphertext"), value.getString("master_token_iv"),
                            value.optString("instance_id"), value.optString("instance_fingerprint"),
                        ))
                    }
                }
            }.getOrElse { emptyList() }
        }
        return migrateLegacyProfile()
    }

    private fun migrateLegacyProfile(): List<StoredProfile> {
        val serverAddress = preferences.getString(KEY_SERVER_ADDRESS, null) ?: return emptyList()
        val deviceId = preferences.getString(KEY_DEVICE_ID, null) ?: return emptyList()
        val encrypted = preferences.getString(KEY_MASTER_TOKEN, null) ?: return emptyList()
        val iv = preferences.getString(KEY_MASTER_TOKEN_IV, null) ?: return emptyList()
        val profile = StoredProfile(
            UUID.randomUUID().toString(), defaultProfileName(serverAddress), serverAddress, deviceId, encrypted, iv, "", "",
        )
        if (writeProfiles(listOf(profile), profile.id)) clearLegacyValues()
        return listOf(profile)
    }

    private fun writeProfiles(profiles: List<StoredProfile>, activeId: String?): Boolean {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("server_address", profile.serverAddress)
                .put("device_id", profile.deviceId)
                .put("master_token_ciphertext", profile.encryptedToken)
                .put("master_token_iv", profile.tokenIv)
                .put("instance_id", profile.instanceId)
                .put("instance_fingerprint", profile.instanceFingerprint))
        }
        val editor = preferences.edit().putString(KEY_PROFILES_JSON, array.toString())
        if (activeId == null) editor.remove(KEY_ACTIVE_PROFILE_ID) else editor.putString(KEY_ACTIVE_PROFILE_ID, activeId)
        return editor.commit()
    }

    private data class StoredProfile(
        val id: String,
        val name: String,
        val serverAddress: String,
        val deviceId: String,
        val encryptedToken: String,
        val tokenIv: String,
        val instanceId: String,
        val instanceFingerprint: String,
    )

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

    private fun clearLegacyValues() {
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
        const val KEY_PROFILES_JSON = "server_profiles_v2"
        const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    }
}

package com.e2eechat.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Result object containing the Signal Protocol key material for a user.
 */
data class KeyGenerationResult(
    val registrationId: Int,
    val identityKeyPair: IdentityKeyPair,
    val signedPreKeyRecord: SignedPreKeyRecord,
    val preKeyRecords: List<PreKeyRecord>,
    val isNewGeneration: Boolean
)

/**
 * Manages Signal Protocol cryptographic key generation and secure persistence.
 *
 * Uses the Android Keystore (via Jetpack Security's [EncryptedSharedPreferences])
 * to guarantee that private key material is encrypted at rest using hardware-backed
 * keys and never stored in plain preferences or databases.
 */
class KeyManager(private val context: Context) {

    companion object {
        private const val SECURE_PREFS_NAME = "secure_e2ee_keys"
        const val DEFAULT_PREKEY_COUNT = 100

        private fun userKey(email: String, key: String): String =
            "${email.trim().lowercase()}::$key"
    }

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val securePreferences: SharedPreferences by lazy {
        createSecurePreferences()
    }

    private fun createSecurePreferences(): SharedPreferences {
        return try {
            EncryptedSharedPreferences.create(
                context.applicationContext,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Handle Keystore inconsistency recovery
            try {
                context.applicationContext.deleteSharedPreferences(SECURE_PREFS_NAME)
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (fatal: Exception) {
                throw IllegalStateException("Failed to initialize Android Keystore-backed encrypted storage", fatal)
            }
        }
    }

    /**
     * Checks whether Signal keys have already been generated for the given user.
     */
    fun hasKeysForUser(userEmail: String): Boolean {
        val email = userEmail.trim().lowercase()
        if (email.isEmpty()) return false
        return securePreferences.getBoolean(userKey(email, "has_keys"), false)
    }

    /**
     * Ensures that Signal keys (IdentityKeyPair, registration ID, one-time prekeys, and signed prekey)
     * exist for the specified user.
     *
     * If keys already exist, the existing key material is returned without regeneration.
     * If keys do not exist (first login), they are generated and stored securely in Android Keystore.
     */
    suspend fun ensureKeysForUser(
        userEmail: String,
        preKeyCount: Int = DEFAULT_PREKEY_COUNT
    ): KeyGenerationResult = withContext(Dispatchers.IO) {
        val email = userEmail.trim().lowercase()
        require(email.isNotEmpty()) { "User email cannot be blank" }

        if (hasKeysForUser(email)) {
            val existing = loadKeysForUser(email)
            if (existing != null) {
                return@withContext existing.copy(isNewGeneration = false)
            }
        }

        generateAndStoreKeys(email, preKeyCount)
    }

    /**
     * Generates a complete set of Signal keys and persists them encrypted with Android Keystore.
     */
    private fun generateAndStoreKeys(email: String, preKeyCount: Int): KeyGenerationResult {
        // 1. Generate identity key pair and registration ID
        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)

        // 2. Generate batch of one-time prekeys (e.g. 1..100)
        val preKeyRecords = (1..preKeyCount).map { id ->
            PreKeyRecord(id, ECKeyPair.generate())
        }

        // 3. Generate signed prekey
        val signedPreKeyId = 1
        val signedPreKeyPair = ECKeyPair.generate()
        val timestamp = System.currentTimeMillis()
        val signature = identityKeyPair.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize())
        val signedPreKeyRecord = SignedPreKeyRecord(
            signedPreKeyId,
            timestamp,
            signedPreKeyPair,
            signature
        )

        // 4. Securely store all private & public key material in EncryptedSharedPreferences
        securePreferences.edit().apply {
            putBoolean(userKey(email, "has_keys"), true)
            putInt(userKey(email, "registration_id"), registrationId)
            putString(
                userKey(email, "identity_key_pair"),
                Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP)
            )
            putInt(userKey(email, "signed_prekey_id"), signedPreKeyId)
            putLong(userKey(email, "signed_prekey_timestamp"), timestamp)
            putString(
                userKey(email, "signed_prekey_record"),
                Base64.encodeToString(signedPreKeyRecord.serialize(), Base64.NO_WRAP)
            )
            putInt(userKey(email, "prekey_count"), preKeyRecords.size)
            preKeyRecords.forEach { preKey ->
                putString(
                    userKey(email, "prekey_${preKey.id}"),
                    Base64.encodeToString(preKey.serialize(), Base64.NO_WRAP)
                )
            }
            apply()
        }

        return KeyGenerationResult(
            registrationId = registrationId,
            identityKeyPair = identityKeyPair,
            signedPreKeyRecord = signedPreKeyRecord,
            preKeyRecords = preKeyRecords,
            isNewGeneration = true
        )
    }

    /**
     * Loads all stored Signal keys for the given user from secure storage.
     */
    fun loadKeysForUser(userEmail: String): KeyGenerationResult? {
        val email = userEmail.trim().lowercase()
        if (!hasKeysForUser(email)) return null

        return try {
            val regId = securePreferences.getInt(userKey(email, "registration_id"), -1)
            if (regId == -1) return null

            val identityKeyBase64 = securePreferences.getString(userKey(email, "identity_key_pair"), null) ?: return null
            val identityKeyPair = IdentityKeyPair(Base64.decode(identityKeyBase64, Base64.NO_WRAP))

            val signedPreKeyBase64 = securePreferences.getString(userKey(email, "signed_prekey_record"), null) ?: return null
            val signedPreKeyRecord = SignedPreKeyRecord(Base64.decode(signedPreKeyBase64, Base64.NO_WRAP))

            val preKeyCount = securePreferences.getInt(userKey(email, "prekey_count"), 0)
            val preKeys = ArrayList<PreKeyRecord>(preKeyCount)
            for (i in 1..preKeyCount) {
                val preKeyBase64 = securePreferences.getString(userKey(email, "prekey_$i"), null) ?: continue
                preKeys.add(PreKeyRecord(Base64.decode(preKeyBase64, Base64.NO_WRAP)))
            }

            KeyGenerationResult(
                registrationId = regId,
                identityKeyPair = identityKeyPair,
                signedPreKeyRecord = signedPreKeyRecord,
                preKeyRecords = preKeys,
                isNewGeneration = false
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Retrieves the IdentityKeyPair for a user.
     */
    fun getIdentityKeyPair(userEmail: String): IdentityKeyPair? {
        val email = userEmail.trim().lowercase()
        val base64 = securePreferences.getString(userKey(email, "identity_key_pair"), null) ?: return null
        return try {
            IdentityKeyPair(Base64.decode(base64, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Retrieves the registration ID for a user.
     */
    fun getRegistrationId(userEmail: String): Int? {
        val email = userEmail.trim().lowercase()
        val id = securePreferences.getInt(userKey(email, "registration_id"), -1)
        return if (id != -1) id else null
    }

    /**
     * Retrieves the SignedPreKeyRecord for a user.
     */
    fun getSignedPreKeyRecord(userEmail: String): SignedPreKeyRecord? {
        val email = userEmail.trim().lowercase()
        val base64 = securePreferences.getString(userKey(email, "signed_prekey_record"), null) ?: return null
        return try {
            SignedPreKeyRecord(Base64.decode(base64, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Retrieves a specific one-time PreKeyRecord by ID for a user.
     */
    fun getPreKeyRecord(userEmail: String, preKeyId: Int): PreKeyRecord? {
        val email = userEmail.trim().lowercase()
        val base64 = securePreferences.getString(userKey(email, "prekey_$preKeyId"), null) ?: return null
        return try {
            PreKeyRecord(Base64.decode(base64, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clears stored key material for a user (e.g. on full account deletion).
     */
    fun clearKeysForUser(userEmail: String) {
        val email = userEmail.trim().lowercase()
        val preKeyCount = securePreferences.getInt(userKey(email, "prekey_count"), 0)
        securePreferences.edit().apply {
            remove(userKey(email, "has_keys"))
            remove(userKey(email, "registration_id"))
            remove(userKey(email, "identity_key_pair"))
            remove(userKey(email, "signed_prekey_id"))
            remove(userKey(email, "signed_prekey_timestamp"))
            remove(userKey(email, "signed_prekey_record"))
            remove(userKey(email, "prekey_count"))
            for (i in 1..preKeyCount) {
                remove(userKey(email, "prekey_$i"))
            }
            apply()
        }
    }
}

package com.e2eechat.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Result object containing the complete Signal Protocol key material for a user.
 */
data class KeyGenerationResult(
    val registrationId: Int,
    val identityKeyPair: IdentityKeyPair,
    val signedPreKeyRecord: SignedPreKeyRecord,
    val kyberPreKeyRecord: KyberPreKeyRecord,
    val preKeyRecords: List<PreKeyRecord>,
    val isNewGeneration: Boolean
)

/**
 * Public bundle representing what is published to Firestore and fetched by other clients.
 */
data class PublishedPreKeyBundle(
    val registrationId: Int,
    val deviceId: Int = 1,
    val identityKey: IdentityKey,
    val signedPreKeyId: Int,
    val signedPreKey: ECPublicKey,
    val signedPreKeySignature: ByteArray,
    val kyberPreKeyId: Int,
    val kyberPreKey: KEMPublicKey,
    val kyberPreKeySignature: ByteArray,
    val oneTimePreKeyId: Int?,
    val oneTimePreKey: ECPublicKey?
) {
    fun toSignalPreKeyBundle(): PreKeyBundle {
        return PreKeyBundle(
            registrationId,
            deviceId,
            oneTimePreKeyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
            oneTimePreKey,
            signedPreKeyId,
            signedPreKey,
            signedPreKeySignature,
            identityKey,
            kyberPreKeyId,
            kyberPreKey,
            kyberPreKeySignature
        )
    }
}

/**
 * Manages Signal Protocol cryptographic key generation, secure persistence in Android Keystore,
 * and publication of public key material to Firestore.
 */
class KeyManager(private val context: Context) {

    companion object {
        private const val TAG = "KeyManager"
        private const val SECURE_PREFS_NAME = "secure_e2ee_keys"
        const val DEFAULT_PREKEY_COUNT = 100
        const val DEVICE_ID = 1

        private fun userKey(emailOrUid: String, key: String): String =
            "${emailOrUid.trim().lowercase()}::$key"
    }

    private val firestore = FirebaseFirestore.getInstance()

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
    fun hasKeysForUser(userIdentifier: String): Boolean {
        val id = userIdentifier.trim().lowercase()
        if (id.isEmpty()) return false
        return securePreferences.getBoolean(userKey(id, "has_keys"), false)
    }

    /**
     * Ensures that Signal keys (IdentityKeyPair, registration ID, one-time prekeys, signed prekey,
     * and Kyber prekey for PQXDH) exist locally.
     *
     * Idempotent: Never regenerates identity keys if they already exist.
     * Synchronizes and preserves existing keys if user was previously identified by email.
     */
    suspend fun ensureKeysForUser(
        userIdentifier: String,
        fallbackIdentifier: String? = null,
        preKeyCount: Int = DEFAULT_PREKEY_COUNT
    ): KeyGenerationResult = withContext(Dispatchers.IO) {
        val id = userIdentifier.trim().lowercase()
        val fallback = fallbackIdentifier?.trim()?.lowercase().orEmpty()
        require(id.isNotEmpty()) { "User identifier cannot be blank" }

        // Migration and synchronization between UID and email:
        // 1. If 'id' has no keys, but 'fallback' has keys, copy fallback keys to 'id'.
        if (!hasKeysForUser(id) && fallback.isNotEmpty() && hasKeysForUser(fallback)) {
            val fallbackKeys = loadKeysForUser(fallback)
            if (fallbackKeys != null) {
                copyKeys(fallback, id, fallbackKeys)
            }
        }
        // 2. If both exist, but fallback is an email (originally published to Firestore)
        // and id is a UID, copy fallback keys to id to guarantee local store matches Firestore.
        else if (hasKeysForUser(id) && fallback.isNotEmpty() && hasKeysForUser(fallback)) {
            if (fallback.contains("@") && !id.contains("@")) {
                val emailKeys = loadKeysForUser(fallback)
                if (emailKeys != null) {
                    copyKeys(fallback, id, emailKeys)
                }
            }
        }

        if (hasKeysForUser(id)) {
            val existing = loadKeysForUser(id)
            if (existing != null) {
                // If existing key set was missing Kyber prekey (legacy format), generate one now
                val verifiedResult = if (getKyberPreKeyRecord(id, 1) == null) {
                    val kyberKey = generateAndStoreKyberPreKey(id, existing.identityKeyPair, 1)
                    existing.copy(kyberPreKeyRecord = kyberKey, isNewGeneration = false)
                } else {
                    existing
                }
                return@withContext verifiedResult.copy(isNewGeneration = false)
            }
        }

        generateAndStoreKeys(id, preKeyCount)
    }

    private fun copyKeys(sourceId: String, destId: String, sourceKeys: KeyGenerationResult) {
        val s = sourceId.trim().lowercase()
        val d = destId.trim().lowercase()
        if (s.isEmpty() || d.isEmpty() || s == d) return

        securePreferences.edit().apply {
            putBoolean(userKey(d, "has_keys"), true)
            putInt(userKey(d, "registration_id"), sourceKeys.registrationId)
            putString(
                userKey(d, "identity_key_pair"),
                Base64.encodeToString(sourceKeys.identityKeyPair.serialize(), Base64.NO_WRAP)
            )
            putInt(userKey(d, "signed_prekey_id"), sourceKeys.signedPreKeyRecord.id)
            putLong(userKey(d, "signed_prekey_timestamp"), sourceKeys.signedPreKeyRecord.timestamp)
            putString(
                userKey(d, "signed_prekey_record"),
                Base64.encodeToString(sourceKeys.signedPreKeyRecord.serialize(), Base64.NO_WRAP)
            )
            putInt(userKey(d, "kyber_prekey_id"), sourceKeys.kyberPreKeyRecord.id)
            putLong(userKey(d, "kyber_prekey_timestamp"), sourceKeys.kyberPreKeyRecord.timestamp)
            putString(
                userKey(d, "kyber_prekey_record"),
                Base64.encodeToString(sourceKeys.kyberPreKeyRecord.serialize(), Base64.NO_WRAP)
            )
            putInt(userKey(d, "prekey_count"), sourceKeys.preKeyRecords.size)
            sourceKeys.preKeyRecords.forEach { preKey ->
                putString(
                    userKey(d, "prekey_${preKey.id}"),
                    Base64.encodeToString(preKey.serialize(), Base64.NO_WRAP)
                )
            }
            apply()
        }
    }

    /**
     * Generates a complete set of Signal keys including Kyber-1024 for PQXDH,
     * and persists them in Android Keystore.
     */
    private fun generateAndStoreKeys(id: String, preKeyCount: Int): KeyGenerationResult {
        // 1. Identity key pair and registration ID
        val identityKeyPair = IdentityKeyPair.generate()
        val registrationId = KeyHelper.generateRegistrationId(false)

        // 2. Batch of one-time EC prekeys (1..preKeyCount)
        val preKeyRecords = (1..preKeyCount).map { preKeyId ->
            PreKeyRecord(preKeyId, ECKeyPair.generate())
        }

        // 3. Signed prekey
        val signedPreKeyId = 1
        val signedPreKeyPair = ECKeyPair.generate()
        val signedTimestamp = System.currentTimeMillis()
        val signedSignature = identityKeyPair.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize())
        val signedPreKeyRecord = SignedPreKeyRecord(
            signedPreKeyId,
            signedTimestamp,
            signedPreKeyPair,
            signedSignature
        )

        // 4. Kyber-1024 prekey for PQXDH
        val kyberPreKeyId = 1
        val kyberTimestamp = System.currentTimeMillis()
        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identityKeyPair.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())
        val kyberPreKeyRecord = KyberPreKeyRecord(
            kyberPreKeyId,
            kyberTimestamp,
            kyberKeyPair,
            kyberSignature
        )

        // 5. Persist to EncryptedSharedPreferences
        securePreferences.edit().apply {
            putBoolean(userKey(id, "has_keys"), true)
            putInt(userKey(id, "registration_id"), registrationId)
            putString(
                userKey(id, "identity_key_pair"),
                Base64.encodeToString(identityKeyPair.serialize(), Base64.NO_WRAP)
            )

            // Signed Prekey
            putInt(userKey(id, "signed_prekey_id"), signedPreKeyId)
            putLong(userKey(id, "signed_prekey_timestamp"), signedTimestamp)
            putString(
                userKey(id, "signed_prekey_record"),
                Base64.encodeToString(signedPreKeyRecord.serialize(), Base64.NO_WRAP)
            )

            // Kyber Prekey
            putInt(userKey(id, "kyber_prekey_id"), kyberPreKeyId)
            putLong(userKey(id, "kyber_prekey_timestamp"), kyberTimestamp)
            putString(
                userKey(id, "kyber_prekey_record"),
                Base64.encodeToString(kyberPreKeyRecord.serialize(), Base64.NO_WRAP)
            )

            // One-Time Prekeys
            putInt(userKey(id, "prekey_count"), preKeyRecords.size)
            preKeyRecords.forEach { preKey ->
                putString(
                    userKey(id, "prekey_${preKey.id}"),
                    Base64.encodeToString(preKey.serialize(), Base64.NO_WRAP)
                )
            }
            apply()
        }

        return KeyGenerationResult(
            registrationId = registrationId,
            identityKeyPair = identityKeyPair,
            signedPreKeyRecord = signedPreKeyRecord,
            kyberPreKeyRecord = kyberPreKeyRecord,
            preKeyRecords = preKeyRecords,
            isNewGeneration = true
        )
    }

    private fun generateAndStoreKyberPreKey(
        id: String,
        identityKeyPair: IdentityKeyPair,
        kyberPreKeyId: Int
    ): KyberPreKeyRecord {
        val kyberTimestamp = System.currentTimeMillis()
        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identityKeyPair.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())
        val record = KyberPreKeyRecord(
            kyberPreKeyId,
            kyberTimestamp,
            kyberKeyPair,
            kyberSignature
        )
        securePreferences.edit().apply {
            putInt(userKey(id, "kyber_prekey_id"), kyberPreKeyId)
            putLong(userKey(id, "kyber_prekey_timestamp"), kyberTimestamp)
            putString(
                userKey(id, "kyber_prekey_record"),
                Base64.encodeToString(record.serialize(), Base64.NO_WRAP)
            )
            apply()
        }
        return record
    }

    /**
     * Loads all stored Signal keys for the given user from secure storage.
     */
    fun loadKeysForUser(userIdentifier: String): KeyGenerationResult? {
        val id = userIdentifier.trim().lowercase()
        if (!hasKeysForUser(id)) return null

        return try {
            val regId = securePreferences.getInt(userKey(id, "registration_id"), -1)
            if (regId == -1) return null

            val identityKeyBase64 = securePreferences.getString(userKey(id, "identity_key_pair"), null) ?: return null
            val identityKeyPair = IdentityKeyPair(Base64.decode(identityKeyBase64, Base64.NO_WRAP))

            val signedPreKeyBase64 = securePreferences.getString(userKey(id, "signed_prekey_record"), null) ?: return null
            val signedPreKeyRecord = SignedPreKeyRecord(Base64.decode(signedPreKeyBase64, Base64.NO_WRAP))

            val kyberPreKeyRecord = getKyberPreKeyRecord(id, 1) ?: generateAndStoreKyberPreKey(id, identityKeyPair, 1)

            val preKeyCount = securePreferences.getInt(userKey(id, "prekey_count"), 0)
            val preKeys = ArrayList<PreKeyRecord>(preKeyCount)
            for (i in 1..preKeyCount) {
                val preKeyBase64 = securePreferences.getString(userKey(id, "prekey_$i"), null) ?: continue
                preKeys.add(PreKeyRecord(Base64.decode(preKeyBase64, Base64.NO_WRAP)))
            }

            KeyGenerationResult(
                registrationId = regId,
                identityKeyPair = identityKeyPair,
                signedPreKeyRecord = signedPreKeyRecord,
                kyberPreKeyRecord = kyberPreKeyRecord,
                preKeyRecords = preKeys,
                isNewGeneration = false
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error loading keys for user $id", e)
            null
        }
    }

    /**
     * Obtains a fully initialized [SignalProtocolStore] backed by Android Keystore for this user.
     */
    suspend fun getProtocolStore(
        userIdentifier: String,
        fallbackIdentifier: String? = null
    ): SignalProtocolStore = withContext(Dispatchers.IO) {
        val id = userIdentifier.trim().lowercase()
        val keys = ensureKeysForUser(id, fallbackIdentifier = fallbackIdentifier)

        val store = KeystoreSignalProtocolStore(
            context = context,
            userIdentifier = id,
            identityKeyPair = keys.identityKeyPair,
            localRegistrationId = keys.registrationId
        )

        // Ensure store has local keys populated and synchronized with published key material
        store.storeSignedPreKey(keys.signedPreKeyRecord.id, keys.signedPreKeyRecord)
        store.storeKyberPreKey(keys.kyberPreKeyRecord.id, keys.kyberPreKeyRecord)
        keys.preKeyRecords.forEach { preKey ->
            store.storePreKey(preKey.id, preKey)
        }

        store
    }

    /**
     * Publishes ONLY public key material to Firestore under `users/{uid}/prekey_bundle`.
     *
     * Private keys NEVER leave Android Keystore / local device.
     */
    suspend fun publishPublicPreKeyBundle(
        uid: String,
        keys: KeyGenerationResult
    ): Unit = withContext(Dispatchers.IO) {
        require(uid.isNotBlank()) { "UID cannot be blank" }

        val oneTimePreKeyMap = keys.preKeyRecords.associate { preKey ->
            preKey.id.toString() to Base64.encodeToString(preKey.keyPair.publicKey.serialize(), Base64.NO_WRAP)
        }

        val bundleData = hashMapOf(
            "registrationId" to keys.registrationId,
            "deviceId" to DEVICE_ID,
            "identityKey" to Base64.encodeToString(keys.identityKeyPair.publicKey.serialize(), Base64.NO_WRAP),
            "signedPreKeyId" to keys.signedPreKeyRecord.id,
            "signedPreKey" to Base64.encodeToString(keys.signedPreKeyRecord.keyPair.publicKey.serialize(), Base64.NO_WRAP),
            "signedPreKeySignature" to Base64.encodeToString(keys.signedPreKeyRecord.signature, Base64.NO_WRAP),
            "signedPreKeyTimestamp" to keys.signedPreKeyRecord.timestamp,
            "kyberPreKeyId" to keys.kyberPreKeyRecord.id,
            "kyberPreKey" to Base64.encodeToString(keys.kyberPreKeyRecord.keyPair.publicKey.serialize(), Base64.NO_WRAP),
            "kyberPreKeySignature" to Base64.encodeToString(keys.kyberPreKeyRecord.signature, Base64.NO_WRAP),
            "kyberPreKeyTimestamp" to keys.kyberPreKeyRecord.timestamp,
            "oneTimePreKeys" to oneTimePreKeyMap,
            "updatedAt" to System.currentTimeMillis()
        )

        firestore.collection("users").document(uid)
            .collection("prekey_bundle").document("current")
            .set(bundleData, SetOptions.merge())
            .await()
    }

    /**
     * Atomically consumes one one-time prekey from the target user's prekey bundle in Firestore.
     *
     * Uses a Firestore transaction to ensure atomic removal, preventing race conditions
     * between multiple clients attempting to claim the same prekey.
     *
     * Falls back safely to a bundle without a one-time prekey if all one-time prekeys are exhausted.
     */
    suspend fun fetchAndConsumePreKeyBundle(
        targetUid: String
    ): PublishedPreKeyBundle = withContext(Dispatchers.IO) {
        require(targetUid.isNotBlank()) { "Target UID cannot be blank" }

        val bundleRef = firestore.collection("users").document(targetUid)
            .collection("prekey_bundle").document("current")

        firestore.runTransaction { transaction ->
            val snapshot = transaction.get(bundleRef)
            if (!snapshot.exists()) {
                throw IllegalStateException("User $targetUid has not published Signal prekeys")
            }

            val regId = snapshot.getLong("registrationId")?.toInt()
                ?: throw IllegalStateException("Missing registrationId")
            val deviceId = snapshot.getLong("deviceId")?.toInt() ?: DEVICE_ID

            val identityKeyBase64 = snapshot.getString("identityKey")
                ?: throw IllegalStateException("Missing identityKey")
            val identityKey = IdentityKey(Base64.decode(identityKeyBase64, Base64.NO_WRAP))

            val signedPreKeyId = snapshot.getLong("signedPreKeyId")?.toInt()
                ?: throw IllegalStateException("Missing signedPreKeyId")
            val signedPreKeyBase64 = snapshot.getString("signedPreKey")
                ?: throw IllegalStateException("Missing signedPreKey")
            val signedPreKey = ECPublicKey(Base64.decode(signedPreKeyBase64, Base64.NO_WRAP))

            val signedSigBase64 = snapshot.getString("signedPreKeySignature")
                ?: throw IllegalStateException("Missing signedPreKeySignature")
            val signedPreKeySignature = Base64.decode(signedSigBase64, Base64.NO_WRAP)

            val kyberPreKeyId = snapshot.getLong("kyberPreKeyId")?.toInt()
                ?: throw IllegalStateException("Missing kyberPreKeyId")
            val kyberPreKeyBase64 = snapshot.getString("kyberPreKey")
                ?: throw IllegalStateException("Missing kyberPreKey")
            val kyberPreKey = KEMPublicKey(Base64.decode(kyberPreKeyBase64, Base64.NO_WRAP))

            val kyberSigBase64 = snapshot.getString("kyberPreKeySignature")
                ?: throw IllegalStateException("Missing kyberPreKeySignature")
            val kyberPreKeySignature = Base64.decode(kyberSigBase64, Base64.NO_WRAP)

            // Select and remove one one-time prekey atomically
            val preKeysMap = snapshot.get("oneTimePreKeys") as? Map<*, *>
            var selectedPreKeyId: Int? = null
            var selectedPreKey: ECPublicKey? = null

            if (!preKeysMap.isNullOrEmpty()) {
                val firstEntry = preKeysMap.entries.firstOrNull()
                if (firstEntry != null) {
                    val keyIdStr = firstEntry.key.toString()
                    val keyId = keyIdStr.toIntOrNull()
                    val keyBase64 = firstEntry.value as? String
                    if (keyId != null && keyBase64 != null) {
                        selectedPreKeyId = keyId
                        selectedPreKey = ECPublicKey(Base64.decode(keyBase64, Base64.NO_WRAP))

                        // Remove this key atomically from Firestore inside transaction
                        transaction.update(
                            bundleRef,
                            "oneTimePreKeys.$keyIdStr",
                            com.google.firebase.firestore.FieldValue.delete()
                        )
                    }
                }
            }

            PublishedPreKeyBundle(
                registrationId = regId,
                deviceId = deviceId,
                identityKey = identityKey,
                signedPreKeyId = signedPreKeyId,
                signedPreKey = signedPreKey,
                signedPreKeySignature = signedPreKeySignature,
                kyberPreKeyId = kyberPreKeyId,
                kyberPreKey = kyberPreKey,
                kyberPreKeySignature = kyberPreKeySignature,
                oneTimePreKeyId = selectedPreKeyId,
                oneTimePreKey = selectedPreKey
            )
        }.await()
    }

    fun getKyberPreKeyRecord(userIdentifier: String, kyberPreKeyId: Int): KyberPreKeyRecord? {
        val id = userIdentifier.trim().lowercase()
        val base64 = securePreferences.getString(userKey(id, "kyber_prekey_record"), null) ?: return null
        return try {
            val record = KyberPreKeyRecord(Base64.decode(base64, Base64.NO_WRAP))
            if (record.id == kyberPreKeyId) record else null
        } catch (e: Exception) {
            null
        }
    }

    fun getIdentityKeyPair(userIdentifier: String): IdentityKeyPair? {
        val id = userIdentifier.trim().lowercase()
        val base64 = securePreferences.getString(userKey(id, "identity_key_pair"), null) ?: return null
        return try {
            IdentityKeyPair(Base64.decode(base64, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    fun getRegistrationId(userIdentifier: String): Int? {
        val id = userIdentifier.trim().lowercase()
        val regId = securePreferences.getInt(userKey(id, "registration_id"), -1)
        return if (regId != -1) regId else null
    }

    fun getSignedPreKeyRecord(userIdentifier: String): SignedPreKeyRecord? {
        val id = userIdentifier.trim().lowercase()
        val base64 = securePreferences.getString(userKey(id, "signed_prekey_record"), null) ?: return null
        return try {
            SignedPreKeyRecord(Base64.decode(base64, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    fun clearKeysForUser(userIdentifier: String) {
        val id = userIdentifier.trim().lowercase()
        val preKeyCount = securePreferences.getInt(userKey(id, "prekey_count"), 0)
        securePreferences.edit().apply {
            remove(userKey(id, "has_keys"))
            remove(userKey(id, "registration_id"))
            remove(userKey(id, "identity_key_pair"))
            remove(userKey(id, "signed_prekey_id"))
            remove(userKey(id, "signed_prekey_timestamp"))
            remove(userKey(id, "signed_prekey_record"))
            remove(userKey(id, "kyber_prekey_id"))
            remove(userKey(id, "kyber_prekey_timestamp"))
            remove(userKey(id, "kyber_prekey_record"))
            remove(userKey(id, "prekey_count"))
            for (i in 1..preKeyCount) {
                remove(userKey(id, "prekey_$i"))
            }
            apply()
        }
    }
}

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
 * Lightweight metadata of a published prekey bundle used for identity and freshness checks.
 */
data class RemoteBundleMetadata(
    val registrationId: Int,
    val deviceId: Int = 1,
    val identityKey: IdentityKey,
    val signedPreKeyId: Int,
    val updatedAt: Long = 0L
)

/**
 * Manages Signal Protocol cryptographic key generation, secure persistence in Android Keystore,
 * and publication of public key material to Firestore.
 */
class KeyManager(
    private val context: Context? = null,
    prefsOverride: SharedPreferences? = null,
    firestoreOverride: FirebaseFirestore? = null,
    private val storePrefsOverride: SharedPreferences? = null
) {

    companion object {
        private const val TAG = "KeyManager"
        private const val SECURE_PREFS_NAME = "secure_e2ee_keys"
        const val DEFAULT_PREKEY_COUNT = 100
        const val MIN_PREKEY_THRESHOLD = 20
        const val DEVICE_ID = 1

        private fun userKey(emailOrUid: String, key: String): String =
            "${emailOrUid.trim().lowercase()}::$key"
    }

    private val firestore: FirebaseFirestore by lazy {
        firestoreOverride ?: FirebaseFirestore.getInstance()
    }

    private val masterKey: MasterKey by lazy {
        val ctx = context ?: throw IllegalStateException("Context required when prefsOverride is null")
        MasterKey.Builder(ctx.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val securePreferences: SharedPreferences by lazy {
        prefsOverride ?: createSecurePreferences()
    }

    private fun createSecurePreferences(): SharedPreferences {
        val ctx = context ?: throw IllegalStateException("Context required when prefsOverride is null")
        return try {
            EncryptedSharedPreferences.create(
                ctx.applicationContext,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            SafeLog.w("TeleXE2EE", "Secure prefs error (${e.javaClass.simpleName}) - recreating, stored keys WIPED", e)
            try {
                ctx.applicationContext.deleteSharedPreferences(SECURE_PREFS_NAME)
                EncryptedSharedPreferences.create(
                    ctx.applicationContext,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (fatal: Exception) {
                SafeLog.e("TeleXE2EE", "Fatal secure prefs error (${fatal.javaClass.simpleName})", fatal)
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
     * Removes a single consumed one-time prekey from local storage.
     */
    fun removePreKey(userIdentifier: String, preKeyId: Int) {
        val id = userIdentifier.trim().lowercase()
        securePreferences.edit().remove(userKey(id, "prekey_$preKeyId")).apply()
        SafeLog.d("TeleXE2EE", "Removed local prekey $preKeyId for uid=$id")
    }

    /**
     * Validates that the identity key, registration ID, signed prekey signature,
     * and Kyber prekey signature are intact.
     * Does not require preKeyRecords to be non-empty (to allow replenishing consumed one-time keys).
     */
    fun validateIdentityAndSignatures(keys: KeyGenerationResult): Boolean {
        return try {
            if (keys.registrationId <= 0) return false
            val idPubKey = keys.identityKeyPair.publicKey.publicKey
            val signedValid = idPubKey.verifySignature(
                keys.signedPreKeyRecord.keyPair.publicKey.serialize(),
                keys.signedPreKeyRecord.signature
            )
            if (!signedValid) return false

            val kyberValid = idPubKey.verifySignature(
                keys.kyberPreKeyRecord.keyPair.publicKey.serialize(),
                keys.kyberPreKeyRecord.signature
            )
            kyberValid
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates that all cryptographic material and signatures are consistent, non-corrupt,
     * and at least one one-time prekey is present.
     */
    fun validateKeyMaterial(keys: KeyGenerationResult): Boolean {
        return validateIdentityAndSignatures(keys) && keys.preKeyRecords.isNotEmpty()
    }

    /**
     * Filters a list of one-time prekeys to ensure every prekey has a matching private record
     * stored in local secure storage. Stale, consumed, or corrupted prekeys are excluded.
     */
    fun filterVerifiedPreKeys(userIdentifier: String, preKeys: List<PreKeyRecord>): List<PreKeyRecord> {
        val id = userIdentifier.trim().lowercase()
        return preKeys.filter { preKey ->
            val storedBase64 = securePreferences.getString(userKey(id, "prekey_${preKey.id}"), null)
            if (storedBase64.isNullOrBlank()) {
                SafeLog.w("TeleXE2EE", "Excluding preKey ${preKey.id} for uid=$id: matching local private record absent")
                false
            } else {
                try {
                    val localRecord = PreKeyRecord(Base64Compat.decode(storedBase64))
                    val matches = localRecord.keyPair.publicKey == preKey.keyPair.publicKey
                    if (!matches) {
                        SafeLog.w("TeleXE2EE", "Excluding preKey ${preKey.id} for uid=$id: local public key mismatch")
                    }
                    matches
                } catch (e: Exception) {
                    SafeLog.w("TeleXE2EE", "Excluding preKey ${preKey.id} for uid=$id: corrupted local record", e)
                    false
                }
            }
        }
    }

    /**
     * Validates that publishing local keys to a remote bundle will not overwrite an existing
     * different identity key without explicit rotation authorization.
     */
    fun validateIdentityForPublication(
        remoteIdentityKeyBase64: String?,
        localIdentityKey: IdentityKey,
        allowIdentityRotation: Boolean = false
    ) {
        val localIdentityKeyBase64 = Base64Compat.encodeToString(localIdentityKey.serialize())
        if (!remoteIdentityKeyBase64.isNullOrBlank() && remoteIdentityKeyBase64 != localIdentityKeyBase64) {
            val remoteFp = try {
                identityFingerprint(IdentityKey(Base64Compat.decode(remoteIdentityKeyBase64)))
            } catch (_: Exception) { "unknown" }
            val localFp = identityFingerprint(localIdentityKey)

            if (!allowIdentityRotation) {
                SafeLog.e(
                    "TeleXE2EE",
                    "REFUSING to overwrite Firestore bundle: remote identity ($remoteFp) != local identity ($localFp). Explicit rotation flow required."
                )
                throw IllegalStateException(
                    "Cannot overwrite existing identity (remote fp=$remoteFp, local fp=$localFp) without deliberate rotation flow"
                )
            } else {
                SafeLog.w(
                    "TeleXE2EE",
                    "Deliberate identity rotation authorized: remote=$remoteFp -> local=$localFp"
                )
            }
        }
    }

    /**
     * Replenishes one-time prekeys locally if the count of valid prekeys is below [minThreshold].
     * Never touches or modifies IdentityKeyPair, registrationId, SignedPreKey, or KyberPreKey.
     * Generates fresh one-time prekeys with strictly increasing IDs, persists them in secure storage,
     * and returns the combined list of active prekeys.
     */
    fun replenishPreKeysIfNeeded(
        userIdentifier: String,
        existingPreKeys: List<PreKeyRecord>,
        minThreshold: Int = MIN_PREKEY_THRESHOLD,
        targetCount: Int = DEFAULT_PREKEY_COUNT
    ): List<PreKeyRecord> {
        val id = userIdentifier.trim().lowercase()
        val verifiedExisting = filterVerifiedPreKeys(id, existingPreKeys)

        if (verifiedExisting.size >= minThreshold) {
            return verifiedExisting
        }

        val needed = targetCount - verifiedExisting.size
        if (needed <= 0) return verifiedExisting

        val currentMaxId = securePreferences.getInt(userKey(id, "max_prekey_id"), 0)
        val highestExistingId = verifiedExisting.maxOfOrNull { it.id } ?: 0
        val startId = maxOf(currentMaxId, highestExistingId) + 1

        val newPreKeys = (startId until (startId + needed)).map { preKeyId ->
            PreKeyRecord(preKeyId, ECKeyPair.generate())
        }

        val editor = securePreferences.edit()
        newPreKeys.forEach { preKey ->
            editor.putString(
                userKey(id, "prekey_${preKey.id}"),
                Base64Compat.encodeToString(preKey.serialize())
            )
        }
        val newMaxId = startId + needed - 1
        editor.putInt(userKey(id, "max_prekey_id"), newMaxId)
        editor.putInt(userKey(id, "prekey_count"), newMaxId)
        editor.apply()

        // Also synchronize into storePrefsOverride if in test or custom environment
        storePrefsOverride?.let { storePrefs ->
            val storeEditor = storePrefs.edit()
            newPreKeys.forEach { preKey ->
                storeEditor.putString(
                    "prekey::${preKey.id}",
                    Base64Compat.encodeToString(preKey.serialize())
                )
            }
            storeEditor.apply()
        }

        SafeLog.i(
            "TeleXE2EE",
            "Replenished $needed new one-time prekeys for uid=$id (ids $startId..$newMaxId), total active=${verifiedExisting.size + needed}"
        )
        return verifiedExisting + newPreKeys
    }

    /**
     * Ensures that Signal keys (IdentityKeyPair, registration ID, one-time prekeys, signed prekey,
     * and Kyber prekey for PQXDH) exist locally.
     *
     * Idempotent: Never regenerates identity keys if they already exist and are valid.
     * Migration: Only migrates fallback keys to a destination UID if the destination has NO established keys.
     * Never overwrites an established UID identity with fallback keys.
     * Replenishment: Replenishes consumed one-time prekeys if below threshold without altering identity keys.
     */
    suspend fun ensureKeysForUser(
        userIdentifier: String,
        fallbackIdentifier: String? = null,
        preKeyCount: Int = DEFAULT_PREKEY_COUNT
    ): KeyGenerationResult = withContext(Dispatchers.IO) {
        val id = userIdentifier.trim().lowercase()
        val fallback = fallbackIdentifier?.trim()?.lowercase().orEmpty()
        require(id.isNotEmpty()) { "User identifier cannot be blank" }

        // Migration from legacy fallback (e.g. email) to UID:
        // ONLY allow migration if destination 'id' has NO established keys,
        // and 'fallback' is valid, different from 'id', and has established, fully valid keys.
        if (!hasKeysForUser(id) && fallback.isNotEmpty() && fallback != id && hasKeysForUser(fallback)) {
            val fallbackKeys = loadKeysForUser(fallback)
            if (fallbackKeys != null && validateKeyMaterial(fallbackKeys)) {
                SafeLog.i(
                    "TeleXE2EE",
                    "Migrating validated keys from fallback ($fallback) to destination ($id), identityFp=${identityFingerprint(fallbackKeys.identityKeyPair.publicKey)}"
                )
                copyKeys(fallback, id, fallbackKeys)
            } else {
                SafeLog.w(
                    "TeleXE2EE",
                    "Skipping migration from fallback ($fallback) to destination ($id): fallback keys invalid or incomplete"
                )
            }
        }
        // NEVER overwrite an established UID identity with fallback email keys!

        if (hasKeysForUser(id)) {
            val existing = loadKeysForUser(id)
            if (existing != null && validateIdentityAndSignatures(existing)) {
                val activeKeys = if (existing.preKeyRecords.size < MIN_PREKEY_THRESHOLD) {
                    val replenishedPreKeys = replenishPreKeysIfNeeded(id, existing.preKeyRecords, targetCount = preKeyCount)
                    existing.copy(preKeyRecords = replenishedPreKeys)
                } else {
                    existing
                }
                return@withContext activeKeys.copy(isNewGeneration = false)
            } else {
                SafeLog.w(
                    "TeleXE2EE",
                    "Local key material for id=$id is missing or corrupt; regenerating complete key set"
                )
            }
        }

        generateAndStoreKeys(id, preKeyCount)
    }

    private fun copyKeys(sourceId: String, destId: String, sourceKeys: KeyGenerationResult) {
        val s = sourceId.trim().lowercase()
        val d = destId.trim().lowercase()
        SafeLog.w("TeleXE2EE", "COPYING keys from $s to $d fp=${identityFingerprint(sourceKeys.identityKeyPair.publicKey)}")
        if (s.isEmpty() || d.isEmpty() || s == d) return

        securePreferences.edit().apply {
            putBoolean(userKey(d, "has_keys"), true)
            putInt(userKey(d, "registration_id"), sourceKeys.registrationId)
            putString(
                userKey(d, "identity_key_pair"),
                Base64Compat.encodeToString(sourceKeys.identityKeyPair.serialize())
            )
            putInt(userKey(d, "signed_prekey_id"), sourceKeys.signedPreKeyRecord.id)
            putLong(userKey(d, "signed_prekey_timestamp"), sourceKeys.signedPreKeyRecord.timestamp)
            putString(
                userKey(d, "signed_prekey_record"),
                Base64Compat.encodeToString(sourceKeys.signedPreKeyRecord.serialize())
            )
            putInt(userKey(d, "kyber_prekey_id"), sourceKeys.kyberPreKeyRecord.id)
            putLong(userKey(d, "kyber_prekey_timestamp"), sourceKeys.kyberPreKeyRecord.timestamp)
            putString(
                userKey(d, "kyber_prekey_record"),
                Base64Compat.encodeToString(sourceKeys.kyberPreKeyRecord.serialize())
            )
            putInt(userKey(d, "prekey_count"), sourceKeys.preKeyRecords.size)
            sourceKeys.preKeyRecords.forEach { preKey ->
                putString(
                    userKey(d, "prekey_${preKey.id}"),
                    Base64Compat.encodeToString(preKey.serialize())
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
        SafeLog.w("TeleXE2EE", "GENERATED NEW identity for id=$id fp=${identityFingerprint(identityKeyPair.publicKey)}")
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
                Base64Compat.encodeToString(identityKeyPair.serialize())
            )

            // Signed Prekey
            putInt(userKey(id, "signed_prekey_id"), signedPreKeyId)
            putLong(userKey(id, "signed_prekey_timestamp"), signedTimestamp)
            putString(
                userKey(id, "signed_prekey_record"),
                Base64Compat.encodeToString(signedPreKeyRecord.serialize())
            )

            // Kyber Prekey
            putInt(userKey(id, "kyber_prekey_id"), kyberPreKeyId)
            putLong(userKey(id, "kyber_prekey_timestamp"), kyberTimestamp)
            putString(
                userKey(id, "kyber_prekey_record"),
                Base64Compat.encodeToString(kyberPreKeyRecord.serialize())
            )

            // One-Time Prekeys
            putInt(userKey(id, "prekey_count"), preKeyRecords.size)
            putInt(userKey(id, "max_prekey_id"), preKeyRecords.size)
            preKeyRecords.forEach { preKey ->
                putString(
                    userKey(id, "prekey_${preKey.id}"),
                    Base64Compat.encodeToString(preKey.serialize())
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
                Base64Compat.encodeToString(record.serialize())
            )
            apply()
        }
        return record
    }

    /**
     * Loads all stored Signal keys for the given user from secure storage.
     * Strictly non-mutating: does not regenerate Kyber keys or mutate storage.
     * Fails explicitly if any required cryptographic record is missing or corrupt.
     */
    fun loadKeysForUser(userIdentifier: String): KeyGenerationResult? {
        val id = userIdentifier.trim().lowercase()
        if (!hasKeysForUser(id)) return null

        return try {
            val regId = securePreferences.getInt(userKey(id, "registration_id"), -1)
            if (regId == -1) {
                SafeLog.w("TeleXE2EE", "loadKeysForUser failed for uid=$id: missing registration_id")
                return null
            }

            val identityKeyBase64 = securePreferences.getString(userKey(id, "identity_key_pair"), null)
            if (identityKeyBase64.isNullOrBlank()) {
                SafeLog.w("TeleXE2EE", "loadKeysForUser failed for uid=$id: missing identity_key_pair")
                return null
            }
            val identityKeyPair = IdentityKeyPair(Base64Compat.decode(identityKeyBase64))

            val signedPreKeyBase64 = securePreferences.getString(userKey(id, "signed_prekey_record"), null)
            if (signedPreKeyBase64.isNullOrBlank()) {
                SafeLog.w("TeleXE2EE", "loadKeysForUser failed for uid=$id: missing signed_prekey_record")
                return null
            }
            val signedPreKeyRecord = SignedPreKeyRecord(Base64Compat.decode(signedPreKeyBase64))

            // Non-mutating: fail explicitly if Kyber prekey record is missing rather than regenerating
            val kyberPreKeyRecord = getKyberPreKeyRecord(id, 1)
            if (kyberPreKeyRecord == null) {
                SafeLog.w("TeleXE2EE", "loadKeysForUser failed for uid=$id: missing or invalid Kyber prekey record (id=1)")
                return null
            }

            // Verify signatures against identity public key
            val idPubKey = identityKeyPair.publicKey.publicKey
            val signedSigValid = idPubKey.verifySignature(
                signedPreKeyRecord.keyPair.publicKey.serialize(),
                signedPreKeyRecord.signature
            )
            if (!signedSigValid) {
                SafeLog.e("TeleXE2EE", "loadKeysForUser failed for uid=$id: invalid signed prekey signature")
                return null
            }

            val kyberSigValid = idPubKey.verifySignature(
                kyberPreKeyRecord.keyPair.publicKey.serialize(),
                kyberPreKeyRecord.signature
            )
            if (!kyberSigValid) {
                SafeLog.e("TeleXE2EE", "loadKeysForUser failed for uid=$id: invalid Kyber prekey signature")
                return null
            }

            val preKeyCount = securePreferences.getInt(userKey(id, "prekey_count"), 0)
            val maxPreKeyId = maxOf(preKeyCount, securePreferences.getInt(userKey(id, "max_prekey_id"), 0))

            val preKeys = ArrayList<PreKeyRecord>()
            if (maxPreKeyId > 0) {
                for (i in 1..maxPreKeyId) {
                    val preKeyBase64 = securePreferences.getString(userKey(id, "prekey_$i"), null) ?: continue
                    try {
                        preKeys.add(PreKeyRecord(Base64Compat.decode(preKeyBase64)))
                    } catch (e: Exception) {
                        SafeLog.e("TeleXE2EE", "loadKeysForUser failed for uid=$id: corrupt prekey record at prekey_$i", e)
                        return null
                    }
                }
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
            SafeLog.e(TAG, "Error loading keys for user $id", e)
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
            localRegistrationId = keys.registrationId,
            prefsOverride = storePrefsOverride,
            onPreKeyRemoved = { preKeyId ->
                removePreKey(id, preKeyId)
            }
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
     * Private keys NEVER leave local storage.
     *
     * Ensures:
     * 1. Every published prekey has a matching locally stored private record.
     * 2. Overwrites whole document (no merge) to purge stale one-time prekeys.
     * 3. Idempotent: Skips unnecessary writes if the published bundle already matches local keys.
     * 4. Safe against identity overwrite: Never replaces an existing Firestore identity without
     *    deliberate authorization ([allowIdentityRotation] = true).
     */
    suspend fun publishPublicPreKeyBundle(
        uid: String,
        keys: KeyGenerationResult,
        allowIdentityRotation: Boolean = false
    ): Unit = withContext(Dispatchers.IO) {
        val cleanUid = uid.trim().lowercase()
        require(cleanUid.isNotBlank()) { "UID cannot be blank" }

        val localIdentityKeyBase64 = Base64Compat.encodeToString(keys.identityKeyPair.publicKey.serialize())
        val localSignedPreKeyBase64 = Base64Compat.encodeToString(keys.signedPreKeyRecord.keyPair.publicKey.serialize())
        val localKyberPreKeyBase64 = Base64Compat.encodeToString(keys.kyberPreKeyRecord.keyPair.publicKey.serialize())

        val bundleDocRef = firestore.collection("users").document(cleanUid)
            .collection("prekey_bundle").document("current")

        val existingSnapshot = try {
            bundleDocRef.get().await()
        } catch (e: Exception) {
            SafeLog.w("TeleXE2EE", "Could not check existing bundle in Firestore for uid=$cleanUid: ${e.message}")
            null
        }

        var activePreKeys = keys.preKeyRecords

        if (existingSnapshot != null && existingSnapshot.exists()) {
            val remoteIdentityKeyBase64 = existingSnapshot.getString("identityKey")
            validateIdentityForPublication(remoteIdentityKeyBase64, keys.identityKeyPair.publicKey, allowIdentityRotation)

            if (remoteIdentityKeyBase64 == localIdentityKeyBase64) {
                val remoteSignedPreKey = existingSnapshot.getString("signedPreKey")
                val remoteKyberPreKey = existingSnapshot.getString("kyberPreKey")
                val remoteOneTimePreKeys = existingSnapshot.get("oneTimePreKeys") as? Map<*, *>

                // Check how many remote prekeys are still present and valid locally
                val validRemotePreKeysCount = remoteOneTimePreKeys?.keys?.count { keyIdStr ->
                    val keyId = keyIdStr.toString().toIntOrNull()
                    keyId != null && securePreferences.contains(userKey(cleanUid, "prekey_$keyId"))
                } ?: 0

                // If remote bundle is low on prekeys (< MIN_PREKEY_THRESHOLD), replenish with new public keys
                if (validRemotePreKeysCount < MIN_PREKEY_THRESHOLD) {
                    SafeLog.i(
                        "TeleXE2EE",
                        "Remote bundle for uid=$cleanUid has only $validRemotePreKeysCount valid prekeys (< $MIN_PREKEY_THRESHOLD); replenishing"
                    )
                    activePreKeys = replenishPreKeysIfNeeded(cleanUid, keys.preKeyRecords)
                } else {
                    // Check idempotency: if Firestore already has identical signed, kyber, and prekeys
                    val verifiedLocalMap = filterVerifiedPreKeys(cleanUid, activePreKeys).associate {
                        it.id.toString() to Base64Compat.encodeToString(it.keyPair.publicKey.serialize())
                    }
                    if (remoteSignedPreKey == localSignedPreKeyBase64 &&
                        remoteKyberPreKey == localKyberPreKeyBase64 &&
                        remoteOneTimePreKeys != null &&
                        remoteOneTimePreKeys == verifiedLocalMap
                    ) {
                        SafeLog.i(
                            "TeleXE2EE",
                            "PreKey bundle for uid=$cleanUid (fp=${identityFingerprint(keys.identityKeyPair.publicKey)}) already in sync with ${verifiedLocalMap.size} prekeys; skipping write (idempotent)"
                        )
                        return@withContext
                    }
                }
            }
        }

        // Ensure every published prekey has a matching locally stored private record
        val verifiedPreKeys = filterVerifiedPreKeys(cleanUid, activePreKeys)

        val oneTimePreKeyMap = verifiedPreKeys.associate { preKey ->
            preKey.id.toString() to Base64Compat.encodeToString(preKey.keyPair.publicKey.serialize())
        }

        val bundleData = hashMapOf(
            "registrationId" to keys.registrationId,
            "deviceId" to DEVICE_ID,
            "identityKey" to localIdentityKeyBase64,
            "signedPreKeyId" to keys.signedPreKeyRecord.id,
            "signedPreKey" to localSignedPreKeyBase64,
            "signedPreKeySignature" to Base64Compat.encodeToString(keys.signedPreKeyRecord.signature),
            "signedPreKeyTimestamp" to keys.signedPreKeyRecord.timestamp,
            "kyberPreKeyId" to keys.kyberPreKeyRecord.id,
            "kyberPreKey" to localKyberPreKeyBase64,
            "kyberPreKeySignature" to Base64Compat.encodeToString(keys.kyberPreKeyRecord.signature),
            "kyberPreKeyTimestamp" to keys.kyberPreKeyRecord.timestamp,
            "oneTimePreKeys" to oneTimePreKeyMap,
            "updatedAt" to System.currentTimeMillis()
        )

        SafeLog.i(
            "TeleXE2EE",
            "Publishing bundle: uid=$cleanUid identityFp=${identityFingerprint(keys.identityKeyPair.publicKey)} verifiedPreKeysCount=${verifiedPreKeys.size}"
        )

        // Purge stale prekeys by replacing whole document without merge
        bundleDocRef.set(bundleData).await()
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
            val identityKey = IdentityKey(Base64Compat.decode(identityKeyBase64))

            val signedPreKeyId = snapshot.getLong("signedPreKeyId")?.toInt()
                ?: throw IllegalStateException("Missing signedPreKeyId")
            val signedPreKeyBase64 = snapshot.getString("signedPreKey")
                ?: throw IllegalStateException("Missing signedPreKey")
            val signedPreKey = ECPublicKey(Base64Compat.decode(signedPreKeyBase64))

            val signedSigBase64 = snapshot.getString("signedPreKeySignature")
                ?: throw IllegalStateException("Missing signedPreKeySignature")
            val signedPreKeySignature = Base64Compat.decode(signedSigBase64)

            val kyberPreKeyId = snapshot.getLong("kyberPreKeyId")?.toInt()
                ?: throw IllegalStateException("Missing kyberPreKeyId")
            val kyberPreKeyBase64 = snapshot.getString("kyberPreKey")
                ?: throw IllegalStateException("Missing kyberPreKey")
            val kyberPreKey = KEMPublicKey(Base64Compat.decode(kyberPreKeyBase64))

            val kyberSigBase64 = snapshot.getString("kyberPreKeySignature")
                ?: throw IllegalStateException("Missing kyberPreKeySignature")
            val kyberPreKeySignature = Base64Compat.decode(kyberSigBase64)

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
                        selectedPreKey = ECPublicKey(Base64Compat.decode(keyBase64))

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

    /**
     * Fetches current prekey bundle metadata without consuming any one-time prekeys.
     * Used for validating identity matching and session freshness before sending messages.
     */
    suspend fun fetchPreKeyBundleMetadata(targetUid: String): RemoteBundleMetadata? = withContext(Dispatchers.IO) {
        val cleanUid = targetUid.trim().lowercase()
        if (cleanUid.isBlank()) return@withContext null
        try {
            val doc = firestore.collection("users").document(cleanUid)
                .collection("prekey_bundle").document("current").get().await()
            if (!doc.exists()) return@withContext null

            val regId = doc.getLong("registrationId")?.toInt() ?: return@withContext null
            val devId = doc.getLong("deviceId")?.toInt() ?: DEVICE_ID
            val identityKeyBase64 = doc.getString("identityKey") ?: return@withContext null
            val identityKey = IdentityKey(Base64Compat.decode(identityKeyBase64))
            val signedPreKeyId = doc.getLong("signedPreKeyId")?.toInt() ?: return@withContext null
            val updatedAt = doc.getLong("updatedAt") ?: 0L

            RemoteBundleMetadata(
                registrationId = regId,
                deviceId = devId,
                identityKey = identityKey,
                signedPreKeyId = signedPreKeyId,
                updatedAt = updatedAt
            )
        } catch (e: Exception) {
            SafeLog.w("TeleXE2EE", "Failed to fetch prekey bundle metadata for $cleanUid: ${e.message}")
            null
        }
    }

    fun getKyberPreKeyRecord(userIdentifier: String, kyberPreKeyId: Int): KyberPreKeyRecord? {
        val id = userIdentifier.trim().lowercase()
        val base64 = securePreferences.getString(userKey(id, "kyber_prekey_record"), null) ?: return null
        return try {
            val record = KyberPreKeyRecord(Base64Compat.decode(base64))
            if (record.id == kyberPreKeyId) record else null
        } catch (e: Exception) {
            null
        }
    }

    fun getIdentityKeyPair(userIdentifier: String): IdentityKeyPair? {
        val id = userIdentifier.trim().lowercase()
        val base64 = securePreferences.getString(userKey(id, "identity_key_pair"), null) ?: return null
        return try {
            IdentityKeyPair(Base64Compat.decode(base64))
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
            SignedPreKeyRecord(Base64Compat.decode(base64))
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

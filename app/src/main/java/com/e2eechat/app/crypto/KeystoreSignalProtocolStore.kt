package com.e2eechat.app.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.util.UUID

/**
 * Android Keystore-backed implementation of [SignalProtocolStore].
 *
 * Persists all identity, prekeys, signed prekeys, Kyber prekeys, and session records
 * using hardware-backed [EncryptedSharedPreferences].
 *
 * Scoped per user identity (UID / email prefix) to prevent cross-account key contamination.
 */
class KeystoreSignalProtocolStore(
    context: Context? = null,
    private val userIdentifier: String,
    private val identityKeyPair: IdentityKeyPair,
    private val localRegistrationId: Int,
    prefsOverride: SharedPreferences? = null,
    private val onPreKeyRemoved: ((Int) -> Unit)? = null
) : SignalProtocolStore {

    companion object {
        private const val PREFS_FILE_PREFIX = "telex_signal_store_"

        fun getStoreName(userIdentifier: String): String =
            "${PREFS_FILE_PREFIX}${userIdentifier.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")}"
    }

    private val masterKey: MasterKey by lazy {
        val ctx = context ?: throw IllegalStateException("Context required when prefsOverride is null")
        MasterKey.Builder(ctx.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs: SharedPreferences = prefsOverride ?: try {
        val ctx = context ?: throw IllegalStateException("Context required when prefsOverride is null")
        createPrefs(ctx)
    } catch (e: Exception) {
        // Recovery in case of Keystore corruption/inconsistency
        android.util.Log.w("TeleXE2EE", "Signal store prefs error - store WIPED for $userIdentifier")
        context?.applicationContext?.deleteSharedPreferences(getStoreName(userIdentifier))
        createPrefs(context ?: throw IllegalStateException("Context required when prefsOverride is null"))
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            getStoreName(userIdentifier),
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun k(prefix: String, key: String): String = "$prefix::$key"

    // ---------------------------------------------------------------------------------------------
    // IdentityKeyStore
    // ---------------------------------------------------------------------------------------------

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = localRegistrationId

    override fun saveIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey
    ): IdentityKeyStore.IdentityChange {
        val key = k("identity", address.name)
        val existingBase64 = prefs.getString(key, null)
        val newBase64 = Base64Compat.encodeToString(identityKey.serialize())

        return if (existingBase64 == null) {
            prefs.edit().putString(key, newBase64).commit()
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else if (existingBase64 == newBase64) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            SafeLog.w("TeleXE2EE", "Identity for ${address.name} REPLACED old=${identityFingerprint(getIdentity(address))} new=${identityFingerprint(identityKey)}")
            prefs.edit().putString(key, newBase64).commit()
            deleteSession(address)
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction
    ): Boolean {
        val trusted = getIdentity(address)
        return trusted == null || trusted == identityKey
    }

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val base64 = prefs.getString(k("identity", address.name), null) ?: return null
        return try {
            IdentityKey(Base64Compat.decode(base64))
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PreKeyStore
    // ---------------------------------------------------------------------------------------------

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val base64 = prefs.getString(k("prekey", preKeyId.toString()), null)
            ?: throw InvalidKeyIdException("No such prekey: $preKeyId")
        return try {
            PreKeyRecord(Base64Compat.decode(base64))
        } catch (e: Exception) {
            throw InvalidKeyIdException("Corrupted prekey: $preKeyId")
        }
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        val base64 = Base64Compat.encodeToString(record.serialize())
        prefs.edit().putString(k("prekey", preKeyId.toString()), base64).commit()
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return prefs.contains(k("prekey", preKeyId.toString()))
    }

    override fun removePreKey(preKeyId: Int) {
        prefs.edit().remove(k("prekey", preKeyId.toString())).commit()
        onPreKeyRemoved?.invoke(preKeyId)
    }

    // ---------------------------------------------------------------------------------------------
    // SignedPreKeyStore
    // ---------------------------------------------------------------------------------------------

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val base64 = prefs.getString(k("signed_prekey", signedPreKeyId.toString()), null)
            ?: throw InvalidKeyIdException("No such signed prekey: $signedPreKeyId")
        return try {
            SignedPreKeyRecord(Base64Compat.decode(base64))
        } catch (e: Exception) {
            throw InvalidKeyIdException("Corrupted signed prekey: $signedPreKeyId")
        }
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        val results = mutableListOf<SignedPreKeyRecord>()
        val prefix = "signed_prekey::"
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String) {
                try {
                    results.add(SignedPreKeyRecord(Base64Compat.decode(value)))
                } catch (_: Exception) {}
            }
        }
        return results
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        val base64 = Base64Compat.encodeToString(record.serialize())
        prefs.edit().putString(k("signed_prekey", signedPreKeyId.toString()), base64).commit()
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return prefs.contains(k("signed_prekey", signedPreKeyId.toString()))
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        prefs.edit().remove(k("signed_prekey", signedPreKeyId.toString())).commit()
    }

    // ---------------------------------------------------------------------------------------------
    // KyberPreKeyStore
    // ---------------------------------------------------------------------------------------------

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val base64 = prefs.getString(k("kyber_prekey", kyberPreKeyId.toString()), null)
            ?: throw InvalidKeyIdException("No such Kyber prekey: $kyberPreKeyId")
        return try {
            KyberPreKeyRecord(Base64Compat.decode(base64))
        } catch (e: Exception) {
            throw InvalidKeyIdException("Corrupted Kyber prekey: $kyberPreKeyId")
        }
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        val results = mutableListOf<KyberPreKeyRecord>()
        val prefix = "kyber_prekey::"
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String) {
                try {
                    results.add(KyberPreKeyRecord(Base64Compat.decode(value)))
                } catch (_: Exception) {}
            }
        }
        return results
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        val base64 = Base64Compat.encodeToString(record.serialize())
        prefs.edit().putString(k("kyber_prekey", kyberPreKeyId.toString()), base64).commit()
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        return prefs.contains(k("kyber_prekey", kyberPreKeyId.toString()))
    }

    override fun markKyberPreKeyUsed(
        kyberPreKeyId: Int,
        signedPreKeyId: Int,
        baseKey: ECPublicKey
    ) {
        val usedKey = k("kyber_used", kyberPreKeyId.toString())
        val baseKeysKey = k("kyber_base_keys", "${kyberPreKeyId}_$signedPreKeyId")
        val baseKeyBase64 = Base64Compat.encodeToString(baseKey.serialize())

        val existingSet = prefs.getStringSet(baseKeysKey, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (existingSet.contains(baseKeyBase64)) {
            throw ReusedBaseKeyException()
        }
        existingSet.add(baseKeyBase64)

        prefs.edit()
            .putBoolean(usedKey, true)
            .putStringSet(baseKeysKey, existingSet)
            .commit()
    }

    fun hasKyberPreKeyBeenUsed(kyberPreKeyId: Int): Boolean {
        return prefs.getBoolean(k("kyber_used", kyberPreKeyId.toString()), false)
    }

    // ---------------------------------------------------------------------------------------------
    // SessionStore
    // ---------------------------------------------------------------------------------------------

    private fun sessionKey(address: SignalProtocolAddress): String =
        k("session", "${address.name}:${address.deviceId}")

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val base64 = prefs.getString(sessionKey(address), null)
        return if (base64 != null) {
            try {
                SessionRecord(Base64Compat.decode(base64))
            } catch (e: Exception) {
                SessionRecord()
            }
        } else {
            SessionRecord()
        }
    }

    override fun loadExistingSessions(
        addresses: List<SignalProtocolAddress>
    ): List<SessionRecord> {
        val list = mutableListOf<SessionRecord>()
        for (addr in addresses) {
            val base64 = prefs.getString(sessionKey(addr), null)
            if (base64 != null) {
                try {
                    list.add(SessionRecord(Base64Compat.decode(base64)))
                } catch (_: Exception) {}
            }
        }
        return list
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        val prefix = "session::$name:"
        val deviceIds = mutableListOf<Int>()
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix)) {
                val deviceIdStr = key.removePrefix(prefix)
                deviceIdStr.toIntOrNull()?.let { deviceIds.add(it) }
            }
        }
        return deviceIds
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        val base64 = Base64Compat.encodeToString(record.serialize())
        prefs.edit().putString(sessionKey(address), base64).commit()
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return prefs.contains(sessionKey(address))
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        prefs.edit().remove(sessionKey(address)).commit()
    }

    override fun deleteAllSessions(name: String) {
        val prefix = "session::$name:"
        val editor = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix)) {
                editor.remove(key)
            }
        }
        editor.commit()
    }

    /**
     * Checks if a locally stored session is structurally valid:
     * - Must exist in storage.
     * - Must have an active sender chain (able to encrypt outgoing messages).
     * - Local identity key must match current local identity key.
     * - Remote identity key must match current trusted identity for this address.
     */
    fun isSessionValid(address: SignalProtocolAddress): Boolean {
        if (!containsSession(address)) return false
        return try {
            val session = loadSession(address)
            if (!session.hasSenderChain()) return false

            val localId = session.localIdentityKey
            if (localId != null && localId != identityKeyPair.publicKey) {
                return false
            }

            val remoteId = session.remoteIdentityKey
            val trustedId = getIdentity(address)
            if (remoteId != null && trustedId != null && remoteId != trustedId) {
                return false
            }

            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Retrieves the peer's registration ID recorded in this session, if any.
     */
    fun getSessionRemoteRegistrationId(address: SignalProtocolAddress): Int? {
        if (!containsSession(address)) return null
        return try {
            val regId = loadSession(address).remoteRegistrationId
            if (regId != 0) regId else null
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------
    // SenderKeyStore (Group Messaging stub / local state)
    // ---------------------------------------------------------------------------------------------

    private fun senderKeyKey(address: SignalProtocolAddress, distributionId: UUID): String =
        k("sender_key", "${address.name}:${address.deviceId}:${distributionId}")

    override fun storeSenderKey(
        address: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord
    ) {
        val base64 = Base64Compat.encodeToString(record.serialize())
        prefs.edit().putString(senderKeyKey(address, distributionId), base64).apply()
    }

    override fun loadSenderKey(
        address: SignalProtocolAddress,
        distributionId: UUID
    ): SenderKeyRecord? {
        val base64 = prefs.getString(senderKeyKey(address, distributionId), null) ?: return null
        return try {
            SenderKeyRecord(Base64Compat.decode(base64))
        } catch (e: Exception) {
            null
        }
    }
}

package com.e2eechat.app.crypto

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord

class KeyLifecycleTest {

    private fun createKeyManager(
        prefs: TestSharedPreferences = TestSharedPreferences(),
        storePrefs: TestSharedPreferences = TestSharedPreferences()
    ): KeyManager {
        return KeyManager(
            context = null,
            prefsOverride = prefs,
            firestoreOverride = null,
            storePrefsOverride = storePrefs
        )
    }

    @Test
    fun testMissingKyberPrekeyFailsExplicitlyAndDoesNotRegenerate() = runBlocking {
        val prefs = TestSharedPreferences()
        val keyManager = createKeyManager(prefs = prefs)
        val uid = "user_test_kyber"

        // 1. Generate keys
        val generated = keyManager.ensureKeysForUser(uid, preKeyCount = 5)
        assertNotNull(generated)
        assertNotNull(keyManager.loadKeysForUser(uid))

        // 2. Simulate missing Kyber prekey record (e.g. legacy installation or deleted)
        prefs.edit().remove("${uid}::kyber_prekey_record").commit()

        // 3. loadKeysForUser must fail explicitly and return null, NOT silently regenerate
        val loadedAfterRemoval = keyManager.loadKeysForUser(uid)
        assertNull("loadKeysForUser must return null when Kyber prekey is missing", loadedAfterRemoval)

        // 4. Verify storage was NOT mutated by the load call
        assertFalse(prefs.contains("${uid}::kyber_prekey_record"))
    }

    @Test
    fun testCorruptedSignatureFailsExplicitly() = runBlocking {
        val prefs = TestSharedPreferences()
        val keyManager = createKeyManager(prefs = prefs)
        val uid = "user_test_corrupt_sig"

        // Generate valid keys
        keyManager.ensureKeysForUser(uid, preKeyCount = 5)

        // Corrupt the signed prekey record with one signed by a different identity
        val attackerIdentity = IdentityKeyPair.generate()
        val newSignedKeyPair = ECKeyPair.generate()
        val forgedSig = attackerIdentity.privateKey.calculateSignature(newSignedKeyPair.publicKey.serialize())
        val forgedRecord = org.signal.libsignal.protocol.state.SignedPreKeyRecord(
            1, System.currentTimeMillis(), newSignedKeyPair, forgedSig
        )
        prefs.edit().putString(
            "${uid}::signed_prekey_record",
            Base64Compat.encodeToString(forgedRecord.serialize())
        ).commit()

        // loadKeysForUser must detect invalid signature and fail explicitly
        val loaded = keyManager.loadKeysForUser(uid)
        assertNull("loadKeysForUser must return null when signed prekey signature is forged", loaded)
    }

    @Test
    fun testMissingLocalPrekeyRecordFailsLoadExplicitly() = runBlocking {
        val prefs = TestSharedPreferences()
        val keyManager = createKeyManager(prefs = prefs)
        val uid = "user_test_missing_prekey"

        keyManager.ensureKeysForUser(uid, preKeyCount = 5)

        // Corrupt one of the prekey records with invalid Base64
        prefs.edit().putString("${uid}::prekey_2", "invalid_base_64_corrupted!@#").commit()

        // loadKeysForUser must fail explicitly when a stored record is corrupted
        val loaded = keyManager.loadKeysForUser(uid)
        assertNull("loadKeysForUser must fail explicitly on corrupt prekey record", loaded)
    }

    @Test
    fun testStalePrekeyRemovalAndTracking() = runBlocking {
        val prefs = TestSharedPreferences()
        val keyManager = createKeyManager(prefs = prefs)
        val uid = "user_test_stale_prekey"

        val keys = keyManager.ensureKeysForUser(uid, preKeyCount = 5)
        assertEquals(5, keys.preKeyRecords.size)

        // Simulate that prekey 1 and 2 were consumed locally
        keyManager.removePreKey(uid, 1)
        keyManager.removePreKey(uid, 2)

        // Verify local storage no longer has prekey 1 and 2
        assertFalse(prefs.contains("${uid}::prekey_1"))
        assertFalse(prefs.contains("${uid}::prekey_2"))
        assertTrue(prefs.contains("${uid}::prekey_3"))

        // Verified prekeys for publication: only prekeys with matching local records survive (production method)
        val verifiedPreKeys = keyManager.filterVerifiedPreKeys(uid, keys.preKeyRecords)

        assertEquals(3, verifiedPreKeys.size)
        assertEquals(listOf(3, 4, 5), verifiedPreKeys.map { it.id })
    }

    @Test
    fun testPreKeyReplenishmentWhenDepletedPreservesIdentity() = runBlocking {
        val prefs = TestSharedPreferences()
        val storePrefs = TestSharedPreferences()
        val keyManager = createKeyManager(prefs = prefs, storePrefs = storePrefs)
        val uid = "user_test_replenish"

        // 1. Initial key generation
        val initialKeys = keyManager.ensureKeysForUser(uid, preKeyCount = 5)
        val initialIdentityFp = identityFingerprint(initialKeys.identityKeyPair.publicKey)
        val initialRegId = initialKeys.registrationId

        // 2. Consume all prekeys locally
        initialKeys.preKeyRecords.forEach { preKey ->
            keyManager.removePreKey(uid, preKey.id)
        }

        // 3. Ensure keys for user again: must NOT regenerate identity key!
        val refreshedKeys = keyManager.ensureKeysForUser(uid, preKeyCount = 10)
        val refreshedIdentityFp = identityFingerprint(refreshedKeys.identityKeyPair.publicKey)

        assertEquals("Identity key must NEVER be regenerated when prekeys are depleted", initialIdentityFp, refreshedIdentityFp)
        assertEquals("Registration ID must remain unchanged", initialRegId, refreshedKeys.registrationId)
        assertEquals("Signed prekey ID must remain unchanged", initialKeys.signedPreKeyRecord.id, refreshedKeys.signedPreKeyRecord.id)
        assertEquals("Kyber prekey ID must remain unchanged", initialKeys.kyberPreKeyRecord.id, refreshedKeys.kyberPreKeyRecord.id)

        // Replenished prekeys must be generated
        assertTrue("Prekeys must be replenished", refreshedKeys.preKeyRecords.size >= 10)
        // IDs must not collide with old consumed IDs (1..5)
        assertTrue("New prekey IDs must be strictly greater than old max ID", refreshedKeys.preKeyRecords.all { it.id > 5 })
        // All new prekeys must have private records in storage
        val verified = keyManager.filterVerifiedPreKeys(uid, refreshedKeys.preKeyRecords)
        assertEquals(refreshedKeys.preKeyRecords.size, verified.size)
    }

    @Test
    fun testUidEmailMigrationRules() = runBlocking {
        val prefs = TestSharedPreferences()
        val keyManager = createKeyManager(prefs = prefs)

        val email = "alice@example.com"
        val uid = "firebase_uid_alice_123"

        // 1. Establish keys under legacy email identifier
        val emailKeys = keyManager.ensureKeysForUser(email, preKeyCount = 10)
        val emailFp = identityFingerprint(emailKeys.identityKeyPair.publicKey)
        assertTrue(keyManager.hasKeysForUser(email))
        assertFalse(keyManager.hasKeysForUser(uid))

        // 2. First login with UID and email as fallback:
        // Since UID has NO keys, migration from valid email source MUST succeed
        val migratedResult = keyManager.ensureKeysForUser(userIdentifier = uid, fallbackIdentifier = email)
        assertTrue(keyManager.hasKeysForUser(uid))
        val uidFp = identityFingerprint(migratedResult.identityKeyPair.publicKey)
        assertEquals("Migrated UID identity fingerprint must match email identity", emailFp, uidFp)
        assertEquals(emailKeys.registrationId, migratedResult.registrationId)

        // 3. Case B: Established UID keys must NEVER be overwritten by fallback keys
        // Create an attacker/different fallback email with different keys
        val otherEmail = "bob@example.com"
        val otherKeys = keyManager.ensureKeysForUser(otherEmail, preKeyCount = 5)
        val otherFp = identityFingerprint(otherKeys.identityKeyPair.publicKey)
        assertTrue(otherFp != uidFp)

        // Call ensureKeysForUser with existing UID and otherEmail as fallback
        val existingUidKeys = keyManager.ensureKeysForUser(userIdentifier = uid, fallbackIdentifier = otherEmail)
        val postCheckFp = identityFingerprint(existingUidKeys.identityKeyPair.publicKey)

        // UID keys must remain untouched and not clobbered by fallback
        assertEquals("Established UID identity must NEVER be overwritten by fallback email", uidFp, postCheckFp)
    }

    @Test
    fun testCorruptFallbackDoesNotMigrate() = runBlocking {
        val prefs = TestSharedPreferences()
        val keyManager = createKeyManager(prefs = prefs)

        val email = "corrupt@example.com"
        val uid = "uid_clean_user"

        // Put corrupted keys in email
        prefs.edit().putBoolean("${email}::has_keys", true).commit()
        prefs.edit().putString("${email}::identity_key_pair", "invalid_garbage").commit()

        // Destination UID has no keys. Migration must be rejected because source is invalid
        val result = keyManager.ensureKeysForUser(userIdentifier = uid, fallbackIdentifier = email, preKeyCount = 5)
        assertNotNull(result)
        assertTrue(result.isNewGeneration)
        assertTrue(keyManager.hasKeysForUser(uid))

        // Fresh keys for UID must be completely valid
        assertTrue(keyManager.validateKeyMaterial(result))
    }

    @Test
    fun testIdentityMismatchRejectionLogic() {
        val keyManager = createKeyManager()
        val localKeys = IdentityKeyPair.generate()
        val remoteKeys = IdentityKeyPair.generate()

        val remoteBase64 = Base64Compat.encodeToString(remoteKeys.publicKey.serialize())

        val localFp = identityFingerprint(localKeys.publicKey)
        val remoteFp = identityFingerprint(remoteKeys.publicKey)
        assertTrue(localFp != remoteFp)

        // Real production validateIdentityForPublication must reject without explicit rotation
        assertThrows(IllegalStateException::class.java) {
            keyManager.validateIdentityForPublication(remoteBase64, localKeys.publicKey, allowIdentityRotation = false)
        }

        // Must succeed when remote is absent or identical
        keyManager.validateIdentityForPublication(null, localKeys.publicKey, allowIdentityRotation = false)
        val localBase64 = Base64Compat.encodeToString(localKeys.publicKey.serialize())
        keyManager.validateIdentityForPublication(localBase64, localKeys.publicKey, allowIdentityRotation = false)

        // Must succeed with explicit rotation
        keyManager.validateIdentityForPublication(remoteBase64, localKeys.publicKey, allowIdentityRotation = true)
    }

    @Test
    fun testFreshBidirectionalMessageDecryptionAcrossKeyStores() = runBlocking {
        val alicePrefs = TestSharedPreferences()
        val aliceStorePrefs = TestSharedPreferences()
        val aliceManager = createKeyManager(prefs = alicePrefs, storePrefs = aliceStorePrefs)

        val bobPrefs = TestSharedPreferences()
        val bobStorePrefs = TestSharedPreferences()
        val bobManager = createKeyManager(prefs = bobPrefs, storePrefs = bobStorePrefs)

        val aliceUid = "alice_uid"
        val bobUid = "bob_uid"

        // 1. Ensure keys exist
        val aliceKeys = aliceManager.ensureKeysForUser(aliceUid, preKeyCount = 5)
        val bobKeys = bobManager.ensureKeysForUser(bobUid, preKeyCount = 5)

        val aliceStore = aliceManager.getProtocolStore(aliceUid)
        val bobStore = bobManager.getProtocolStore(bobUid)

        // 2. Construct Bob's published PreKeyBundle (with verified prekey 1)
        val bobOneTime = bobKeys.preKeyRecords.first()
        val bobPublishedBundle = PublishedPreKeyBundle(
            registrationId = bobKeys.registrationId,
            deviceId = 1,
            identityKey = bobKeys.identityKeyPair.publicKey,
            signedPreKeyId = bobKeys.signedPreKeyRecord.id,
            signedPreKey = bobKeys.signedPreKeyRecord.keyPair.publicKey,
            signedPreKeySignature = bobKeys.signedPreKeyRecord.signature,
            kyberPreKeyId = bobKeys.kyberPreKeyRecord.id,
            kyberPreKey = bobKeys.kyberPreKeyRecord.keyPair.publicKey,
            kyberPreKeySignature = bobKeys.kyberPreKeyRecord.signature,
            oneTimePreKeyId = bobOneTime.id,
            oneTimePreKey = bobOneTime.keyPair.publicKey
        )

        val bobAddress = SignalProtocolAddress(bobUid, 1)
        val aliceAddress = SignalProtocolAddress(aliceUid, 1)

        // 3. Alice processes Bob's bundle and encrypts initial message
        val aliceSessionBuilder = SessionBuilder(aliceStore, bobAddress)
        aliceSessionBuilder.process(bobPublishedBundle.toSignalPreKeyBundle())

        val aliceCipher = SessionCipher(aliceStore, bobAddress)
        val initialText = "Hello Bob! Quantum-safe PQXDH message from Alice."
        val ciphertext1 = aliceCipher.encrypt(initialText.toByteArray(Charsets.UTF_8))
        assertTrue("Initial message must be PreKeySignalMessage", ciphertext1 is PreKeySignalMessage)

        // 4. Bob receives and decrypts initial message
        val bobCipher = SessionCipher(bobStore, aliceAddress)
        val decryptedBytes1 = bobCipher.decrypt(PreKeySignalMessage(ciphertext1.serialize()))
        val decryptedText1 = String(decryptedBytes1, Charsets.UTF_8)
        assertEquals(initialText, decryptedText1)

        // Verify Bob's prekey 1 was consumed locally
        assertFalse("Bob's consumed prekey 1 must be removed from local storage", bobManager.hasKeysForUser(bobUid) && bobPrefs.contains("${bobUid}::prekey_1"))

        // 5. Bob replies to Alice (ratchet step)
        val replyText = "Hello Alice! Received your secure message loud and clear."
        val ciphertext2 = bobCipher.encrypt(replyText.toByteArray(Charsets.UTF_8))

        // Alice decrypts Bob's reply
        val decryptedBytes2 = aliceCipher.decrypt(SignalMessage(ciphertext2.serialize()))
        val decryptedText2 = String(decryptedBytes2, Charsets.UTF_8)
        assertEquals(replyText, decryptedText2)

        // 6. Alice sends subsequent message to Bob (forward ratcheting)
        val followupText = "Great! The bidirectional ratchet is operating smoothly."
        val ciphertext3 = aliceCipher.encrypt(followupText.toByteArray(Charsets.UTF_8))
        assertTrue("Subsequent message should be standard SignalMessage", ciphertext3 is SignalMessage)

        val decryptedBytes3 = bobCipher.decrypt(SignalMessage(ciphertext3.serialize()))
        val decryptedText3 = String(decryptedBytes3, Charsets.UTF_8)
        assertEquals(followupText, decryptedText3)
    }

    @Test
    fun testSessionValidityChecksDetectStaleSessions() = runBlocking {
        val alicePrefs = TestSharedPreferences()
        val aliceStorePrefs = TestSharedPreferences()
        val aliceManager = createKeyManager(prefs = alicePrefs, storePrefs = aliceStorePrefs)

        val bobPrefs = TestSharedPreferences()
        val bobStorePrefs = TestSharedPreferences()
        val bobManager = createKeyManager(prefs = bobPrefs, storePrefs = bobStorePrefs)

        val aliceUid = "alice_val"
        val bobUid = "bob_val"

        val aliceKeys = aliceManager.ensureKeysForUser(aliceUid, preKeyCount = 5)
        val bobKeys = bobManager.ensureKeysForUser(bobUid, preKeyCount = 5)

        val aliceStore = aliceManager.getProtocolStore(aliceUid) as KeystoreSignalProtocolStore
        val bobAddress = SignalProtocolAddress(bobUid, 1)

        // 1. Before session established: isSessionValid must return false
        assertFalse(aliceStore.isSessionValid(bobAddress))

        // 2. Establish valid session
        val bobOneTime = bobKeys.preKeyRecords.first()
        val bobPublishedBundle = PublishedPreKeyBundle(
            registrationId = bobKeys.registrationId,
            deviceId = 1,
            identityKey = bobKeys.identityKeyPair.publicKey,
            signedPreKeyId = bobKeys.signedPreKeyRecord.id,
            signedPreKey = bobKeys.signedPreKeyRecord.keyPair.publicKey,
            signedPreKeySignature = bobKeys.signedPreKeyRecord.signature,
            kyberPreKeyId = bobKeys.kyberPreKeyRecord.id,
            kyberPreKey = bobKeys.kyberPreKeyRecord.keyPair.publicKey,
            kyberPreKeySignature = bobKeys.kyberPreKeyRecord.signature,
            oneTimePreKeyId = bobOneTime.id,
            oneTimePreKey = bobOneTime.keyPair.publicKey
        )

        val sessionBuilder = SessionBuilder(aliceStore, bobAddress)
        sessionBuilder.process(bobPublishedBundle.toSignalPreKeyBundle())

        // Now session must be structurally valid
        assertTrue("Session must be structurally valid after processing bundle", aliceStore.isSessionValid(bobAddress))
        assertEquals(bobKeys.registrationId, aliceStore.getSessionRemoteRegistrationId(bobAddress))

        // 3. Simulate trusted identity change (e.g. remote identity was replaced)
        val untrustedIdentity = IdentityKeyPair.generate().publicKey
        aliceStorePrefs.edit().putString(
            "identity::${bobAddress.name}",
            Base64Compat.encodeToString(untrustedIdentity.serialize())
        ).commit()

        // Session's remote identity no longer matches trusted identity -> must be detected as invalid
        assertFalse("Session must be invalid if trusted identity does not match session remote identity", aliceStore.isSessionValid(bobAddress))
    }

    @Test
    fun testStrictIdentityVerificationRejectsUntrustedIdentityOnBothSendingAndReceiving() = runBlocking {
        val alicePrefs = TestSharedPreferences()
        val aliceStorePrefs = TestSharedPreferences()
        val aliceManager = createKeyManager(prefs = alicePrefs, storePrefs = aliceStorePrefs)

        val bobPrefs = TestSharedPreferences()
        val bobStorePrefs = TestSharedPreferences()
        val bobManager = createKeyManager(prefs = bobPrefs, storePrefs = bobStorePrefs)

        val aliceUid = "alice_strict"
        val bobUid = "bob_strict"

        aliceManager.ensureKeysForUser(aliceUid, preKeyCount = 5)
        val bobKeys1 = bobManager.ensureKeysForUser(bobUid, preKeyCount = 5)

        val aliceStore = aliceManager.getProtocolStore(aliceUid) as KeystoreSignalProtocolStore
        val bobAddress = SignalProtocolAddress(bobUid, 1)

        // Establish session with Bob's original identity (TOFU)
        val bobOneTime1 = bobKeys1.preKeyRecords.first()
        val bobBundle1 = PublishedPreKeyBundle(
            registrationId = bobKeys1.registrationId,
            deviceId = 1,
            identityKey = bobKeys1.identityKeyPair.publicKey,
            signedPreKeyId = bobKeys1.signedPreKeyRecord.id,
            signedPreKey = bobKeys1.signedPreKeyRecord.keyPair.publicKey,
            signedPreKeySignature = bobKeys1.signedPreKeyRecord.signature,
            kyberPreKeyId = bobKeys1.kyberPreKeyRecord.id,
            kyberPreKey = bobKeys1.kyberPreKeyRecord.keyPair.publicKey,
            kyberPreKeySignature = bobKeys1.kyberPreKeyRecord.signature,
            oneTimePreKeyId = bobOneTime1.id,
            oneTimePreKey = bobOneTime1.keyPair.publicKey
        )

        SessionBuilder(aliceStore, bobAddress).process(bobBundle1.toSignalPreKeyBundle())
        assertEquals(bobKeys1.identityKeyPair.publicKey, aliceStore.getIdentity(bobAddress))

        // Now Bob generates a NEW identity (e.g. reinstallation)
        val bobNewIdentity = IdentityKeyPair.generate()
        val newSignedRecord = bobKeys1.signedPreKeyRecord
        val newKyberRecord = bobKeys1.kyberPreKeyRecord
        val newOneTime = bobKeys1.preKeyRecords.last()

        val forgedSignedSig = bobNewIdentity.privateKey.calculateSignature(newSignedRecord.keyPair.publicKey.serialize())
        val forgedKyberSig = bobNewIdentity.privateKey.calculateSignature(newKyberRecord.keyPair.publicKey.serialize())

        val bobBundle2 = PublishedPreKeyBundle(
            registrationId = 9999,
            deviceId = 1,
            identityKey = bobNewIdentity.publicKey,
            signedPreKeyId = newSignedRecord.id,
            signedPreKey = newSignedRecord.keyPair.publicKey,
            signedPreKeySignature = forgedSignedSig,
            kyberPreKeyId = newKyberRecord.id,
            kyberPreKey = newKyberRecord.keyPair.publicKey,
            kyberPreKeySignature = forgedKyberSig,
            oneTimePreKeyId = newOneTime.id,
            oneTimePreKey = newOneTime.keyPair.publicKey
        )

        // isTrustedIdentity must return FALSE for BOTH directions
        assertFalse(
            "Sending direction must reject untrusted identity",
            aliceStore.isTrustedIdentity(bobAddress, bobNewIdentity.publicKey, IdentityKeyStore.Direction.SENDING)
        )
        assertFalse(
            "Receiving direction must reject untrusted identity",
            aliceStore.isTrustedIdentity(bobAddress, bobNewIdentity.publicKey, IdentityKeyStore.Direction.RECEIVING)
        )

        // SessionBuilder.process must fail with UntrustedIdentityException and NOT overwrite identity
        assertThrows(UntrustedIdentityException::class.java) {
            SessionBuilder(aliceStore, bobAddress).process(bobBundle2.toSignalPreKeyBundle())
        }

        // Stored trusted identity must remain Bob's original identity
        assertEquals(bobKeys1.identityKeyPair.publicKey, aliceStore.getIdentity(bobAddress))
    }

    @Test
    fun testExplicitIdentityChangeAndResetAllowsRecovery() = runBlocking {
        val alicePrefs = TestSharedPreferences()
        val aliceStorePrefs = TestSharedPreferences()
        val aliceManager = createKeyManager(prefs = alicePrefs, storePrefs = aliceStorePrefs)

        val bobPrefs = TestSharedPreferences()
        val bobStorePrefs = TestSharedPreferences()
        val bobManager = createKeyManager(prefs = bobPrefs, storePrefs = bobStorePrefs)

        val aliceUid = "alice_rec"
        val bobUid = "bob_rec"

        val aliceKeys = aliceManager.ensureKeysForUser(aliceUid, preKeyCount = 5)
        val bobKeys1 = bobManager.ensureKeysForUser(bobUid, preKeyCount = 5)

        val aliceStore = aliceManager.getProtocolStore(aliceUid) as KeystoreSignalProtocolStore
        val bobAddress = SignalProtocolAddress(bobUid, 1)
        val aliceAddress = SignalProtocolAddress(aliceUid, 1)

        // 1. Initial communication
        val bobOneTime1 = bobKeys1.preKeyRecords.first()
        val bobBundle1 = PublishedPreKeyBundle(
            registrationId = bobKeys1.registrationId,
            deviceId = 1,
            identityKey = bobKeys1.identityKeyPair.publicKey,
            signedPreKeyId = bobKeys1.signedPreKeyRecord.id,
            signedPreKey = bobKeys1.signedPreKeyRecord.keyPair.publicKey,
            signedPreKeySignature = bobKeys1.signedPreKeyRecord.signature,
            kyberPreKeyId = bobKeys1.kyberPreKeyRecord.id,
            kyberPreKey = bobKeys1.kyberPreKeyRecord.keyPair.publicKey,
            kyberPreKeySignature = bobKeys1.kyberPreKeyRecord.signature,
            oneTimePreKeyId = bobOneTime1.id,
            oneTimePreKey = bobOneTime1.keyPair.publicKey
        )

        SessionBuilder(aliceStore, bobAddress).process(bobBundle1.toSignalPreKeyBundle())
        assertEquals(bobKeys1.identityKeyPair.publicKey, aliceStore.getIdentity(bobAddress))

        // 2. Bob completely resets / generates fresh identity
        bobManager.clearKeysForUser(bobUid)
        bobStorePrefs.edit().clear().commit()
        val bobKeys2 = bobManager.ensureKeysForUser(bobUid, preKeyCount = 5)
        val bobStore2 = bobManager.getProtocolStore(bobUid) as KeystoreSignalProtocolStore

        // 3. Alice explicitly performs recovery (user accepted safety number change)
        val bobOneTime2 = bobKeys2.preKeyRecords.first()
        val bobBundle2 = PublishedPreKeyBundle(
            registrationId = bobKeys2.registrationId,
            deviceId = 1,
            identityKey = bobKeys2.identityKeyPair.publicKey,
            signedPreKeyId = bobKeys2.signedPreKeyRecord.id,
            signedPreKey = bobKeys2.signedPreKeyRecord.keyPair.publicKey,
            signedPreKeySignature = bobKeys2.signedPreKeyRecord.signature,
            kyberPreKeyId = bobKeys2.kyberPreKeyRecord.id,
            kyberPreKey = bobKeys2.kyberPreKeyRecord.keyPair.publicKey,
            kyberPreKeySignature = bobKeys2.kyberPreKeyRecord.signature,
            oneTimePreKeyId = bobOneTime2.id,
            oneTimePreKey = bobOneTime2.keyPair.publicKey
        )

        // Explicitly update trusted identity and delete old stale session
        aliceStore.saveIdentity(bobAddress, bobBundle2.identityKey)
        aliceStore.deleteSession(bobAddress)

        // Rebuild session
        SessionBuilder(aliceStore, bobAddress).process(bobBundle2.toSignalPreKeyBundle())

        // Verify trusted identity updated
        assertEquals(bobKeys2.identityKeyPair.publicKey, aliceStore.getIdentity(bobAddress))
        assertTrue(aliceStore.isSessionValid(bobAddress))

        // 4. Test bidirectional messaging succeeds after recovery
        val aliceCipher = SessionCipher(aliceStore, bobAddress)
        val messageFromAlice = "Hello Bob after identity reset!"
        val ciphertextAlice = aliceCipher.encrypt(messageFromAlice.toByteArray(Charsets.UTF_8))

        val bobCipher = SessionCipher(bobStore2, aliceAddress)
        val decryptedByBob = bobCipher.decrypt(PreKeySignalMessage(ciphertextAlice.serialize()))
        assertEquals(messageFromAlice, String(decryptedByBob, Charsets.UTF_8))

        // Bob replies
        val replyFromBob = "Hello Alice! Reset recovery successful."
        val ciphertextBob = bobCipher.encrypt(replyFromBob.toByteArray(Charsets.UTF_8))
        val decryptedByAlice = aliceCipher.decrypt(SignalMessage(ciphertextBob.serialize()))
        assertEquals(replyFromBob, String(decryptedByAlice, Charsets.UTF_8))
    }

    @Test
    fun testObsoletePastMessageDoesNotOverrideApprovedIdentityOrTriggerRepeatedAlert() = runBlocking {
        val alicePrefs = TestSharedPreferences()
        val aliceStorePrefs = TestSharedPreferences()
        val aliceManager = createKeyManager(prefs = alicePrefs, storePrefs = aliceStorePrefs)

        val bobPrefs = TestSharedPreferences()
        val bobStorePrefs = TestSharedPreferences()
        val bobManager = createKeyManager(prefs = bobPrefs, storePrefs = bobStorePrefs)

        val aliceUid = "alice_rep"
        val bobUid = "bob_rep"

        val aliceKeys = aliceManager.ensureKeysForUser(aliceUid, preKeyCount = 5)
        val bobKeys1 = bobManager.ensureKeysForUser(bobUid, preKeyCount = 5)

        val aliceStore = aliceManager.getProtocolStore(aliceUid) as KeystoreSignalProtocolStore
        val bobAddress = SignalProtocolAddress(bobUid, 1)
        val aliceAddress = SignalProtocolAddress(aliceUid, 1)

        // 1. Establish session 1
        val bobBundle1 = PublishedPreKeyBundle(
            registrationId = bobKeys1.registrationId,
            deviceId = 1,
            identityKey = bobKeys1.identityKeyPair.publicKey,
            signedPreKeyId = bobKeys1.signedPreKeyRecord.id,
            signedPreKey = bobKeys1.signedPreKeyRecord.keyPair.publicKey,
            signedPreKeySignature = bobKeys1.signedPreKeyRecord.signature,
            kyberPreKeyId = bobKeys1.kyberPreKeyRecord.id,
            kyberPreKey = bobKeys1.kyberPreKeyRecord.keyPair.publicKey,
            kyberPreKeySignature = bobKeys1.kyberPreKeyRecord.signature,
            oneTimePreKeyId = bobKeys1.preKeyRecords.first().id,
            oneTimePreKey = bobKeys1.preKeyRecords.first().keyPair.publicKey
        )
        SessionBuilder(aliceStore, bobAddress).process(bobBundle1.toSignalPreKeyBundle())

        // Bob sends an old message under Identity 1
        val bobCipher1 = SessionCipher(bobManager.getProtocolStore(bobUid), aliceAddress)
        val oldAliceBundle = PublishedPreKeyBundle(
            registrationId = aliceKeys.registrationId,
            deviceId = 1,
            identityKey = aliceKeys.identityKeyPair.publicKey,
            signedPreKeyId = aliceKeys.signedPreKeyRecord.id,
            signedPreKey = aliceKeys.signedPreKeyRecord.keyPair.publicKey,
            signedPreKeySignature = aliceKeys.signedPreKeyRecord.signature,
            kyberPreKeyId = aliceKeys.kyberPreKeyRecord.id,
            kyberPreKey = aliceKeys.kyberPreKeyRecord.keyPair.publicKey,
            kyberPreKeySignature = aliceKeys.kyberPreKeyRecord.signature,
            oneTimePreKeyId = aliceKeys.preKeyRecords.first().id,
            oneTimePreKey = aliceKeys.preKeyRecords.first().keyPair.publicKey
        )
        SessionBuilder(bobManager.getProtocolStore(bobUid), aliceAddress).process(oldAliceBundle.toSignalPreKeyBundle())
        val oldCiphertextMessage = bobCipher1.encrypt("Old message from Bob 1".toByteArray(Charsets.UTF_8))
        assertTrue(oldCiphertextMessage is PreKeySignalMessage)

        // 2. Bob changes identity to Identity 2 and user accepts it
        bobManager.clearKeysForUser(bobUid)
        bobStorePrefs.edit().clear().commit()
        val bobKeys2 = bobManager.ensureKeysForUser(bobUid, preKeyCount = 5)

        val bobBundle2 = PublishedPreKeyBundle(
            registrationId = bobKeys2.registrationId,
            deviceId = 1,
            identityKey = bobKeys2.identityKeyPair.publicKey,
            signedPreKeyId = bobKeys2.signedPreKeyRecord.id,
            signedPreKey = bobKeys2.signedPreKeyRecord.keyPair.publicKey,
            signedPreKeySignature = bobKeys2.signedPreKeyRecord.signature,
            kyberPreKeyId = bobKeys2.kyberPreKeyRecord.id,
            kyberPreKey = bobKeys2.kyberPreKeyRecord.keyPair.publicKey,
            kyberPreKeySignature = bobKeys2.kyberPreKeyRecord.signature,
            oneTimePreKeyId = bobKeys2.preKeyRecords.first().id,
            oneTimePreKey = bobKeys2.preKeyRecords.first().keyPair.publicKey
        )

        // User accepts Identity 2
        aliceStore.saveIdentity(bobAddress, bobBundle2.identityKey)
        aliceStore.deleteSession(bobAddress)
        SessionBuilder(aliceStore, bobAddress).process(bobBundle2.toSignalPreKeyBundle())

        // 3. Stored identity is now Bob 2
        assertEquals(bobKeys2.identityKeyPair.publicKey, aliceStore.getIdentity(bobAddress))

        // 4. Now Alice encounters the old message. It has Bob 1's identity key.
        val oldPreKey = PreKeySignalMessage(oldCiphertextMessage.serialize())
        assertEquals(bobKeys1.identityKeyPair.publicKey, oldPreKey.identityKey)

        // Remote published key is Bob 2
        val remotePublishedKey = bobBundle2.identityKey
        // Verification logic: incoming key does NOT match remote published key; it is an obsolete past message
        val isCurrentRemoteIdentity = (oldPreKey.identityKey == remotePublishedKey)
        assertFalse("Obsolete past message identity must not be treated as current remote identity", isCurrentRemoteIdentity)

        // Decrypting the old message fails with UntrustedIdentityException and must NOT overwrite the trusted identity
        val aliceCipher = SessionCipher(aliceStore, bobAddress)
        assertThrows(UntrustedIdentityException::class.java) {
            aliceCipher.decrypt(oldPreKey)
        }

        // Trusted identity remains Bob 2!
        assertEquals(bobKeys2.identityKeyPair.publicKey, aliceStore.getIdentity(bobAddress))
    }

    @Test
    fun testIdentityApprovalPersistenceAcrossStoreInstances() = runBlocking {
        val alicePrefs = TestSharedPreferences()
        val aliceStorePrefs = TestSharedPreferences()
        val aliceManager = createKeyManager(prefs = alicePrefs, storePrefs = aliceStorePrefs)
        val aliceUid = "alice_persist"
        val bobUid = "bob_persist"

        val aliceKeys = aliceManager.ensureKeysForUser(aliceUid, preKeyCount = 5)
        val bobKeys = IdentityKeyPair.generate()
        val bobAddress = SignalProtocolAddress(bobUid, 1)

        val store1 = aliceManager.getProtocolStore(aliceUid) as KeystoreSignalProtocolStore
        store1.saveIdentity(bobAddress, bobKeys.publicKey)

        // Verify saved in store 1
        assertEquals(bobKeys.publicKey, store1.getIdentity(bobAddress))

        // Create a completely new store instance (simulating restart) backed by the same storage
        val store2 = KeystoreSignalProtocolStore(
            context = null,
            userIdentifier = aliceUid,
            identityKeyPair = aliceKeys.identityKeyPair,
            localRegistrationId = aliceKeys.registrationId,
            prefsOverride = aliceStorePrefs
        )

        // Must immediately reflect approved identity
        assertEquals(bobKeys.publicKey, store2.getIdentity(bobAddress))
        assertTrue(store2.isTrustedIdentity(bobAddress, bobKeys.publicKey, IdentityKeyStore.Direction.SENDING))
        assertTrue(store2.isTrustedIdentity(bobAddress, bobKeys.publicKey, IdentityKeyStore.Direction.RECEIVING))
    }
}

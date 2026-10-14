package com.e2eechat.app.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemoryIdentityKeyStore
import org.signal.libsignal.protocol.state.impl.InMemoryKyberPreKeyStore
import org.signal.libsignal.protocol.state.impl.InMemoryPreKeyStore
import org.signal.libsignal.protocol.state.impl.InMemorySessionStore
import org.signal.libsignal.protocol.state.impl.InMemorySignedPreKeyStore

class SignalProtocolStoreTest {

    @Test
    fun testKeyGenerationAndSerialization() {
        // 1. Identity Key Pair
        val identityKeyPair = IdentityKeyPair.generate()
        assertNotNull(identityKeyPair.publicKey)
        assertNotNull(identityKeyPair.privateKey)
        val serializedId = identityKeyPair.serialize()
        val deserializedId = IdentityKeyPair(serializedId)
        assertEquals(identityKeyPair.publicKey, deserializedId.publicKey)

        // 2. Signed PreKey
        val signedPreKeyPair = ECKeyPair.generate()
        val signature = identityKeyPair.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize())
        val signedPreKeyRecord = SignedPreKeyRecord(1, System.currentTimeMillis(), signedPreKeyPair, signature)
        val serializedSigned = signedPreKeyRecord.serialize()
        val deserializedSigned = SignedPreKeyRecord(serializedSigned)
        assertEquals(signedPreKeyRecord.id, deserializedSigned.id)
        assertEquals(signedPreKeyRecord.keyPair.publicKey, deserializedSigned.keyPair.publicKey)

        // 3. Kyber PreKey (PQXDH)
        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature = identityKeyPair.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())
        val kyberPreKeyRecord = KyberPreKeyRecord(1, System.currentTimeMillis(), kyberKeyPair, kyberSignature)
        val serializedKyber = kyberPreKeyRecord.serialize()
        val deserializedKyber = KyberPreKeyRecord(serializedKyber)
        assertEquals(kyberPreKeyRecord.id, deserializedKyber.id)
        assertEquals(kyberPreKeyRecord.keyPair.publicKey, deserializedKyber.keyPair.publicKey)

        // 4. One-time PreKey
        val preKeyPair = ECKeyPair.generate()
        val preKeyRecord = PreKeyRecord(1, preKeyPair)
        val serializedPreKey = preKeyRecord.serialize()
        val deserializedPreKey = PreKeyRecord(serializedPreKey)
        assertEquals(preKeyRecord.id, deserializedPreKey.id)
        assertEquals(preKeyRecord.keyPair.publicKey, deserializedPreKey.keyPair.publicKey)
    }

    @Test
    fun testPQXDHSessionSetupAndRatcheting() {
        // Setup Alice stores
        val aliceIdentity = IdentityKeyPair.generate()
        val aliceRegId = 1111
        val aliceIdStore = InMemoryIdentityKeyStore(aliceIdentity, aliceRegId)
        val alicePreKeyStore = InMemoryPreKeyStore()
        val aliceSignedPreKeyStore = InMemorySignedPreKeyStore()
        val aliceKyberStore = InMemoryKyberPreKeyStore()
        val aliceSessionStore = InMemorySessionStore()

        // Setup Bob stores & keys
        val bobIdentity = IdentityKeyPair.generate()
        val bobRegId = 2222
        val bobIdStore = InMemoryIdentityKeyStore(bobIdentity, bobRegId)
        val bobPreKeyStore = InMemoryPreKeyStore()
        val bobSignedPreKeyStore = InMemorySignedPreKeyStore()
        val bobKyberStore = InMemoryKyberPreKeyStore()
        val bobSessionStore = InMemorySessionStore()

        // Bob generates Signed PreKey
        val bobSignedKeyPair = ECKeyPair.generate()
        val bobSignedSig = bobIdentity.privateKey.calculateSignature(bobSignedKeyPair.publicKey.serialize())
        val bobSignedPreKey = SignedPreKeyRecord(1, System.currentTimeMillis(), bobSignedKeyPair, bobSignedSig)
        bobSignedPreKeyStore.storeSignedPreKey(1, bobSignedPreKey)

        // Bob generates Kyber PreKey (PQXDH)
        val bobKyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val bobKyberSig = bobIdentity.privateKey.calculateSignature(bobKyberKeyPair.publicKey.serialize())
        val bobKyberPreKey = KyberPreKeyRecord(1, System.currentTimeMillis(), bobKyberKeyPair, bobKyberSig)
        bobKyberStore.storeKyberPreKey(1, bobKyberPreKey)

        // Bob generates one-time PreKey
        val bobOneTimeKeyPair = ECKeyPair.generate()
        val bobOneTimePreKey = PreKeyRecord(1, bobOneTimeKeyPair)
        bobPreKeyStore.storePreKey(1, bobOneTimePreKey)

        // Construct Bob's PreKeyBundle with Kyber parameters
        val bobBundle = PreKeyBundle(
            bobRegId,
            1, // deviceId
            1, // preKeyId
            bobOneTimeKeyPair.publicKey,
            1, // signedPreKeyId
            bobSignedKeyPair.publicKey,
            bobSignedSig,
            bobIdentity.publicKey,
            1, // kyberPreKeyId
            bobKyberKeyPair.publicKey,
            bobKyberSig
        )

        val bobAddress = SignalProtocolAddress("bob", 1)
        val aliceAddress = SignalProtocolAddress("alice", 1)

        // Alice initializes session with Bob's bundle
        val aliceSessionBuilder = SessionBuilder(
            aliceSessionStore,
            alicePreKeyStore,
            aliceSignedPreKeyStore,
            aliceIdStore,
            bobAddress
        )
        aliceSessionBuilder.process(bobBundle)

        // Alice encrypts initial PreKeySignalMessage for Bob
        val aliceCipher = SessionCipher(
            aliceSessionStore,
            alicePreKeyStore,
            aliceSignedPreKeyStore,
            aliceKyberStore,
            aliceIdStore,
            bobAddress
        )
        val msg1 = "Hello quantum-safe world".toByteArray(Charsets.UTF_8)
        val ciphertext1 = aliceCipher.encrypt(msg1)
        assertTrue(ciphertext1 is PreKeySignalMessage)

        // Bob receives and decrypts initial PreKeySignalMessage
        val bobCipher = SessionCipher(
            bobSessionStore,
            bobPreKeyStore,
            bobSignedPreKeyStore,
            bobKyberStore,
            bobIdStore,
            aliceAddress
        )
        val decrypted1 = bobCipher.decrypt(PreKeySignalMessage(ciphertext1.serialize()))
        assertEquals("Hello quantum-safe world", String(decrypted1, Charsets.UTF_8))

        // Subsequent message from Alice to Bob (PreKey message until Bob replies and ratchets)
        val msg2 = "Second message using established session".toByteArray(Charsets.UTF_8)
        val ciphertext2 = aliceCipher.encrypt(msg2)

        val decrypted2 = if (ciphertext2 is PreKeySignalMessage) {
            bobCipher.decrypt(PreKeySignalMessage(ciphertext2.serialize()))
        } else {
            bobCipher.decrypt(SignalMessage(ciphertext2.serialize()))
        }
        assertEquals("Second message using established session", String(decrypted2, Charsets.UTF_8))

        // Bob replies to Alice (initiates ratchet advance)
        val replyMsg = "Acknowledged, ratchet step completed".toByteArray(Charsets.UTF_8)
        val replyCiphertext = bobCipher.encrypt(replyMsg)
        val decryptedReply = aliceCipher.decrypt(SignalMessage(replyCiphertext.serialize()))
        assertEquals("Acknowledged, ratchet step completed", String(decryptedReply, Charsets.UTF_8))

        // After Bob's reply has been received by Alice, Alice now produces standard SignalMessages
        val msg3 = "Third message with advanced ratchet".toByteArray(Charsets.UTF_8)
        val ciphertext3 = aliceCipher.encrypt(msg3)
        assertTrue(ciphertext3 is SignalMessage)
        val decrypted3 = bobCipher.decrypt(SignalMessage(ciphertext3.serialize()))
        assertEquals("Third message with advanced ratchet", String(decrypted3, Charsets.UTF_8))
    }

    @Test
    fun testTwoPartyBidirectionalAndPersistedSessionState() {
        // Setup initial users
        val aliceIdentity = IdentityKeyPair.generate()
        val aliceAddress = SignalProtocolAddress("alice", 1)
        val bobAddress = SignalProtocolAddress("bob", 1)

        val alicePreKeyStore = InMemoryPreKeyStore()
        val aliceSignedPreKeyStore = InMemorySignedPreKeyStore()
        val aliceKyberStore = InMemoryKyberPreKeyStore()
        val aliceSessionStore = InMemorySessionStore()
        val aliceIdStore = InMemoryIdentityKeyStore(aliceIdentity, 1001)

        val bobIdentity = IdentityKeyPair.generate()
        val bobPreKeyStore = InMemoryPreKeyStore()
        val bobSignedPreKeyStore = InMemorySignedPreKeyStore()
        val bobKyberStore = InMemoryKyberPreKeyStore()
        val bobSessionStore = InMemorySessionStore()
        val bobIdStore = InMemoryIdentityKeyStore(bobIdentity, 2002)

        // Setup Bob prekeys
        val bobSignedKeyPair = ECKeyPair.generate()
        val bobSignedSig = bobIdentity.privateKey.calculateSignature(bobSignedKeyPair.publicKey.serialize())
        val bobSignedPreKey = SignedPreKeyRecord(1, System.currentTimeMillis(), bobSignedKeyPair, bobSignedSig)
        bobSignedPreKeyStore.storeSignedPreKey(1, bobSignedPreKey)

        val bobKyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val bobKyberSig = bobIdentity.privateKey.calculateSignature(bobKyberKeyPair.publicKey.serialize())
        val bobKyberPreKey = KyberPreKeyRecord(1, System.currentTimeMillis(), bobKyberKeyPair, bobKyberSig)
        bobKyberStore.storeKyberPreKey(1, bobKyberPreKey)

        val bobOneTimeKeyPair = ECKeyPair.generate()
        val bobOneTimePreKey = PreKeyRecord(1, bobOneTimeKeyPair)
        bobPreKeyStore.storePreKey(1, bobOneTimePreKey)

        val bobBundle = PreKeyBundle(
            2002, 1, 1, bobOneTimeKeyPair.publicKey,
            1, bobSignedKeyPair.publicKey, bobSignedSig,
            bobIdentity.publicKey, 1, bobKyberKeyPair.publicKey, bobKyberSig
        )

        // Alice processes bundle and sends message
        SessionBuilder(aliceSessionStore, alicePreKeyStore, aliceSignedPreKeyStore, aliceIdStore, bobAddress)
            .process(bobBundle)

        val aliceCipher = SessionCipher(aliceSessionStore, alicePreKeyStore, aliceSignedPreKeyStore, aliceKyberStore, aliceIdStore, bobAddress)
        val c1 = aliceCipher.encrypt("Message before persistence".toByteArray(Charsets.UTF_8))

        // Bob decrypts initial PreKey message
        val bobCipher = SessionCipher(bobSessionStore, bobPreKeyStore, bobSignedPreKeyStore, bobKyberStore, bobIdStore, aliceAddress)
        val d1 = bobCipher.decrypt(PreKeySignalMessage(c1.serialize()))
        assertEquals("Message before persistence", String(d1, Charsets.UTF_8))

        // Bob replies, completing the initial handshake ratchet
        val r1 = bobCipher.encrypt("Handshake complete".toByteArray(Charsets.UTF_8))
        val dReply = aliceCipher.decrypt(SignalMessage(r1.serialize()))
        assertEquals("Handshake complete", String(dReply, Charsets.UTF_8))

        // Simulate session persistence: Serialize Bob and Alice session records to byte arrays
        val aliceSessionRecordBytes = aliceSessionStore.loadSession(bobAddress).serialize()
        val bobSessionRecordBytes = bobSessionStore.loadSession(aliceAddress).serialize()

        // Reconstruct fresh session stores from serialized state (simulating app restart / storage reload)
        val newAliceSessionStore = InMemorySessionStore()
        newAliceSessionStore.storeSession(bobAddress, SessionRecord(aliceSessionRecordBytes))

        val newBobSessionStore = InMemorySessionStore()
        newBobSessionStore.storeSession(aliceAddress, SessionRecord(bobSessionRecordBytes))

        // Continue messaging seamlessly across persisted session instances
        val newAliceCipher = SessionCipher(newAliceSessionStore, alicePreKeyStore, aliceSignedPreKeyStore, aliceKyberStore, aliceIdStore, bobAddress)
        val newBobCipher = SessionCipher(newBobSessionStore, bobPreKeyStore, bobSignedPreKeyStore, bobKyberStore, bobIdStore, aliceAddress)

        val c2 = newAliceCipher.encrypt("Message after reloading persisted session".toByteArray(Charsets.UTF_8))
        val d2 = newBobCipher.decrypt(SignalMessage(c2.serialize()))
        assertEquals("Message after reloading persisted session", String(d2, Charsets.UTF_8))
    }

    @Test
    fun testWrongKeysAndUntrustedRecipient() {
        val aliceIdentity = IdentityKeyPair.generate()
        val bobAddress = SignalProtocolAddress("bob", 1)

        val alicePreKeyStore = InMemoryPreKeyStore()
        val aliceSignedPreKeyStore = InMemorySignedPreKeyStore()
        val aliceKyberStore = InMemoryKyberPreKeyStore()
        val aliceSessionStore = InMemorySessionStore()
        val aliceIdStore = InMemoryIdentityKeyStore(aliceIdentity, 1001)

        val bobIdentity = IdentityKeyPair.generate()
        val eveIdentity = IdentityKeyPair.generate() // Impostor key

        val bobSignedKeyPair = ECKeyPair.generate()
        // Sign with Eve's key instead of Bob's identity key to forge bundle
        val forgedSig = eveIdentity.privateKey.calculateSignature(bobSignedKeyPair.publicKey.serialize())

        val bobKyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val forgedKyberSig = eveIdentity.privateKey.calculateSignature(bobKyberKeyPair.publicKey.serialize())

        val forgedBundle = PreKeyBundle(
            2002, 1, PreKeyBundle.NULL_PRE_KEY_ID, null,
            1, bobSignedKeyPair.publicKey, forgedSig,
            bobIdentity.publicKey, 1, bobKyberKeyPair.publicKey, forgedKyberSig
        )

        // SessionBuilder must reject the bundle because the signature is invalid for Bob's IdentityKey
        val sessionBuilder = SessionBuilder(aliceSessionStore, alicePreKeyStore, aliceSignedPreKeyStore, aliceIdStore, bobAddress)
        assertThrows(org.signal.libsignal.protocol.InvalidKeyException::class.java) {
            sessionBuilder.process(forgedBundle)
        }
    }
}

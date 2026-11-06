package com.e2eechat.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Unit test suite for [AttachmentCrypto].
 *
 * Validates:
 * 1. Encryption and decryption round-trip for various payload sizes.
 * 2. Fresh random IV generation on each invocation (IV uniqueness).
 * 3. Rejection of tampered ciphertext and modified authentication tags.
 * 4. SHA-256 ciphertext hash verification and mismatch detection.
 * 5. Full AttachmentDescriptor serialization, deserialization, and field validation.
 * 6. Malformed descriptor handling and transparent fallback for legacy text.
 * 7. Key and IV redaction in toString() representations.
 */
class AttachmentCryptoTest {

    @Test
    fun testEncryptionDecryptionRoundTrip() {
        val sampleData = "TeleX Secure Image Payload: Test byte buffer for AES-GCM".toByteArray(Charsets.UTF_8)

        val encrypted = AttachmentCrypto.encrypt(sampleData)

        assertNotNull(encrypted.ciphertext)
        assertTrue("Ciphertext must include data plus 16-byte GCM tag", encrypted.ciphertext.size == sampleData.size + 16)
        assertEquals(32, encrypted.keyBytes.size)
        assertEquals(12, encrypted.ivBytes.size)
        assertEquals(64, encrypted.sha256Hex.length)
        assertEquals(sampleData.size.toLong(), encrypted.plaintextLength)

        val decrypted = AttachmentCrypto.decrypt(
            ciphertext = encrypted.ciphertext,
            keyBytes = encrypted.keyBytes,
            ivBytes = encrypted.ivBytes,
            expectedSha256Hex = encrypted.sha256Hex
        )

        assertArrayEquals("Decrypted payload must match original plaintext exactly", sampleData, decrypted)
    }

    @Test
    fun testEmptyAndLargePayloadRoundTrip() {
        // 1. Empty payload
        val emptyBytes = ByteArray(0)
        val encryptedEmpty = AttachmentCrypto.encrypt(emptyBytes)
        assertEquals(16, encryptedEmpty.ciphertext.size) // 16-byte GCM auth tag only
        val decryptedEmpty = AttachmentCrypto.decrypt(
            encryptedEmpty.ciphertext,
            encryptedEmpty.keyBytes,
            encryptedEmpty.ivBytes,
            encryptedEmpty.sha256Hex
        )
        assertArrayEquals(emptyBytes, decryptedEmpty)

        // 2. Large simulated photo payload (500 KB)
        val largeBytes = ByteArray(500 * 1024)
        SecureRandom().nextBytes(largeBytes)
        val encryptedLarge = AttachmentCrypto.encrypt(largeBytes)
        val decryptedLarge = AttachmentCrypto.decrypt(
            encryptedLarge.ciphertext,
            encryptedLarge.keyBytes,
            encryptedLarge.ivBytes,
            encryptedLarge.sha256Hex
        )
        assertArrayEquals(largeBytes, decryptedLarge)
    }

    @Test
    fun testEachEncryptionGeneratesUniqueIvAndKey() {
        val data = "Fixed plaintext for IV uniqueness test".toByteArray(Charsets.UTF_8)

        val enc1 = AttachmentCrypto.encrypt(data)
        val enc2 = AttachmentCrypto.encrypt(data)
        val enc3 = AttachmentCrypto.encrypt(data)

        // Ensure IVs are never repeated
        assertFalse(enc1.ivBytes.contentEquals(enc2.ivBytes))
        assertFalse(enc1.ivBytes.contentEquals(enc3.ivBytes))
        assertFalse(enc2.ivBytes.contentEquals(enc3.ivBytes))

        // Ensure keys are unique
        assertFalse(enc1.keyBytes.contentEquals(enc2.keyBytes))
        assertFalse(enc1.keyBytes.contentEquals(enc3.keyBytes))

        // Ciphertexts must differ completely due to distinct IVs and keys
        assertFalse(enc1.ciphertext.contentEquals(enc2.ciphertext))
        assertFalse(enc1.sha256Hex == enc2.sha256Hex)
    }

    @Test
    fun testTamperedCiphertextRejectedByAuthentication() {
        val data = "Confidential image data".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(data)

        val tamperedCiphertext = encrypted.ciphertext.clone()
        // Flip a bit in the body of the ciphertext
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()

        // Decryption must fail with authentication exception even if hash check is bypassed
        assertThrows(AttachmentAuthenticationException::class.java) {
            AttachmentCrypto.decrypt(
                ciphertext = tamperedCiphertext,
                keyBytes = encrypted.keyBytes,
                ivBytes = encrypted.ivBytes,
                expectedSha256Hex = null // bypass hash check to test GCM tag check directly
            )
        }
    }

    @Test
    fun testTamperedAuthTagRejected() {
        val data = "Confidential image data with auth tag".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(data)

        val tamperedTag = encrypted.ciphertext.clone()
        // Flip a bit in the final byte of the 16-byte authentication tag
        tamperedTag[tamperedTag.size - 1] = (tamperedTag[tamperedTag.size - 1].toInt() xor 0xFF).toByte()

        assertThrows(AttachmentAuthenticationException::class.java) {
            AttachmentCrypto.decrypt(
                ciphertext = tamperedTag,
                keyBytes = encrypted.keyBytes,
                ivBytes = encrypted.ivBytes,
                expectedSha256Hex = null
            )
        }
    }

    @Test
    fun testSha256HashMismatchDetectedBeforeDecryption() {
        val data = "Integrity check test".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(data)

        val wrongHash = "0000000000000000000000000000000000000000000000000000000000000000"

        val ex = assertThrows(AttachmentHashMismatchException::class.java) {
            AttachmentCrypto.decrypt(
                ciphertext = encrypted.ciphertext,
                keyBytes = encrypted.keyBytes,
                ivBytes = encrypted.ivBytes,
                expectedSha256Hex = wrongHash
            )
        }
        assertTrue(ex.message!!.contains("integrity verification failed"))
    }

    @Test
    fun testDescriptorRoundTripSerializationAndParsing() {
        val rawImage = "Simulated JPEG header and raster scanlines".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(rawImage)

        val descriptor = AttachmentCrypto.createDescriptor(
            encrypted = encrypted,
            storagePath = "chat-images/test_attachment_uuid_1234.enc",
            mimeType = "image/jpeg",
            caption = "Look at this photo! \uD83D\uDCF7 \"Quotes\" & \\slashes\\"
        )

        // 1. Serialize to JSON
        val json = AttachmentCrypto.serializeDescriptor(descriptor)
        assertTrue(json.contains("\"type\":\"attachment\""))
        assertTrue(json.contains("\"storagePath\":\"chat-images/test_attachment_uuid_1234.enc\""))
        assertTrue(json.contains("\"mimeType\":\"image/jpeg\""))

        // 2. Parse from JSON
        val parsed = AttachmentCrypto.parseDescriptorOrNull(json)
        assertNotNull("Descriptor must be parsed successfully", parsed)
        assertEquals(descriptor.type, parsed!!.type)
        assertEquals(descriptor.storagePath, parsed.storagePath)
        assertEquals(descriptor.keyBase64, parsed.keyBase64)
        assertEquals(descriptor.ivBase64, parsed.ivBase64)
        assertEquals(descriptor.mimeType, parsed.mimeType)
        assertEquals(descriptor.sha256Hex, parsed.sha256Hex)
        assertEquals(descriptor.caption, parsed.caption)
        assertEquals(descriptor.sizeBytes, parsed.sizeBytes)

        // 3. Full decryption via parsed descriptor
        val decrypted = AttachmentCrypto.decryptWithDescriptor(parsed, encrypted.ciphertext)
        assertArrayEquals(rawImage, decrypted)
    }

    @Test
    fun testLegacyTextMessageParsingReturnsNull() {
        // Plain text messages must return null transparently so they are handled as normal text
        assertNull(AttachmentCrypto.parseDescriptorOrNull(null))
        assertNull(AttachmentCrypto.parseDescriptorOrNull(""))
        assertNull(AttachmentCrypto.parseDescriptorOrNull("   "))
        assertNull(AttachmentCrypto.parseDescriptorOrNull("Hello, how are you?"))
        assertNull(AttachmentCrypto.parseDescriptorOrNull("🔒 Encrypted message"))
        assertNull(AttachmentCrypto.parseDescriptorOrNull("{This is not valid json}"))
        assertNull(AttachmentCrypto.parseDescriptorOrNull("{\"message\":\"hello world\"}"))
    }

    @Test
    fun testMalformedDescriptorsRejected() {
        val validKey = Base64Compat.encodeToString(ByteArray(32))
        val validIv = Base64Compat.encodeToString(ByteArray(12))
        val validHash = "a".repeat(64)

        // 1. Wrong type
        assertNull(
            AttachmentCrypto.parseDescriptorOrNull(
                "{\"type\":\"other\",\"storagePath\":\"path\",\"key\":\"$validKey\",\"iv\":\"$validIv\",\"mimeType\":\"image/jpeg\",\"sha256\":\"$validHash\"}"
            )
        )

        // 2. Missing storagePath
        assertNull(
            AttachmentCrypto.parseDescriptorOrNull(
                "{\"type\":\"attachment\",\"key\":\"$validKey\",\"iv\":\"$validIv\",\"mimeType\":\"image/jpeg\",\"sha256\":\"$validHash\"}"
            )
        )

        // 3. Invalid key length (16 bytes instead of 32)
        val shortKey = Base64Compat.encodeToString(ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentCrypto.AttachmentDescriptor(
                storagePath = "path",
                keyBase64 = shortKey,
                ivBase64 = validIv,
                mimeType = "image/jpeg",
                sha256Hex = validHash
            )
        }

        // 4. Invalid IV length (16 bytes instead of 12)
        val longIv = Base64Compat.encodeToString(ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentCrypto.AttachmentDescriptor(
                storagePath = "path",
                keyBase64 = validKey,
                ivBase64 = longIv,
                mimeType = "image/jpeg",
                sha256Hex = validHash
            )
        }

        // 5. Invalid SHA-256 hex length (63 characters instead of 64)
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentCrypto.AttachmentDescriptor(
                storagePath = "path",
                keyBase64 = validKey,
                ivBase64 = validIv,
                mimeType = "image/jpeg",
                sha256Hex = "a".repeat(63)
            )
        }

        // 6. Non-hex characters in SHA-256
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentCrypto.AttachmentDescriptor(
                storagePath = "path",
                keyBase64 = validKey,
                ivBase64 = validIv,
                mimeType = "image/jpeg",
                sha256Hex = "z".repeat(64)
            )
        }
    }

    @Test
    fun testKeyAndIvRedactedInToString() {
        val encrypted = AttachmentCrypto.encrypt("Secret".toByteArray(Charsets.UTF_8))
        val descriptor = AttachmentCrypto.createDescriptor(
            encrypted = encrypted,
            storagePath = "chat-images/secret.enc",
            mimeType = "image/jpeg"
        )

        val encryptedStr = encrypted.toString()
        val descriptorStr = descriptor.toString()

        // Key and IV values must NOT be present in string outputs
        assertFalse(encryptedStr.contains(Base64Compat.encodeToString(encrypted.keyBytes)))
        assertFalse(encryptedStr.contains(Base64Compat.encodeToString(encrypted.ivBytes)))
        assertTrue(encryptedStr.contains("[REDACTED]"))

        assertFalse(descriptorStr.contains(descriptor.keyBase64))
        assertFalse(descriptorStr.contains(descriptor.ivBase64))
        assertTrue(descriptorStr.contains("[REDACTED]"))
    }
}

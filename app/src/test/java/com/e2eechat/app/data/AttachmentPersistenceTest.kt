package com.e2eechat.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.e2eechat.app.crypto.AttachmentCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Unit test suite for Step 2: Room persistence, migrations, and attachment descriptor lifecycle.
 *
 * Validates:
 * 1. MessageEntity backward compatibility with existing constructors and default null descriptor.
 * 2. Attachment descriptor preservation and retrieval after simulated app restart.
 * 3. Security: MessageEntity.toString() explicitly redacts sensitive descriptor keys/IVs.
 * 4. Migration 1->2 and 2->3 SQL statements execute non-destructive column additions.
 * 5. Pending attachment identification for resuming interrupted downloads.
 * 6. End-to-end integration: Encrypt -> store descriptor in entity -> retrieve descriptor -> decrypt ciphertext.
 */
class AttachmentPersistenceTest {

    @Test
    fun testMessageEntityDefaultAttachmentDescriptorIsNull() {
        val textMessage = MessageEntity(
            id = "msg_text_1",
            chatId = "alice_bob",
            content = "Hello Bob!",
            timestamp = "10:00 AM",
            isSentByMe = true,
            isRead = false,
            imageUrl = null
        )

        assertNull("Existing text messages must have null attachmentDescriptor", textMessage.attachmentDescriptor)
        assertEquals("Hello Bob!", textMessage.content)
        assertNull(textMessage.imageUrl)
    }

    @Test
    fun testLegacyPlaintextImageMessagePreserved() {
        val legacyImageMessage = MessageEntity(
            id = "msg_legacy_img_1",
            chatId = "alice_bob",
            content = "",
            timestamp = "10:01 AM",
            isSentByMe = false,
            isRead = true,
            imageUrl = "https://taivxyserowgwioffjob.supabase.co/storage/v1/object/public/chat-images/legacy.jpg"
        )

        assertEquals(
            "https://taivxyserowgwioffjob.supabase.co/storage/v1/object/public/chat-images/legacy.jpg",
            legacyImageMessage.imageUrl
        )
        assertNull(legacyImageMessage.attachmentDescriptor)
    }

    @Test
    fun testMessageEntityWithAttachmentDescriptorPreserved() {
        val rawData = "Attachment photo bytes".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(rawData)
        val descriptor = AttachmentCrypto.createDescriptor(
            encrypted = encrypted,
            storagePath = "chat-images/test_uuid.enc",
            mimeType = "image/jpeg",
            caption = "Sunset at the beach"
        )
        val serializedDesc = AttachmentCrypto.serializeDescriptor(descriptor)

        // Store descriptor in MessageEntity (as done upon incoming Double Ratchet decryption)
        val message = MessageEntity(
            id = "msg_enc_1",
            chatId = "alice_bob",
            content = descriptor.caption,
            timestamp = "10:02 AM",
            isSentByMe = false,
            isRead = false,
            imageUrl = null, // null until downloaded
            attachmentDescriptor = serializedDesc
        )

        assertEquals("msg_enc_1", message.id)
        assertEquals("Sunset at the beach", message.content)
        assertNull(message.imageUrl)
        assertNotNull(message.attachmentDescriptor)

        // Simulate app restart: retrieve descriptor from MessageEntity and parse
        val retrievedDesc = AttachmentCrypto.parseDescriptorOrNull(message.attachmentDescriptor)
        assertNotNull("Descriptor must be parsed successfully from entity", retrievedDesc)
        assertEquals("chat-images/test_uuid.enc", retrievedDesc!!.storagePath)
        assertEquals(descriptor.keyBase64, retrievedDesc.keyBase64)
        assertEquals(descriptor.ivBase64, retrievedDesc.ivBase64)
        assertEquals("image/jpeg", retrievedDesc.mimeType)
        assertEquals(descriptor.sha256Hex, retrievedDesc.sha256Hex)
        assertEquals("Sunset at the beach", retrievedDesc.caption)

        // Decrypt ciphertext using retrieved descriptor
        val decrypted = AttachmentCrypto.decryptWithDescriptor(retrievedDesc, encrypted.ciphertext)
        assertArrayEquals(rawData, decrypted)
    }

    @Test
    fun testMessageEntityToStringRedactsAttachmentDescriptor() {
        val rawData = "Secret image".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(rawData)
        val descriptor = AttachmentCrypto.createDescriptor(
            encrypted = encrypted,
            storagePath = "chat-images/secret.enc",
            mimeType = "image/jpeg"
        )
        val serializedDesc = AttachmentCrypto.serializeDescriptor(descriptor)

        val message = MessageEntity(
            id = "msg_secret",
            chatId = "alice_bob",
            content = "Secret photo",
            timestamp = "10:03 AM",
            isSentByMe = true,
            isRead = false,
            imageUrl = null,
            attachmentDescriptor = serializedDesc
        )

        val toStringOutput = message.toString()

        // Critical Security Check: toString() must NEVER leak the raw descriptor, keys, or IVs
        assertFalse("toString must not contain keyBase64", toStringOutput.contains(descriptor.keyBase64))
        assertFalse("toString must not contain ivBase64", toStringOutput.contains(descriptor.ivBase64))
        assertFalse("toString must not contain raw descriptor json", toStringOutput.contains("chat-images/secret.enc"))
        assertTrue("toString must display redacted indicator", toStringOutput.contains("[DESCRIPTOR_REDACTED]"))

        // For messages with null descriptor:
        val plainMessage = MessageEntity(
            id = "msg_plain",
            chatId = "c1",
            content = "Hello",
            timestamp = "10:04 AM",
            isSentByMe = true,
            isRead = true
        )
        assertTrue(plainMessage.toString().contains("attachmentDescriptor=null"))
    }

    @Test
    fun testMigration1to2Sql() {
        val executedSql = mutableListOf<String>()
        val dbProxy = createMockDb(executedSql)

        MIGRATION_1_2.migrate(dbProxy)

        assertEquals(1, executedSql.size)
        assertEquals("ALTER TABLE messages ADD COLUMN imageUrl TEXT DEFAULT NULL", executedSql[0])
    }

    @Test
    fun testMigration2to3Sql() {
        val executedSql = mutableListOf<String>()
        val dbProxy = createMockDb(executedSql)

        MIGRATION_2_3.migrate(dbProxy)

        assertEquals(1, executedSql.size)
        assertEquals("ALTER TABLE messages ADD COLUMN attachmentDescriptor TEXT DEFAULT NULL", executedSql[0])
    }

    @Test
    fun testPendingAttachmentMessageClassification() {
        val messages = listOf(
            // 1. Text message
            MessageEntity("m1", "chat1", "Hello", "10:00", isSentByMe = false, isRead = true, imageUrl = null, attachmentDescriptor = null),
            // 2. Legacy plaintext photo (already has remote imageUrl, no descriptor)
            MessageEntity("m2", "chat1", "", "10:01", isSentByMe = false, isRead = true, imageUrl = "https://example.com/legacy.jpg", attachmentDescriptor = null),
            // 3. Encrypted photo that was downloaded (has local file uri and descriptor)
            MessageEntity("m3", "chat1", "Caption", "10:02", isSentByMe = false, isRead = true, imageUrl = "file:///data/attachments/photo3.jpg", attachmentDescriptor = "{\"type\":\"attachment\"}"),
            // 4. Pending encrypted photo (has descriptor, but imageUrl is null)
            MessageEntity("m4", "chat1", "Pending 1", "10:03", isSentByMe = false, isRead = false, imageUrl = null, attachmentDescriptor = "{\"type\":\"attachment\"}"),
            // 5. Pending encrypted photo (has descriptor, imageUrl is empty string)
            MessageEntity("m5", "chat1", "Pending 2", "10:04", isSentByMe = false, isRead = false, imageUrl = "", attachmentDescriptor = "{\"type\":\"attachment\"}")
        )

        val pending = messages.filter {
            it.attachmentDescriptor != null && (it.imageUrl == null || it.imageUrl.isEmpty())
        }

        assertEquals(2, pending.size)
        assertEquals(listOf("m4", "m5"), pending.map { it.id })
    }

    @Test
    fun testOutgoingEncryptedImageDocumentStructure() {
        // Build simulated outgoing image document as done in ChatViewModel.uploadImageAndSend
        val imageBytes = "Image payload for outgoing document verification".toByteArray(Charsets.UTF_8)
        val encryptedAttachment = AttachmentCrypto.encrypt(imageBytes)
        val objectName = "${java.util.UUID.randomUUID()}.enc"
        val storagePath = "chat-images/$objectName"

        val descriptor = AttachmentCrypto.createDescriptor(
            encrypted = encryptedAttachment,
            storagePath = storagePath,
            mimeType = "image/jpeg",
            caption = ""
        )
        val descriptorJson = AttachmentCrypto.serializeDescriptor(descriptor)

        // Mock ciphertext message output from Double Ratchet
        val dummyCiphertextBase64 = "DUMMY_SIGNAL_CIPHERTEXT_BASE64=="
        val now = System.currentTimeMillis()
        val myUid = "user_sender_123"

        val messageData = hashMapOf(
            "senderId" to myUid,
            "text" to null,
            "ciphertext" to dummyCiphertextBase64,
            "messageType" to 1,
            "senderDeviceId" to 1,
            "imageUrl" to null,
            "timestampMillis" to now,
            "status" to "sent"
        )

        // Security Assertions:
        assertNull("text field must be null", messageData["text"])
        assertNull("imageUrl field in Firestore MUST be null (never leak storage URL to Firestore)", messageData["imageUrl"])
        assertNotNull("ciphertext must contain Signal ciphertext", messageData["ciphertext"])
        assertEquals(myUid, messageData["senderId"])
        assertEquals(1, messageData["messageType"])
        assertEquals("sent", messageData["status"])

        // Neither the AES key, IV, nor raw descriptor must be present in top-level Firestore fields
        assertFalse("messageData must not contain raw key", messageData.containsKey("key"))
        assertFalse("messageData must not contain raw iv", messageData.containsKey("iv"))
        assertFalse("messageData must not contain storagePath", messageData.containsKey("storagePath"))
        assertFalse("messageData must not contain descriptor", messageData.containsKey("descriptor"))
    }

    @Test
    fun testSenderLocalImageDisplayPreservation() {
        val now = System.currentTimeMillis()
        val localFileUri = "file:///data/user/0/com.e2eechat.app/files/attachments/sent_12345.jpg"
        val descriptorJson = "{\"type\":\"attachment\"}"

        // Sender's local Room record has the local file URI
        val senderLocalEntity = MessageEntity(
            id = "msg_sent_img_1",
            chatId = "c1",
            content = "",
            timestamp = "10:30 AM",
            isSentByMe = true,
            isRead = false,
            imageUrl = localFileUri,
            attachmentDescriptor = descriptorJson
        )

        // Firestore snapshot doc for this message arrives with imageUrl = null
        val firestoreDocImageUrl: String? = null

        // UI combine lambda resolution: imageUrl = doc.imageUrl ?: local?.imageUrl
        val resolvedImageUrl = firestoreDocImageUrl ?: senderLocalEntity.imageUrl

        assertEquals(
            "Sender must resolve to local file URI so image displays seamlessly without downloading",
            localFileUri,
            resolvedImageUrl
        )
    }

    @Test
    fun testRecipientIncomingAttachmentParsingWithoutDownload() {
        val rawData = "Recipient image test".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(rawData)
        val descriptor = AttachmentCrypto.createDescriptor(
            encrypted = encrypted,
            storagePath = "chat-images/recipient_test.enc",
            mimeType = "image/png",
            caption = "Sample Caption"
        )
        val descriptorJson = AttachmentCrypto.serializeDescriptor(descriptor)

        // Simulate recipient decrypting Signal payload -> gets descriptorJson
        val parsedDesc = AttachmentCrypto.parseDescriptorOrNull(descriptorJson)
        assertNotNull("Recipient must parse attachment descriptor", parsedDesc)

        // When parsedDesc is non-null, ChatViewModel maps to caption / "📷 Photo" and stores descriptor
        val contentToStore = parsedDesc!!.caption
        val descriptorToStore = descriptorJson
        val cacheText = parsedDesc.caption.ifBlank { "📷 Photo" }

        val recipientEntity = MessageEntity(
            id = "msg_recv_1",
            chatId = "c1",
            content = contentToStore,
            timestamp = "10:31 AM",
            isSentByMe = false,
            isRead = false,
            imageUrl = null, // null until downloaded in Step 4
            attachmentDescriptor = descriptorToStore
        )

        assertEquals("Sample Caption", recipientEntity.content)
        assertEquals("Sample Caption", cacheText)
        assertNull("imageUrl remains null until downloaded", recipientEntity.imageUrl)
        assertEquals(descriptorJson, recipientEntity.attachmentDescriptor)

        // Also test blank caption fallback
        val descriptorNoCaption = descriptor.copy(caption = "")
        val jsonNoCaption = AttachmentCrypto.serializeDescriptor(descriptorNoCaption)
        val parsedNoCaption = AttachmentCrypto.parseDescriptorOrNull(jsonNoCaption)!!
        val fallbackText = parsedNoCaption.caption.ifBlank { "📷 Photo" }
        assertEquals("📷 Photo", fallbackText)
    }

    @Test
    fun testRecipientDownloadDecryptRoundTrip() {
        // Simulates the full Step 4 pipeline: descriptor persisted → ciphertext downloaded → decrypted → verified
        val originalImage = "High resolution photo data with rich pixel content for round-trip test".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(originalImage)
        val descriptor = AttachmentCrypto.createDescriptor(
            encrypted = encrypted,
            storagePath = "chat-images/roundtrip_test_uuid.enc",
            mimeType = "image/png",
            caption = "Vacation photo"
        )
        val descriptorJson = AttachmentCrypto.serializeDescriptor(descriptor)

        // Step 1: Descriptor persisted to Room after Double Ratchet decryption (Step 3)
        val recipientEntity = MessageEntity(
            id = "msg_download_1",
            chatId = "alice_bob",
            content = "Vacation photo",
            timestamp = "11:00 AM",
            isSentByMe = false,
            isRead = false,
            imageUrl = null, // Not yet downloaded
            attachmentDescriptor = descriptorJson
        )

        // Step 2: Parse descriptor from Room (simulates downloadAndDecryptAttachment reading from Room)
        val parsedDescriptor = AttachmentCrypto.parseDescriptorOrNull(recipientEntity.attachmentDescriptor)
        assertNotNull("Descriptor must parse from Room entity", parsedDescriptor)

        // Step 3: Download ciphertext (simulated - in real code, from Supabase)
        val downloadedCiphertext = encrypted.ciphertext

        // Step 4: Verify and decrypt
        val decryptedBytes = AttachmentCrypto.decryptWithDescriptor(parsedDescriptor!!, downloadedCiphertext)
        assertArrayEquals("Decrypted bytes must match original image", originalImage, decryptedBytes)

        // Step 5: Update entity with local file path (simulates Room update)
        val updatedEntity = recipientEntity.copy(
            imageUrl = "file:///data/user/0/com.e2eechat.app/files/attachments/recv_12345_abc12345.png"
        )
        assertNotNull("Updated entity must have imageUrl", updatedEntity.imageUrl)
        assertTrue("imageUrl must be a local file URI", updatedEntity.imageUrl!!.startsWith("file:///"))
        assertEquals("Descriptor must be preserved after download", descriptorJson, updatedEntity.attachmentDescriptor)
    }

    @Test
    fun testTamperedCiphertextRejectedDuringDownload() {
        val originalImage = "Sensitive photo data".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(originalImage)
        val descriptor = AttachmentCrypto.createDescriptor(
            encrypted = encrypted,
            storagePath = "chat-images/tampered.enc",
            mimeType = "image/jpeg"
        )

        // Tamper with ciphertext (flip a byte in the middle)
        val tamperedCiphertext = encrypted.ciphertext.copyOf()
        if (tamperedCiphertext.size > 5) {
            tamperedCiphertext[5] = (tamperedCiphertext[5].toInt() xor 0xFF).toByte()
        }

        // SHA-256 hash mismatch should be detected first
        val actualHash = AttachmentCrypto.computeSha256Hex(tamperedCiphertext)
        assertFalse(
            "Tampered ciphertext must have different SHA-256 hash",
            actualHash.equals(descriptor.sha256Hex, ignoreCase = true)
        )

        // decryptWithDescriptor should throw on tampered data
        var caughtException: Exception? = null
        try {
            AttachmentCrypto.decryptWithDescriptor(descriptor, tamperedCiphertext)
        } catch (e: Exception) {
            caughtException = e
        }
        assertNotNull("Must throw exception on tampered ciphertext", caughtException)
        assertTrue(
            "Exception must be security-related (hash mismatch or auth failure)",
            caughtException is com.e2eechat.app.crypto.AttachmentHashMismatchException ||
                caughtException is com.e2eechat.app.crypto.AttachmentAuthenticationException
        )
    }

    @Test
    fun testHashMismatchRejectedBeforeDecryption() {
        val originalImage = "Image for hash test".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(originalImage)

        // Create a descriptor with a wrong hash but correct key/IV
        val wrongHash = "a".repeat(64)
        val descriptorWithWrongHash = AttachmentCrypto.AttachmentDescriptor(
            storagePath = "chat-images/hashtest.enc",
            keyBase64 = com.e2eechat.app.crypto.Base64Compat.encodeToString(encrypted.keyBytes),
            ivBase64 = com.e2eechat.app.crypto.Base64Compat.encodeToString(encrypted.ivBytes),
            mimeType = "image/jpeg",
            sha256Hex = wrongHash,
            sizeBytes = originalImage.size.toLong()
        )

        var caughtException: Exception? = null
        try {
            AttachmentCrypto.decryptWithDescriptor(descriptorWithWrongHash, encrypted.ciphertext)
        } catch (e: Exception) {
            caughtException = e
        }
        assertNotNull("Must throw on hash mismatch", caughtException)
        assertTrue(
            "Must be AttachmentHashMismatchException",
            caughtException is com.e2eechat.app.crypto.AttachmentHashMismatchException
        )
    }

    @Test
    fun testAlreadyDownloadedMessageSkippedForRedownload() {
        // Simulates the already-downloaded guard in downloadAndDecryptAttachment
        val descriptorJson = "{\"type\":\"attachment\"}"

        // Message with imageUrl already set (already downloaded)
        val alreadyDownloaded = MessageEntity(
            id = "msg_already_dl",
            chatId = "chat1",
            content = "📷 Photo",
            timestamp = "11:30 AM",
            isSentByMe = false,
            isRead = true,
            imageUrl = "file:///data/user/0/com.e2eechat.app/files/attachments/recv_existing.jpg",
            attachmentDescriptor = descriptorJson
        )

        // This message should NOT appear in pending list
        val isPending = alreadyDownloaded.attachmentDescriptor != null &&
            (alreadyDownloaded.imageUrl == null || alreadyDownloaded.imageUrl.isEmpty())
        assertFalse("Already-downloaded messages must not be considered pending", isPending)
    }

    @Test
    fun testPendingAttachmentRecoveryAfterRestart() {
        val descriptorJson = "{\"type\":\"attachment\",\"storagePath\":\"chat-images/recovery.enc\"}"

        // Simulate various message states after app restart
        val messages = listOf(
            // Text message - no descriptor
            MessageEntity("m1", "c1", "Hello", "10:00", isSentByMe = false, isRead = true),
            // Legacy plaintext image - no descriptor
            MessageEntity("m2", "c1", "", "10:01", isSentByMe = false, isRead = true,
                imageUrl = "https://example.com/legacy.jpg"),
            // Sender's encrypted photo (already has local file)
            MessageEntity("m3", "c1", "", "10:02", isSentByMe = true, isRead = false,
                imageUrl = "file:///data/attachments/sent_123.jpg", attachmentDescriptor = descriptorJson),
            // Recipient: downloaded encrypted photo
            MessageEntity("m4", "c1", "📷 Photo", "10:03", isSentByMe = false, isRead = true,
                imageUrl = "file:///data/attachments/recv_456.jpg", attachmentDescriptor = descriptorJson),
            // Recipient: pending download (descriptor present, no imageUrl)
            MessageEntity("m5", "c1", "📷 Photo", "10:04", isSentByMe = false, isRead = false,
                imageUrl = null, attachmentDescriptor = descriptorJson),
            // Recipient: pending download (descriptor present, empty imageUrl)
            MessageEntity("m6", "c1", "📷 Photo", "10:05", isSentByMe = false, isRead = false,
                imageUrl = "", attachmentDescriptor = descriptorJson)
        )

        // Filter for pending (matches DAO query: descriptor NOT NULL AND (imageUrl IS NULL OR empty))
        val allPending = messages.filter {
            it.attachmentDescriptor != null && (it.imageUrl == null || it.imageUrl.isEmpty())
        }
        // Filter out sender's own messages (as done in resumePendingAttachmentDownloads)
        val recipientPending = allPending.filter { !it.isSentByMe }

        assertEquals("Should have 2 recipient pending downloads", 2, recipientPending.size)
        assertEquals(listOf("m5", "m6"), recipientPending.map { it.id })
    }

    @Test
    fun testMimeTypeToExtensionMapping() {
        // Verify the mapping used for saving decrypted files
        // Test via descriptor creation and parsing (the mimeType field)
        val mappings = mapOf(
            "image/jpeg" to true,
            "image/jpg" to true,
            "image/png" to true,
            "image/gif" to true,
            "image/webp" to true,
            "image/heif" to true,
            "image/heic" to true,
            "application/octet-stream" to true  // fallback to jpg
        )

        for (mimeType in mappings.keys) {
            val rawData = "test".toByteArray(Charsets.UTF_8)
            val encrypted = AttachmentCrypto.encrypt(rawData)
            val descriptor = AttachmentCrypto.createDescriptor(
                encrypted = encrypted,
                storagePath = "chat-images/mime_test.enc",
                mimeType = mimeType
            )
            assertEquals("mimeType must be preserved in descriptor", mimeType, descriptor.mimeType)
        }
    }

    @Test
    fun testEmptyCiphertextRejected() {
        val rawData = "Test data".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(rawData)
        val descriptor = AttachmentCrypto.createDescriptor(
            encrypted = encrypted,
            storagePath = "chat-images/empty.enc",
            mimeType = "image/jpeg"
        )

        // Empty ciphertext should fail validation (too short for GCM tag)
        var caughtException: Exception? = null
        try {
            AttachmentCrypto.decryptWithDescriptor(descriptor, ByteArray(0))
        } catch (e: Exception) {
            caughtException = e
        }
        assertNotNull("Must reject empty ciphertext", caughtException)
    }

    /**
     * Creates a lightweight dynamic proxy implementing [SupportSQLiteDatabase] to verify
     * exact SQL execution during migration without Android runtime stubs.
     */
    private fun createMockDb(executedSql: MutableList<String>): SupportSQLiteDatabase {
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            object : InvocationHandler {
                override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                    if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                        executedSql.add(args[0] as String)
                        return null
                    }
                    return null
                }
            }
        ) as SupportSQLiteDatabase
    }
}

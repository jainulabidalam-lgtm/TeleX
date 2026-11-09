package com.e2eechat.app.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-performance, zero-knowledge cryptographic engine for TeleX media attachments.
 *
 * Security Specifications:
 * - Cipher: Standard AES-256 in Galois/Counter Mode (AES/GCM/NoPadding).
 * - Key length: 256 bits (32 bytes), cryptographically random per attachment.
 * - Nonce/IV length: 96 bits (12 bytes), fresh cryptographically random per attachment.
 * - Tag length: 128 bits (16 bytes) authentication tag.
 * - Integrity hashing: SHA-256 over raw ciphertext bytes.
 * - Privacy: Keys, IVs, and plaintext are never logged or exposed to transport layers.
 */
object AttachmentCrypto {

    const val KEY_SIZE_BYTES = 32
    const val IV_SIZE_BYTES = 12
    const val TAG_LENGTH_BITS = 128
    const val TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8
    const val CIPHER_ALGO = "AES/GCM/NoPadding"
    const val KEY_ALGO = "AES"
    const val HASH_ALGO = "SHA-256"
    const val TYPE_ATTACHMENT = "attachment"

    private val SHA256_HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * Container holding encrypted ciphertext and the ephemeral symmetric key material.
     */
    data class EncryptedAttachment(
        val ciphertext: ByteArray,
        val keyBytes: ByteArray,
        val ivBytes: ByteArray,
        val sha256Hex: String,
        val plaintextLength: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as EncryptedAttachment
            if (!ciphertext.contentEquals(other.ciphertext)) return false
            if (!keyBytes.contentEquals(other.keyBytes)) return false
            if (!ivBytes.contentEquals(other.ivBytes)) return false
            if (sha256Hex != other.sha256Hex) return false
            return plaintextLength == other.plaintextLength
        }

        override fun hashCode(): Int {
            var result = ciphertext.contentHashCode()
            result = 31 * result + keyBytes.contentHashCode()
            result = 31 * result + ivBytes.contentHashCode()
            result = 31 * result + sha256Hex.hashCode()
            result = 31 * result + plaintextLength.hashCode()
            return result
        }

        override fun toString(): String {
            return "EncryptedAttachment(ciphertextLength=${ciphertext.size}, sha256Hex=$sha256Hex, plaintextLength=$plaintextLength, key=[REDACTED], iv=[REDACTED])"
        }
    }

    /**
     * Validated descriptor transferred end-to-end inside the Double Ratchet message payload.
     * Contains all metadata necessary for the authorized recipient to download and decrypt the media.
     */
    data class AttachmentDescriptor(
        val type: String = TYPE_ATTACHMENT,
        val storagePath: String,
        val keyBase64: String,
        val ivBase64: String,
        val mimeType: String,
        val sha256Hex: String,
        val caption: String = "",
        val sizeBytes: Long = 0L
    ) {
        init {
            validate(this)
        }

        override fun toString(): String {
            return "AttachmentDescriptor(type=$type, storagePath=$storagePath, mimeType=$mimeType, sha256Hex=$sha256Hex, caption=$caption, sizeBytes=$sizeBytes, key=[REDACTED], iv=[REDACTED])"
        }

        companion object {
            fun validate(desc: AttachmentDescriptor) {
                require(desc.type == TYPE_ATTACHMENT) {
                    "Invalid descriptor type: '${desc.type}'. Expected '$TYPE_ATTACHMENT'"
                }
                require(desc.storagePath.isNotBlank()) {
                    "Descriptor storagePath cannot be blank"
                }
                require(desc.mimeType.isNotBlank()) {
                    "Descriptor mimeType cannot be blank"
                }
                require(desc.sizeBytes >= 0L) {
                    "Descriptor sizeBytes cannot be negative: ${desc.sizeBytes}"
                }
                require(SHA256_HEX_REGEX.matches(desc.sha256Hex)) {
                    "Invalid SHA-256 hex string: '${desc.sha256Hex}'. Must be 64 hexadecimal characters"
                }

                val keyBytes = try {
                    Base64Compat.decode(desc.keyBase64)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Malformed key Base64: ${e.message}", e)
                }
                require(keyBytes.size == KEY_SIZE_BYTES) {
                    "Invalid key length: ${keyBytes.size} bytes. Expected $KEY_SIZE_BYTES bytes for AES-256"
                }

                val ivBytes = try {
                    Base64Compat.decode(desc.ivBase64)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Malformed IV Base64: ${e.message}", e)
                }
                require(ivBytes.size == IV_SIZE_BYTES) {
                    "Invalid IV length: ${ivBytes.size} bytes. Expected $IV_SIZE_BYTES bytes for AES-GCM"
                }
            }
        }
    }

    /**
     * Computes the lower-case hexadecimal SHA-256 digest of [bytes].
     */
    fun computeSha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance(HASH_ALGO).digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM with a fresh random key and 12-byte IV.
     * Computes SHA-256 over the resulting ciphertext (which includes the 128-bit authentication tag).
     */
    fun encrypt(
        plaintext: ByteArray,
        secureRandom: SecureRandom = SecureRandom()
    ): EncryptedAttachment {
        val keyBytes = ByteArray(KEY_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val ivBytes = ByteArray(IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        return encryptWithKeyAndIv(plaintext, keyBytes, ivBytes)
    }

    /**
     * Internal encryption routine allowing explicit key and IV parameters (useful for deterministic tests).
     */
    fun encryptWithKeyAndIv(
        plaintext: ByteArray,
        keyBytes: ByteArray,
        ivBytes: ByteArray
    ): EncryptedAttachment {
        require(keyBytes.size == KEY_SIZE_BYTES) { "AES-256 key must be $KEY_SIZE_BYTES bytes" }
        require(ivBytes.size == IV_SIZE_BYTES) { "AES-GCM IV must be $IV_SIZE_BYTES bytes" }

        val cipher = Cipher.getInstance(CIPHER_ALGO)
        val keySpec = SecretKeySpec(keyBytes, KEY_ALGO)
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, ivBytes)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)
        val sha256Hex = computeSha256Hex(ciphertext)

        return EncryptedAttachment(
            ciphertext = ciphertext,
            keyBytes = keyBytes,
            ivBytes = ivBytes,
            sha256Hex = sha256Hex,
            plaintextLength = plaintext.size.toLong()
        )
    }

    /**
     * Decrypts AES-256-GCM ciphertext bytes and optionally validates the ciphertext against [expectedSha256Hex].
     *
     * @throws AttachmentHashMismatchException if [expectedSha256Hex] is provided and does not match the ciphertext digest.
     * @throws AttachmentAuthenticationException if GCM authentication tag verification fails (ciphertext or tag was tampered with).
     * @throws IllegalArgumentException if key or IV sizes are invalid.
     */
    fun decrypt(
        ciphertext: ByteArray,
        keyBytes: ByteArray,
        ivBytes: ByteArray,
        expectedSha256Hex: String? = null
    ): ByteArray {
        require(keyBytes.size == KEY_SIZE_BYTES) {
            "Invalid key length: ${keyBytes.size} bytes. Expected $KEY_SIZE_BYTES bytes"
        }
        require(ivBytes.size == IV_SIZE_BYTES) {
            "Invalid IV length: ${ivBytes.size} bytes. Expected $IV_SIZE_BYTES bytes"
        }
        require(ciphertext.size >= TAG_LENGTH_BYTES) {
            "Ciphertext too short: ${ciphertext.size} bytes. Must be at least $TAG_LENGTH_BYTES bytes for GCM tag"
        }

        if (expectedSha256Hex != null) {
            val actualHash = computeSha256Hex(ciphertext)
            val expectedBytes = expectedSha256Hex.lowercase().toByteArray(Charsets.US_ASCII)
            val actualBytes = actualHash.lowercase().toByteArray(Charsets.US_ASCII)
            if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
                throw AttachmentHashMismatchException(
                    "Attachment ciphertext integrity verification failed. Expected SHA-256 '$expectedSha256Hex' but calculated '$actualHash'"
                )
            }
        }

        return try {
            val cipher = Cipher.getInstance(CIPHER_ALGO)
            val keySpec = SecretKeySpec(keyBytes, KEY_ALGO)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw AttachmentAuthenticationException(
                "AES-GCM authentication failed: ciphertext or authentication tag has been tampered with or key/IV is incorrect",
                e
            )
        } catch (e: Exception) {
            if (e is AttachmentAuthenticationException || e is AttachmentHashMismatchException) throw e
            throw AttachmentAuthenticationException("Attachment decryption failed: ${e.message}", e)
        }
    }

    /**
     * Creates an [AttachmentDescriptor] from an [EncryptedAttachment] and target metadata.
     */
    fun createDescriptor(
        encrypted: EncryptedAttachment,
        storagePath: String,
        mimeType: String,
        caption: String = ""
    ): AttachmentDescriptor {
        return AttachmentDescriptor(
            type = TYPE_ATTACHMENT,
            storagePath = storagePath,
            keyBase64 = Base64Compat.encodeToString(encrypted.keyBytes),
            ivBase64 = Base64Compat.encodeToString(encrypted.ivBytes),
            mimeType = mimeType,
            sha256Hex = encrypted.sha256Hex,
            caption = caption,
            sizeBytes = encrypted.plaintextLength
        )
    }

    /**
     * Decrypts ciphertext according to an [AttachmentDescriptor].
     */
    fun decryptWithDescriptor(
        descriptor: AttachmentDescriptor,
        ciphertext: ByteArray
    ): ByteArray {
        val keyBytes = Base64Compat.decode(descriptor.keyBase64)
        val ivBytes = Base64Compat.decode(descriptor.ivBase64)
        return decrypt(
            ciphertext = ciphertext,
            keyBytes = keyBytes,
            ivBytes = ivBytes,
            expectedSha256Hex = descriptor.sha256Hex
        )
    }

    /**
     * Serializes [descriptor] to a compact, standardized JSON string.
     */
    fun serializeDescriptor(descriptor: AttachmentDescriptor): String {
        AttachmentDescriptor.validate(descriptor)
        return buildString {
            append("{")
            append("\"type\":").append(escapeJson(descriptor.type)).append(",")
            append("\"storagePath\":").append(escapeJson(descriptor.storagePath)).append(",")
            append("\"key\":").append(escapeJson(descriptor.keyBase64)).append(",")
            append("\"iv\":").append(escapeJson(descriptor.ivBase64)).append(",")
            append("\"mimeType\":").append(escapeJson(descriptor.mimeType)).append(",")
            append("\"sha256\":").append(escapeJson(descriptor.sha256Hex)).append(",")
            append("\"caption\":").append(escapeJson(descriptor.caption)).append(",")
            append("\"sizeBytes\":").append(descriptor.sizeBytes)
            append("}")
        }
    }

    /**
     * Safely attempts to parse [payload] as an [AttachmentDescriptor].
     * Returns null if the payload is not a valid attachment descriptor (e.g. legacy plain text message),
     * allowing transparent backward compatibility.
     */
    fun parseDescriptorOrNull(payload: String?): AttachmentDescriptor? {
        if (payload.isNullOrBlank()) return null
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null

        return try {
            val map = parseSimpleJson(trimmed)
            if (map["type"] != TYPE_ATTACHMENT) return null

            val storagePath = map["storagePath"] ?: return null
            val keyBase64 = map["key"] ?: return null
            val ivBase64 = map["iv"] ?: return null
            val mimeType = map["mimeType"] ?: return null
            val sha256Hex = map["sha256"] ?: return null
            val caption = map["caption"] ?: ""
            val sizeBytes = map["sizeBytes"]?.toLongOrNull() ?: 0L

            AttachmentDescriptor(
                type = TYPE_ATTACHMENT,
                storagePath = storagePath,
                keyBase64 = keyBase64,
                ivBase64 = ivBase64,
                mimeType = mimeType,
                sha256Hex = sha256Hex,
                caption = caption,
                sizeBytes = sizeBytes
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun escapeJson(value: String): String {
        val out = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append(String.format("\\u%04x", ch.code))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        out.append("\"")
        return out.toString()
    }

    /**
     * Lightweight, zero-dependency JSON parser for flat key-value objects.
     * Guarantees identical execution on Android and JVM unit tests without mocked dependencies.
     */
    private fun parseSimpleJson(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw IllegalArgumentException("Not a JSON object")
        }

        var i = 1
        val len = trimmed.length - 1

        fun skipWhitespace() {
            while (i < len && trimmed[i].isWhitespace()) i++
        }

        fun parseString(): String {
            if (i >= len || trimmed[i] != '"') throw IllegalArgumentException("Expected string at $i")
            i++ // skip opening quote
            val sb = StringBuilder()
            while (i < len) {
                val c = trimmed[i]
                if (c == '"') {
                    i++ // skip closing quote
                    return sb.toString()
                } else if (c == '\\') {
                    i++
                    if (i >= len) throw IllegalArgumentException("Unexpected escape end at $i")
                    when (val esc = trimmed[i]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= len) throw IllegalArgumentException("Invalid unicode escape at $i")
                            val hex = trimmed.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
                i++
            }
            throw IllegalArgumentException("Unterminated string")
        }

        skipWhitespace()
        while (i < len) {
            skipWhitespace()
            if (i >= len) break
            val key = parseString()
            skipWhitespace()
            if (i >= len || trimmed[i] != ':') throw IllegalArgumentException("Expected ':' at $i")
            i++ // skip ':'
            skipWhitespace()

            val value = if (i < len && trimmed[i] == '"') {
                parseString()
            } else {
                // Literal (number, boolean, null)
                val start = i
                while (i < len && trimmed[i] != ',' && trimmed[i] != '}' && !trimmed[i].isWhitespace()) {
                    i++
                }
                trimmed.substring(start, i)
            }

            result[key] = value
            skipWhitespace()
            if (i < len && trimmed[i] == ',') {
                i++ // skip ','
            } else if (i < len && trimmed[i] != '}') {
                throw IllegalArgumentException("Expected ',' or '}' at $i")
            }
        }

        return result
    }
}

/**
 * Thrown when the calculated SHA-256 hash of a ciphertext blob does not match the descriptor.
 */
class AttachmentHashMismatchException(message: String) : SecurityException(message)

/**
 * Thrown when AES-GCM ciphertext or authentication tag verification fails.
 */
class AttachmentAuthenticationException(message: String, cause: Throwable? = null) : SecurityException(message, cause)

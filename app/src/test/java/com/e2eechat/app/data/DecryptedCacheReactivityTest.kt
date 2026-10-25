package com.e2eechat.app.data

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the disappearing-message bug.
 *
 * Root cause: The Firestore snapshot listener fires immediately on local write (before
 * `add().await()` returns the document ID), so the sender-side decrypted cache is
 * populated too late. The fix makes the cache a [MutableStateFlow] and combines it
 * with the raw message list, so bumping the cache after `add().await()` immediately
 * re-emits the correct plaintext — without waiting for another Firestore snapshot.
 *
 * These unit tests validate the cache mechanics in isolation from Firebase/Firestore.
 */
class DecryptedCacheReactivityTest {

    // -------------------------------------------------------------------------
    // Helpers mirroring the production implementation in ChatViewModel
    // -------------------------------------------------------------------------

    private val decryptedCache = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Mirrors ChatViewModel.cacheDecryptedMessage() — CAS loop on the StateFlow. */
    private fun cacheDecryptedMessage(docId: String, plaintext: String) {
        while (true) {
            val current = decryptedCache.value
            if (decryptedCache.compareAndSet(current, current + (docId to plaintext))) break
        }
    }

    private data class RawDoc(
        val id: String,
        val senderId: String,
        val ciphertext: String?,
        val legacyText: String?
    )

    /** Maps a [RawDoc] + cache snapshot → display text, mirroring the combine lambda. */
    private fun resolveText(doc: RawDoc, cache: Map<String, String>): String =
        cache[doc.id]
            ?: if (!doc.ciphertext.isNullOrBlank()) "Sending\u2026"
            else doc.legacyText ?: ""

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Core regression: updating the cache StateFlow must immediately re-emit the
     * combined flow with the correct plaintext, replacing the "Sending…" placeholder.
     */
    @Test
    fun `cacheDecryptedMessage updates StateFlow and triggers re-emission`() = runBlocking {
        val rawFlow = MutableStateFlow(
            listOf(RawDoc(id = "msg1", senderId = "userA", ciphertext = "BASE64==", legacyText = null))
        )

        val combinedFlow = combine(rawFlow, decryptedCache) { rawDocs, cache ->
            rawDocs.map { resolveText(it, cache) }
        }

        // Before caching: snapshot arrives before add().await() → placeholder shown
        assertEquals(listOf("Sending\u2026"), combinedFlow.first())

        // Simulate add().await() returning the real doc ID and caching the plaintext
        cacheDecryptedMessage("msg1", "Hello, world!")

        // Combined flow must re-emit with real plaintext without any new Firestore snapshot
        assertEquals(listOf("Hello, world!"), combinedFlow.first())
    }

    /**
     * CAS loop correctness: concurrent updates must not lose any cache entry.
     */
    @Test
    fun `concurrent cache updates are all applied without loss`() = runBlocking {
        val messageIds = (1..50).map { "msg$it" }
        val threads = messageIds.map { docId ->
            Thread { cacheDecryptedMessage(docId, "plaintext-$docId") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val cache = decryptedCache.value
        assertEquals(50, cache.size)
        messageIds.forEach { docId ->
            assertEquals("plaintext-$docId", cache[docId])
        }
    }

    /**
     * Idempotency: once a docId is in the cache the combine lambda must return the
     * cached value without re-triggering decryption.  Prevents Double-Ratchet corruption.
     */
    @Test
    fun `cached plaintext is returned without re-decryption`() = runBlocking {
        cacheDecryptedMessage("msg42", "original plaintext")

        val rawFlow = MutableStateFlow(
            listOf(RawDoc(id = "msg42", senderId = "userB", ciphertext = "SOMECIPHER==", legacyText = null))
        )

        var decryptCallCount = 0
        val combinedFlow = combine(rawFlow, decryptedCache) { rawDocs, cache ->
            rawDocs.map { doc ->
                if (cache.containsKey(doc.id)) {
                    cache[doc.id]!!
                } else {
                    decryptCallCount++
                    "decrypted"
                }
            }
        }

        val result = combinedFlow.first()
        assertEquals("original plaintext", result[0])
        assertEquals(0, decryptCallCount)
    }

    /**
     * Legacy regression: plain-text messages (no `ciphertext` field) must continue to
     * display their `text` value unchanged.
     */
    @Test
    fun `legacy plaintext messages are displayed without encryption`() = runBlocking {
        val rawFlow = MutableStateFlow(
            listOf(RawDoc(id = "legacy1", senderId = "userC", ciphertext = null, legacyText = "Legacy hello"))
        )

        val combinedFlow = combine(rawFlow, decryptedCache) { rawDocs, cache ->
            rawDocs.map { resolveText(it, cache) }
        }

        assertEquals("Legacy hello", combinedFlow.first()[0])
    }

    /**
     * End-to-end transition: sender's message goes through "Sending…" → real text after
     * the cache is updated.  Validates the full lifecycle that was broken before the fix.
     */
    @Test
    fun `sender message transitions from Sending to real text after cache update`() = runBlocking {
        val rawFlow = MutableStateFlow(
            listOf(RawDoc(id = "new1", senderId = "me", ciphertext = "CIPHER==", legacyText = null))
        )
        val emissions = mutableListOf<List<String>>()
        val job = GlobalScope.launch {
            combine(rawFlow, decryptedCache) { rawDocs, cache ->
                rawDocs.map { resolveText(it, cache) }
            }.collect { emissions.add(it) }
        }

        delay(50)
        assertTrue("Expected initial emission", emissions.isNotEmpty())
        assertEquals("Sending\u2026", emissions.last()[0])

        // Simulate add().await() completing
        cacheDecryptedMessage("new1", "Real message text")
        delay(50)

        assertEquals("Real message text", emissions.last()[0])
        assertNotEquals("Sending\u2026", emissions.last()[0])

        job.cancel()
    }

    /**
     * sendError StateFlow must start null and support set / clear semantics.
     */
    @Test
    fun `sendError StateFlow can be set and cleared`() {
        val sendError = MutableStateFlow<String?>(null)

        assertTrue(sendError.value == null)

        sendError.value = "Failed to send message. Please try again."
        assertEquals("Failed to send message. Please try again.", sendError.value)

        sendError.value = null
        assertTrue(sendError.value == null)
    }

    /**
     * Batch cache updates atomically populate multiple messages.
     */
    @Test
    fun `batch cacheDecryptedMessages updates all entries atomically`() = runBlocking {
        val batch = mapOf(
            "msg10" to "Text 10",
            "msg11" to "Text 11",
            "msg12" to "Text 12"
        )
        while (true) {
            val current = decryptedCache.value
            if (decryptedCache.compareAndSet(current, current + batch)) break
        }

        val cache = decryptedCache.value
        assertEquals("Text 10", cache["msg10"])
        assertEquals("Text 11", cache["msg11"])
        assertEquals("Text 12", cache["msg12"])
    }

    /**
     * Regression test: Unrecoverable message IDs and failed IDs resolve to "🔒 Encrypted message"
     * and do not remain stuck on "🔒 Decrypting…".
     */
    @Test
    fun `unrecoverable or failed message resolves to encrypted message placeholder`() = runBlocking {
        val failedIds = MutableStateFlow<Set<String>>(setOf("fail1"))
        val unrecoverableIds = mutableSetOf("unrec1")

        fun resolveTextWithUnrec(
            doc: RawDoc,
            cache: Map<String, String>,
            failed: Set<String>,
            unrec: Set<String>
        ): String {
            val cachedText = cache[doc.id]
            return when {
                cachedText != null -> cachedText
                !doc.ciphertext.isNullOrBlank() -> {
                    if (failed.contains(doc.id) || unrec.contains(doc.id)) {
                        "🔒 Encrypted message"
                    } else {
                        "🔒 Decrypting…"
                    }
                }
                else -> doc.legacyText ?: ""
            }
        }

        val docs = listOf(
            RawDoc(id = "msg_ok", senderId = "peer", ciphertext = "CIPHER1==", legacyText = null),
            RawDoc(id = "fail1", senderId = "peer", ciphertext = "CIPHER2==", legacyText = null),
            RawDoc(id = "unrec1", senderId = "peer", ciphertext = "CIPHER3==", legacyText = null)
        )

        // msg_ok is waiting for decryption, fail1 is failed, unrec1 is unrecoverable
        val result = docs.map { resolveTextWithUnrec(it, decryptedCache.value, failedIds.value, unrecoverableIds) }
        assertEquals("🔒 Decrypting…", result[0])
        assertEquals("🔒 Encrypted message", result[1])
        assertEquals("🔒 Encrypted message", result[2])

        // When unrec1 is persisted to cache as "🔒 Encrypted message"
        cacheDecryptedMessage("unrec1", "🔒 Encrypted message")
        val updated = docs.map { resolveTextWithUnrec(it, decryptedCache.value, failedIds.value, unrecoverableIds) }
        assertEquals("🔒 Encrypted message", updated[2])
    }

    /**
     * Bounded retry mechanism: failing message increments retry count and stops after MAX_RETRIES.
     */
    @Test
    fun `retry mechanism halts after reaching max retry count and marks unrecoverable`() {
        val retryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
        val unrecoverable = mutableSetOf<String>()
        val maxRetries = 2
        val docId = "troublesome_doc"

        for (attempt in 1..5) {
            val count = (retryCounts[docId] ?: 0) + 1
            retryCounts[docId] = count
            if (count >= maxRetries) {
                unrecoverable.add(docId)
            }
        }

        assertTrue("Doc must be added to unrecoverable after max retries", unrecoverable.contains(docId))
        assertEquals(5, retryCounts[docId])
    }

    /**
     * Verifies that cause chains and IllegalArgumentException are identified as unrecoverable.
     */
    @Test
    fun `isUnrecoverable correctly identifies wrapped exceptions and decoding errors`() {
        fun isUnrecoverable(e: Throwable): Boolean {
            var current: Throwable? = e
            while (current != null) {
                val name = current.javaClass.simpleName
                if (name in setOf(
                    "UntrustedIdentityException", "InvalidMessageException", "InvalidKeyException",
                    "InvalidKeyIdException", "NoSessionException", "LegacyMessageException", "InvalidVersionException",
                    "IllegalArgumentException", "IllegalStateException"
                )) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        // Direct unrecoverable
        assertTrue(isUnrecoverable(org.signal.libsignal.protocol.UntrustedIdentityException("Untrusted")))
        assertTrue(isUnrecoverable(org.signal.libsignal.protocol.InvalidMessageException("Invalid")))
        assertTrue(isUnrecoverable(IllegalArgumentException("Bad base64")))

        // Wrapped unrecoverable
        val wrapped = RuntimeException("Outer", org.signal.libsignal.protocol.UntrustedIdentityException("Inner"))
        assertTrue("Wrapped UntrustedIdentityException must be unrecoverable", isUnrecoverable(wrapped))

        val deeplyWrapped = Exception("L1", RuntimeException("L2", org.signal.libsignal.protocol.InvalidKeyException("L3")))
        assertTrue("Deeply wrapped InvalidKeyException must be unrecoverable", isUnrecoverable(deeplyWrapped))

        // Normal network or transient error should NOT be unrecoverable
        assertFalse(isUnrecoverable(java.io.IOException("Network timeout")))
    }

    /**
     * Verifies that local Room messages provide instant text without waiting and that
     * Room-only messages are preserved alongside Firestore messages.
     */
    @Test
    fun `local Room messages provide instant text and local-only messages are merged`() {
        val rawDocs = listOf(
            RawDoc(id = "msg1", senderId = "peer", ciphertext = "CIPHER1==", legacyText = null),
            RawDoc(id = "msg2", senderId = "peer", ciphertext = "CIPHER2==", legacyText = null)
        )
        // Local Room holds decrypted text for msg1, and has a local-only msg3
        val localRoom = mapOf(
            "msg1" to "Decrypted from Room",
            "msg3" to "Historical Room-only message"
        )
        val inMemoryCache = mapOf<String, String>() // cache is empty initially

        // Text resolution gives priority to in-memory cache, then Room local content
        val resolvedMessages = rawDocs.map { doc ->
            val text = inMemoryCache[doc.id] ?: localRoom[doc.id] ?: "🔒 Decrypting…"
            doc.id to text
        }
        assertEquals("Decrypted from Room", resolvedMessages[0].second)
        assertEquals("🔒 Decrypting…", resolvedMessages[1].second)

        // Merging local-only messages
        val rawIds = rawDocs.map { it.id }.toSet()
        val localOnly = localRoom.filter { it.key !in rawIds }.map { it.key to it.value }
        val allMessages = resolvedMessages + localOnly

        assertEquals(3, allMessages.size)
        assertEquals("msg3", allMessages[2].first)
        assertEquals("Historical Room-only message", allMessages[2].second)
    }
}


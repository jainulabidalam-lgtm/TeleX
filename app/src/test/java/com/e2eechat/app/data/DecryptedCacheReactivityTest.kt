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
}

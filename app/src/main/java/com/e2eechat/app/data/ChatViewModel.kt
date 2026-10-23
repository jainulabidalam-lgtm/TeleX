package com.e2eechat.app.data

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.e2eechat.app.crypto.KeyManager
import com.e2eechat.app.crypto.identityFingerprint
import com.e2eechat.app.model.Chat
import com.e2eechat.app.model.Message
import com.e2eechat.app.model.MessageStatus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val keyManager = KeyManager(application.applicationContext)
    private val database = AppDatabase.getInstance(application.applicationContext)
    private val chatDao = database.chatDao()
    private val messageDao = database.messageDao()

    private val supabase = createSupabaseClient(
        supabaseUrl = "https://taivxyserowgwioffjob.supabase.co",
        supabaseKey = "sb_publishable_NaKHhP54MD64THYf1EbuVw_t6NTLP7Y"
    ) {
        install(Storage)
    }

    private val myUid: String get() = auth.currentUser?.uid ?: ""
    private val myEmail: String get() = auth.currentUser?.email?.trim()?.lowercase().orEmpty()

    // Cache message flows per chatId to avoid creating new Firestore listeners on every recomposition
    private val messageFlowCache = ConcurrentHashMap<String, Flow<List<Message>>>()

    /**
     * Reactive decrypted-text cache. Keyed by Firestore document ID → plaintext.
     * Using StateFlow so that any update (e.g. caching a just-sent message) immediately
     * re-triggers the combined message flow without waiting for the next Firestore snapshot.
     */
    private val _decryptedCache = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Convenience accessor for the current cache snapshot. */
    private val decryptedMessageCache: Map<String, String> get() = _decryptedCache.value

    /** Updates the decrypted cache atomically and notifies observers. */
    private fun cacheDecryptedMessage(docId: String, plaintext: String) {
        // CAS loop to safely update the immutable map held by the StateFlow
        while (true) {
            val current = _decryptedCache.value
            if (_decryptedCache.compareAndSet(current, current + (docId to plaintext))) break
        }
    }

    /** Batch updates the decrypted cache atomically and notifies observers. */
    private fun cacheDecryptedMessages(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        while (true) {
            val current = _decryptedCache.value
            if (_decryptedCache.compareAndSet(current, current + entries)) break
        }
    }

    /**
     * Transient set of message IDs whose decryption failed with a retryable error.
     * Kept in memory only; never permanently cached as plaintext or persisted to Room.
     */
    private val _failedMessageIds = MutableStateFlow<Set<String>>(emptySet())
    val failedMessageIds: StateFlow<Set<String>> = _failedMessageIds.asStateFlow()

    private val unrecoverableIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val decryptionRetryCounts = ConcurrentHashMap<String, Int>()

    companion object {
        private const val MAX_DECRYPTION_RETRIES = 2
    }

    private fun isUnrecoverable(e: Throwable): Boolean {
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

    private fun markMessageFailed(docId: String) {
        while (true) {
            val current = _failedMessageIds.value
            if (current.contains(docId) || _failedMessageIds.compareAndSet(current, current + docId)) break
        }
    }

    private fun clearMessageFailed(docId: String) {
        while (true) {
            val current = _failedMessageIds.value
            if (!current.contains(docId) || _failedMessageIds.compareAndSet(current, current - docId)) break
        }
    }

    private suspend fun ensureChatExists(chatId: String) {
        if (chatDao.findById(chatId) == null) {
            chatDao.insertChat(
                ChatEntity(
                    id = chatId,
                    contactName = "Chat",
                    contactInitials = "C",
                    lastMessage = "",
                    timestamp = "",
                    unreadCount = 0,
                    isOnline = false,
                    avatarColorHex = 0xFF6E56CFL,
                    lastSeenText = ""
                )
            )
        }
    }

    /** Exposed so the UI layer can observe and react to send errors. */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    fun clearSendError() { _sendError.value = null }

    /**
     * Event triggered when an untrusted or mismatched identity is detected during incoming decryption
     * or outgoing message preparation. Strict identity verification prevents automatic overwrite.
     */
    private val _identityMismatch = MutableStateFlow<IdentityMismatchEvent?>(null)
    val identityMismatch: StateFlow<IdentityMismatchEvent?> = _identityMismatch.asStateFlow()

    fun clearIdentityMismatch() { _identityMismatch.value = null }

    // Mutex per participant address to ensure serial session operations and avoid ratchet race conditions
    private val sessionMutexes = ConcurrentHashMap<String, Mutex>()

    private fun getMutexFor(uid: String): Mutex =
        sessionMutexes.getOrPut(uid) { Mutex() }

    val chats: Flow<List<Chat>> = callbackFlow {
        if (myUid.isBlank()) {
            trySend(emptyList())
            awaitClose { }
        } else {
            val registration = firestore.collection("chats")
                .whereArrayContains("participants", myUid)
                .addSnapshotListener { snapshot, _ ->
                    val mapped = snapshot?.documents?.mapNotNull { doc ->
                        val participants = doc.get("participants") as? List<*> ?: return@mapNotNull null
                        val otherUid = participants.map { it.toString() }.firstOrNull { it != myUid } ?: return@mapNotNull null
                        val names = doc.get("participantNames") as? Map<*, *>
                        val rawMillis = doc.getLong("lastMessageTimestamp") ?: 0L
                        val lastMsgRaw = doc.getString("lastMessage") ?: ""

                        val chat = Chat(
                            id = doc.id,
                            contactName = (names?.get(otherUid) as? String) ?: "Unknown",
                            avatarInitials = ((names?.get(otherUid) as? String) ?: "??").take(2).uppercase(),
                            avatarColorHex = 0xFF6E56CFL,
                            isOnline = false,
                            lastSeenText = "",
                            lastMessage = lastMsgRaw,
                            lastMessageTimestamp = formatTime(rawMillis),
                            unreadCount = 0
                        )
                        rawMillis to chat
                    }?.sortedByDescending { it.first }?.map { it.second } ?: emptyList()
                    trySend(mapped)
                }
            awaitClose { registration.remove() }
        }
    }.distinctUntilChanged()

    /**
     * Raw Firestore document snapshots for [chatId], emitted on every snapshot event.
     * Each entry is a lightweight data holder; decryption happens downstream.
     */
    private data class RawMessageDoc(
        val id: String,
        val senderId: String,
        val legacyText: String?,
        val ciphertextBase64: String?,
        val messageType: Int?,
        val timestampMillis: Long,
        val status: String?,
        val imageUrl: String?
    )

    fun messagesForChat(chatId: String): Flow<List<Message>> =
        messageFlowCache.getOrPut(chatId) {
            // Pre-populate decrypted cache from local Room database for this chat
            viewModelScope.launch(Dispatchers.IO) {
                val localMessages = messageDao.getMessagesForChatList(chatId)
                if (localMessages.isNotEmpty()) {
                    val map = HashMap<String, String>(localMessages.size)
                    for (m in localMessages) {
                        if (m.content.isNotBlank()) {
                            map[m.id] = m.content
                        }
                    }
                    cacheDecryptedMessages(map)
                }
            }

            // Firestore snapshot flow (raw docs, no decryption)
            val rawFlow: Flow<List<RawMessageDoc>> = callbackFlow {
                val registration = firestore.collection("chats").document(chatId)
                    .collection("messages")
                    .addSnapshotListener { snapshot, _ ->
                        val currentMyUid = myUid
                        if (snapshot == null || currentMyUid.isBlank()) {
                            trySend(emptyList())
                            return@addSnapshotListener
                        }
                        val raw = snapshot.documents.map { doc ->
                            val millis = doc.getLong("timestampMillis")
                                ?: doc.getLong("timestamp")
                                ?: doc.getTimestamp("timestamp")?.toDate()?.time
                                ?: doc.getLong("createdAt")
                                ?: 0L
                            RawMessageDoc(
                                id = doc.id,
                                senderId = doc.getString("senderId") ?: "",
                                legacyText = doc.getString("text"),
                                ciphertextBase64 = doc.getString("ciphertext"),
                                messageType = doc.getLong("messageType")?.toInt(),
                                timestampMillis = millis,
                                status = doc.getString("status"),
                                imageUrl = doc.getString("imageUrl")
                            )
                        }.sortedBy { it.timestampMillis }
                        trySend(raw)

                        // Process incoming encrypted messages sequentially per sender in chronological order
                        viewModelScope.launch(Dispatchers.IO) {
                            val incomingBySender = raw
                                .filter { doc ->
                                    doc.senderId != currentMyUid &&
                                    !doc.ciphertextBase64.isNullOrBlank() &&
                                    !decryptedMessageCache.containsKey(doc.id) &&
                                    !unrecoverableIds.contains(doc.id)
                                }
                                .groupBy { it.senderId }

                            for ((senderUid, docsForSender) in incomingBySender) {
                                val sortedDocs = docsForSender.sortedBy { it.timestampMillis }
                                processIncomingMessagesForSender(chatId, senderUid, sortedDocs)
                            }
                        }
                    }
                awaitClose {
                    registration.remove()
                    messageFlowCache.remove(chatId)
                }
            }

            // Combine raw docs and Room messages with the reactive decrypted-text cache and failure tracker.
            // Pure synchronous mapping: NO decryption coroutines launched here.
            combine(rawFlow, messageDao.getMessagesForChat(chatId), _decryptedCache, _failedMessageIds) { rawDocs, localMessages, cache, failedIds ->
                    val currentMyUid = myUid
                    val now = System.currentTimeMillis()
                    val localMap = localMessages.associateBy { it.id }

                    val messagesFromRaw = rawDocs.map { doc ->
                        val isFromMe = doc.senderId == currentMyUid
                        val local = localMap[doc.id]

                        // Resolve display text in priority order:
                        // 1. Reactive decrypted cache StateFlow
                        // 2. Local Room persistence (already saved/decrypted)
                        // 3. Ciphertext fallback (Sending... / Encrypted / Decrypting...)
                        // 4. Legacy text
                        val cachedText = cache[doc.id] ?: local?.content?.takeIf { it.isNotBlank() }
                        val decryptedText: String = when {
                            cachedText != null -> cachedText

                            !doc.ciphertextBase64.isNullOrBlank() -> {
                                if (isFromMe) {
                                    // Sender-side: transient placeholder until cache is updated
                                    val isRecent = (now - doc.timestampMillis) < 30_000L
                                    if (isRecent) "Sending…" else "🔒 Sent message"
                                } else if (failedIds.contains(doc.id) || unrecoverableIds.contains(doc.id)) {
                                    // Decryption failed or unrecoverable
                                    "🔒 Encrypted message"
                                } else {
                                    // Awaiting sequential background decryption
                                    "🔒 Decrypting…"
                                }
                            }

                            doc.legacyText != null -> doc.legacyText

                            else -> ""
                        }

                        Message(
                            id = doc.id,
                            senderId = doc.senderId,
                            text = decryptedText,
                            timestamp = if (doc.timestampMillis > 0L) formatTime(doc.timestampMillis) else (local?.timestamp ?: ""),
                            isFromMe = isFromMe,
                            status = if (doc.status == "read" || local?.isRead == true) MessageStatus.READ else MessageStatus.SENT,
                            imageUrl = doc.imageUrl ?: local?.imageUrl
                        )
                    }

                    // Append any local Room messages that are not present in Firestore (e.g. historical local-only messages)
                    val rawIds = rawDocs.map { it.id }.toSet()
                    val localOnlyMessages = localMessages.filter { it.id !in rawIds }.map { local ->
                        Message(
                            id = local.id,
                            senderId = if (local.isSentByMe) currentMyUid else "contact",
                            text = local.content,
                            timestamp = local.timestamp,
                            isFromMe = local.isSentByMe,
                            status = if (local.isRead) MessageStatus.READ else MessageStatus.SENT,
                            imageUrl = local.imageUrl
                        )
                    }

                    // Batch read-receipt writes on IO to avoid blocking the main thread
                    val unreadIds = rawDocs.filter { doc ->
                        doc.senderId != currentMyUid && doc.status != "read"
                    }
                    if (unreadIds.isNotEmpty()) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val batch = firestore.batch()
                                unreadIds.forEach { doc ->
                                    batch.update(
                                        firestore.collection("chats").document(chatId)
                                            .collection("messages").document(doc.id),
                                        "status", "read"
                                    )
                                    messageDao.updateMessageStatus(doc.id, isRead = true)
                                }
                                batch.commit().await()
                            } catch (e: Exception) {
                                android.util.Log.e("ChatViewModel", "Failed to batch commit read receipts", e)
                            }
                        }
                    }

                    messagesFromRaw + localOnlyMessages
                }
            .distinctUntilChanged()
        }

    fun markChatRead(chatId: String) {
        // Handled reactively via snapshot listener read-receipt batching
    }

    fun createOrGetChatWithUser(otherUid: String, otherUsername: String, myUidParam: String, onChatReady: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val chatId = listOf(myUidParam, otherUid).sorted().joinToString("_")
                val chatRef = firestore.collection("chats").document(chatId)
                val existing = chatRef.get().await()
                if (!existing.exists()) {
                    val myDoc = firestore.collection("users").document(myUidParam).get().await()
                    val myUsername = myDoc.getString("username") ?: "Me"
                    val myPhoto = myDoc.getString("profilePictureUrl") ?: ""
                    val otherDoc = firestore.collection("users").document(otherUid).get().await()
                    val otherPhoto = otherDoc.getString("profilePictureUrl") ?: ""

                    val data = hashMapOf(
                        "participants" to listOf(myUidParam, otherUid),
                        "participantNames" to mapOf(myUidParam to myUsername, otherUid to otherUsername),
                        "participantPhotos" to mapOf(myUidParam to myPhoto, otherUid to otherPhoto),
                        "lastMessage" to "",
                        "lastMessageTimestamp" to System.currentTimeMillis(),
                        "createdAt" to System.currentTimeMillis()
                    )
                    chatRef.set(data).await()
                }
                onChatReady(chatId)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Failed to create chat", e)
            }
        }
    }

    /**
     * Sends an E2EE text message using the Signal Protocol Double Ratchet session cipher.
     * No plaintext is written to Firestore for messages or lastMessage previews.
     */
    fun sendTextMessage(chatId: String, text: String) {
        if (text.isBlank() || myUid.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Determine recipient UID from chatId (format: "uid1_uid2")
                val parts = chatId.split("_")
                val otherUid = parts.firstOrNull { it != myUid }
                if (otherUid.isNullOrBlank()) {
                    android.util.Log.e("ChatViewModel", "Cannot resolve recipient UID from chatId: $chatId")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val recipientAddress = SignalProtocolAddress(otherUid, KeyManager.DEVICE_ID)

                // Serialized session encryption under mutex
                val ciphertextMessage: CiphertextMessage? = getMutexFor(otherUid).withLock {
                    val store = keyManager.getProtocolStore(myUid, myEmail)
                    val keystoreStore = store as? com.e2eechat.app.crypto.KeystoreSignalProtocolStore
                    val trustedIdentity = keystoreStore?.getIdentity(recipientAddress) ?: store.getIdentity(recipientAddress)

                    // 1. Clean up structurally invalid sessions (no sender chain, rotated local identity, or mismatched remote identity)
                    if (keystoreStore != null && keystoreStore.containsSession(recipientAddress) && !keystoreStore.isSessionValid(recipientAddress)) {
                        android.util.Log.w("TeleXE2EE", "Deleting structurally invalid session for $otherUid")
                        keystoreStore.deleteSession(recipientAddress)
                    }

                    // 2. Validate against peer's published prekey bundle metadata
                    val remoteMetadata = keyManager.fetchPreKeyBundleMetadata(otherUid)
                    if (remoteMetadata != null) {
                        // Strict identity check: if remote identity changed from trusted identity,
                        // NEVER automatically overwrite and NEVER reuse stale session!
                        if (trustedIdentity != null && trustedIdentity != remoteMetadata.identityKey) {
                            val trustedFp = identityFingerprint(trustedIdentity)
                            val newFp = identityFingerprint(remoteMetadata.identityKey)
                            android.util.Log.e(
                                "TeleXE2EE",
                                "Outgoing send blocked: Identity mismatch for $otherUid: trusted=$trustedFp, remote=$newFp"
                            )
                            _identityMismatch.value = IdentityMismatchEvent(
                                peerUid = otherUid,
                                storedIdentityFingerprint = trustedFp,
                                newIdentityFingerprint = newFp
                            )
                            _sendError.value = "Security warning: Recipient identity key has changed. Verification required."
                            return@withLock null
                        }

                        // If remote registration ID changed, the peer has re-registered; the existing session is dead
                        val sessionRegId = keystoreStore?.getSessionRemoteRegistrationId(recipientAddress)
                        if (store.containsSession(recipientAddress) && sessionRegId != null && sessionRegId != remoteMetadata.registrationId) {
                            android.util.Log.i(
                                "TeleXE2EE",
                                "Peer $otherUid re-registered (oldRegId=$sessionRegId, newRegId=${remoteMetadata.registrationId}); replacing session"
                            )
                            keystoreStore.deleteSession(recipientAddress)
                        }
                    }

                    // 3. If no session exists (or stale session was deleted), establish new one from bundle
                    if (!store.containsSession(recipientAddress)) {
                        val bundle = keyManager.fetchAndConsumePreKeyBundle(otherUid)
                        android.util.Log.i(
                            "TeleXE2EE",
                            "Outgoing session establish: targetUid=$otherUid fetchedBundleFp=${identityFingerprint(bundle.identityKey)} myFp=${identityFingerprint(store.identityKeyPair.publicKey)}"
                        )

                        // Strict identity check before processing bundle
                        if (trustedIdentity != null && trustedIdentity != bundle.identityKey) {
                            val trustedFp = identityFingerprint(trustedIdentity)
                            val newFp = identityFingerprint(bundle.identityKey)
                            android.util.Log.e(
                                "TeleXE2EE",
                                "Outgoing bundle blocked: Identity mismatch for $otherUid: trusted=$trustedFp, bundle=$newFp"
                            )
                            _identityMismatch.value = IdentityMismatchEvent(
                                peerUid = otherUid,
                                storedIdentityFingerprint = trustedFp,
                                newIdentityFingerprint = newFp
                            )
                            _sendError.value = "Security warning: Recipient identity key has changed. Verification required."
                            return@withLock null
                        }

                        val signalBundle = bundle.toSignalPreKeyBundle()
                        val sessionBuilder = SessionBuilder(store, recipientAddress)
                        sessionBuilder.process(signalBundle)
                    }

                    val cipher = SessionCipher(store, recipientAddress)
                    cipher.encrypt(text.toByteArray(Charsets.UTF_8))
                }

                if (ciphertextMessage == null) {
                    return@launch
                }

                val ciphertextBase64 = Base64.encodeToString(ciphertextMessage.serialize(), Base64.NO_WRAP)
                val messageType = ciphertextMessage.type

                val messageData = hashMapOf(
                    "senderId" to myUid,
                    "text" to null,
                    "ciphertext" to ciphertextBase64,
                    "messageType" to messageType,
                    "senderDeviceId" to KeyManager.DEVICE_ID,
                    "imageUrl" to null,
                    "timestampMillis" to now,
                    "status" to "sent"
                )

                val chatRef = firestore.collection("chats").document(chatId)
                val addedDoc = chatRef.collection("messages").add(messageData).await()

                // Cache the sent plaintext IMMEDIATELY after we have the real document ID.
                // This updates _decryptedCache (a StateFlow), which re-triggers the combined
                // message flow and replaces the transient "Sending…" placeholder with the
                // real message text — without waiting for another Firestore snapshot.
                cacheDecryptedMessage(addedDoc.id, text)

                // Persist locally sent message to Room so it survives app restarts
                ensureChatExists(chatId)
                messageDao.insertMessage(
                    MessageEntity(
                        id = addedDoc.id,
                        chatId = chatId,
                        content = text,
                        timestamp = formatTime(now),
                        isSentByMe = true,
                        isRead = false,
                        imageUrl = null
                    )
                )

                // Update chat metadata with opaque preview to guarantee zero plaintext reaches Firestore
                chatRef.update(
                    mapOf(
                        "lastMessage" to "Encrypted message",
                        "lastMessageTimestamp" to now
                    )
                ).await()

            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Send E2EE message failed", e)
                _sendError.value = "Failed to send message. Please try again."
            }
        }
    }

    private sealed class DecryptionResult {
        data class Success(val plaintext: String) : DecryptionResult()
        object Duplicate : DecryptionResult()
        data class Failure(val error: Throwable) : DecryptionResult()
    }

    /**
     * Decrypts an incoming message payload using SessionCipher and persists updated ratchet state.
     * Note: Session mutex is held by processIncomingMessagesForSender across the entire chronological batch.
     */
    private suspend fun decryptIncomingMessage(
        messageId: String,
        senderUid: String,
        ciphertextBase64: String,
        messageType: Int?,
        getRemoteMetadata: suspend () -> com.e2eechat.app.crypto.RemoteBundleMetadata? = { keyManager.fetchPreKeyBundleMetadata(senderUid) }
    ): DecryptionResult = withContext(Dispatchers.IO) {
        val recipientUid = myUid
        val ciphertextLen = ciphertextBase64.length
        var sessionExists = false
        try {
            val store = keyManager.getProtocolStore(myUid, myEmail)
            val senderAddress = SignalProtocolAddress(senderUid, KeyManager.DEVICE_ID)
            sessionExists = store.containsSession(senderAddress)

            android.util.Log.d(
                "TeleXE2EE",
                "Decrypt attempt: messageId=$messageId, senderUid=$senderUid, recipientUid=$recipientUid, ciphertextLength=$ciphertextLen, sessionExists=$sessionExists, messageType=$messageType"
            )

            val cipher = SessionCipher(store, senderAddress)
            val ciphertextBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

            val decryptedBytes = when (messageType) {
                CiphertextMessage.PREKEY_TYPE -> {
                    val preKeyMessage = PreKeySignalMessage(ciphertextBytes)
                    val incomingFp = identityFingerprint(preKeyMessage.identityKey)
                    val storedSender = store.getIdentity(senderAddress)
                    val storedSenderFp = identityFingerprint(storedSender)
                    android.util.Log.i("TeleXE2EE", "Identity check: senderUid=$senderUid storedSenderFp=$storedSenderFp incomingFp=$incomingFp myFp=${identityFingerprint(store.identityKeyPair.publicKey)}")
                    if (storedSender != null && storedSender != preKeyMessage.identityKey) {
                        // Check whether incoming prekey matches remote peer's CURRENT published bundle.
                        // If it doesn't match the current bundle, it's an obsolete message from a past identity,
                        // NOT a new identity change, so we must not trigger an alert.
                        val remoteMetadata = getRemoteMetadata()
                        if (remoteMetadata != null && remoteMetadata.identityKey == preKeyMessage.identityKey) {
                            val currentEvent = _identityMismatch.value
                            if (currentEvent == null || currentEvent.peerUid != senderUid || currentEvent.newIdentityFingerprint != incomingFp) {
                                _identityMismatch.value = IdentityMismatchEvent(
                                    peerUid = senderUid,
                                    storedIdentityFingerprint = storedSenderFp,
                                    newIdentityFingerprint = incomingFp
                                )
                            }
                        } else {
                            android.util.Log.w(
                                "TeleXE2EE",
                                "Incoming message $messageId has obsolete identity key $incomingFp (trusted=$storedSenderFp, remotePublished=${remoteMetadata?.let { identityFingerprint(it.identityKey) }}); skipping alert for obsolete past key"
                            )
                        }
                    }
                    cipher.decrypt(preKeyMessage)
                }
                CiphertextMessage.WHISPER_TYPE -> {
                    val signalMessage = SignalMessage(ciphertextBytes)
                    cipher.decrypt(signalMessage)
                }
                else -> {
                    // Fallback attempt: Try PreKeySignalMessage first, then SignalMessage
                    try {
                        val preKeyMessage = PreKeySignalMessage(ciphertextBytes)
                        val storedSender = store.getIdentity(senderAddress)
                        if (storedSender != null && storedSender != preKeyMessage.identityKey) {
                            val remoteMetadata = getRemoteMetadata()
                            if (remoteMetadata != null && remoteMetadata.identityKey == preKeyMessage.identityKey) {
                                val incomingFp = identityFingerprint(preKeyMessage.identityKey)
                                val currentEvent = _identityMismatch.value
                                if (currentEvent == null || currentEvent.peerUid != senderUid || currentEvent.newIdentityFingerprint != incomingFp) {
                                    _identityMismatch.value = IdentityMismatchEvent(
                                        peerUid = senderUid,
                                        storedIdentityFingerprint = identityFingerprint(storedSender),
                                        newIdentityFingerprint = incomingFp
                                    )
                                }
                            }
                        }
                        cipher.decrypt(preKeyMessage)
                    } catch (e: Exception) {
                        val signalMessage = SignalMessage(ciphertextBytes)
                        cipher.decrypt(signalMessage)
                    }
                }
            }

            android.util.Log.i(
                "TeleXE2EE",
                "Decrypt success: messageId=$messageId, senderUid=$senderUid, recipientUid=$recipientUid, ciphertextLength=$ciphertextLen, sessionExists=$sessionExists"
            )

            val plaintext = String(decryptedBytes, Charsets.UTF_8)
            DecryptionResult.Success(plaintext)
        } catch (dup: DuplicateMessageException) {
            android.util.Log.w(
                "TeleXE2EE",
                "Duplicate message detected: messageId=$messageId, senderUid=$senderUid, recipientUid=$recipientUid, ciphertextLength=$ciphertextLen, sessionExists=$sessionExists",
                dup
            )
            DecryptionResult.Duplicate
        } catch (e: Exception) {
            android.util.Log.e(
                "TeleXE2EE",
                "Decryption failed: messageId=$messageId, senderUid=$senderUid, recipientUid=$recipientUid, ciphertextLength=$ciphertextLen, sessionExists=$sessionExists, error=${e.javaClass.simpleName}: ${e.message}",
                e
            )
            DecryptionResult.Failure(e)
        }
    }

    /**
     * Sequentially processes incoming messages for a sender in chronological order under session mutex.
     * Plaintext is saved to Room (never to Firestore) and cached in memory.
     * Failed messages are persisted as encrypted placeholders after retries to prevent infinite loops.
     */
    private suspend fun processIncomingMessagesForSender(
        chatId: String,
        senderUid: String,
        docs: List<RawMessageDoc>
    ) {
        getMutexFor(senderUid).withLock {
            var cachedRemoteMetadata: com.e2eechat.app.crypto.RemoteBundleMetadata? = null
            var fetchedMetadata = false
            suspend fun getRemoteMetadata(): com.e2eechat.app.crypto.RemoteBundleMetadata? {
                if (!fetchedMetadata) {
                    cachedRemoteMetadata = keyManager.fetchPreKeyBundleMetadata(senderUid)
                    fetchedMetadata = true
                }
                return cachedRemoteMetadata
            }

            for (doc in docs) {
                if (unrecoverableIds.contains(doc.id)) continue

                // 1. Check in-memory decrypted cache
                if (decryptedMessageCache.containsKey(doc.id)) {
                    continue
                }

                // 2. Check local Room database (persisted from previous app sessions)
                val localPlaintext = messageDao.getPlaintextById(doc.id)
                if (!localPlaintext.isNullOrBlank()) {
                    cacheDecryptedMessage(doc.id, localPlaintext)
                    clearMessageFailed(doc.id)
                    if (doc.status == "read") {
                        messageDao.updateMessageStatus(doc.id, isRead = true)
                    }
                    continue
                }

                // 3. Needs cryptographic decryption
                val ciphertext = doc.ciphertextBase64
                if (ciphertext.isNullOrBlank()) {
                    continue
                }

                when (val result = decryptIncomingMessage(doc.id, senderUid, ciphertext, doc.messageType, ::getRemoteMetadata)) {
                    is DecryptionResult.Success -> {
                        // Persist to local Room database (never to Firestore)
                        ensureChatExists(chatId)
                        messageDao.insertMessage(
                            MessageEntity(
                                id = doc.id,
                                chatId = chatId,
                                content = result.plaintext,
                                timestamp = formatTime(doc.timestampMillis),
                                isSentByMe = false,
                                isRead = doc.status == "read",
                                imageUrl = doc.imageUrl
                            )
                        )
                        cacheDecryptedMessage(doc.id, result.plaintext)
                        clearMessageFailed(doc.id)
                        decryptionRetryCounts.remove(doc.id)
                    }
                    is DecryptionResult.Duplicate -> {
                        // Re-check Room in case written concurrently
                        val recheck = messageDao.getPlaintextById(doc.id)
                        if (!recheck.isNullOrBlank()) {
                            cacheDecryptedMessage(doc.id, recheck)
                            clearMessageFailed(doc.id)
                            if (doc.status == "read") {
                                messageDao.updateMessageStatus(doc.id, isRead = true)
                            }
                        } else {
                            // Ratchet keys already consumed in past session; unrecoverable duplicate
                            ensureChatExists(chatId)
                            messageDao.insertMessage(
                                MessageEntity(
                                    id = doc.id,
                                    chatId = chatId,
                                    content = "🔒 Encrypted message",
                                    timestamp = formatTime(doc.timestampMillis),
                                    isSentByMe = false,
                                    isRead = doc.status == "read",
                                    imageUrl = doc.imageUrl
                                )
                            )
                            cacheDecryptedMessage(doc.id, "🔒 Encrypted message")
                            clearMessageFailed(doc.id)
                            unrecoverableIds.add(doc.id)
                        }
                        decryptionRetryCounts.remove(doc.id)
                    }
                    is DecryptionResult.Failure -> {
                        val attempts = (decryptionRetryCounts[doc.id] ?: 0) + 1
                        decryptionRetryCounts[doc.id] = attempts
                        val unrecoverable = isUnrecoverable(result.error) || attempts >= MAX_DECRYPTION_RETRIES

                        if (unrecoverable) {
                            unrecoverableIds.add(doc.id)
                            android.util.Log.w(
                                "TeleXE2EE",
                                "Message ${doc.id} permanently failed (attempts=$attempts, unrecoverable=${isUnrecoverable(result.error)}, error=${result.error.javaClass.simpleName}); persisting as encrypted"
                            )
                            ensureChatExists(chatId)
                            messageDao.insertMessage(
                                MessageEntity(
                                    id = doc.id,
                                    chatId = chatId,
                                    content = "🔒 Encrypted message",
                                    timestamp = formatTime(doc.timestampMillis),
                                    isSentByMe = false,
                                    isRead = doc.status == "read",
                                    imageUrl = doc.imageUrl
                                )
                            )
                            cacheDecryptedMessage(doc.id, "🔒 Encrypted message")
                            clearMessageFailed(doc.id)
                        } else {
                            markMessageFailed(doc.id)
                        }
                    }
                }
            }
        }
    }

    fun uploadImageAndSend(chatId: String, imageUri: Uri, senderId: String, timestamp: String) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val inputStream = context.contentResolver.openInputStream(imageUri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes == null) {
                    android.util.Log.e("ChatViewModel", "Could not read image bytes")
                    return@launch
                }
                val fileName = "${chatId}_${System.currentTimeMillis()}.jpg"
                val bucket = supabase.storage.from("chat-images")
                bucket.upload(fileName, bytes)
                val publicUrl = bucket.publicUrl(fileName)

                val now = System.currentTimeMillis()
                val messageData = hashMapOf(
                    "senderId" to myUid,
                    "text" to "",
                    "imageUrl" to publicUrl,
                    "timestampMillis" to now,
                    "status" to "sent"
                )
                val chatRef = firestore.collection("chats").document(chatId)
                chatRef.collection("messages").add(messageData).await()
                chatRef.update(mapOf("lastMessage" to "Photo", "lastMessageTimestamp" to now)).await()
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Image upload failed", e)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        if (millis == 0L) return ""
        return SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    /**
     * Explicit, secure recovery flow for an identity change.
     * Called only upon explicit user confirmation (e.g. user accepts safety number change dialog).
     *
     * 1. Fetches current PreKeyBundle from Firestore.
     * 2. Overwrites trusted identity record in keystore with user's explicit authorization.
     * 3. Deletes any old stale session for this peer.
     * 4. Establishes a fresh session using the newly trusted key.
     * 5. Clears identity mismatch state and allows retry of undecrypted messages.
     */
    fun acceptIdentityChangeAndResetSession(peerUid: String) {
        if (peerUid.isBlank() || myUid.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            getMutexFor(peerUid).withLock {
                try {
                    val store = keyManager.getProtocolStore(myUid, myEmail)
                    val peerAddress = SignalProtocolAddress(peerUid, KeyManager.DEVICE_ID)

                    val bundle = keyManager.fetchAndConsumePreKeyBundle(peerUid)
                    val newFp = identityFingerprint(bundle.identityKey)
                    android.util.Log.w(
                        "TeleXE2EE",
                        "User explicitly accepted identity change for $peerUid: newFp=$newFp"
                    )

                    // Explicitly update trusted identity in keystore store
                    store.saveIdentity(peerAddress, bundle.identityKey)

                    // Delete existing session and establish fresh session with the new identity
                    store.deleteSession(peerAddress)
                    val sessionBuilder = SessionBuilder(store, peerAddress)
                    sessionBuilder.process(bundle.toSignalPreKeyBundle())

                    // Clear mismatch event and allow retry of pending messages
                    _identityMismatch.value = null
                    _sendError.value = null

                    // Reset retry counts and transient failures for messages from this peer
                    _failedMessageIds.value = emptySet()
                    decryptionRetryCounts.keys.removeAll { id ->
                        !unrecoverableIds.contains(id)
                    }

                    android.util.Log.i("TeleXE2EE", "Successfully recovered session and trusted new identity for $peerUid")
                } catch (e: Exception) {
                    android.util.Log.e("TeleXE2EE", "Failed to reset session and trust new identity for $peerUid", e)
                    _sendError.value = "Failed to update identity: ${e.message}"
                }
            }
        }
    }
}

/**
 * Event describing an identity key change detected for a peer.
 */
data class IdentityMismatchEvent(
    val peerUid: String,
    val storedIdentityFingerprint: String,
    val newIdentityFingerprint: String
)
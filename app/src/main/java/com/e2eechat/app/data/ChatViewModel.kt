package com.e2eechat.app.data

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.e2eechat.app.crypto.AttachmentAuthenticationException
import com.e2eechat.app.crypto.AttachmentCrypto
import com.e2eechat.app.crypto.AttachmentHashMismatchException
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
import java.io.File
import java.util.UUID
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

    /**
     * Tracks message IDs currently being downloaded to prevent duplicate concurrent downloads.
     * Access is synchronized via ConcurrentHashMap.newKeySet().
     */
    private val attachmentDownloadsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    companion object {
        private const val MAX_DECRYPTION_RETRIES = 2
        private const val MAX_MESSAGE_TEXT_LENGTH = 5000
        private const val MAX_CHAT_IMAGE_SIZE = 10 * 1024 * 1024 // 10 MB
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
        var registration: com.google.firebase.firestore.ListenerRegistration? = null

        val authListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val uid = firebaseAuth.currentUser?.uid?.trim().orEmpty()
            registration?.remove()
            registration = null

            if (uid.isBlank()) {
                trySend(emptyList())
            } else {
                registration = firestore.collection("chats")
                    .whereArrayContains("participants", uid)
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            android.util.Log.e("ChatViewModel", "Error listening to chats for uid $uid", error)
                            return@addSnapshotListener
                        }
                        val mapped = snapshot?.documents?.mapNotNull { doc ->
                            val participants = doc.get("participants") as? List<*> ?: return@mapNotNull null
                            val otherUid = participants.map { it.toString() }.firstOrNull { it != uid } ?: return@mapNotNull null
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
            }
        }

        auth.addAuthStateListener(authListener)

        awaitClose {
            auth.removeAuthStateListener(authListener)
            registration?.remove()
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

                // Resume pending attachment downloads that survived an app restart.
                // Descriptors were persisted to Room immediately after Double Ratchet decryption,
                // so they are always available even if the previous download was interrupted.
                resumePendingAttachmentDownloads(chatId)
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
        val cleanMyUid = myUidParam.trim()
        val cleanOtherUid = otherUid.trim()
        if (cleanMyUid.isBlank() || cleanOtherUid.isBlank()) {
            android.util.Log.e("ChatViewModel", "Cannot create chat: invalid blank UID (myUid='$cleanMyUid', otherUid='$cleanOtherUid')")
            return
        }

        viewModelScope.launch {
            try {
                val chatId = listOf(cleanMyUid, cleanOtherUid).sorted().joinToString("_")
                val chatRef = firestore.collection("chats").document(chatId)

                var chatExists = false
                try {
                    val existing = chatRef.get().await()
                    chatExists = existing.exists()
                } catch (e: Exception) {
                    // Under strict Firestore security rules, reading a chat document that does
                    // not exist yet evaluates isParticipant(resource.data) with null resource,
                    // which returns PERMISSION_DENIED. We catch this and proceed to create the chat.
                    chatExists = false
                }

                if (!chatExists) {
                    val myDoc = try {
                        firestore.collection("users").document(cleanMyUid).get().await()
                    } catch (e: Exception) {
                        null
                    }
                    val myUsername = myDoc?.getString("username") ?: "Me"
                    val myPhoto = myDoc?.getString("profilePictureUrl") ?: ""

                    val otherDoc = try {
                        firestore.collection("users").document(cleanOtherUid).get().await()
                    } catch (e: Exception) {
                        null
                    }
                    val otherPhoto = otherDoc?.getString("profilePictureUrl") ?: ""

                    val data = hashMapOf(
                        "participants" to listOf(cleanMyUid, cleanOtherUid),
                        "participantNames" to mapOf(cleanMyUid to myUsername, cleanOtherUid to otherUsername),
                        "participantPhotos" to mapOf(cleanMyUid to myPhoto, cleanOtherUid to otherPhoto),
                        "lastMessage" to "",
                        "lastMessageTimestamp" to System.currentTimeMillis(),
                        "createdAt" to System.currentTimeMillis()
                    )
                    // Set creates or merges the chat document. Create is permitted under Firestore rules
                    // when request.auth.uid is in request.resource.data.participants.
                    chatRef.set(data, com.google.firebase.firestore.SetOptions.merge()).await()
                }
                onChatReady(chatId)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Failed to create or get chat", e)
            }
        }
    }

    /**
     * Serialized session encryption under per-peer mutex.
     * Reused by both sendTextMessage and uploadImageAndSend for consistent security,
     * identity key checking, and Double Ratchet state management.
     */
    private suspend fun encryptPayloadForPeer(otherUid: String, payloadBytes: ByteArray): CiphertextMessage? {
        val recipientAddress = SignalProtocolAddress(otherUid, KeyManager.DEVICE_ID)
        return getMutexFor(otherUid).withLock {
            if (!keyManager.hasKeysForUser(myUid) && (myEmail.isBlank() || !keyManager.hasKeysForUser(myEmail))) {
                android.util.Log.e("TeleXE2EE", "Cannot encrypt: local keys are missing for user $myUid")
                _sendError.value = "Cannot send message: local encryption keys are missing. Key recovery required."
                return@withLock null
            }
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
            cipher.encrypt(payloadBytes)
        }
    }

    /**
     * Sends an E2EE text message using the Signal Protocol Double Ratchet session cipher.
     * No plaintext is written to Firestore for messages or lastMessage previews.
     */
    fun sendTextMessage(chatId: String, text: String) {
        if (text.isBlank() || myUid.isBlank()) return
        if (text.length > MAX_MESSAGE_TEXT_LENGTH) {
            _sendError.value = "Message exceeds maximum length of $MAX_MESSAGE_TEXT_LENGTH characters."
            return
        }
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
                val ciphertextMessage = encryptPayloadForPeer(otherUid, text.toByteArray(Charsets.UTF_8))
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
        if (!keyManager.hasKeysForUser(myUid) && (myEmail.isBlank() || !keyManager.hasKeysForUser(myEmail))) {
            android.util.Log.w("TeleXE2EE", "Cannot decrypt message $messageId: local encryption keys are missing for user $myUid")
            return@withContext DecryptionResult.Failure(IllegalStateException("Local encryption keys missing"))
        }
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
                        val parsedDesc = AttachmentCrypto.parseDescriptorOrNull(result.plaintext)
                        val (contentToStore, descriptorToStore, cacheText) = if (parsedDesc != null) {
                            Triple(parsedDesc.caption, result.plaintext, parsedDesc.caption.ifBlank { "📷 Photo" })
                        } else {
                            Triple(result.plaintext, null, result.plaintext)
                        }
                        messageDao.insertMessage(
                            MessageEntity(
                                id = doc.id,
                                chatId = chatId,
                                content = contentToStore,
                                timestamp = formatTime(doc.timestampMillis),
                                isSentByMe = false,
                                isRead = doc.status == "read",
                                imageUrl = doc.imageUrl,
                                attachmentDescriptor = descriptorToStore
                            )
                        )
                        cacheDecryptedMessage(doc.id, cacheText)
                        clearMessageFailed(doc.id)
                        decryptionRetryCounts.remove(doc.id)

                        // Trigger background download for encrypted attachments
                        if (parsedDesc != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                downloadAndDecryptAttachment(doc.id, chatId)
                            }
                        }
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
        if (myUid.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val parts = chatId.split("_")
                val otherUid = parts.firstOrNull { it != myUid }
                if (otherUid.isNullOrBlank()) {
                    android.util.Log.e("ChatViewModel", "Cannot resolve recipient UID from chatId: $chatId")
                    _sendError.value = "Cannot resolve recipient."
                    return@launch
                }

                val context = getApplication<Application>()

                // 1. Read image bytes and validate max size
                val bytes = context.contentResolver.openInputStream(imageUri)?.use { stream ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val data = ByteArray(8192)
                    var totalRead = 0
                    var nRead: Int
                    while (stream.read(data, 0, data.size).also { nRead = it } != -1) {
                        totalRead += nRead
                        if (totalRead > MAX_CHAT_IMAGE_SIZE) {
                            throw IllegalArgumentException("Image exceeds 10MB limit")
                        }
                        buffer.write(data, 0, nRead)
                    }
                    buffer.toByteArray()
                }
                if (bytes == null || bytes.isEmpty()) {
                    android.util.Log.e("ChatViewModel", "Could not read image bytes")
                    _sendError.value = "Could not read image file."
                    return@launch
                }

                val mimeType = context.contentResolver.getType(imageUri) ?: "image/jpeg"

                // 2. Persist local copy to internal app filesDir so sender can display the photo reliably
                val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
                val localAttachmentFile = File(attachmentsDir, "sent_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")
                localAttachmentFile.writeBytes(bytes)
                val localFileUriString = localAttachmentFile.toURI().toString()

                // 3. Client-side encryption with AttachmentCrypto
                val encryptedAttachment = AttachmentCrypto.encrypt(bytes)
                val objectName = "${UUID.randomUUID()}.enc"
                val storagePath = "chat-images/$objectName"

                val descriptor = AttachmentCrypto.createDescriptor(
                    encrypted = encryptedAttachment,
                    storagePath = storagePath,
                    mimeType = mimeType,
                    caption = ""
                )
                val descriptorJson = AttachmentCrypto.serializeDescriptor(descriptor)

                // 4. Upload ciphertext ONLY to Supabase before ratcheting
                // If upload fails here (e.g. offline/timeout), the Double Ratchet is NEVER advanced
                val bucket = supabase.storage.from("chat-images")
                bucket.upload(objectName, encryptedAttachment.ciphertext)

                // 5. Encrypt descriptor through per-peer Double Ratchet session under mutex
                val ciphertextMessage = encryptPayloadForPeer(otherUid, descriptorJson.toByteArray(Charsets.UTF_8))
                if (ciphertextMessage == null) {
                    // Identity mismatch or session establishment failure (error state already set)
                    return@launch
                }

                val ciphertextBase64 = Base64.encodeToString(ciphertextMessage.serialize(), Base64.NO_WRAP)
                val messageType = ciphertextMessage.type
                val now = System.currentTimeMillis()

                // 6. Post standard encrypted message document to Firestore
                // Never place AES key, IV, descriptor, or plaintext image URL in Firestore
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

                // 7. Update reactive cache and local Room persistence
                // Sender displays the local file image immediately with double-tick tracking
                cacheDecryptedMessage(addedDoc.id, "")

                ensureChatExists(chatId)
                messageDao.insertMessage(
                    MessageEntity(
                        id = addedDoc.id,
                        chatId = chatId,
                        content = "",
                        timestamp = formatTime(now),
                        isSentByMe = true,
                        isRead = false,
                        imageUrl = localFileUriString,
                        attachmentDescriptor = descriptorJson
                    )
                )

                // Update chat metadata with opaque "Photo" preview
                chatRef.update(
                    mapOf(
                        "lastMessage" to "Photo",
                        "lastMessageTimestamp" to now
                    )
                ).await()

            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Image upload and send failed", e)
                _sendError.value = "Failed to send photo: ${e.message ?: "Unknown error"}"
            }
        }
    }

    /**
     * Downloads, verifies, and decrypts an encrypted attachment for a received message.
     *
     * Safety guarantees:
     * - The descriptor was persisted to Room before this method is called, so the Double Ratchet
     *   message never needs to be replayed to recover the AES key and IV.
     * - SHA-256 hash is verified before GCM decryption to detect transit corruption early.
     * - AES-GCM authenticated decryption rejects tampered ciphertext or incorrect keys.
     * - Decrypted bytes are saved only to app-private internal storage (filesDir/attachments/).
     * - The Room imageUrl is updated atomically, so the UI picks up the local file path.
     * - In-flight tracking prevents duplicate concurrent downloads for the same message.
     * - Network failures, missing blobs, and corrupted data are caught without crashing.
     * - Keys, IVs, and plaintext are never logged.
     */
    private suspend fun downloadAndDecryptAttachment(messageId: String, chatId: String) {
        // Guard: prevent duplicate concurrent downloads
        if (!attachmentDownloadsInFlight.add(messageId)) {
            return
        }

        try {
            // 1. Read persisted descriptor from Room
            val descriptorJson = messageDao.getAttachmentDescriptorById(messageId)
            if (descriptorJson.isNullOrBlank()) {
                android.util.Log.w("TeleXAttachment", "No descriptor found for message $messageId; skipping download")
                return
            }

            val descriptor = AttachmentCrypto.parseDescriptorOrNull(descriptorJson)
            if (descriptor == null) {
                android.util.Log.e("TeleXAttachment", "Failed to parse descriptor for message $messageId; data may be corrupt")
                return
            }

            // 2. Check if already downloaded (imageUrl already set to a local file)
            val existingMessage = messageDao.getMessageById(messageId)
            if (existingMessage != null && !existingMessage.imageUrl.isNullOrBlank()) {
                // Already downloaded; ensure cache is populated
                val displayText = existingMessage.content.ifBlank { descriptor.caption.ifBlank { "📷 Photo" } }
                cacheDecryptedMessage(messageId, displayText)
                return
            }

            // 3. Download ciphertext from Supabase Storage
            //    The descriptor.storagePath is "chat-images/<uuid>.enc".
            //    The bucket reference is "chat-images", so the object name is just the filename.
            val objectName = descriptor.storagePath.removePrefix("chat-images/")
            val bucket = supabase.storage.from("chat-images")

            val ciphertextBytes: ByteArray
            try {
                ciphertextBytes = bucket.downloadAuthenticated(objectName)
            } catch (e: Exception) {
                android.util.Log.e(
                    "TeleXAttachment",
                    "Failed to download attachment for message $messageId: ${e.javaClass.simpleName}"
                )
                return
            }

            if (ciphertextBytes.isEmpty()) {
                android.util.Log.e("TeleXAttachment", "Downloaded empty ciphertext for message $messageId")
                return
            }

            // 4. Verify SHA-256 hash and decrypt with AES-GCM
            val decryptedBytes: ByteArray
            try {
                decryptedBytes = AttachmentCrypto.decryptWithDescriptor(descriptor, ciphertextBytes)
            } catch (e: AttachmentHashMismatchException) {
                android.util.Log.e(
                    "TeleXAttachment",
                    "Attachment hash mismatch for message $messageId; ciphertext may have been tampered with"
                )
                return
            } catch (e: AttachmentAuthenticationException) {
                android.util.Log.e(
                    "TeleXAttachment",
                    "Attachment GCM authentication failed for message $messageId; ciphertext or key is invalid"
                )
                return
            } catch (e: Exception) {
                android.util.Log.e(
                    "TeleXAttachment",
                    "Attachment decryption failed for message $messageId: ${e.javaClass.simpleName}"
                )
                return
            }

            // 5. Save decrypted image to app-private internal storage
            val context = getApplication<Application>()
            val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
            val extension = mimeTypeToExtension(descriptor.mimeType)
            val localFile = File(
                attachmentsDir,
                "recv_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"
            )
            localFile.writeBytes(decryptedBytes)
            val localFileUri = localFile.toURI().toString()

            // 6. Update Room: set imageUrl to the local file path
            messageDao.updateMessageImageUrl(messageId, localFileUri)

            // 7. Update the reactive decrypted cache so UI recomposes with the image
            val displayText = descriptor.caption.ifBlank { "📷 Photo" }
            cacheDecryptedMessage(messageId, displayText)

            android.util.Log.i(
                "TeleXAttachment",
                "Successfully downloaded and decrypted attachment for message $messageId (${decryptedBytes.size} bytes)"
            )
        } finally {
            attachmentDownloadsInFlight.remove(messageId)
        }
    }

    /**
     * Resumes all pending attachment downloads for a chat.
     * Called on app restart when Room data is pre-loaded for a chat.
     * Uses the persisted descriptor from Room (never requires replaying ratchet messages).
     */
    private suspend fun resumePendingAttachmentDownloads(chatId: String) {
        try {
            val pendingMessages = messageDao.getPendingAttachmentMessages(chatId)
            for (msg in pendingMessages) {
                // Skip sender's own messages (they already have local file URIs)
                if (msg.isSentByMe) continue
                viewModelScope.launch(Dispatchers.IO) {
                    downloadAndDecryptAttachment(msg.id, chatId)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TeleXAttachment", "Failed to resume pending downloads for chat $chatId", e)
        }
    }

    /**
     * Maps a MIME type to a file extension for saving decrypted attachments.
     */
    private fun mimeTypeToExtension(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heif", "image/heic" -> "heic"
            else -> "jpg"
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
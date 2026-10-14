package com.e2eechat.app.data

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.e2eechat.app.crypto.KeyManager
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

    private val supabase = createSupabaseClient(
        supabaseUrl = "https://taivxyserowgwioffjob.supabase.co",
        supabaseKey = "sb_publishable_NaKHhP54MD64THYf1EbuVw_t6NTLP7Y"
    ) {
        install(Storage)
    }

    private val myUid: String get() = auth.currentUser?.uid ?: ""

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

    /** Exposed so the UI layer can observe and react to send errors. */
    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    fun clearSendError() { _sendError.value = null }

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
            // Firestore snapshot flow (raw docs, no decryption)
            val rawFlow: Flow<List<RawMessageDoc>> = callbackFlow {
                val registration = firestore.collection("chats").document(chatId)
                    .collection("messages")
                    .orderBy("timestampMillis", Query.Direction.ASCENDING)
                    .addSnapshotListener { snapshot, _ ->
                        val currentMyUid = myUid
                        if (snapshot == null || currentMyUid.isBlank()) {
                            trySend(emptyList())
                            return@addSnapshotListener
                        }
                        val raw = snapshot.documents.map { doc ->
                            RawMessageDoc(
                                id = doc.id,
                                senderId = doc.getString("senderId") ?: "",
                                legacyText = doc.getString("text"),
                                ciphertextBase64 = doc.getString("ciphertext"),
                                messageType = doc.getLong("messageType")?.toInt(),
                                timestampMillis = doc.getLong("timestampMillis") ?: 0L,
                                status = doc.getString("status"),
                                imageUrl = doc.getString("imageUrl")
                            )
                        }
                        trySend(raw)
                    }
                awaitClose {
                    registration.remove()
                    messageFlowCache.remove(chatId)
                }
            }

            // Combine raw docs with the reactive decrypted-text cache.
            // Any change to the cache (e.g. caching a just-sent message) immediately
            // re-triggers message building without waiting for the next Firestore snapshot.
            combine(rawFlow, _decryptedCache) { rawDocs, cache ->
                    val currentMyUid = myUid
                    val messages = rawDocs.map { doc ->
                        val isFromMe = doc.senderId == currentMyUid

                        // Resolve display text in priority order:
                        // 1. Already cached (covers sent messages and previously decrypted received ones)
                        val cachedText = cache[doc.id]
                        val decryptedText: String = when {
                            cachedText != null -> cachedText

                            !doc.ciphertextBase64.isNullOrBlank() -> {
                                if (isFromMe) {
                                    // Sender-side: cache was not populated yet → show placeholder.
                                    // This branch is transient; sendTextMessage() will update _decryptedCache
                                    // after add().await() returns, causing an immediate re-emission.
                                    "Sending…"
                                } else {
                                    // Received encrypted message: decrypt and persist into cache.
                                    // decryptIncomingMessage is a suspend function; we call it
                                    // from a separate launch to keep the combine lambda non-blocking.
                                    // For the initial pass, return a placeholder; the launched
                                    // coroutine will update _decryptedCache and trigger re-emission.
                                    if (!cache.containsKey(doc.id)) {
                                        viewModelScope.launch(Dispatchers.IO) {
                                            val plaintext = decryptIncomingMessage(
                                                doc.senderId,
                                                doc.ciphertextBase64,
                                                doc.messageType
                                            )
                                            if (plaintext != null) {
                                                cacheDecryptedMessage(doc.id, plaintext)
                                            } else {
                                                // Mark as permanently unreadable to avoid retry loops
                                                cacheDecryptedMessage(doc.id, "🔒 Encrypted message")
                                            }
                                        }
                                        "🔒 Decrypting…"
                                    } else {
                                        cache[doc.id] ?: "🔒 Encrypted message"
                                    }
                                }
                            }

                            doc.legacyText != null -> doc.legacyText

                            else -> ""
                        }

                        Message(
                            id = doc.id,
                            senderId = doc.senderId,
                            text = decryptedText,
                            timestamp = formatTime(doc.timestampMillis),
                            isFromMe = isFromMe,
                            status = if (doc.status == "read") MessageStatus.READ else MessageStatus.SENT,
                            imageUrl = doc.imageUrl
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
                                }
                                batch.commit().await()
                            } catch (e: Exception) {
                                android.util.Log.e("ChatViewModel", "Failed to batch commit read receipts", e)
                            }
                        }
                    }

                    messages
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
                val ciphertextMessage: CiphertextMessage = getMutexFor(otherUid).withLock {
                    val store = keyManager.getProtocolStore(myUid)

                    // If no existing session with recipient, establish one using published PreKeyBundle
                    if (!store.containsSession(recipientAddress)) {
                        val publishedBundle = keyManager.fetchAndConsumePreKeyBundle(otherUid)
                        val signalBundle = publishedBundle.toSignalPreKeyBundle()
                        val sessionBuilder = SessionBuilder(store, recipientAddress)
                        sessionBuilder.process(signalBundle)
                    }

                    val cipher = SessionCipher(store, recipientAddress)
                    cipher.encrypt(text.toByteArray(Charsets.UTF_8))
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

    /**
     * Decrypts an incoming message payload using SessionCipher and persists updated ratchet state.
     */
    private suspend fun decryptIncomingMessage(
        senderUid: String,
        ciphertextBase64: String,
        messageType: Int?
    ): String? = withContext(Dispatchers.IO) {
        getMutexFor(senderUid).withLock {
            try {
                val store = keyManager.getProtocolStore(myUid)
                val senderAddress = SignalProtocolAddress(senderUid, KeyManager.DEVICE_ID)
                val cipher = SessionCipher(store, senderAddress)
                val ciphertextBytes = Base64.decode(ciphertextBase64, Base64.NO_WRAP)

                val decryptedBytes = when (messageType) {
                    CiphertextMessage.PREKEY_TYPE -> {
                        val preKeyMessage = PreKeySignalMessage(ciphertextBytes)
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
                            cipher.decrypt(preKeyMessage)
                        } catch (e: Exception) {
                            val signalMessage = SignalMessage(ciphertextBytes)
                            cipher.decrypt(signalMessage)
                        }
                    }
                }

                String(decryptedBytes, Charsets.UTF_8)
            } catch (dup: DuplicateMessageException) {
                android.util.Log.w("ChatViewModel", "Duplicate message – already decrypted from $senderUid")
                null
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Decryption failed for sender $senderUid: ${e.javaClass.simpleName}: ${e.message}")
                null
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
}
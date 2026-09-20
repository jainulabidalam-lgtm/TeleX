package com.e2eechat.app.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.e2eechat.app.model.Chat
import com.e2eechat.app.model.Message
import com.e2eechat.app.model.MessageStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val chatDao = database.chatDao()
    private val messageDao = database.messageDao()

    private val supabase = createSupabaseClient(
        supabaseUrl = "https://taivxyserowgwioffjob.supabase.co",
        supabaseKey = "sb_publishable_NaKHhP54MD64THYf1EbuVw_t6NTLP7Y"
    ) {
        install(Storage)
    }

    val chats: Flow<List<Chat>> = chatDao.getAllChats().map { chats ->
        chats.map { it.toChat() }
    }

    init {
        viewModelScope.launch {
            database.withTransaction {
                if (chatDao.getChatCount() == 0) {
                    MockData.sampleChats.forEach { chat ->
                        chatDao.insertChat(chat.toEntity())
                        chat.messages.forEach { message ->
                            messageDao.insertMessage(message.toEntity(chat.id))
                        }
                    }
                }
            }
        }
    }

    fun messagesForChat(chatId: String): Flow<List<Message>> =
        messageDao.getMessagesForChat(chatId).map { messages ->
            messages.map { it.toMessage() }
        }

    fun markChatRead(chatId: String) {
        viewModelScope.launch {
            val chat = chatDao.findById(chatId) ?: return@launch
            if (chat.unreadCount > 0) {
                chatDao.updateChat(chat.copy(unreadCount = 0))
            }
        }
    }

    fun sendMessage(chatId: String, message: Message) {
        viewModelScope.launch {
            database.withTransaction {
                messageDao.insertMessage(message.toEntity(chatId))
                val chat = chatDao.findById(chatId) ?: return@withTransaction
                chatDao.updateChat(
                    chat.copy(
                        lastMessage = message.text,
                        timestamp = message.timestamp
                    )
                )
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

                val message = Message(
                    id = "msg_${System.currentTimeMillis()}",
                    senderId = senderId,
                    text = "",
                    timestamp = timestamp,
                    isFromMe = true,
                    status = MessageStatus.READ,
                    imageUrl = publicUrl
                )
                sendMessage(chatId, message)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Image upload failed", e)
            }
        }
    }
}

private fun ChatEntity.toChat() = Chat(
    id = id,
    contactName = contactName,
    avatarInitials = contactInitials,
    avatarColorHex = avatarColorHex,
    isOnline = isOnline,
    lastSeenText = lastSeenText,
    lastMessage = lastMessage,
    lastMessageTimestamp = timestamp,
    unreadCount = unreadCount
)

private fun Chat.toEntity() = ChatEntity(
    id = id,
    contactName = contactName,
    contactInitials = avatarInitials,
    lastMessage = lastMessage,
    timestamp = lastMessageTimestamp,
    unreadCount = unreadCount,
    isOnline = isOnline,
    avatarColorHex = avatarColorHex,
    lastSeenText = lastSeenText
)

private fun MessageEntity.toMessage() = Message(
    id = id,
    senderId = if (isSentByMe) "me" else "contact",
    text = content,
    timestamp = timestamp,
    isFromMe = isSentByMe,
    status = if (isRead) MessageStatus.READ else MessageStatus.SENT,
    imageUrl = imageUrl
)

private fun Message.toEntity(chatId: String) = MessageEntity(
    id = id,
    chatId = chatId,
    content = text,
    timestamp = timestamp,
    isSentByMe = isFromMe,
    isRead = status == MessageStatus.READ,
    imageUrl = imageUrl
)
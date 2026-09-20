package com.e2eechat.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.e2eechat.app.model.Chat
import com.e2eechat.app.model.Message
import com.e2eechat.app.model.MessageStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getInstance(application)
    private val chatDao = database.chatDao()
    private val messageDao = database.messageDao()

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

    fun uploadImageAndSend(chatId: String, imageUri: android.net.Uri, senderId: String, timestamp: String) {
        viewModelScope.launch {
            try {
                val storageRef = com.google.firebase.storage.FirebaseStorage.getInstance().reference
                val fileRef = storageRef.child("chat_images/${chatId}/${System.currentTimeMillis()}.jpg")
                fileRef.putFile(imageUri).await()
                val downloadUrl = fileRef.downloadUrl.await().toString()

                val message = Message(
                    id = "msg_${System.currentTimeMillis()}",
                    senderId = senderId,
                    text = "",
                    timestamp = timestamp,
                    isFromMe = true,
                    status = MessageStatus.READ,
                    imageUrl = downloadUrl
                )
                sendMessage(chatId, message)
            } catch (e: Exception) {
                // Upload failed, could add error state here later
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
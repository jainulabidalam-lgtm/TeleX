package com.e2eechat.app.model

enum class MessageStatus {
    SENT,
    DELIVERED,
    READ
}

data class Message(
    val id: String,
    val senderId: String,
    val text: String,
    val timestamp: String,
    val isFromMe: Boolean,
    val status: MessageStatus = MessageStatus.READ
)

data class Chat(
    val id: String,
    val contactName: String,
    val avatarInitials: String,
    val avatarColorHex: Long,
    val isOnline: Boolean,
    val lastSeenText: String,
    val lastMessage: String,
    val lastMessageTimestamp: String,
    val unreadCount: Int = 0,
    val messages: List<Message> = emptyList()
)

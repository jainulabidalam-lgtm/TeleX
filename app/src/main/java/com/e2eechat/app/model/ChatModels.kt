package com.e2eechat.app.model

import androidx.compose.runtime.Immutable

enum class MessageStatus {
    SENT,
    DELIVERED,
    READ
}

@Immutable
data class Message(
    val id: String,
    val senderId: String,
    val text: String,
    val timestamp: String,
    val isFromMe: Boolean,
    val status: MessageStatus = MessageStatus.READ,
    val imageUrl: String? = null
)

@Immutable
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

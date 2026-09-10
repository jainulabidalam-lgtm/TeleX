package com.e2eechat.app.data

import com.e2eechat.app.model.Chat
import com.e2eechat.app.model.Message
import com.e2eechat.app.model.MessageStatus

object MockData {

    val sampleChats: List<Chat> = listOf(
        Chat(
            id = "chat_elena",
            contactName = "Elena Rostova",
            avatarInitials = "ER",
            avatarColorHex = 0xFF7C5CFC, // Electric Violet
            isOnline = true,
            lastSeenText = "Online • E2E Encrypted",
            lastMessage = "Double ratchet session keys rotated. Verification complete.",
            lastMessageTimestamp = "10:42 AM",
            unreadCount = 2,
            messages = listOf(
                Message("m1", "elena", "Hey! Did you check the new zero-knowledge key exchange protocol?", "10:35 AM", false),
                Message("m2", "me", "Yes, reviewed it carefully. Looks bulletproof against replay attacks.", "10:38 AM", true, MessageStatus.READ),
                Message("m3", "elena", "Great! Just updated the local identity keys.", "10:40 AM", false),
                Message("m4", "elena", "Double ratchet session keys rotated. Verification complete.", "10:42 AM", false)
            )
        ),
        Chat(
            id = "chat_marcus",
            contactName = "Marcus Vance",
            avatarInitials = "MV",
            avatarColorHex = 0xFF3B82F6, // Deep Azure
            isOnline = true,
            lastSeenText = "Online • E2E Encrypted",
            lastMessage = "Are we storing identity keys in Android Keystore?",
            lastMessageTimestamp = "09:15 AM",
            unreadCount = 0,
            messages = listOf(
                Message("m10", "me", "Are we hardware-backing the private key storage?", "09:10 AM", true, MessageStatus.READ),
                Message("m11", "marcus", "Are we storing identity keys in Android Keystore?", "09:15 AM", false)
            )
        ),
        Chat(
            id = "chat_sophia",
            contactName = "Sophia Chen",
            avatarInitials = "SC",
            avatarColorHex = 0xFFEC4899, // Cyber Magenta
            isOnline = false,
            lastSeenText = "Last seen 12m ago • E2E Encrypted",
            lastMessage = "The headline typography with tight letter spacing looks super crisp!",
            lastMessageTimestamp = "Yesterday",
            unreadCount = 1,
            messages = listOf(
                Message("m20", "sophia", "The dark-first design direction feels extremely premium.", "Yesterday", false),
                Message("m21", "sophia", "The headline typography with tight letter spacing looks super crisp!", "Yesterday", false)
            )
        ),
        Chat(
            id = "chat_alex",
            contactName = "Alex Rivera",
            avatarInitials = "AR",
            avatarColorHex = 0xFF10B981, // Emerald Green
            isOnline = true,
            lastSeenText = "Online • E2E Encrypted",
            lastMessage = "60fps animations verified across mid-range test devices.",
            lastMessageTimestamp = "Yesterday",
            unreadCount = 0,
            messages = listOf(
                Message("m30", "alex", "LazyColumn layout optimization completed.", "Yesterday", false),
                Message("m31", "me", "Nice! Any jank during rapid message scrolling?", "Yesterday", true, MessageStatus.READ),
                Message("m32", "alex", "60fps animations verified across mid-range test devices.", "Yesterday", false)
            )
        ),
        Chat(
            id = "chat_aris",
            contactName = "Dr. Aris Thorne",
            avatarInitials = "AT",
            avatarColorHex = 0xFFF59E0B, // Amber Gold
            isOnline = false,
            lastSeenText = "Last seen 3h ago • E2E Encrypted",
            lastMessage = "Safety numbers match. Channel verified.",
            lastMessageTimestamp = "Aug 22",
            unreadCount = 0,
            messages = listOf(
                Message("m40", "aris", "Safety numbers match. Channel verified.", "Aug 22", false)
            )
        )
    )
}

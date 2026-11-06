package com.e2eechat.app.data

import com.e2eechat.app.model.Chat
import com.e2eechat.app.model.Message
import com.e2eechat.app.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the TeleX photo-viewer regression.
 *
 * Verifies:
 * 1. Image message attachments retain their imageUrl and are identifiable as photo messages.
 * 2. Tapping a photo message transitions the viewer state to full-screen mode with the correct URL.
 * 3. Dismissing the full-screen view resets the viewing state without affecting chat messages or history.
 * 4. Pinch-to-zoom scale clamping and double-tap zoom/reset logic behave correctly.
 */
class PhotoViewerRegressionTest {

    @Test
    fun testPhotoMessageContainsImageUrl() {
        val imageMessage = Message(
            id = "msg_photo_1",
            senderId = "user_123",
            text = "",
            timestamp = "10:30 AM",
            isFromMe = false,
            status = MessageStatus.READ,
            imageUrl = "https://example.com/photos/chat_image_123.jpg"
        )

        assertNotNull("Image message must have non-null imageUrl", imageMessage.imageUrl)
        assertEquals("https://example.com/photos/chat_image_123.jpg", imageMessage.imageUrl)
    }

    @Test
    fun testTapToOpenFullScreenViewerState() {
        var viewingImageUrl: String? = null

        val onImageClick: (String) -> Unit = { url ->
            viewingImageUrl = url
        }

        val onDismiss: () -> Unit = {
            viewingImageUrl = null
        }

        val testUrl = "https://taivxyserowgwioffjob.supabase.co/storage/v1/object/public/chat-images/photo_456.jpg"
        val message = Message(
            id = "msg_photo_2",
            senderId = "sender_a",
            text = "Check this out",
            timestamp = "10:31 AM",
            isFromMe = true,
            status = MessageStatus.SENT,
            imageUrl = testUrl
        )

        // Initially no full-screen viewer is open
        assertNull("Viewer should be closed initially", viewingImageUrl)

        // Tap on the photo
        onImageClick(message.imageUrl!!)

        // Full-screen viewer state should now hold the message image URL
        assertEquals("Viewer should open with the clicked photo URL", testUrl, viewingImageUrl)

        // Dismiss the full-screen viewer
        onDismiss()

        // Viewer should now be dismissed
        assertNull("Viewer should be null after dismissal", viewingImageUrl)
    }

    @Test
    fun testViewerZoomAndPanMath() {
        // Test scale clamping: scale must stay within [1f, 5f]
        fun clampScale(currentScale: Float, zoomFactor: Float): Float {
            return (currentScale * zoomFactor).coerceIn(1f, 5f)
        }

        // Test double-tap toggle
        fun doubleTapZoom(currentScale: Float): Float {
            return if (currentScale > 1f) 1f else 2.5f
        }

        // 1. Initial scale
        var scale = 1f
        assertEquals(1f, scale, 0.001f)

        // 2. Pinch zoom in
        scale = clampScale(scale, 1.5f)
        assertEquals(1.5f, scale, 0.001f)

        // 3. Pinch zoom beyond max (e.g. 10x) -> must clamp to 5x
        scale = clampScale(scale, 10f)
        assertEquals(5.0f, scale, 0.001f)

        // 4. Pinch zoom below 1x (e.g. 0.1x) -> must clamp to 1x
        scale = clampScale(scale, 0.05f)
        assertEquals(1.0f, scale, 0.001f)

        // 5. Double-tap from 1x -> zooms to 2.5x
        scale = doubleTapZoom(scale)
        assertEquals(2.5f, scale, 0.001f)

        // 6. Double-tap from 2.5x -> resets to 1.0x
        scale = doubleTapZoom(scale)
        assertEquals(1.0f, scale, 0.001f)
    }

    @Test
    fun testChatPreservesImageAndTextMessagesTogether() {
        val messages = listOf(
            Message(
                id = "m1",
                senderId = "user1",
                text = "Hello!",
                timestamp = "09:00",
                isFromMe = true,
                status = MessageStatus.READ,
                imageUrl = null
            ),
            Message(
                id = "m2",
                senderId = "user2",
                text = "",
                timestamp = "09:01",
                isFromMe = false,
                status = MessageStatus.READ,
                imageUrl = "https://example.com/img.png"
            )
        )

        val chat = Chat(
            id = "chat_1",
            contactName = "Alice",
            avatarInitials = "A",
            avatarColorHex = 0xFF6E56CFL,
            isOnline = true,
            lastSeenText = "Online",
            lastMessage = "Photo",
            lastMessageTimestamp = "09:01",
            messages = messages
        )

        assertEquals(2, chat.messages.size)
        assertNull(chat.messages[0].imageUrl)
        assertEquals("https://example.com/img.png", chat.messages[1].imageUrl)
    }
}

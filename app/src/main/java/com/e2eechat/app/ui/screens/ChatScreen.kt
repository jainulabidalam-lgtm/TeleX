package com.e2eechat.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.e2eechat.app.model.Chat
import com.e2eechat.app.model.Message
import com.e2eechat.app.model.MessageStatus
import com.e2eechat.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chat: Chat,
    onBackClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendImage: (Uri) -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onSendImage(it) }
    }

    LaunchedEffect(chat.messages.size) {
        if (chat.messages.isNotEmpty()) {
            listState.animateScrollToItem(chat.messages.size - 1)
        }
    }

    fun handleSend() {
        if (inputText.isNotBlank()) {
            val textToSend = inputText.trim()
            inputText = ""
            onSendMessage(textToSend)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TeleXTextPrimary
                            )
                        }
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(TeleXPrimaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = chat.avatarInitials,
                                    style = Typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    ),
                                    color = TeleXPrimaryDeep
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column {
                                Text(
                                    text = chat.contactName,
                                    style = Typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = TeleXTextPrimary
                                )
                                val subtitleText = when {
                                    chat.isOnline -> "Online"
                                    chat.lastSeenText.isNotBlank() -> chat.lastSeenText
                                    else -> "Direct Message"
                                }
                                Text(
                                    text = subtitleText,
                                    style = Typography.labelMedium.copy(fontSize = 11.sp),
                                    color = TeleXTextSecondary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { /* Voice call preview */ }) {
                            Icon(
                                imageVector = Icons.Filled.Phone,
                                contentDescription = "Voice Call",
                                tint = TeleXTextSecondary
                            )
                        }
                        IconButton(onClick = { /* Video call preview */ }) {
                            Icon(
                                imageVector = Icons.Filled.Videocam,
                                contentDescription = "Video Call",
                                tint = TeleXTextSecondary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = TeleXBackground
                    )
                )
                HorizontalDivider(thickness = 0.5.dp, color = TeleXDivider)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(thickness = 0.5.dp, color = TeleXDivider)
                Surface(
                    color = TeleXBackground,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                            .navigationBarsPadding()
                            .imePadding(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { imagePickerLauncher.launch("image/*") }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AttachFile,
                                contentDescription = "Attach Image",
                                tint = TeleXTextSecondary
                            )
                        }

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    text = "Message",
                                    style = Typography.bodyMedium,
                                    color = TeleXTextMuted
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp, max = 120.dp),
                            shape = RoundedCornerShape(22.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = BubbleReceivedBg,
                                unfocusedContainerColor = BubbleReceivedBg,
                                focusedBorderColor = TeleXPrimary,
                                unfocusedBorderColor = BubbleReceivedBorder,
                                focusedTextColor = TeleXTextPrimary,
                                unfocusedTextColor = TeleXTextPrimary,
                                cursorColor = TeleXPrimary
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { handleSend() })
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { handleSend() },
                            enabled = inputText.isNotBlank(),
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(
                                    if (inputText.isNotBlank()) TeleXPrimary else TeleXOutlineVariant.copy(alpha = 0.5f)
                                )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Message",
                                tint = if (inputText.isNotBlank()) TeleXTextOnPrimary else TeleXTextMuted,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }
        },
        containerColor = TeleXBackground
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(
                items = chat.messages,
                key = { _, msg -> msg.id },
                contentType = { _, msg -> if (msg.imageUrl != null) "image" else "text" }
            ) { _, message ->
                MessageBubble(message = message)
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message
) {
    val isFromMe = message.isFromMe

    val bubbleShape = if (isFromMe) {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = 18.dp,
            bottomEnd = 4.dp
        )
    } else {
        RoundedCornerShape(
            topStart = 18.dp,
            topEnd = 18.dp,
            bottomStart = 4.dp,
            bottomEnd = 18.dp
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        contentAlignment = if (isFromMe) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = bubbleShape,
            color = if (isFromMe) BubbleSentBg else BubbleReceivedBg,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .border(
                    width = if (!isFromMe) 0.5.dp else 0.dp,
                    color = BubbleReceivedBorder,
                    shape = bubbleShape
                )
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (message.imageUrl != null) 6.dp else 14.dp,
                    vertical = if (message.imageUrl != null) 6.dp else 9.dp
                )
            ) {
                if (message.imageUrl != null) {
                    AsyncImage(
                        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(message.imageUrl)
                            .size(520, 520)
                            .crossfade(200)
                            .memoryCacheKey(message.imageUrl)
                            .build(),
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (message.text.isNotBlank()) {
                    Text(
                        text = message.text,
                        style = Typography.bodyLarge,
                        color = if (isFromMe) BubbleSentText else BubbleReceivedText
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = message.timestamp,
                        style = Typography.labelMedium.copy(fontSize = 10.5.sp),
                        color = if (isFromMe) BubbleSentTime else BubbleReceivedTime
                    )

                    if (isFromMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        val isRead = message.status == MessageStatus.READ
                        Icon(
                            imageVector = if (isRead) Icons.Filled.DoneAll else Icons.Filled.Done,
                            contentDescription = if (isRead) "Read" else "Sent",
                            tint = if (isRead) BubbleSentTickRead else BubbleSentTickSent,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

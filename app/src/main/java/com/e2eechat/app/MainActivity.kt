package com.e2eechat.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.e2eechat.app.crypto.KeyManager
import com.e2eechat.app.data.ChatViewModel
import com.e2eechat.app.data.UserPreferences
import com.e2eechat.app.model.Chat
import com.e2eechat.app.model.Message
import com.e2eechat.app.model.MessageStatus
import com.e2eechat.app.ui.screens.ChatListScreen
import com.e2eechat.app.ui.screens.ChatScreen
import com.e2eechat.app.ui.screens.LoginScreen
import com.e2eechat.app.ui.screens.SettingsScreen
import com.e2eechat.app.ui.theme.DarkBackground
import com.e2eechat.app.ui.theme.E2EEChatTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.firebase.auth.FirebaseAuth

sealed class Screen {
    object Login : Screen()
    object ChatList : Screen()
    object Settings : Screen()
    data class ChatDetail(val chatId: String) : Screen()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val userPreferences = UserPreferences(applicationContext)
        val keyManager = KeyManager(applicationContext)

        setContent {
            E2EEChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    E2EEChatApp(
                        userPreferences = userPreferences,
                        keyManager = keyManager,
                        onToast = { message ->
                            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun E2EEChatApp(
    userPreferences: UserPreferences? = null,
    keyManager: KeyManager? = null,
    onToast: (String) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(userPreferences) {
        userPreferences ?: UserPreferences(context.applicationContext)
    }
    val keys = remember(keyManager) {
        keyManager ?: KeyManager(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf<Screen?>(null) }
    var loggedInUserEmail by remember { mutableStateOf<String?>(null) }
    val chatViewModel: ChatViewModel = viewModel()
    val chatsState by chatViewModel.chats.collectAsStateWithLifecycle(emptyList())

    LaunchedEffect(Unit) {
        val loggedIn = prefs.isLoggedIn.first()
        val email = prefs.userEmail.first()
        if (loggedIn && !email.isNullOrBlank()) {
            keys.ensureKeysForUser(email)
            loggedInUserEmail = email
            currentScreen = Screen.ChatList
        } else {
            currentScreen = Screen.Login
        }
    }

    BackHandler(enabled = currentScreen is Screen.ChatDetail || currentScreen is Screen.Settings) {
        currentScreen = Screen.ChatList
    }

    val activeScreen = currentScreen ?: return

    // Transition animations: subtle smooth slide & fade
    AnimatedContent(
        targetState = activeScreen,
        transitionSpec = {
            if (targetState is Screen.ChatDetail || targetState is Screen.Settings || (initialState is Screen.Login && targetState is Screen.ChatList)) {
                // Forward navigation: Slide in from right + Fade in
                (slideInHorizontally(
                    animationSpec = tween(350),
                    initialOffsetX = { fullWidth -> (fullWidth * 0.25f).toInt() }
                ) + fadeIn(animationSpec = tween(350))).togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(250),
                        targetOffsetX = { fullWidth -> -(fullWidth * 0.25f).toInt() }
                    ) + fadeOut(animationSpec = tween(250))
                )
            } else {
                // Backward navigation: Slide in from left + Fade in
                (slideInHorizontally(
                    animationSpec = tween(350),
                    initialOffsetX = { fullWidth -> -(fullWidth * 0.25f).toInt() }
                ) + fadeIn(animationSpec = tween(350))).togetherWith(
                    slideOutHorizontally(
                        animationSpec = tween(250),
                        targetOffsetX = { fullWidth -> (fullWidth * 0.25f).toInt() }
                    ) + fadeOut(animationSpec = tween(250))
                )
            }
        },
        label = "ScreenTransition"
    ) { targetScreen ->
        when (targetScreen) {
            is Screen.Login -> {
                LoginScreen(
                    onLoginSuccess = {
                        val email = FirebaseAuth.getInstance().currentUser?.email ?: ""
                        coroutineScope.launch {
                            if (email.isNotBlank()) {
                                keys.ensureKeysForUser(email)
                                prefs.saveLoginState(email = email, isLoggedIn = true)
                            }
                        }
                        loggedInUserEmail = email
                        currentScreen = Screen.ChatList
                    }
                )
            }
            is Screen.ChatList -> {
                ChatListScreen(
                    chats = chatsState,
                    onChatSelected = { chatId ->
                        chatViewModel.markChatRead(chatId)
                        currentScreen = Screen.ChatDetail(chatId)
                    },
                    onNewChatClick = {
                        onToast("New Encrypted Chat started")
                    },
                    onSettingsClick = {
                        currentScreen = Screen.Settings
                    }
                )
            }
            is Screen.Settings -> {
                SettingsScreen(
                    onLogout = {
                        coroutineScope.launch {
                            prefs.clearLoginState()
                            loggedInUserEmail = null
                            currentScreen = Screen.Login
                        }
                    }
                )
            }
            is Screen.ChatDetail -> {
                val currentChat = chatsState.firstOrNull { it.id == targetScreen.chatId }
                val messages by chatViewModel.messagesForChat(targetScreen.chatId)
                    .collectAsStateWithLifecycle(emptyList())
                if (currentChat != null) {
                    ChatScreen(
                        chat = currentChat.copy(messages = messages),
                        onBackClick = {
                            currentScreen = Screen.ChatList
                        },
                        onSendMessage = { text ->
                            val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                            chatViewModel.sendMessage(
                                targetScreen.chatId,
                                Message(
                                    id = "msg_${System.currentTimeMillis()}",
                                    senderId = loggedInUserEmail ?: "me",
                                    text = text,
                                    timestamp = currentTime,
                                    isFromMe = true,
                                    status = MessageStatus.READ
                                )
                            )
                        }
                    )
                }
            }
        }
    }
}

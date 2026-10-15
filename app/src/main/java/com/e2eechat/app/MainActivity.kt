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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.e2eechat.app.crypto.KeyManager
import com.e2eechat.app.data.AuthViewModel
import com.e2eechat.app.data.ChatViewModel
import com.e2eechat.app.data.UserPreferences
import com.e2eechat.app.model.Chat
import com.e2eechat.app.model.Message
import com.e2eechat.app.model.MessageStatus
import com.e2eechat.app.ui.screens.ChatListScreen
import com.e2eechat.app.ui.screens.ChatScreen
import com.e2eechat.app.ui.screens.LoginScreen
import com.e2eechat.app.ui.screens.ProfilePictureScreen
import com.e2eechat.app.ui.screens.SettingsScreen
import com.e2eechat.app.ui.screens.UserBrowseScreen
import com.e2eechat.app.ui.screens.UsernameDobScreen
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
    object UserBrowse : Screen()
    object UsernameDob : Screen()
    object ProfilePicture : Screen()
    data class ChatDetail(val chatId: String, val contactName: String = "") : Screen()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val userPreferences = UserPreferences(applicationContext)
        val keyManager = KeyManager(applicationContext)

        setContent {
            E2EEChatTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
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
    val authViewModel: AuthViewModel = viewModel()
    val chatsState by chatViewModel.chats.collectAsStateWithLifecycle(emptyList())
    val snackbarHostState = remember { SnackbarHostState() }
    val sendError by chatViewModel.sendError.collectAsStateWithLifecycle()

    // Show a Snackbar whenever a send error occurs, then auto-clear it
    LaunchedEffect(sendError) {
        val err = sendError
        if (!err.isNullOrBlank()) {
            snackbarHostState.showSnackbar(err)
            chatViewModel.clearSendError()
        }
    }

    LaunchedEffect(Unit) {
        val loggedIn = prefs.isLoggedIn.first()
        val email = prefs.userEmail.first()
        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid?.trim().orEmpty()
        if (loggedIn && !email.isNullOrBlank()) {
            if (uid.isNotBlank()) {
                val keyRes = keys.ensureKeysForUser(userIdentifier = uid, fallbackIdentifier = email)
                try {
                    keys.publishPublicPreKeyBundle(uid, keyRes)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to sync prekey bundle to Firestore on startup for uid: $uid", e)
                }
            } else {
                android.util.Log.e("MainActivity", "Firebase UID is blank on startup, cannot sync prekey bundle")
            }
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

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { _ ->
    // Transition animations: subtle smooth slide & fade
    AnimatedContent(
        targetState = activeScreen,
        transitionSpec = {
            if (targetState is Screen.ChatDetail || targetState is Screen.Settings || targetState is Screen.UsernameDob || targetState is Screen.ProfilePicture || targetState is Screen.UserBrowse || (initialState is Screen.Login && targetState is Screen.ChatList)) {
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
                    authViewModel = authViewModel,
                    onLoginSuccess = {
                        coroutineScope.launch {
                            val user = FirebaseAuth.getInstance().currentUser
                            val email = user?.email?.trim().orEmpty()
                            val uid = user?.uid?.trim().orEmpty()
                            if (email.isBlank() || uid.isBlank()) {
                                android.util.Log.e("MainActivity", "Cannot publish prekeys on login: blank email or UID (email='$email', uid='$uid')")
                                snackbarHostState.showSnackbar("Login failed: missing user credentials. Please try again.")
                                return@launch
                            }
                            try {
                                val keyRes = keys.ensureKeysForUser(userIdentifier = uid, fallbackIdentifier = email)
                                keys.publishPublicPreKeyBundle(uid, keyRes)
                                prefs.saveLoginState(email = email, isLoggedIn = true)
                                loggedInUserEmail = email
                                currentScreen = Screen.ChatList
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Failed to publish prekeys on login for uid: $uid", e)
                                snackbarHostState.showSnackbar("Failed to publish encryption keys: ${e.localizedMessage ?: "Network error"}. Please try again.")
                            }
                        }
                    },
                    onNeedsOnboarding = {
                        currentScreen = Screen.UsernameDob
                    }
                )
            }
            is Screen.UsernameDob -> {
                UsernameDobScreen(
                    onContinue = { username, dob ->
                        authViewModel.saveUsernameAndDob(username, dob) {
                            currentScreen = Screen.ProfilePicture
                        }
                    }
                )
            }
            is Screen.ProfilePicture -> {
                ProfilePictureScreen(
                    onDone = { uri ->
                        authViewModel.uploadProfilePictureAndFinish(uri, context) {
                            coroutineScope.launch {
                                val user = FirebaseAuth.getInstance().currentUser
                                val email = user?.email?.trim().orEmpty()
                                val uid = user?.uid?.trim().orEmpty()
                                if (email.isBlank() || uid.isBlank()) {
                                    android.util.Log.e("MainActivity", "Cannot complete onboarding: authenticated user has blank email or UID (email='$email', uid='$uid')")
                                    snackbarHostState.showSnackbar("Profile setup error: missing user credentials. Please try again.")
                                    return@launch
                                }
                                try {
                                    val keyRes = keys.ensureKeysForUser(userIdentifier = uid, fallbackIdentifier = email)
                                    keys.publishPublicPreKeyBundle(uid, keyRes)
                                    prefs.saveLoginState(email = email, isLoggedIn = true)
                                    loggedInUserEmail = email
                                    currentScreen = Screen.ChatList
                                } catch (e: Exception) {
                                    android.util.Log.e("MainActivity", "Failed to publish Signal prekeys on onboarding finish for uid: $uid", e)
                                    snackbarHostState.showSnackbar("Failed to publish encryption keys: ${e.localizedMessage ?: "Network error"}. Please tap to retry.")
                                }
                            }
                        }
                    }
                )
            }
            is Screen.ChatList -> {
                ChatListScreen(
                    chats = chatsState,
                    onChatSelected = { chatId ->
                        chatViewModel.markChatRead(chatId)
                        val chatName = chatsState.firstOrNull { it.id == chatId }?.contactName ?: ""
                        currentScreen = Screen.ChatDetail(chatId, chatName)
                    },
                    onNewChatClick = {
                        currentScreen = Screen.UserBrowse
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
            is Screen.UserBrowse -> {
                UserBrowseScreen(
                    onBackClick = {
                        currentScreen = Screen.ChatList
                    },
                    onUserSelected = { user ->
                        val myUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ?: ""
                        chatViewModel.createOrGetChatWithUser(user.uid, user.username, myUid) { chatId ->
                            currentScreen = Screen.ChatDetail(chatId, user.username)
                        }
                    }
                )
            }
            is Screen.ChatDetail -> {
                val currentChat = chatsState.firstOrNull { it.id == targetScreen.chatId }
                val messages by chatViewModel.messagesForChat(targetScreen.chatId)
                    .collectAsStateWithLifecycle(emptyList())
                val displayChat = currentChat ?: Chat(
                    id = targetScreen.chatId,
                    contactName = targetScreen.contactName.ifBlank { "Chat" },
                    avatarInitials = targetScreen.contactName.take(2).uppercase().ifBlank { "?" },
                    avatarColorHex = 0xFF6E56CFL,
                    isOnline = false,
                    lastSeenText = "",
                    lastMessage = "",
                    lastMessageTimestamp = "",
                    unreadCount = 0
                )
                ChatScreen(
                    chat = displayChat.copy(messages = messages),
                    onBackClick = {
                        currentScreen = Screen.ChatList
                    },
                    onSendMessage = { text ->
                        chatViewModel.sendTextMessage(targetScreen.chatId, text)
                    },
                    onSendImage = { uri ->
                        val currentTime = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
                        chatViewModel.uploadImageAndSend(
                            targetScreen.chatId,
                            uri,
                            loggedInUserEmail ?: "me",
                            currentTime
                        )
                    }
                )
            }
        }
    }
    } // end Scaffold
}

package com.e2eechat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.e2eechat.app.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

data class UserProfile(
    val uid: String,
    val username: String,
    val profilePictureUrl: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserBrowseScreen(
    onBackClick: () -> Unit,
    onUserSelected: (UserProfile) -> Unit
) {
    var users by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        try {
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid
            val snapshot = FirebaseFirestore.getInstance().collection("users").get().await()
            users = snapshot.documents.mapNotNull { doc ->
                val uid = doc.id
                if (uid == currentUid) return@mapNotNull null
                val username = doc.getString("username") ?: return@mapNotNull null
                val pic = doc.getString("profilePictureUrl")
                UserProfile(uid, username, pic)
            }
        } catch (e: Exception) {
            android.util.Log.e("UserBrowseScreen", "Failed to load users", e)
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New Chat",
                        style = Typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = TeleXTextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TeleXTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TeleXBackground
                )
            )
        },
        containerColor = TeleXBackground
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = TeleXPrimary
                    )
                }
                users.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No other users yet",
                            style = Typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TeleXTextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "When other users sign up, they will appear here.",
                            style = Typography.bodyMedium,
                            color = TeleXTextSecondary
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(users) { user ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onUserSelected(user) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(TeleXPrimaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (user.profilePictureUrl != null) {
                                        AsyncImage(
                                            model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                                .data(user.profilePictureUrl)
                                                .size(96)
                                                .crossfade(150)
                                                .memoryCacheKey("avatar_${user.uid}")
                                                .build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = user.username.take(2).uppercase(),
                                            style = Typography.titleMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp
                                            ),
                                            color = TeleXPrimaryDeep
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.username,
                                        style = Typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = TeleXTextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Available to chat",
                                        style = Typography.bodySmall,
                                        color = TeleXTextSecondary
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                                thickness = 0.5.dp,
                                color = TeleXDivider
                            )
                        }
                    }
                }
            }
        }
    }
}

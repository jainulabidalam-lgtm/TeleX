package com.e2eechat.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.e2eechat.app.ui.components.ClayCard
import com.e2eechat.app.ui.components.ClayRaisedButton
import com.e2eechat.app.ui.theme.*

@Composable
fun ProfilePictureScreen(
    onDone: (imageUri: Uri?) -> Unit
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) selectedUri = uri
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TeleXBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "TeleX",
                style = Typography.displayLarge,
                color = TeleXTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Almost There",
                style = Typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = TeleXTextSecondary
            )

            Spacer(modifier = Modifier.height(28.dp))

            ClayCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Add a Profile Picture",
                    style = Typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TeleXTextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Optional. Helps friends and contacts recognize you in chats.",
                    style = Typography.bodyMedium,
                    color = TeleXTextSecondary
                )

                Spacer(modifier = Modifier.height(28.dp))

                // Tactile Clay Avatar Frame
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .shadow(
                                elevation = 3.dp,
                                shape = CircleShape,
                                spotColor = TeleXClayShadowDark,
                                ambientColor = TeleXClayShadowDark
                            )
                            .clip(CircleShape)
                            .background(TeleXSurfaceInset)
                            .border(
                                width = 1.5.dp,
                                color = TeleXClayHighlight,
                                shape = CircleShape
                            )
                            .clickable { imagePickerLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedUri != null) {
                            AsyncImage(
                                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                                    .data(selectedUri)
                                    .size(280)
                                    .crossfade(200)
                                    .build(),
                                contentDescription = "Profile picture",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CameraAlt,
                                    contentDescription = "Add photo",
                                    modifier = Modifier.size(36.dp),
                                    tint = TeleXPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap to choose",
                                    style = Typography.labelMedium.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    color = TeleXTextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                ClayRaisedButton(
                    text = if (selectedUri != null) "Continue" else "Skip for now",
                    onClick = { onDone(selectedUri) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

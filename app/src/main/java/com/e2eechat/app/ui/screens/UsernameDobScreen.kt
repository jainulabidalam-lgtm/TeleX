package com.e2eechat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.e2eechat.app.ui.components.ClayCard
import com.e2eechat.app.ui.components.ClayInsetTextField
import com.e2eechat.app.ui.components.ClayRaisedButton
import com.e2eechat.app.ui.icons.CalendarIcon
import com.e2eechat.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsernameDobScreen(
    onContinue: (username: String, dob: String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var dobMillis by remember { mutableStateOf<Long?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val dobText = dobMillis?.let {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(it))
    } ?: ""

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
                text = "Profile Setup",
                style = Typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = TeleXTextSecondary
            )

            Spacer(modifier = Modifier.height(28.dp))

            ClayCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Choose Your Handle",
                    style = Typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TeleXTextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Pick a unique username and enter your date of birth to continue.",
                    style = Typography.bodyMedium,
                    color = TeleXTextSecondary
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Username Inset Field
                ClayInsetTextField(
                    value = username,
                    onValueChange = {
                        username = it.filter { c -> c.isLetterOrDigit() || c == '_' }.lowercase()
                        error = null
                    },
                    label = "Username",
                    placeholder = "e.g. marcus_v",
                    isError = error?.contains("username", ignoreCase = true) == true
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Date of Birth Inset Field (Clickable)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDatePicker = true }
                ) {
                    ClayInsetTextField(
                        value = dobText,
                        onValueChange = {},
                        readOnly = true,
                        label = "Date of birth",
                        placeholder = "DD/MM/YYYY",
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(
                                    imageVector = CalendarIcon,
                                    contentDescription = "Pick date",
                                    tint = TeleXTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        isError = error?.contains("date", ignoreCase = true) == true
                    )
                }

                // Error Banner
                if (error != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = TeleXErrorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = error!!,
                            color = TeleXOnErrorContainer,
                            style = Typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                ClayRaisedButton(
                    text = "Continue",
                    onClick = {
                        when {
                            username.length < 3 -> error = "Username must be at least 3 characters"
                            dobMillis == null -> error = "Select your date of birth"
                            else -> onContinue(username, dobText)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dobMillis = datePickerState.selectedDateMillis
                        showDatePicker = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = TeleXPrimaryDark)
                ) {
                    Text("OK", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = TeleXTextSecondary)
                ) {
                    Text("Cancel")
                }
            },
            colors = DatePickerDefaults.colors(
                containerColor = TeleXSurfaceRaised
            )
        ) {
            DatePicker(
                state = datePickerState,
                colors = DatePickerDefaults.colors(
                    containerColor = TeleXSurfaceRaised,
                    titleContentColor = TeleXTextPrimary,
                    headlineContentColor = TeleXTextPrimary,
                    weekdayContentColor = TeleXTextSecondary,
                    subheadContentColor = TeleXTextSecondary,
                    yearContentColor = TeleXTextPrimary,
                    currentYearContentColor = TeleXPrimary,
                    selectedYearContentColor = TeleXTextOnPrimary,
                    selectedYearContainerColor = TeleXPrimary,
                    dayContentColor = TeleXTextPrimary,
                    disabledDayContentColor = TeleXTextMuted,
                    selectedDayContentColor = TeleXTextOnPrimary,
                    selectedDayContainerColor = TeleXPrimary,
                    todayContentColor = TeleXPrimary,
                    todayDateBorderColor = TeleXPrimary
                )
            )
        }
    }
}

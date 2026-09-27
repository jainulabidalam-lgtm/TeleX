package com.e2eechat.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.e2eechat.app.ui.icons.CalendarIcon
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Set up your profile",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Choose a username and enter your date of birth",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = {
                username = it.filter { c -> c.isLetterOrDigit() || c == '_' }.lowercase()
                error = null
            },
            label = { Text("Username") },
            placeholder = { Text("e.g. marcus_v") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = dobText,
            onValueChange = {},
            readOnly = true,
            label = { Text("Date of birth") },
            placeholder = { Text("DD/MM/YYYY") },
            trailingIcon = {
                IconButton(onClick = { showDatePicker = true }) {
                    Icon(CalendarIcon, contentDescription = "Pick date")
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                when {
                    username.length < 3 -> error = "Username must be at least 3 characters"
                    dobMillis == null -> error = "Select your date of birth"
                    else -> onContinue(username, dobText)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dobMillis = datePickerState.selectedDateMillis
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

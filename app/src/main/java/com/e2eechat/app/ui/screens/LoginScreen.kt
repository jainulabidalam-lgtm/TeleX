package com.e2eechat.app.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.e2eechat.app.data.AuthState
import com.e2eechat.app.data.AuthViewModel
import com.e2eechat.app.ui.components.ClayCard
import com.e2eechat.app.ui.components.ClayInsetTextField
import com.e2eechat.app.ui.components.ClayRaisedButton
import com.e2eechat.app.ui.icons.EyeOffIcon
import com.e2eechat.app.ui.icons.EyeOpenIcon
import com.e2eechat.app.ui.icons.GoogleGLogo
import com.e2eechat.app.ui.theme.*

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel = viewModel(),
    onLoginSuccess: () -> Unit,
    onNeedsOnboarding: () -> Unit = {}
) {
    val context = LocalContext.current
    var isSignUpMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val authState by authViewModel.authState

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            authViewModel.handleGoogleSignInResult(result.data)
        }
    }

    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.Success -> onLoginSuccess()
            is AuthState.NeedsOnboarding -> onNeedsOnboarding()
            else -> {}
        }
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
            // App Branding Header
            Text(
                text = "TeleX",
                style = Typography.displayLarge,
                color = TeleXTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Private, by default.",
                style = Typography.bodyMedium,
                color = TeleXTextSecondary
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Raised Claymorphism Form Card
            ClayCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isSignUpMode) "Create Account" else "Welcome Back",
                    style = Typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = TeleXTextPrimary
                )
                Text(
                    text = if (isSignUpMode) "Sign up to begin chatting" else "Log in to your account",
                    style = Typography.bodyMedium,
                    color = TeleXTextSecondary
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Recessed Inset Email Field
                ClayInsetTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = "Email",
                    placeholder = "you@example.com",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Recessed Inset Password Field
                ClayInsetTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    placeholder = "Enter your password",
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) EyeOpenIcon else EyeOffIcon,
                                contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                tint = TeleXTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )

                // Error Banner
                if (authState is AuthState.Error) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = TeleXOutline.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = (authState as AuthState.Error).message,
                            color = TeleXError,
                            style = Typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Raised Forest-Green Login Button (with touch compression feedback)
                ClayRaisedButton(
                    text = if (isSignUpMode) "Sign Up" else "Log In",
                    isLoading = authState is AuthState.Loading,
                    onClick = {
                        if (isSignUpMode) {
                            authViewModel.signUpWithEmail(email, password)
                        } else {
                            authViewModel.loginWithEmail(email, password)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Raised Ivory Google Button with Official Multi-color Logo
                ClayRaisedButton(
                    isSecondary = true,
                    onClick = {
                        val client = authViewModel.getGoogleSignInClient(context)
                        googleLauncher.launch(client.signInIntent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Image(
                        imageVector = GoogleGLogo,
                        contentDescription = "Google Logo",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Continue with Google",
                        style = Typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.2.sp
                        ),
                        color = TeleXTextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Switch Mode Button
            TextButton(
                onClick = { isSignUpMode = !isSignUpMode },
                colors = ButtonDefaults.textButtonColors(contentColor = TeleXPrimaryDark)
            ) {
                Text(
                    text = if (isSignUpMode) "Already have an account? Log In"
                    else "Don't have an account? Sign Up",
                    style = Typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
            }
        }
    }
}

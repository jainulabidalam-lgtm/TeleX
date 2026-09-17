package com.e2eechat.app.data

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()

    var authState = androidx.compose.runtime.mutableStateOf<AuthState>(AuthState.Idle)
        private set

    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(com.e2eechat.app.R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    fun signUpWithEmail(email: String, password: String) {
        authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                auth.createUserWithEmailAndPassword(email, password).await()
                authState.value = AuthState.Success
            } catch (e: Exception) {
                authState.value = AuthState.Error(e.message ?: "Sign up failed")
            }
        }
    }

    fun loginWithEmail(email: String, password: String) {
        authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                auth.signInWithEmailAndPassword(email, password).await()
                authState.value = AuthState.Success
            } catch (e: Exception) {
                authState.value = AuthState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun handleGoogleSignInResult(data: Intent?) {
        authState.value = AuthState.Loading
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        viewModelScope.launch {
            try {
                val account = task.await()
                val idToken = account.idToken ?: throw Exception("Google sign in failed: no ID token")
                val credential = GoogleAuthProvider.getCredential(idToken, null)
                auth.signInWithCredential(credential).await()
                authState.value = AuthState.Success
            } catch (e: Exception) {
                authState.value = AuthState.Error(e.message ?: "Google sign in failed")
            }
        }
    }

    fun signOut() {
        auth.signOut()
        authState.value = AuthState.Idle
    }
}

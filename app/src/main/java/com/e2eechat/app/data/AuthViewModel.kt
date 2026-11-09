package com.e2eechat.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    object Success : AuthState()
    object NeedsOnboarding : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel : ViewModel() {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    private val supabase = createSupabaseClient(
        supabaseUrl = "https://taivxyserowgwioffjob.supabase.co",
        supabaseKey = "sb_publishable_NaKHhP54MD64THYf1EbuVw_t6NTLP7Y"
    ) {
        install(Storage)
    }

    var authState = androidx.compose.runtime.mutableStateOf<AuthState>(AuthState.Idle)
        private set

    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(com.e2eechat.app.R.string.default_web_client_id))
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, gso)
    }

    private suspend fun resolveNextState() {
        val uid = auth.currentUser?.uid ?: return
        try {
            val doc = firestore.collection("users").document(uid).get().await()
            val hasUsername = doc.exists() && !doc.getString("username").isNullOrBlank()
            authState.value = if (hasUsername) AuthState.Success else AuthState.NeedsOnboarding
        } catch (e: Exception) {
            // For existing accounts where Firebase Auth succeeded, network or Firestore lookup
            // failures must not block login or falsely redirect to onboarding.
            android.util.Log.w("AuthViewModel", "Could not check profile completeness for uid=$uid: ${e.message}; defaulting to Success to restore existing account", e)
            authState.value = AuthState.Success
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        authState.value = AuthState.Loading
        viewModelScope.launch {
            try {
                auth.createUserWithEmailAndPassword(email, password).await()
                authState.value = AuthState.NeedsOnboarding
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
                resolveNextState()
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
                resolveNextState()
            } catch (e: Exception) {
                authState.value = AuthState.Error(e.message ?: "Google sign in failed")
            }
        }
    }

    companion object {
        private const val MAX_PROFILE_IMAGE_SIZE = 5 * 1024 * 1024 // 5 MB
        private val USERNAME_REGEX = Regex("^[a-z0-9_]{3,30}$")
    }

    fun saveUsernameAndDob(username: String, dob: String, onDone: () -> Unit) {
        val uid = auth.currentUser?.uid ?: return
        val cleanUsername = username.trim().lowercase()
        if (!cleanUsername.matches(USERNAME_REGEX)) {
            authState.value = AuthState.Error("Username must be 3-30 characters (letters, numbers, underscores)")
            return
        }
        val cleanDob = dob.trim().take(20)

        viewModelScope.launch {
            try {
                val existing = firestore.collection("users")
                    .whereEqualTo("username", cleanUsername)
                    .get().await()
                val takenByOther = existing.documents.any { it.id != uid }
                if (takenByOther) {
                    authState.value = AuthState.Error("Username already taken")
                    return@launch
                }
                val data = hashMapOf(
                    "username" to cleanUsername,
                    "dob" to cleanDob,
                    "email" to (auth.currentUser?.email ?: "")
                )
                firestore.collection("users").document(uid).set(data).await()
                onDone()
            } catch (e: Exception) {
                authState.value = AuthState.Error(e.message ?: "Could not save profile")
            }
        }
    }

    fun uploadProfilePictureAndFinish(imageUri: Uri?, context: Context, onDone: () -> Unit) {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                if (imageUri != null) {
                    val bytes = context.contentResolver.openInputStream(imageUri)?.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val data = ByteArray(8192)
                        var totalRead = 0
                        var nRead: Int
                        while (stream.read(data, 0, data.size).also { nRead = it } != -1) {
                            totalRead += nRead
                            if (totalRead > MAX_PROFILE_IMAGE_SIZE) {
                                throw IllegalArgumentException("Profile picture exceeds 5MB size limit")
                            }
                            buffer.write(data, 0, nRead)
                        }
                        buffer.toByteArray()
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val fileName = "${uid}_${System.currentTimeMillis()}.jpg"
                        val bucket = supabase.storage.from("profile-pictures")
                        bucket.upload(fileName, bytes)
                        val publicUrl = bucket.publicUrl(fileName)
                        firestore.collection("users").document(uid)
                            .update("profilePictureUrl", publicUrl).await()
                    }
                }
                authState.value = AuthState.Success
                onDone()
            } catch (e: Exception) {
                authState.value = AuthState.Success
                onDone()
            }
        }
    }

    fun signOut() {
        auth.signOut()
        authState.value = AuthState.Idle
    }
}

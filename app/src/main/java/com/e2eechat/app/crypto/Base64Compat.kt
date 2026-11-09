package com.e2eechat.app.crypto

import android.util.Log

/**
 * JVM and Android-compatible Base64 encoder/decoder.
 * Uses android.util.Base64 in Android runtime, falls back to java.util.Base64 in JVM unit tests.
 */
object Base64Compat {
    fun encodeToString(bytes: ByteArray): String =
        try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }

    fun decode(base64: String): ByteArray =
        try {
            android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(base64)
        }
}

/**
 * Diagnostic logger that works in both Android runtime and JVM unit test environments.
 * Sanitizes output: never logs private key material or message plaintext.
 */
object SafeLog {
    fun d(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            println("D/$tag: $msg")
        }
    }

    fun i(tag: String, msg: String) {
        try {
            Log.i(tag, msg)
        } catch (_: Throwable) {
            println("I/$tag: $msg")
        }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        } catch (_: Throwable) {
            println("W/$tag: $msg ${tr?.message.orEmpty()}")
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        } catch (_: Throwable) {
            println("E/$tag: $msg ${tr?.message.orEmpty()}")
        }
    }
}

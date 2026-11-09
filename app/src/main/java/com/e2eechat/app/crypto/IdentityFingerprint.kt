package com.e2eechat.app.crypto

import org.signal.libsignal.protocol.IdentityKey

fun identityFingerprint(key: IdentityKey?): String {
    if (key == null) return "none"
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(key.serialize())
    return digest.take(6).joinToString("") { "%02x".format(it) }
}

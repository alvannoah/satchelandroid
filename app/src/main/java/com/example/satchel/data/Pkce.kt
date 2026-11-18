package com.example.satchel.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE for signing in through a browser (docs/ANDROID_APP.md in the backend).
 * The app keeps the verifier; the server only ever sees its challenge until the
 * app swaps the one-time code for a token. Another app that grabs the
 * satchel:// link gets a code it cannot use.
 */
object Pkce {
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 43 characters from A-Z a-z 0-9 - _ (32 random bytes). */
    fun newVerifier(): String = ByteArray(32).also(SecureRandom()::nextBytes).let(encoder::encodeToString)

    /** base64url, without padding, of the SHA-256 of the verifier. */
    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

package com.axiom.kai

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

/**
 * Google Sign-In for Gemini — gates the feature, no server auth.
 * After sign-in, user pastes Gemini API key (free from aistudio.google.com).
 * If sign-in fails (e.g. error 10 = missing OAuth client config),
 * the app falls back to: user pastes API key directly — Gemini free tier works with just the key.
 */
object GoogleAuthManager {
    private fun client(ctx: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(ctx, gso)
    }

    fun isSignedIn(ctx: Context): Boolean = GoogleSignIn.getLastSignedInAccount(ctx) != null

    /** Returns the sign-in intent; caller starts it via startActivityForResult */
    fun signInIntent(ctx: Context): Intent = client(ctx).signInIntent

    /** Handles the result from Google Sign-In.
     *  - Success: marks logged-in + returns account email.
     *  - Error 10 (DEVELOPER_ERROR): falls back to API-key path silently.
     *  - Other errors: passes through the status code.
     */
fun handleResult(ctx: Context, data: Intent?, onResult: (Boolean, String) -> Unit) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val acct = task.getResult(ApiException::class.java)
            if (acct != null) {
                GeminiClient.setLoggedIn(ctx, true)
                onResult(true, acct.email ?: "signed in")
            } else onResult(false, "no account")
        } catch (e: ApiException) {
            val code = e.statusCode
            if (code == 10) {
                GeminiClient.clear(ctx)
                onResult(false, "login_unavailable_fallback_key")
            } else {
                onResult(false, "sign-in failed: $code")
            }
        }
    }

    fun signOut(ctx: Context, onDone: () -> Unit = {}) {
        client(ctx).signOut().addOnCompleteListener {
            GeminiClient.clear(ctx)
            onDone()
        }
    }
}

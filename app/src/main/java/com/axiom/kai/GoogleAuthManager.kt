package com.axiom.kai

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

/**
 * Google Sign-In for Gemini — just gates the feature, no server auth.
 * After sign-in, user pastes Gemini API key (free from aistudio.google.com).
 */
object GoogleAuthManager {
    private fun client(ctx: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(ctx, gso)
    }

    fun isSignedIn(ctx: Context): Boolean = GoogleSignIn.getLastSignedInAccount(ctx) != null

    fun signInIntent(ctx: Context): Intent = client(ctx).signInIntent

    fun handleResult(ctx: Context, data: Intent?, onResult: (Boolean, String) -> Unit) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val acct = task.getResult(ApiException::class.java)
            if (acct != null) {
                GeminiClient.setLoggedIn(ctx, true)
                onResult(true, acct.email ?: "signed in")
            } else onResult(false, "no account")
        } catch (e: ApiException) {
            onResult(false, "sign-in failed: ${e.statusCode}")
        }
    }

    fun signOut(ctx: Context, onDone: () -> Unit = {}) {
        client(ctx).signOut().addOnCompleteListener {
            GeminiClient.clear(ctx)
            onDone()
        }
    }
}

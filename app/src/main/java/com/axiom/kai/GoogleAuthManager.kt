package com.axiom.kai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Google Sign-In for Kai account login and Gemini API access.
 * Supports two modes:
 * 1. Account login: creates/links a local Kai account with the Google email
 * 2. Gemini API: gates the feature with a free API key from aistudio.google.com
 * If sign-in fails (e.g. error 10 = missing OAuth client config),
 * the app falls back to: user pastes API key directly — Gemini free tier works with just the key.
 */
object GoogleAuthManager {
    private fun client(ctx: Context): GoogleSignInClient {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        // Request ID token for Firebase Auth if google-services.json is configured
        try {
            val resId = ctx.resources.getIdentifier("default_web_client_id", "string", ctx.packageName)
            if (resId != 0) {
                val webId = ctx.getString(resId)
                if (webId.isNotBlank() && !webId.contains("placeholder")) builder.requestIdToken(webId)
            }
        } catch (_: Exception) {}
        val gso = builder.build()
        return GoogleSignIn.getClient(ctx, gso)
    }

    fun isSignedIn(ctx: Context): Boolean = GoogleSignIn.getLastSignedInAccount(ctx) != null

    /** Returns the sign-in intent; caller starts it via startActivityForResult */
    fun signInIntent(ctx: Context): Intent = client(ctx).signInIntent

    /**
     * Handles the result from Google Sign-In.
     * - Success: creates/links a local Kai account with the Google email, then calls onResult.
     * - Error 10 (DEVELOPER_ERROR): falls back to API-key path silently.
     * - Other errors: passes through the status code.
     */
    fun handleResult(ctx: Context, data: Intent?, onResult: (Boolean, String) -> Unit) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val acct = task.getResult(ApiException::class.java)
            if (acct != null) {
                val email = acct.email ?: return onResult(false, "no email")
                val displayName = acct.displayName ?: email.substringBefore("@")
                // Create or update local Kai account with this Google email
                linkGoogleAccount(ctx, email, displayName)
                // Firebase Google credential if available
                acct.idToken?.let { token ->
                    if (FirebaseHelper.isConfigured(ctx)) {
                        GlobalScope.launch(Dispatchers.IO) {
                            FirebaseHelper.signInWithGoogle(token)
                        }
                    }
                }
                GeminiClient.setLoggedIn(ctx, true)
                onResult(true, email)
            } else {
                onResult(false, "no account")
            }
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

    /** Links a Google account to a local Kai account (creates if not exists, updates if exists) */
    private fun linkGoogleAccount(ctx: Context, email: String, displayName: String) {
        val e = email.trim().lowercase()
        val users = AccountManager.loadUsers(ctx)
        val existing = users.find { it.email == e }
        if (existing != null) {
            // Already exists — just log in
            ctx.getSharedPreferences("kai_accounts", Context.MODE_PRIVATE)
                .edit().putString("current_email", e).apply()
            AccountManager.currentEmail = e
        } else {
            // Create new Kai account linked to Google
            val saltBytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(saltBytes)
            val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
            // Google accounts use a special "google_auth" hash marker (no password-based encryption)
            val googleHash = "GOOGLE:" + email
            val googleUser = AccountManager.User(displayName.trim(), e, e, salt, googleHash)
            val updated = users.toMutableList()
            updated.add(googleUser)
            AccountManager.saveUsers(ctx, updated)
            ctx.getSharedPreferences("kai_accounts", Context.MODE_PRIVATE)
                .edit().putString("current_email", e).apply()
            AccountManager.currentEmail = e
        }
    }

    fun signOut(ctx: Context, onDone: () -> Unit = {}) {
        client(ctx).signOut().addOnCompleteListener {
            GeminiClient.clear(ctx)
            onDone()
        }
    }
}

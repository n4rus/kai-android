package com.axiom.kai

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

object FirebaseHelper {
    private const val TAG = "FirebaseHelper"

    fun isConfigured(ctx: Context): Boolean {
        return try {
            val app = FirebaseApp.getInstance()
            val opts = app.options
            // placeholder has projectId containing "placeholder"
            val pid = opts.projectId ?: ""
            !pid.contains("placeholder") && opts.apiKey != "PLACEHOLDER_REPLACE_ME"
        } catch (_: Exception) { false }
    }

    suspend fun createAccountAndSendVerification(email: String, password: String): Result<Unit> {
        return try {
            val auth = FirebaseAuth.getInstance()
            val res = auth.createUserWithEmailAndPassword(email, password).await()
            res.user?.sendEmailVerification()?.await()
            Log.i(TAG, "✓ Firebase account created + verification sent to $email")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Firebase create failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> {
        return try {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
            Log.i(TAG, "✓ Firebase sign-in $email")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign-in failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogle(idToken: String): Result<Unit> {
        return try {
            val cred = GoogleAuthProvider.getCredential(idToken, null)
            FirebaseAuth.getInstance().signInWithCredential(cred).await()
            Log.i(TAG, "✓ Firebase Google sign-in")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Google failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun deleteCurrentUser(): Result<Unit> {
        return try {
            FirebaseAuth.getInstance().currentUser?.delete()?.await()
            Log.i(TAG, "✓ Firebase user deleted")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Firebase delete failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            FirebaseAuth.getInstance().sendPasswordResetEmail(email).await()
            Log.i(TAG, "✓ Firebase reset email to $email")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Firebase reset failed: ${e.message}")
            Result.failure(e)
        }
    }
}

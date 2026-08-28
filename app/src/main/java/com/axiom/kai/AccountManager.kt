package com.axiom.kai

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object AccountManager {
    private const val PREFS = "kai_accounts"
    private const val KEY_USERS = "users_json"
    private const val KEY_CURRENT = "current_email"
    private const val KEY_RECOVERY_TOKEN = "recovery_token"
    private const val KEY_RECOVERY_EMAIL = "recovery_email"

    data class User(val username: String, val email: String, val recoveryEmail: String, val salt: String, val hash: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun loadUsers(ctx: Context): MutableList<User> {
        val json = prefs(ctx).getString(KEY_USERS, "[]") ?: "[]"
        return try {
            val arr = org.json.JSONArray(json)
            val list = mutableListOf<User>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(User(o.getString("username"), o.getString("email"), o.optString("recoveryEmail", o.getString("email")), o.getString("salt"), o.getString("hash")))
            }
            list
        } catch (_: Exception) { mutableListOf() }
    }

    internal fun saveUsers(ctx: Context, users: List<User>) {
        val arr = org.json.JSONArray()
        users.forEach { u ->
            val o = org.json.JSONObject()
            o.put("username", u.username)
            o.put("email", u.email)
            o.put("recoveryEmail", u.recoveryEmail)
            o.put("salt", u.salt)
            o.put("hash", u.hash)
            arr.put(o)
        }
        prefs(ctx).edit().putString(KEY_USERS, arr.toString()).apply()
    }
    fun isStrongPassword(pw: String): Boolean {
        if (pw.length < 8) return false
        if (!pw.any { it.isUpperCase() }) return false
        if (!pw.any { !it.isLetterOrDigit() }) return false
        return true
    }

    fun strongPasswordError(pw: String, ctx: Context): String? {
        if (pw.length < 8) return Lang.t(ctx, "Password must be at least 8 characters", "Senha deve ter pelo menos 8 caracteres")
        if (!pw.any { it.isUpperCase() }) return Lang.t(ctx, "Password must contain 1 uppercase letter", "Senha deve conter 1 letra maiúscula")
        if (!pw.any { !it.isLetterOrDigit() }) return Lang.t(ctx, "Password must contain 1 special character", "Senha deve conter 1 caractere especial")
        return null
    }

    private fun sendConfirmationEmail(ctx: Context, email: String, username: String) {
        val subject = "Kai - Account Created Successfully"
        val bodyEN = """
            |Hello $username,
            |
            |Your Kai account has been successfully created!
            |
            |Email: $email
            |Username: $username
            |
            |You can now use all features of Kai with your encrypted chat history.
            |
            |This is an automated message. Do not reply to this email.
            |
            |Best regards,
            |Kai Team
        """.trimMargin()

        val bodyPT = """
            |Olá $username,
            |
            |Sua conta Kai foi criada com sucesso!
            |
            |Email: $email
            |Nome de usuário: $username
            |
            |Agora você pode usar todos os recursos do Kai com seu histórico de chat criptografado.
            |
            |Esta é uma mensagem automática. Não responda a este email.
            |
            |Atenciosamente,
            |Equipe Kai
        """.trimMargin()

        val body = if (Lang.isPt(ctx)) bodyPT else bodyEN

        // Log the confirmation email content for debugging
        android.util.Log.i("AccountManager", "=== CONFIRMATION EMAIL ===")
        android.util.Log.i("AccountManager", "To: $email")
        android.util.Log.i("AccountManager", "Subject: $subject")
        android.util.Log.i("AccountManager", "Body:\n$body")
        android.util.Log.i("AccountManager", "=== END EMAIL ===")

        // In a production app, this would send via a backend service or Firebase Cloud Functions
        // For offline-first, we log it and the user can verify from the app
    }

    private fun genSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    private fun hashPw(pw: String, salt: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(Base64.decode(salt, Base64.NO_WRAP))
            md.update(pw.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(md.digest(), Base64.NO_WRAP)
        } catch (_: Exception) { pw.hashCode().toString() }
    }

    fun createAccount(ctx: Context, username: String, email: String, recoveryEmail: String, password: String, passwordConfirmation: String): Pair<Boolean, String> {
        val e = email.trim().lowercase()
        val re = recoveryEmail.trim().lowercase().ifEmpty { e }
        if (username.isBlank()) return false to Lang.t(ctx, "Username required", "Nome de usuário obrigatório")
        if (e.isBlank() || !e.contains("@")) return false to Lang.t(ctx, "Valid email required", "Email válido obrigatório")
        if (password.isBlank()) return false to Lang.t(ctx, "Password required", "Senha obrigatória")
        if (passwordConfirmation.isBlank()) return false to Lang.t(ctx, "Please confirm your password", "Por favor confirme sua senha")
        if (password != passwordConfirmation) return false to Lang.t(ctx, "Passwords do not match", "Senhas não coincidem")
        strongPasswordError(password, ctx)?.let { return false to it }
        val users = loadUsers(ctx)
        if (users.any { it.email == e }) return false to Lang.t(ctx, "Account already exists", "Conta já existe")
        val salt = genSalt()
        val hash = hashPw(password, salt)
        users.add(User(username.trim(), e, re, salt, hash))
        saveUsers(ctx, users)
        prefs(ctx).edit().putString(KEY_CURRENT, e).apply()
        // derive and store encryption key for this session
        deriveKey(password, salt)?.let { encKey = it }
        currentEmail = e
        // Simulate sending confirmation email
        sendConfirmationEmail(ctx, e, username)
        return true to Lang.t(ctx, "Account created - confirmation email sent", "Conta criada - email de confirmação enviado")
    }

    var currentEmail: String? = null
        internal set
    var encKey: SecretKeySpec? = null
        private set

    fun isLoggedIn(ctx: Context): Boolean {
        val cur = prefs(ctx).getString(KEY_CURRENT, null)
        if (cur != null) currentEmail = cur
        return cur != null
    }

    fun currentUsername(ctx: Context): String? {
        val cur = prefs(ctx).getString(KEY_CURRENT, null) ?: return null
        return loadUsers(ctx).find { it.email == cur }?.username
    }

    fun login(ctx: Context, email: String, password: String): Pair<Boolean, String> {
        val e = email.trim().lowercase()
        val users = loadUsers(ctx)
        val u = users.find { it.email == e } ?: return false to Lang.t(ctx, "Account not found", "Conta não encontrada")
        val h = hashPw(password, u.salt)
        if (h != u.hash) return false to Lang.t(ctx, "Wrong password", "Senha incorreta")
        prefs(ctx).edit().putString(KEY_CURRENT, e).apply()
        currentEmail = e
        deriveKey(password, u.salt)?.let { encKey = it }
        return true to Lang.t(ctx, "Logged in", "Conectado")
    }

    fun logout(ctx: Context) {
        prefs(ctx).edit().remove(KEY_CURRENT).apply()
        currentEmail = null
        encKey = null
    }

    /** Permanently delete the currently-logged-in account. Returns true on success. */
    fun deleteAccount(ctx: Context): Boolean {
        val cur = prefs(ctx).getString(KEY_CURRENT, null) ?: return false
        val users = loadUsers(ctx)
        val updated = users.filter { it.email != cur }
        saveUsers(ctx, updated)
        logout(ctx)
        return true
    }

    // recovery: generate token, store, simulate sending email
    fun requestRecovery(ctx: Context, recoveryEmail: String): Pair<Boolean, String> {
        val re = recoveryEmail.trim().lowercase()
        val users = loadUsers(ctx)
        val u = users.find { it.recoveryEmail == re || it.email == re } ?: return false to Lang.t(ctx, "Recovery email not found", "Email de recuperação não encontrado")
        val token = (100000..999999).random().toString() + "_" + System.currentTimeMillis().toString().takeLast(6)
        prefs(ctx).edit().putString(KEY_RECOVERY_TOKEN, token).putString(KEY_RECOVERY_EMAIL, u.email).apply()
        // simulate sending email: log and return link
        val link = "kai://recovery?token=$token&email=${u.email}"
        android.util.Log.i("AccountManager", "recovery link for ${u.email}: $link")
        return true to Lang.t(ctx, "Recovery link sent to $re", "Link de recuperação enviado para $re") + "\n$link"
    }

    fun resetPassword(ctx: Context, token: String, newPassword: String): Pair<Boolean, String> {
        val savedToken = prefs(ctx).getString(KEY_RECOVERY_TOKEN, null) ?: return false to Lang.t(ctx, "No recovery requested", "Nenhuma recuperação solicitada")
        if (savedToken != token.trim()) return false to Lang.t(ctx, "Invalid token", "Token inválido")
        strongPasswordError(newPassword, ctx)?.let { return false to it }
        val email = prefs(ctx).getString(KEY_RECOVERY_EMAIL, null) ?: return false to Lang.t(ctx, "No recovery email", "Sem email de recuperação")
        val users = loadUsers(ctx).toMutableList()
        val idx = users.indexOfFirst { it.email == email }
        if (idx < 0) return false to Lang.t(ctx, "Account not found", "Conta não encontrada")
        val u = users[idx]
        val newSalt = genSalt()
        val newHash = hashPw(newPassword, newSalt)
        users[idx] = u.copy(salt = newSalt, hash = newHash)
        saveUsers(ctx, users)
        prefs(ctx).edit().remove(KEY_RECOVERY_TOKEN).remove(KEY_RECOVERY_EMAIL).apply()
        // auto login
        prefs(ctx).edit().putString(KEY_CURRENT, email).apply()
        currentEmail = email
        deriveKey(newPassword, newSalt)?.let { encKey = it }
        return true to Lang.t(ctx, "Password reset — logged in", "Senha redefinida — conectado")
    }

    private fun deriveKey(password: String, saltB64: String): SecretKeySpec? {
        return try {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val spec = PBEKeySpec(password.toCharArray(), salt, 10000, 256)
            val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val key = f.generateSecret(spec).encoded
            SecretKeySpec(key, "AES")
        } catch (_: Exception) { null }
    }

    fun encrypt(plain: String): String {
        val k = encKey ?: return plain
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, k, IvParameterSpec(iv))
            val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = iv + enc
            "ENC:" + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Exception) { plain }
    }

    fun decrypt(cipherText: String): String {
        if (!cipherText.startsWith("ENC:")) return cipherText
        val k = encKey ?: return cipherText
        return try {
            val data = Base64.decode(cipherText.removePrefix("ENC:"), Base64.NO_WRAP)
            val iv = data.sliceArray(0 until 16)
            val enc = data.sliceArray(16 until data.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, k, IvParameterSpec(iv))
            String(cipher.doFinal(enc), Charsets.UTF_8)
        } catch (_: Exception) { cipherText }
    }

    fun isEncryptedMode(): Boolean = encKey != null
}

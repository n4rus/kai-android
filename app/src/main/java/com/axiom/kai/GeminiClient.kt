package com.axiom.kai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini client — uses API key stored after Google Sign-In.
 * Free tier: gemini-1.5-flash (default), gemini-1.5-pro.
 * After login, user pastes key from aistudio.google.com (stored securely).
 */
object GeminiClient {
    private const val PREFS = "gemini_prefs"
    private const val KEY_API = "gemini_api_key"
    private const val KEY_LOGGED_IN = "google_logged_in"

    fun isLoggedIn(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOGGED_IN, false)

    fun setLoggedIn(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOGGED_IN, v).apply()
    }

    fun hasApiKey(ctx: Context): Boolean = getApiKey(ctx) != null

    fun getApiKey(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, null)?.takeIf { it.isNotBlank() }

    fun setApiKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API, key.trim()).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Call Gemini with history JSON (same format as Rust). Returns text or error. */
    fun generate(ctx: Context, historyJson: String, model: String = "gemini-1.5-flash"): String {
        val key = getApiKey(ctx) ?: return "⚠ Gemini API key not set. Tap ⚙️ → paste key from aistudio.google.com (free)."
        if (!isLoggedIn(ctx)) return "⚠ Please sign in with Google first (tap ⚙️ → Sign in)."
        return try {
            // Convert history JSON (role/content) to Gemini contents format
            val arr = org.json.JSONArray(historyJson)
            val contents = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val role = o.optString("role")
                val content = o.optString("content")
                if (content.isBlank()) continue
                // Gemini roles: user, model (assistant -> model)
                val gRole = when (role) {
                    "assistant", "model" -> "model"
                    "system" -> "user" // system -> user (Gemini has no system role in this API version, prepend)
                    else -> "user"
                }
                val part = JSONObject().put("text", content)
                val contentObj = JSONObject().put("role", gRole).put("parts", org.json.JSONArray().put(part))
                contents.put(contentObj)
            }
            val body = JSONObject().put("contents", contents)
                .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 1024))
                .toString()

            val urlStr = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "empty"
            if (code !in 200..299) return "⚠ Gemini error $code: ${resp.take(500)}"
            val json = JSONObject(resp)
            val cands = json.optJSONArray("candidates") ?: return "⚠ Gemini: no candidates"
            val first = cands.getJSONObject(0)
            val content = first.optJSONObject("content") ?: return "⚠ Gemini: no content"
            val parts = content.optJSONArray("parts") ?: return "⚠ Gemini: no parts"
            val text = parts.getJSONObject(0).optString("text", "")
            if (text.isBlank()) "⚠ Gemini: empty response" else text
        } catch (e: Exception) {
            "⚠ Gemini failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}

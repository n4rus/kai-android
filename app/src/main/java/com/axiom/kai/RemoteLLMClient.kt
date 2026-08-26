package com.axiom.kai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Remote LLM gateway — DeepSeek, GPT (OpenAI), Qwen (DashScope compat), Claude (Anthropic).
 * OpenAI-compatible providers share one path; Claude uses its own.
 * Keys stored locally, gated in picker (show after key saved).
 */
object RemoteLLMClient {
    private const val PREFS = "remote_llm_prefs"
    private const val KEY_DEEPSEEK = "deepseek_key"
    private const val KEY_OPENAI = "openai_key"
    private const val KEY_QWEN = "qwen_key"
    private const val KEY_CLAUDE = "claude_key"

    fun getKey(ctx: Context, provider: String): String? {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (provider) {
            "deepseek" -> prefs.getString(KEY_DEEPSEEK, null)
            "openai", "gpt" -> prefs.getString(KEY_OPENAI, null)
            "qwen" -> prefs.getString(KEY_QWEN, null)
            "claude" -> prefs.getString(KEY_CLAUDE, null)
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    fun setKey(ctx: Context, provider: String, key: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = when (provider) {
            "deepseek" -> KEY_DEEPSEEK
            "openai", "gpt" -> KEY_OPENAI
            "qwen" -> KEY_QWEN
            "claude" -> KEY_CLAUDE
            else -> return
        }
        prefs.edit().putString(k, key.trim()).apply()
    }

    fun hasKey(ctx: Context, provider: String): Boolean = getKey(ctx, provider) != null

    /** Map app tag → provider + model id + endpoint */
    private fun resolve(tag: String): Triple<String, String, String>? = when (tag) {
        "deepseek:chat" -> Triple("deepseek", "deepseek-chat", "https://api.deepseek.com/v1/chat/completions")
        "deepseek:reasoner" -> Triple("deepseek", "deepseek-reasoner", "https://api.deepseek.com/v1/chat/completions")
        "gpt:4o" -> Triple("openai", "gpt-4o", "https://api.openai.com/v1/chat/completions")
        "gpt:4o-mini" -> Triple("openai", "gpt-4o-mini", "https://api.openai.com/v1/chat/completions")
        "qwen:plus" -> Triple("qwen", "qwen-plus", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
        "qwen:turbo" -> Triple("qwen", "qwen-turbo", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
        "claude:sonnet" -> Triple("claude", "claude-3-5-sonnet-20241022", "https://api.anthropic.com/v1/messages")
        "claude:opus" -> Triple("claude", "claude-3-opus-20240229", "https://api.anthropic.com/v1/messages")
        else -> null
    }

    /** Entry point from ChatViewModel — historyJson + tag → text */
    fun generate(ctx: Context, historyJson: String, tag: String): String {
        val resolved = resolve(tag) ?: return "⚠ Unknown remote model: $tag"
        val (provider, model, endpoint) = resolved
        val key = getKey(ctx, provider) ?: return "⚠ ${provider.uppercase()} API key not set. Tap ✦ → paste key for $provider."
        return if (provider == "claude") generateClaude(historyJson, model, endpoint, key) else generateOpenAI(historyJson, model, endpoint, key)
    }

    private fun generateOpenAI(historyJson: String, model: String, endpoint: String, key: String): String {
        return try {
            val arr = JSONArray(historyJson)
            val messages = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val role = o.optString("role")
                val content = o.optString("content")
                if (content.isBlank()) continue
                val r = when (role) { "assistant", "model" -> "assistant"; "system" -> "system"; else -> "user" }
                messages.put(JSONObject().put("role", r).put("content", content))
            }
            val body = JSONObject().put("model", model).put("messages", messages)
                .put("temperature", 0.7).put("max_tokens", 1024).toString()
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.connectTimeout = 15000; conn.readTimeout = 40000; conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "empty"
            if (code !in 200..299) return "⚠ ${model} error $code: ${resp.take(600)}"
            val json = JSONObject(resp)
            val choices = json.optJSONArray("choices") ?: return "⚠ $model: no choices"
            val first = choices.getJSONObject(0)
            val msg = first.optJSONObject("message") ?: return "⚠ $model: no message"
            msg.optString("content", "").ifBlank { "⚠ $model: empty" }
        } catch (e: Exception) { "⚠ ${model} failed: ${e.javaClass.simpleName}: ${e.message}" }
    }

    private fun generateClaude(historyJson: String, model: String, endpoint: String, key: String): String {
        return try {
            val arr = JSONArray(historyJson)
            var system: String? = null
            val messages = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val role = o.optString("role")
                val content = o.optString("content")
                if (content.isBlank()) continue
                if (role == "system" && system == null) { system = content; continue }
                val r = if (role == "assistant" || role == "model") "assistant" else "user"
                messages.put(JSONObject().put("role", r).put("content", content))
            }
            val body = JSONObject().put("model", model).put("messages", messages)
                .put("max_tokens", 1024).put("temperature", 0.7)
            if (system != null) body.put("system", system)
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.connectTimeout = 15000; conn.readTimeout = 40000; conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "empty"
            if (code !in 200..299) return "⚠ Claude error $code: ${resp.take(600)}"
            val json = JSONObject(resp)
            val content = json.optJSONArray("content") ?: return "⚠ Claude: no content"
            val first = content.getJSONObject(0)
            first.optString("text", "").ifBlank { "⚠ Claude: empty" }
        } catch (e: Exception) { "⚠ Claude failed: ${e.javaClass.simpleName}: ${e.message}" }
    }
}

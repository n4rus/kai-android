package com.axiom.kai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Real streaming: Rust generates GGUF-aware text, Kotlin streams word-by-word with VFE/temp pacing
class StreamingGenerator(private val ctx: android.content.Context) {

    // Stream a history-aware response: Rust renders multi-turn per model family, Kotlin streams words
    suspend fun streamChat(
        historyJson: String,
        slot: Int,
        temp: Float,
        vfe: Float,
        onToken: (String) -> Unit,
        onDone: () -> Unit
    ) {
        val full = try {
            KaiBridge.generateChat(historyJson, temp, vfe, slot)
        } catch (_: Throwable) {
            "[Kai] generation failed (JNI) — check model loaded"
        }

        // Real inference is done — stream out fast so text appears naturally
        val tokens = full.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }
        val baseDelayMs = when {
            vfe > 3.5f -> 12L  // high VFE → slightly slower reveal
            temp > 1.0f -> 10L
            else -> 8L
        }

        for (tok in tokens) {
            onToken(tok)
            delay(baseDelayMs + (Math.random() * 20).toLong())
        }
        onDone()
    }

    // Legacy single-turn (compat: ghost recursion, PC paths)
    suspend fun stream(
        prompt: String,
        temp: Float,
        vfe: Float,
        onToken: (String) -> Unit,
        onDone: () -> Unit
    ) {
        val json = org.json.JSONArray().apply {
            put(org.json.JSONObject().put("role", "user").put("content", prompt))
        }.toString()
        streamChat(json, 0, temp, vfe, onToken, onDone)
    }
}

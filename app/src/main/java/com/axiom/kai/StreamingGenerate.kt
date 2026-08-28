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

        // Real inference is done — batch chunks to avoid recomposition storm on long answers (freeze fix)
        // Borrow GPU power: larger batches + longer delay = fewer recompositions, UI stays responsive while typing
        val tokens = full.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }
        val chunks = tokens.chunked(8).map { it.joinToString("") }
        val baseDelayMs = when {
            vfe > 3.5f -> 32L  // high VFE → slightly slower reveal, but heavily batched
            temp > 1.0f -> 28L
            else -> 24L
        }

        for (chunk in chunks) {
            onToken(chunk)
            delay(baseDelayMs + (Math.random() * 8).toLong())
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

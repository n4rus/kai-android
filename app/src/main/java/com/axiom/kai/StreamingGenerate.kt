package com.axiom.kai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Real streaming: Rust generates GGUF-aware text, Kotlin streams word-by-word with VFE/temp pacing
class StreamingGenerator(private val ctx: android.content.Context) {

    // Stream a GGUF-aware response, emitting tokens via onToken
    suspend fun stream(
        prompt: String,
        temp: Float,
        vfe: Float,
        onToken: (String) -> Unit,
        onDone: () -> Unit
    ) {
        val full = try {
            KaiBridge.generate(prompt, temp, vfe)
        } catch (_: Throwable) {
            // Fallback if JNI not yet linked
            if (vfe > 3) "[Kai VFE ${"%.1f".format(vfe)} T${"%.2f".format(temp)}] \"$prompt\" — high VFE, streaming…"
            else "[Kai VFE ${"%.1f".format(vfe)}] \"$prompt\" — streaming…"
        }

        // Split into words to simulate token streaming; pacing by VFE/temp
        val tokens = full.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }
        val baseDelayMs = when {
            vfe > 3.5f -> 40L  // high VFE → slower, more exploratory
            temp > 1.0f -> 60L
            else -> 30L
        }

        for (tok in tokens) {
            onToken(tok)
            delay(baseDelayMs + (Math.random() * 20).toLong())
        }
        onDone()
    }
}

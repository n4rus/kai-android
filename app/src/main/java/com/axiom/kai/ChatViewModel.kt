package com.axiom.kai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

class ChatViewModel : ViewModel() {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _model = MutableStateFlow("qwen2.5:0.5b")
    val model: StateFlow<String> = _model.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var recursionDepth = 0

    // GGUF download state
    private val _downloadState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val downloadState: StateFlow<Map<String, Boolean>> = _downloadState.asStateFlow()

    fun setModel(m: String) {
        _model.value = m
        // Try to load local GGUF for this model (best effort)
        ModelCatalog.byTag(m)?.let { entry ->
            // check in viewModelScope
        }
    }

    fun refreshDownloadState(ctx: Context) {
        val mgr = ModelManager(ctx)
        _downloadState.value = ModelCatalog.models.associate { it.tag to mgr.isDownloaded(it) }
    }

    fun downloadModel(ctx: Context, tag: String, onEnqueue: (Long) -> Unit = {}) {
        val entry = ModelCatalog.byTag(tag) ?: return
        val mgr = ModelManager(ctx)
        if (mgr.isDownloaded(entry)) {
            // already there — try load
            viewModelScope.launch { mgr.loadInRust(entry) }
            refreshDownloadState(ctx)
            return
        }
        val id = mgr.download(entry)
        onEnqueue(id)
        // optimistic: mark as downloading (false until finished)
        refreshDownloadState(ctx)
    }

    fun tryLoadCurrentModel(ctx: Context): Int {
        val entry = ModelCatalog.byTag(_model.value) ?: return -1
        val r = ModelManager(ctx).loadInRust(entry)
        // refresh info after load
        try {
            val info = KaiBridge.lastGgufInfo()
            // info is "path|size|is_gguf|version" — VFE meter will show it via next message
        } catch (_: Exception) {}
        return r
    }

    fun lastGgufLabel(ctx: Context): String {
        return try {
            val info = KaiBridge.lastGgufInfo() // path|size|is_gguf|version
            val parts = info.split("|")
            if (parts.size >= 4) {
                val name = java.io.File(parts[0]).name
                val mb = parts[1].toLongOrNull()?.let { it/1024/1024 } ?: 0
                val ok = parts[2].toBoolean()
                val ver = parts[3]
                if (ok) "$name ${mb}MB v$ver ✓" else "$name invalid ✗"
            } else info
        } catch (_: Throwable) {
            val mgr = ModelManager(ctx)
            val dl = try { mgr.downloadedModels().joinToString { it.tag } } catch (_: Throwable) { "" }
            if (dl.isNotEmpty()) "downloaded: $dl (tap picker to load)" else "no GGUF — picker ⬇ 0.5b"
        }
    }

    // Billing — v1 free, v2/v3 $4.99 per period
    fun billingHasV2(ctx: Context): Boolean = BillingManager(ctx).hasV2()
    fun billingHasV3(ctx: Context): Boolean = BillingManager(ctx).hasV3()
    fun billingDaysLeft(ctx: Context, v2: Boolean = true): Long {
        val bm = BillingManager(ctx)
        return if (v2) bm.daysLeftV2() else bm.daysLeftV3()
    }
    fun requiresV2ForModel(tag: String): Boolean {
        // 0.5b is v1 free, 7b/8b/9b are v2
        return tag != "qwen2.5:0.5b"
    }

    fun send(userText: String) {
        if (userText.isBlank() || _isGenerating.value) return
        val curModel = _model.value
        _messages.value = _messages.value + ChatMessage(role = Role.USER, text = userText, model = curModel)
        _isGenerating.value = true

        // Estimate stubs — later replace with real tokenizer + worldgraph
        val curvature = estimateCurvature(userText)
        val surprise = estimateSurprise(userText, curvature)
        val kl = estimateKL(userText)
        val vfe = safeVFE(surprise, kl)
        val baseTemp = 0.85f
        val temp = safeTemp(baseTemp, curvature, 0.4f)

        val kaiText = safeGenerate(userText, temp, vfe)
        _messages.value = _messages.value + ChatMessage(role = Role.KAI, text = kaiText, vfe = vfe, curvature = curvature, temp = temp, model = curModel)

        // Recursive self-prompt — Kai talking to Kai'
        if (vfe > 3.0f && recursionDepth < 2) {
            recursionDepth++
            val selfPrompt = "What would reduce VFE about: \"$userText\"? (curvature $curvature)"
            val selfVfe = safeVFE(surprise * 0.7f, kl * 0.8f)
            val selfTemp = safeTemp(temp, curvature * 0.8f, 0.35f)
            val selfText = safeGenerate(selfPrompt, selfTemp, selfVfe)
            _messages.value = _messages.value + ChatMessage(role = Role.KAI_RECURSIVE, text = "↻ $selfText", vfe = selfVfe, curvature = curvature*0.8f, temp = selfTemp, model = curModel)
        } else {
            recursionDepth = 0
        }
        _isGenerating.value = false
    }

    fun promoteRecursive(msg: ChatMessage) {
        // Use ghost as next user prompt
        val stripped = msg.text.removePrefix("↻ ").take(400)
        send(stripped)
    }

    fun clear() { _messages.value = emptyList(); recursionDepth = 0 }

    // ---- stubs that become real kai-fusion later ----

    private fun estimateCurvature(text: String): Float {
        // hash distance vs last 3 messages → 0..1
        val last = _messages.value.takeLast(3).joinToString(" ") { it.text.take(20) }
        if (last.isEmpty()) return 0.45f
        val h = abs(text.hashCode() - last.hashCode()) % 1000 / 1000f
        return (0.2f + h * 0.6f).coerceIn(0f, 1f)
    }
    private fun estimateSurprise(text: String, c: Float): Float {
        // length + curvature
        return (text.length / 120f + c * 1.5f + 0.5f).coerceIn(0.5f, 5f)
    }
    private fun estimateKL(text: String): Float {
        // stub: distance to attractor prior (later 173 vectors)
        return (abs(text.hashCode() % 100) / 100f * 0.8f + 0.2f)
    }
    private fun safeVFE(s: Float, k: Float): Float = try { KaiBridge.calculateVFE(s,k) } catch (_: Throwable) { KaiBridge.calculateVFEFallback(s,k) }
    private fun safeTemp(t: Float, c: Float, a: Float): Float = try { KaiBridge.curvatureToTemp(t,c,a) } catch (_: Throwable) { KaiBridge.curvatureToTempFallback(t,c,a) }
    private fun safeGenerate(p: String, t: Float, v: Float): String = try { KaiBridge.generate(p,t,v) } catch (_: Throwable) {
        // Kotlin fallback template
        if (v > 3) "[Kai VFE ${"%.1f".format(v)} T${"%.2f".format(t)}] \"$p\" — novel. What prior minimizes KL here? (VFE→${"%.1f".format(v*0.8)})"
        else "[Kai VFE ${"%.1f".format(v)}] \"$p\" — in groove. Next: assert_eq!(kai_calculate_vfe(s,kl), s+kl)"
    }
}

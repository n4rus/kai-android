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

    // Download progress state
    private val _downloadProgress = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Int>> = _downloadProgress.asStateFlow()

    fun downloadModel(ctx: Context, tag: String, onEnqueue: (Long) -> Unit = {}) {
        val entry = ModelCatalog.byTag(tag) ?: return
        val mgr = ModelManager(ctx)
        if (mgr.isDownloaded(entry)) {
            viewModelScope.launch { mgr.loadInRust(entry) }
            refreshDownloadState(ctx)
            return
        }
        // Try DownloadManager (external dir, SecurityException-safe), then poll+copy+load
        val id = try { mgr.download(entry) } catch (t: Throwable) {
            android.util.Log.e("ChatViewModel", "download() threw: $t")
            -1L
        }
        if (id > 0) {
            onEnqueue(id)
            // Poll DownloadManager in background; on success copy external→internal and load
            viewModelScope.launch {
                val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                val q = android.app.DownloadManager.Query().setFilterById(id)
                while (true) {
                    dm.query(q)?.use { c ->
                        if (c.moveToFirst()) {
                            val status = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                            if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                mgr.syncExternalToInternal(entry)
                                mgr.loadInRust(entry)
                                refreshDownloadState(ctx)
                                return@launch
                            }
                            if (status == android.app.DownloadManager.STATUS_FAILED) {
                                // Fallback to direct download
                                directFallback(ctx, entry)
                                return@launch
                            }
                        }
                    } ?: run { directFallback(ctx, entry); return@launch }
                    kotlinx.coroutines.delay(2000)
                }
            }
        } else {
            // DownloadManager unavailable/rejected — direct download with progress
            directFallback(ctx, entry)
        }
    }

    private fun directFallback(ctx: Context, entry: ModelEntry) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ModelManager(ctx).downloadDirect(entry) { pct ->
                    _downloadProgress.value = _downloadProgress.value + (entry.tag to pct)
                }
            }
            if (ok) {
                ModelManager(ctx).loadInRust(entry)
                _downloadProgress.value = _downloadProgress.value - entry.tag
            }
            refreshDownloadState(ctx)
        }
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

    // Cache last GGUF label to avoid spamming JNI on every recomposition
    private var cachedLabel: String? = null
    private var cachedLabelTime: Long = 0
    fun lastGgufLabel(ctx: Context): String {
        // Return cached if <2s old
        if (cachedLabel != null && System.currentTimeMillis() - cachedLabelTime < 2000) return cachedLabel!!
        val label = try {
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
        cachedLabel = label
        cachedLabelTime = System.currentTimeMillis()
        return label
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

    fun send(ctx: Context, userText: String) {
        if (userText.isBlank() || _isGenerating.value) return
        val curModel = _model.value
        _messages.value = _messages.value + ChatMessage(role = Role.USER, text = userText, model = curModel)
        _isGenerating.value = true

        val curvature = estimateCurvature(userText)
        val surprise = estimateSurprise(userText, curvature)
        val kl = estimateKL(userText)
        val vfe = safeVFE(surprise, kl)
        val baseTemp = 0.85f
        val temp = safeTemp(baseTemp, curvature, 0.4f)

        // Create empty Kai message and stream into it (real streaming UX)
        val kaiId = java.util.UUID.randomUUID().toString()
        val kaiMsg = ChatMessage(id = kaiId, role = Role.KAI, text = "", vfe = vfe, curvature = curvature, temp = temp, model = curModel)
        _messages.value = _messages.value + kaiMsg

        viewModelScope.launch {
            val streamer = StreamingGenerator(ctx)
            // Stream main answer
            streamer.stream(userText, temp, vfe,
                onToken = { tok ->
                    _messages.value = _messages.value.map { if (it.id == kaiId) it.copy(text = it.text + tok) else it }
                },
                onDone = {
                    // Recursive self-prompt — Kai talking to Kai' (also streamed)
                    if (vfe > 3.0f && recursionDepth < 2) {
                        recursionDepth++
                        val selfPrompt = "What would reduce VFE about: \"$userText\"? (curvature $curvature)"
                        val selfVfe = safeVFE(surprise * 0.7f, kl * 0.8f)
                        val selfTemp = safeTemp(temp, curvature * 0.8f, 0.35f)
                        val ghostId = java.util.UUID.randomUUID().toString()
                        val ghostMsg = ChatMessage(id = ghostId, role = Role.KAI_RECURSIVE, text = "", vfe = selfVfe, curvature = curvature*0.8f, temp = selfTemp, model = curModel)
                        _messages.value = _messages.value + ghostMsg
                        viewModelScope.launch {
                            streamer.stream(selfPrompt, selfTemp, selfVfe,
                                onToken = { t2 ->
                                    _messages.value = _messages.value.map { if (it.id == ghostId) it.copy(text = if (it.text.isEmpty()) "↻ $t2" else it.text + t2) else it }
                                },
                                onDone = { _isGenerating.value = false }
                            )
                        }
                    } else {
                        recursionDepth = 0
                        _isGenerating.value = false
                    }
                }
            )
            // If not recursive, isGenerating will be set false in onDone above; for non-recursive case we need to set it there too
            if (vfe <= 3.0f || recursionDepth == 0) {
                // onDone for non-recursive already sets false, but ensure
            }
        }
    }

    fun promoteRecursive(ctx: Context, msg: ChatMessage) {
        val stripped = msg.text.removePrefix("↻ ").take(400)
        send(ctx, stripped)
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

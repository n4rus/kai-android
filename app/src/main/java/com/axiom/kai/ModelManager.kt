package com.axiom.kai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ModelEntry(
    val tag: String, // e.g. "qwen2.5:0.5b" or "kai-pc:live"
    val url: String, // Hugging Face GGUF URL or https://pc-ip:port for remote
    val fileName: String,
    val sizeMb: Int,
    val description: String,
    val isRemote: Boolean = false // true → not a GGUF, is an encrypted live PC endpoint
)

object ModelCatalog {
    // Q4_K_M GGUFs — phone-sized. ALL FREE in v1 (models are never paywalled).
    // Remote models: kai-pc:live and gemini (after Google login, free).
    val models = listOf(
        ModelEntry(
            "kai-pc:live", "https://pc.kai.local:8443", "kai-pc-live",
            0, "🔒 Kai PC — encrypted live (this PC's opencode, phone is remote control)", isRemote = true
        ),
        ModelEntry(
            "gemini:flash", "https://generativelanguage.googleapis.com", "gemini-flash",
            0, "✦ Gemini Flash — Google, free after login", isRemote = true
        ),
        ModelEntry(
            "gemini:pro", "https://generativelanguage.googleapis.com", "gemini-pro",
            0, "✦ Gemini Pro — Google, free after login", isRemote = true
        ),
        ModelEntry(
            "qwen2.5:0.5b", "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            "qwen2.5-0.5b-q4_k_m.gguf", 400, "0.5B — 800MB RAM, fastest, instant"
        ),
        ModelEntry(
            "qwen2.5-coder:3b", "https://huggingface.co/bartowski/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-3B-Instruct-Q4_K_M.gguf",
            "qwen2.5-coder-3b-q4_k_m.gguf", 1900, "Coder 3B — best for code tasks"
        ),
        ModelEntry(
            "gemma2:2b", "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            "gemma2-2b-q4_k_m.gguf", 1600, "Gemma 2 — 2B, balanced quality"
        ),
        ModelEntry(
            "llama3.2:3b", "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "llama3.2-3b-q4_k_m.gguf", 1900, "Llama 3.2 — 3B, strong general"
        ),
        ModelEntry(
            "qwen2.5:7b", "https://huggingface.co/bartowski/Qwen2_5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            "qwen2.5-7b-q4_k_m.gguf", 4500, "7B — 5GB RAM, flagship"
        ),
        ModelEntry(
            "llama3:8b", "https://huggingface.co/bartowski/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct-Q4_K_M.gguf",
            "llama3-8b-q4_k_m.gguf", 4700, "Llama 3 — 8B dense"
        ),
        ModelEntry(
            "gemma2:9b", "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
            "gemma2-9b-q4_k_m.gguf", 5500, "Gemma2 — 9B"
        )
    )
    fun byTag(tag: String) = models.find { it.tag == tag }
}

class ModelManager(private val ctx: Context) {
    private val modelsDir: File get() = File(ctx.filesDir, "models").also { it.mkdirs() }
    private val extDir: File get() = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir, "models").also { it.mkdirs() }

    fun localFile(entry: ModelEntry): File {
        val internal = File(modelsDir, entry.fileName)
        if (internal.exists()) return internal
        val external = File(extDir, entry.fileName)
        if (external.exists()) return external
        // DownloadManager duplicate-rename fallback: "name-1.gguf", "name-2.gguf"…
        val parent = external.parentFile
        if (parent != null) {
            val base = entry.fileName.removeSuffix(".gguf")
            val dup = parent.listFiles { f -> f.name.startsWith("$base-") && f.name.endsWith(".gguf") }
                ?.maxByOrNull { it.length() }
            if (dup != null && dup.length() > 1024*1024) return dup
        }
        return internal // default target for new downloads
    }

    /** Copy external GGUF (incl. -1 duplicates) into filesDir/models so Rust mmap has a stable path */
    fun syncExternalToInternal(entry: ModelEntry): Boolean {
        val src = localFile(entry) // may resolve to external or duplicate
        val internal = File(modelsDir, entry.fileName)
        if (!src.exists() || src.length() < 1024*1024) return false
        if (src.absolutePath == internal.absolutePath) return true
        if (internal.exists() && internal.length() == src.length()) return true
        return try {
            src.copyTo(internal, overwrite = true)
            true
        } catch (_: Exception) { false }
    }

    /** Launch-time auto-recovery: if any GGUF for this entry exists anywhere, sync + load. Returns load result or -1 */
    fun autoRecoverAndLoad(entry: ModelEntry): Int {
        return if (isDownloaded(entry)) {
            syncExternalToInternal(entry)
            loadInRust(entry)
        } else -1
    }

    fun isDownloaded(entry: ModelEntry): Boolean = localFile(entry).exists() && localFile(entry).length() > 1024*1024

    fun downloadedModels(): List<ModelEntry> = ModelCatalog.models.filter { isDownloaded(it) }

    /**
     * Download via DownloadManager to EXTERNAL app dir (SecurityException-safe on One UI/16KB),
     * then copy into filesDir/models for Rust mmap. Returns download id or -1 if fell back to direct.
     */
    fun download(entry: ModelEntry): Long {
        val dest = File(extDir, entry.fileName)
        if (dest.exists() && dest.length() < 1024*1024) dest.delete()
        dest.parentFile?.mkdirs()
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(entry.url)).apply {
            setTitle("Downloading ${entry.tag}")
            setDescription(entry.description + " — ${entry.sizeMb}MB, stay on WiFi")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // EXTERNAL app-specific dir is always allowed (no SecurityException)
            setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "models/${entry.fileName}")
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setRequiresCharging(false)
        }
        return try {
            dm.enqueue(req)
        } catch (e: Exception) {
            android.util.Log.e("ModelManager", "DownloadManager failed for ${entry.tag}: $e — direct download fallback")
            -1
        }
    }

    /** Direct download — RESUMABLE via .part + HTTP Range; survives app quit */
    fun downloadDirect(entry: ModelEntry, onProgress: (Int) -> Unit = {}): Boolean {
        val dest = File(modelsDir, entry.fileName)
        if (dest.exists() && dest.length() > 1024*1024) return true
        dest.parentFile?.mkdirs()
        val tmp = File(modelsDir, entry.fileName + ".part")
        var attempts = 0
        while (attempts < 5) {
            attempts++
            try {
                val already = if (tmp.exists()) tmp.length() else 0L
                val conn = URL(entry.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                if (already > 0) conn.setRequestProperty("Range", "bytes=$already-")
                conn.connect()
                // If server ignores Range and restarts from 0, restart file too
                val resume = conn.responseCode == 206
                val total = conn.contentLengthLong.takeIf { it > 0 }
                    ?.let { if (resume) it + already else it }
                    ?: (entry.sizeMb * 1024L * 1024L)
                var done = if (resume) already else 0L
                if (!resume) { tmp.delete(); tmp.createNewFile() }
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(tmp, true).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var read: Int
                        var lastPct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        onProgress(lastPct)
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
                if (tmp.length() > 1024*1024) {
                    tmp.renameTo(dest)
                    return true
                }
                tmp.delete()
                return false
            } catch (e: java.io.IOException) {
                // Network drop — keep .part, retry with Range resume
                android.util.Log.w("ModelManager", "Direct dl attempt $attempts failed (will resume): ${e.message}")
                try { Thread.sleep(2000L * attempts) } catch (_: InterruptedException) {}
            } catch (e: Exception) {
                android.util.Log.e("ModelManager", "Direct download failed for ${entry.tag}: $e")
                break
            }
        }
        return false
    }

    /** Bytes downloaded so far (for UI progress restore after app quit) */
    fun partialBytes(entry: ModelEntry): Long {
        val part = File(modelsDir, entry.fileName + ".part")
        if (part.exists()) return part.length()
        val ext = File(extDir, entry.fileName)
        if (ext.exists() && ext.length() < 1024*1024) return ext.length() // partial from DownloadManager
        return 0L
    }

    /** True if a download is incomplete (has .part or small partial) */
    fun isIncomplete(entry: ModelEntry): Boolean {
        if (isDownloaded(entry)) return false
        return partialBytes(entry) > 0L
    }

    fun loadInRust(entry: ModelEntry, slot: Int = 0): Int {
        val f = localFile(entry)
        if (!f.exists()) return -1
        return try { KaiBridge.loadGgufSlot(slot, f.absolutePath) } catch (_: Throwable) { -1 }
    }

    /** True if the given entry's file is already loaded into the given slot */
    fun isLoadedInSlot(entry: ModelEntry, slot: Int): Boolean {
        return try {
            val info = KaiBridge.slotInfo(slot) ?: return false
            val loadedPath = info.split("|").firstOrNull() ?: return false
            loadedPath == localFile(entry).absolutePath
        } catch (_: Throwable) { false }
    }
}

/**
 * AUTO ROUTER (Block E — soul-fusion). Picks the right voice per task:
 * code → coder model, deep reasoning → biggest downloaded general model, else fast.
 * Returns (tag, slot) or null if only kai-pc/fast available (caller keeps current).
 */
object ModelRouter {
    private val CODE_MARKERS = listOf("def ", "fun ", "class ", "import ", "#include", "function ",
        "error", "exception", "stack trace", "compile", "debug", "bug", "syntax", "refactor",
        "python", "kotlin", "rust", "javascript", "sql", "code", "script", "regex")
    private val DEEP_MARKERS = listOf("explain", "why ", "how does", "design", "architecture",
        "compare", "analyze", "physics", "math", "proof", "theory", "principle", "trade-off",
        "strategy", "plan ", "step by step")
    private val DEEP_PREF = listOf("gemma2:2b", "llama3.2:3b", "qwen2.5:7b", "llama3:8b", "gemma2:9b")
    private val CODE_PREF = listOf("qwen2.5-coder:3b")
    const val FAST_TAG = "qwen2.5:0.5b"

    fun route(userText: String, mgr: ModelManager): Pair<String, Int>? {
        val lower = userText.lowercase()
        val isCode = CODE_MARKERS.any { lower.contains(it) }
        val isDeep = userText.length > 220 || DEEP_MARKERS.any { lower.contains(it) }
        if (isCode) {
            CODE_PREF.firstOrNull { tag ->
                val e = ModelCatalog.byTag(tag)
                e != null && mgr.isDownloaded(e)
            }?.let { return it to 1 }
        }
        if (isDeep) {
            DEEP_PREF.firstOrNull { tag ->
                val e = ModelCatalog.byTag(tag)
                e != null && mgr.isDownloaded(e)
            }?.let { return it to 1 }
        }
        // Fast path: always slot 0
        val fast = ModelCatalog.byTag(FAST_TAG)
        if (fast != null && mgr.isDownloaded(fast)) return FAST_TAG to 0
        return null
    }
}

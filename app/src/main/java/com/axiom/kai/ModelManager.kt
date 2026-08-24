package com.axiom.kai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ModelEntry(
    val tag: String, // e.g. "qwen2.5:0.5b"
    val url: String, // Hugging Face GGUF direct URL
    val fileName: String,
    val sizeMb: Int,
    val description: String
)

object ModelCatalog {
    // Q4_K_M GGUFs — small enough for phone, all via Hugging Face (bartowski etc.)
    val models = listOf(
        ModelEntry(
            "qwen2.5:0.5b", "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
            "qwen2.5-0.5b-q4_k_m.gguf", 400, "0.5B — 800MB RAM, fastest, Galaxy A36 OK"
        ),
        ModelEntry(
            "qwen2.5:7b", "https://huggingface.co/bartowski/Qwen2_5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            "qwen2.5-7b-q4_k_m.gguf", 4500, "7B — 5GB RAM, flagship"
        ),
        ModelEntry(
            "llama3:8b", "https://huggingface.co/bartowski/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct-Q4_K_M.gguf",
            "llama3-8b-q4_k_m.gguf", 4700, "Llama 3 — dense baseline"
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

    /** Direct download (coroutine-friendly) — writes straight to filesDir/models for Rust mmap */
    fun downloadDirect(entry: ModelEntry, onProgress: (Int) -> Unit = {}): Boolean {
        val dest = File(modelsDir, entry.fileName)
        if (dest.exists() && dest.length() > 1024*1024) return true
        dest.parentFile?.mkdirs()
        val tmp = File(modelsDir, entry.fileName + ".part")
        return try {
            val conn = URL(entry.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.connect()
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (entry.sizeMb * 1024L * 1024L)
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    var done = 0L
                    var lastPct = -1
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
                true
            } else { tmp.delete(); false }
        } catch (e: Exception) {
            android.util.Log.e("ModelManager", "Direct download failed for ${entry.tag}: $e")
            tmp.delete()
            false
        }
    }

    fun loadInRust(entry: ModelEntry): Int {
        val f = localFile(entry)
        if (!f.exists()) return -1
        return try { KaiBridge.loadGguf(f.absolutePath) } catch (_: Throwable) { -1 }
    }
}

package com.axiom.kai

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

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
            "qwen2.5-0.5b-q4_k_m.gguf", 400, "0.5B — 800MB RAM, fastest, POCO X3 OK"
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

    fun localFile(entry: ModelEntry): File = File(modelsDir, entry.fileName)
    fun isDownloaded(entry: ModelEntry): Boolean = localFile(entry).exists() && localFile(entry).length() > 1024*1024

    fun downloadedModels(): List<ModelEntry> = ModelCatalog.models.filter { isDownloaded(it) }

    fun download(entry: ModelEntry): Long {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(entry.url)
        val req = DownloadManager.Request(uri).apply {
            setTitle("Downloading ${entry.tag}")
            setDescription(entry.description)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(localFile(entry)))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        return dm.enqueue(req)
    }

    fun loadInRust(entry: ModelEntry): Int {
        val f = localFile(entry)
        if (!f.exists()) return -1
        return try { KaiBridge.loadGguf(f.absolutePath) } catch (_: Exception) { -1 }
    }
}

package com.axiom.kai

import android.content.Context
import java.io.File

/**
 * Kai tools — web browsing, file exploring, dev terminal (coding + shell).
 * All local-first: shell runs in app-private dir; file access via app dirs + SAF picks; web via HTTP GET.
 */
object Tools {

    // ==================== WEB BROWSING ====================

    /** Fetch a URL → plain text (strips HTML tags roughly). Returns content or error. */
    fun browse(url: String, maxLength: Int = 8000): String {
        return try {
            val full = if (url.startsWith("http")) url else "https://$url"
            val conn = java.net.URL(full).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Kai-Android/0.1")
            conn.connect()
            val contentType = conn.contentType ?: ""
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val text = if (contentType.contains("html")) {
                // strip scripts/styles/tags, collapse whitespace
                body.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("&nbsp;"), " ")
                    .replace(Regex("&amp;"), "&")
                    .replace(Regex("&lt;"), "<")
                    .replace(Regex("&gt;"), ">")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            } else body
            if (text.length > maxLength) text.take(maxLength) + "…[truncated]" else text
        } catch (e: Exception) {
            "⚠ browse failed: ${e.message}"
        }
    }

    // ==================== FILE EXPLORER ====================

    /** List app-visible files: models dir + external files dir + (SAF picks handled in UI) */
    fun listFiles(ctx: Context, sub: String? = null): List<File> {
        val roots = ArrayList<File>()
        val m = File(ctx.filesDir, "models")
        if (m.exists()) roots.add(m)
        val ext = ctx.getExternalFilesDir(null)
        if (ext != null && ext.exists()) roots.add(ext)
        if (ctx.filesDir.exists()) roots.add(ctx.filesDir)
        return roots.flatMap { root ->
            val dir = if (sub != null) File(root, sub) else root
            dir.listFiles()?.toList() ?: emptyList()
        }.distinctBy { it.absolutePath }
    }

    /** Read a text file (app-private or SAF-picked path) up to maxLen */
    fun readFile(path: String, maxLen: Int = 6000): String {
        return try {
            val f = File(path)
            if (!f.exists()) return "⚠ file not found: $path"
            val text = f.readText(Charsets.UTF_8)
            if (text.length > maxLen) text.take(maxLen) + "…[truncated]" else text
        } catch (e: Exception) {
            "⚠ read failed: ${e.message}"
        }
    }

    /** Write a text file into app files dir */
    fun writeFile(ctx: Context, name: String, content: String): String {
        return try {
            val f = File(ctx.filesDir, name)
            f.parentFile?.mkdirs()
            f.writeText(content)
            "✓ wrote ${f.absolutePath} (${content.length} chars)"
        } catch (e: Exception) {
            "⚠ write failed: ${e.message}"
        }
    }

    // ==================== DEV TERMINAL ====================

    /**
     * Run a shell command in the app-private working dir (sandboxed by OS user).
     * Supports: ls, cat, echo, pwd, mkdir, touch, rm, cp, mv, grep, wc, curl, ping,
     * and any binary in the app's native lib dir. NOT root — app sandbox only.
     */
    fun shell(ctx: Context, command: String, timeoutMs: Long = 15000): String {
        return try {
            val workDir = File(ctx.filesDir, "workspace").also { it.mkdirs() }
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val out = StringBuilder()
            val reader = proc.inputStream.bufferedReader()
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = CharArray(4096)
            while (true) {
                val ready = reader.ready()
                if (ready) {
                    val n = reader.read(buf)
                    if (n > 0) out.append(buf, 0, n)
                } else if (!proc.isAlive) break
                else if (System.currentTimeMillis() > deadline) {
                    proc.destroy()
                    out.append("\n[timeout after ${timeoutMs}ms]")
                    break
                } else Thread.sleep(30)
                if (out.length > 20000) { out.append("\n[output truncated]"); proc.destroy(); break }
            }
            val exit = proc.waitFor()
            "exit=$exit\n${out.toString().trim()}"
        } catch (e: Exception) {
            "⚠ shell failed: ${e.message}"
        }
    }

    /** Detect user intent → tool call. Returns tool result or null if not a tool request */
    fun tryTool(ctx: Context, text: String): String? {
        val t = text.trim()
        val lower = t.lowercase()
        return when {
            lower.startsWith("/shell ") || lower.startsWith("> ") ->
                shell(ctx, t.substringAfter(' ').trim())
            lower.startsWith("/browse ") || lower.startsWith("browse ") || lower.startsWith("open ") && lower.contains(".") ->
                browse(t.substringAfter(' ').trim())
            lower.startsWith("/ls") ->
                listFiles(ctx).joinToString("\n") { "${if (it.isDirectory) "📁" else "📄"} ${it.name} (${it.length()}b)" }
            lower.startsWith("/cat ") ->
                readFile(t.substringAfter(' ').trim())
            lower.startsWith("/write ") && t.contains(": ") -> {
                val rest = t.substringAfter(' ')
                val name = rest.substringBefore(": ").trim()
                val content = rest.substringAfter(": ")
                writeFile(ctx, name, content)
            }
            else -> null
        }
    }

    /** System prompt section describing tools (injected so Kai knows its abilities) */
    fun toolsPrompt(ctx: Context): String {
        val ws = File(ctx.filesDir, "workspace")
        return "[Tools] You can: browse web pages (user says 'browse example.com'), list files (/ls), " +
            "read files (/cat path), write files (/write name.txt: content), and run shell commands " +
            "in a sandbox (user prefix '> cmd' or '/shell cmd'). Workspace: ${ws.absolutePath}. " +
            "For coding questions prefer the qwen2.5-coder model. [/Tools]\n\n"
    }
}

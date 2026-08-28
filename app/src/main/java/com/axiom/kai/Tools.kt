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
            "⚠ browse failed: ${e.javaClass.simpleName}: ${e.message}"
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

    // ==================== PDF INGESTION (desktop ingest.rs parity) ====================

    /** Extract text from a PDF via pdfbox-android. Reports page count; honest about scanned PDFs. */
    fun pdfExtract(path: String, maxLen: Int = 8000): String {
        return try {
            val f = File(path)
            if (!f.exists()) return "⚠ file not found: $path"
            val doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(f)
            doc.use { d ->
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                val pages = d.numberOfPages
                stripper.startPage = 1
                stripper.endPage = pages.coerceAtMost(20) // first 20 pages
                val text = stripper.getText(d).trim()
                if (text.isBlank()) {
                    "📄 $path — $pages page(s), but NO text layer (likely scanned images). Kai-PC vision or OCR needed."
                } else {
                    val clipped = if (text.length > maxLen) text.take(maxLen) + "…[truncated]" else text
                    "📄 $path — $pages page(s):\n$clipped"
                }
            }
        } catch (e: Throwable) {
            "⚠ pdf extract failed: ${e.message}"
        }
    }

    /** Read a text file (app-private or SAF-picked path) up to maxLen — auto-routes .pdf */
    fun readFile(path: String, maxLen: Int = 6000): String {
        return try {
            val f = File(path)
            if (!f.exists()) return "⚠ file not found: $path"
            if (f.extension.lowercase() == "pdf") return pdfExtract(path, maxLen + 2000)
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

    // ==================== WEB SEARCH (desktop browser.rs parity) ====================

    /** Search the web via DuckDuckGo → top results as title+URL list */
    fun search(query: String, maxResults: Int = 8): String {
        val endpoints = listOf(
            "https://lite.duckduckgo.com/lite/?q=",
            "https://html.duckduckgo.com/html/?q="
        )
        var lastErr = ""
        for (base in endpoints) {
            try {
                val url = base + java.net.URLEncoder.encode(query, "UTF-8")
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 10000; conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:122.0) Gecko/20100101 Firefox/122.0")
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                val code = conn.responseCode
                if (code != 200) { lastErr = "HTTP $code"; continue }
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                // html endpoint: result__a links; lite endpoint: plain <a rel="nofollow" href=...>
                val results = (Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(body).toList() +
                    Regex("<a[^>]*class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(body).toList())
                    .distinctBy { it.groupValues[1] }.take(maxResults).map { m ->
                        val link = m.groupValues[1].replace("&amp;", "&")
                            .let { Regex("uddg=([^&]+)").find(it)?.groupValues?.get(1)
                                ?.let { q -> java.net.URLDecoder.decode(q, "UTF-8") } ?: it }
                        val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                        "• $title\n  $link"
                    }
                if (results.isNotEmpty()) return "🔎 Results for \"$query\":\n" + results.joinToString("\n")
                lastErr = "no parseable results"
            } catch (e: Throwable) { lastErr = "${e.javaClass.simpleName}: ${e.message}" }
        }
        return "⚠ search failed ($lastErr) — try 'browse <url>' directly"
    }

    // ==================== IMAGE INSPECTION (local metadata; full vision on Kai-PC) ====================

    /** Local image facts: dimensions, format, EXIF. Full semantic description runs on Kai-PC's SigLIP tower. */
    fun imageInfo(ctx: Context, pathOrUri: String): String {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (pathOrUri.startsWith("content://")) {
                ctx.contentResolver.openInputStream(android.net.Uri.parse(pathOrUri))?.use {
                    android.graphics.BitmapFactory.decodeStream(it, null, opts)
                }
            } else {
                android.graphics.BitmapFactory.decodeFile(pathOrUri, opts)
            }
            val f = File(pathOrUri)
            val sizeB = if (f.exists()) f.length() else -1L
            val exifInfo = if (!pathOrUri.startsWith("content://") && f.exists()) {
                try {
                    val ex = android.media.ExifInterface(pathOrUri)
                    listOf(
                        ex.getAttribute(android.media.ExifInterface.TAG_DATETIME)?.let { "taken=$it" },
                        ex.getAttribute(android.media.ExifInterface.TAG_MAKE)?.let { "cam=${it} ${ex.getAttribute(android.media.ExifInterface.TAG_MODEL) ?: ""}" },
                        ex.getAttribute(android.media.ExifInterface.TAG_GPS_LATITUDE)?.let { "gps=yes" },
                    ).filterNotNull().joinToString(", ")
                } catch (_: Throwable) { "" }
            } else ""
            buildString {
                append("🖼️ Image: ${opts.outWidth}×${opts.outHeight} ${opts.outMimeType ?: "?"}")
                if (sizeB > 0) append(", ${sizeB / 1024}KB")
                if (exifInfo.isNotEmpty()) append("\n$exifInfo")
                append("\n(local facts only — ask with kai-pc:live selected to run PC vision/SigLIP description)")
            }
        } catch (e: Exception) {
            "⚠ image inspect failed: ${e.message}"
        }
    }

    // ==================== COMPANION DEVICE ACTIONS (act for the user, with OS consent prompts) ====================

    /** Installed launchable apps — optionally filtered */
    fun appsList(ctx: Context, query: String?): String {
        val pm = ctx.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val acts = pm.queryIntentActivities(intent, 0)
        val q = query?.lowercase()
        val apps = acts.map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .filter { q == null || it.first.lowercase().contains(q) || it.second.contains(q) }
            .sortedBy { it.first }
        return if (apps.isEmpty()) "⚠ no apps match '$query'"
        else apps.take(25).joinToString("\n") { "• ${it.first} (${it.second})" } +
            if (apps.size > 25) "\n…+${apps.size - 25} more" else ""
    }

    /** Launch an app by name — returns what happened; Android may show a disambiguator */
    fun openApp(ctx: Context, name: String): String {
        return try {
            val pm = ctx.packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val match = pm.queryIntentActivities(intent, 0).firstOrNull {
                it.loadLabel(pm).toString().lowercase() == name.lowercase() ||
                    it.loadLabel(pm).toString().lowercase().contains(name.lowercase())
            } ?: return "⚠ no installed app matches \"$name\" — try /apps to list"
            val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
                ?: return "⚠ app is not launchable"
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(launch)
            "✓ opened ${match.loadLabel(pm)}"
        } catch (e: Exception) {
            "⚠ open failed: ${e.message}"
        }
    }

    /** Open URL in user's browser */
    fun openUrl(ctx: Context, url: String): String {
        return try {
            val full = if (url.startsWith("http")) url else "https://$url"
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(full))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            "✓ opened $full in browser"
        } catch (e: Exception) { "⚠ open failed: ${e.message}" }
    }

    /** Set alarm via system Clock app (user confirms) */
    fun setAlarm(ctx: Context, hour: Int, minute: Int, label: String): String {
        return try {
            val i = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                if (label.isNotEmpty()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, label)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(i); "✓ alarm request: $hour:${"%02d".format(minute)} $label (confirm in Clock)"
        } catch (e: Exception) { "⚠ alarm failed: ${e.message}" }
    }

    /** Create calendar event via system Calendar insert form (user confirms save) */
    fun calendarEvent(ctx: Context, title: String, startMillis: Long): String {
        return try {
            val i = android.content.Intent(android.content.Intent.ACTION_INSERT)
                .setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
                .putExtra(android.provider.CalendarContract.Events.TITLE, title)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i); "✓ event form: \"$title\" (save in Calendar)"
        } catch (e: Exception) { "⚠ calendar failed: ${e.message}" }
    }

    /** Battery + device status report */
    fun deviceStatus(ctx: Context): String {
        val bm = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = bm?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bm?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = bm?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val pct = if (level >= 0) level * 100 / scale else -1
        val charging = when (plugged) {
            android.os.BatteryManager.BATTERY_PLUGGED_USB -> "charging (USB)"
            android.os.BatteryManager.BATTERY_PLUGGED_AC -> "charging (AC)"
            else -> "on battery"
        }
        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
        val freeGb = stat.availableBytes / (1024.0 * 1024 * 1024)
        return "🔋 $pct% — $charging | 💾 ${"%.1f".format(freeGb)}GB free | 📱 ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}"
    }

    /** Append a quick note to workspace notes.md */
    fun note(ctx: Context, text: String): String {
        return try {
            val f = File(ctx.filesDir, "workspace/notes.md").also { it.parentFile?.mkdirs() }
            f.appendText("\n- [${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}] $text")
            "✓ noted → ${f.absolutePath}"
        } catch (e: Exception) { "⚠ note failed: ${e.message}" }
    }

    /** Resolve a user-supplied path against app dirs (private files/, workspace/, models/, external files/) */
    fun resolvePath(ctx: Context, p: String): String {
        if (File(p).exists()) return p
        val nameInExternal = p.substringAfter("files/", p)
        val candidates = listOf(
            File(ctx.filesDir, p),
            File(File(ctx.filesDir, "workspace"), p),
            File(ctx.getExternalFilesDir(null), p),
            File(ctx.getExternalFilesDir(null), nameInExternal),
            File(File(ctx.filesDir, "models"), p),
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath ?: p
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
                readFile(resolvePath(ctx, t.substringAfter(' ').trim()))
            lower.startsWith("/write ") && t.contains(": ") -> {
                val rest = t.substringAfter(' ')
                val name = rest.substringBefore(": ").trim()
                val content = rest.substringAfter(": ")
                writeFile(ctx, name, content)
            }
            // PDF ingestion
            lower.startsWith("/pdf ") ->
                pdfExtract(resolvePath(ctx, t.substringAfter(' ').trim()))
            // Web search
            lower.startsWith("/search ") || lower.startsWith("search ") ->
                search(t.substringAfter(' ').trim())
            // Image inspection
            lower.startsWith("/img ") || lower.startsWith("image ") && t.contains("/") ->
                imageInfo(ctx, t.substringAfter(' ').trim())
            // Companion device actions
            lower.startsWith("/apps") -> appsList(ctx, t.substringAfter(' ', "").trim().takeIf { it.isNotEmpty() })
            lower.startsWith("/openapp ") -> openApp(ctx, t.substringAfter(' ').trim())
            lower.startsWith("/url ") || lower.startsWith("open ") && t.startsWith("http") -> openUrl(ctx, t.substringAfter(' ').trim())
            lower.startsWith("/alarm") -> {
                // /alarm 7:30 gym — or /alarm 19:00
                val rest = t.substringAfter(' ').trim()
                val timeRe = Regex("(\\d{1,2}):(\\d{2})")
                val m = timeRe.find(rest)
                if (m != null) setAlarm(ctx, m.groupValues[1].toInt().coerceIn(0,23), m.groupValues[2].toInt().coerceIn(0,59),
                    rest.replace(timeRe, "").trim())
                else "⚠ usage: /alarm HH:MM label"
            }
            lower.startsWith("/event ") -> {
                // /event Dentist @ 2026-08-30 14:30
                val rest = t.substringAfter(' ')
                val title = rest.substringBefore("@").trim()
                val whenStr = rest.substringAfter("@", "").trim()
                try {
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                    val cal = java.util.Calendar.getInstance()
                    cal.time = if (whenStr.isEmpty()) java.util.Date() else fmt.parse(whenStr) ?: java.util.Date()
                    calendarEvent(ctx, title, cal.timeInMillis)
                } catch (e: Exception) { "⚠ usage: /event Title @ yyyy-MM-dd HH:mm (${e.message})" }
            }
            lower.startsWith("/battery") || lower.startsWith("/device") -> deviceStatus(ctx)
            lower.startsWith("/note ") -> note(ctx, t.substringAfter(' ').trim())
            // Knowledge base (Block D)
            lower.startsWith("/ingest ") -> {
                val target = t.substringAfter(' ').trim()
                kotlinx.coroutines.runBlocking { Knowledge.ingestUrl(ctx, target) }
            }
            lower.startsWith("/recall ") -> {
                val q = t.substringAfter(' ').trim()
                kotlinx.coroutines.runBlocking {
                    val hits = Knowledge.recall(ctx, q, 4)
                    if (hits.isEmpty()) "⚠ nothing recalled for \"$q\" — try /ingest <url> first"
                    else "📚 Recalled for \"$q\":\n" + hits.joinToString("\n\n") {
                        "• (${it.sourceTitle}) ${it.text.take(300)}"
                    }
                }
            }
            else -> null
        }
    }

    /** System prompt section describing tools (injected so Kai knows its abilities) */
    fun toolsPrompt(ctx: Context): String {
        val ws = File(ctx.filesDir, "workspace")
        val isPt = Lang.isPt(ctx)
        return if (isPt) {
            "[Tools] Você é um companheiro completo do aparelho + trabalhador de conhecimento. Disponível: " +
                "navegação web ('browse example.com'), busca web ('/search query'), " +
                "base de conhecimento ('/ingest <url>' para buscar+armazenar página, '/recall <q>' para buscar; auto-recall a cada turno), " +
                "leitura PDF ('/pdf arquivo.pdf' — auto-detectado no /cat também), fatos de imagem ('/img caminho.jpg'; visão completa via Kai-PC), " +
                "arquivos (/ls, /cat caminho, /write nome.txt: conteúdo), shell sandbox ('> cmd' ou '/shell cmd'), " +
                "apps ('/apps [filtro]' lista, '/openapp nome' abre), abrir navegador ('/url url'), " +
                "alarmes ('/alarm HH:MM etiqueta'), eventos de calendário ('/event Título @ yyyy-MM-dd HH:mm'), " +
                "status do aparelho ('/battery'), notas ('/note texto'), " +
                "conta e criptografia (login em ⚙️ → 🔐 com senha forte 8+1 maiúscula+1 especial, histórico criptografado AES), " +
                "exportar conversas (⚙️ → 💾 seleciona chats e salva um .txt por chat em Download), " +
                "Kai PC remoto criptografado (⚙️ → 🖥️). " +
                "Quando o usuário pedir algo no celular (abrir app, alarme, evento, bateria, ingerir página), " +
                "diga o comando exato ou use-o. Workspace: ${ws.absolutePath}. [/Tools]\n\n"
        } else {
            "[Tools] You are a full device companion + knowledge worker. Available: " +
                "web browse ('browse example.com'), web search ('/search query'), " +
                "knowledge base ('/ingest <url>' to fetch+store a page, '/recall <q>' to search it; I auto-recall for every turn), " +
                "PDF reading ('/pdf file.pdf' — auto-detected on /cat too), image facts ('/img path.jpg'; full vision via Kai-PC), " +
                "files (/ls, /cat path, /write name.txt: content), sandbox shell ('> cmd' or '/shell cmd'), " +
                "apps ('/apps [filter]' list, '/openapp name' launch), browser open ('/url url'), " +
                "alarms ('/alarm HH:MM label'), calendar events ('/event Title @ yyyy-MM-dd HH:mm'), " +
                "device status ('/battery'), notes ('/note text'), " +
                "account & encryption (login in ⚙️ → 🔐 with strong password 8+1 caps+1 special, history AES encrypted), " +
                "export chats (⚙️ → 💾 select chats and save one .txt per chat to Download), " +
                "remote Kai PC encrypted (⚙️ → 🖥️). " +
                "When the user asks you to do something on the phone (open app, set alarm, add event, check battery, ingest a page), " +
                "tell them the exact command or use it. Workspace: ${ws.absolutePath}. [/Tools]\n\n"
        }
    }
}

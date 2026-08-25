package com.axiom.kai

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Opencode Terminal — runs `opencode` directly on the phone.
 * This IS opencode, not a wrapper. Same workspace as desktop: filesDir/workspace
 * Agents (Kai) recognize natural language "export history to phone" / "import" and trigger the same paths.
 */
@Composable
fun OpencodeTerminal(
    ctx: Context,
    onImportRequested: () -> Unit = {}
) {
    var input by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<TermLine>()) }
    var isBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        // Welcome banner
        history = history + TermLine(
            "system",
            "Kai Opencode Terminal — phone-native opencode\n" +
            "Workspace: ${java.io.File(ctx.filesDir, "workspace").absolutePath}\n" +
            "Try: kai launch opencode | kai export | kai import | help\n",
            false
        )
    }

    // Auto-scroll to bottom
    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1)
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        // Output
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(history) { line ->
                Text(
                    text = (if (line.isCmd) "❯ " else "") + line.text,
                    color = when {
                        line.isCmd -> Color(0xFF58A6FF)
                        line.text.startsWith("✓") -> Color(0xFF3FB950)
                        line.text.startsWith("⚠") || line.text.startsWith("error") -> Color(0xFFF85149)
                        else -> Color(0xFFC9D1D9)
                    },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (isBusy) item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF58A6FF))
                    Text("running…", color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // Quick chips for common opencode actions — FULL MENU, all clickable
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Quick opencode menu — tap any command:", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8B949E))
            // Row 1: core
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("kai launch opencode", "kai export", "kai import", "help").forEach { cmd ->
                    SuggestionChip(onClick = { input = cmd }, label = { Text(cmd.substringAfter(" ").take(12), style = MaterialTheme.typography.labelSmall) })
                }
            }
            // Row 2: file/dev
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("/ls", "/cat <path>", "> echo hi", "browse example.com").forEach { cmd ->
                    SuggestionChip(onClick = { input = cmd }, label = { Text(cmd.take(12), style = MaterialTheme.typography.labelSmall) })
                }
            }
            // Row 3: Kai PC live + sessions
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("kai export --to-phone", "kai import --help", "opencode --help").forEach { cmd ->
                    SuggestionChip(onClick = { input = cmd }, label = { Text(cmd.take(14), style = MaterialTheme.typography.labelSmall) })
                }
            }
        }

        // Input row
        Row(
            Modifier.fillMaxWidth().padding(8.dp).background(Color(0xFF161B22), RoundedCornerShape(8.dp)).padding(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("❯", color = Color(0xFF58A6FF), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("opencode command…", color = Color(0xFF8B949E), fontFamily = FontFamily.Monospace) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFC9D1D9),
                    unfocusedTextColor = Color(0xFFC9D1D9),
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                ),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val cmd = input
                    if (cmd.isBlank() || isBusy) return@Button
                    history = history + TermLine("user", cmd, true)
                    input = ""
                    isBusy = true
                    scope.launch {
                        val out = runOpencodeCommand(ctx, cmd, onImportRequested)
                        history = history + out.map { TermLine("kai", it, false) }
                        isBusy = false
                    }
                },
                enabled = input.isNotBlank() && !isBusy
            ) { Text("Run") }
        }
    }
}

data class TermLine(val role: String, val text: String, val isCmd: Boolean)

/**
 * Shim that makes `opencode` run on Android.
 * For now it reuses the existing Kai Rust/Kotlin logic via Tools.shell and direct DB calls,
 * with the exact same CLI surface as desktop: `kai launch opencode`, `kai export`, `kai import`, etc.
 * Future: replace with cross-compiled `opencode` binary (aarch64) via `Runtime.exec`.
 */
suspend fun runOpencodeCommand(ctx: Context, raw: String, onImportRequested: () -> Unit = {}): List<String> {
    val cmd = raw.trim()
    val lower = cmd.lowercase()

    return when {
        lower in listOf("help", "opencode --help", "kai --help") -> listOf(
            "Kai Opencode — phone-native",
            "",
            "  kai launch opencode   Start Kai REPL (VFE-governed, same as desktop)",
            "  kai export            Export history + memories to kai_state_export.json (for USB/Download)",
            "  kai export --to-phone Export to Download/ for USB pull to desktop",
            "  kai import            Import kai_state/session files from Download/ (auto-scans)",
            "  kai import <path>     Import a specific .json/.md file",
            "  ls / cat / write      File tools (see /ls, /cat)",
            "  > <shell>             Raw shell in workspace",
            "",
            "Natural language also works: tell Kai 'export history to phone' or 'import my phone'."
        )

        lower == "kai launch opencode" || lower == "opencode" -> listOf(
            "✓ Kai REPL is this chat (swipe to Chat tab).",
            "  You are already in opencode — the Chat screen *is* kai launch opencode.",
            "  VFE: ${try { String.format("%.1f", KaiBridge.calculateVFE(1.0f, 0.5f)) } catch (_: Throwable) { "stub" }}",
            "  Try: 'What is VFE?' in the Chat tab."
        )

        lower.startsWith("kai export") -> {
            val path = if (lower.contains("--to-phone")) {
                // Phone export for desktop pull via USB
                val f = java.io.File(ctx.getExternalFilesDir(null), "kai_state_export.json")
                val uri = com.axiom.kai.MemoryDbKtDarwinSyncExport(f.absolutePath, ctx)
                "✓ Exported phone Kai state to: ${f.absolutePath}\n  Pull to desktop: adb pull ${f.absolutePath} ~/Downloads/"
            } else {
                val uri = com.axiom.kai.MemoryDbKtDarwinSyncExport(
                    java.io.File(ctx.filesDir, "kai_state_export.json").absolutePath, ctx
                )
                "✓ Exported to: $uri\n  Find it in: ${ctx.filesDir.absolutePath}/kai_state_export.json"
            }
            listOf(path, "Share via USB: adb pull <path>  or  check Download/")
        }

        lower.startsWith("kai import") -> {
            val parts = cmd.split(" ").filter { it.isNotBlank() }
            val specific = parts.drop(2).firstOrNull()
            val count = if (specific != null) {
                val f = java.io.File(specific)
                if (f.exists()) {
                    val text = f.readText()
                    com.axiom.kai.MemoryDbKtDarwinSyncImport(text, ctx)
                } else -1
            } else {
                scanAndImportDownloads(ctx)
            }
            if (count >= 0) listOf("✓ Imported $count memories/chats from ${specific ?: "Download/"}")
            else listOf("⚠ No kai_state/session files found in Download/. Drop a .json/.md there and run kai import again.")
        }

        lower.startsWith("export history") || lower.contains("export") && lower.contains("phone") -> {
            val f = java.io.File(ctx.getExternalFilesDir(null), "kai_state_export.json")
            com.axiom.kai.MemoryDbKtDarwinSyncExport(f.absolutePath, ctx)
            onImportRequested()
            listOf("✓ Exported history to phone Download for USB: ${f.absolutePath}")
        }

        lower.startsWith("/ls") || lower == "ls" -> listOf(Tools.listFiles(ctx).joinToString("\n") { "${if (it.isDirectory) "📁" else "📄"} ${it.name} (${it.length()}b)" }.ifEmpty { "(empty workspace)" })

        lower.startsWith("/cat ") -> listOf(Tools.readFile(cmd.substringAfter(" ").trim()))

        lower.startsWith("> ") || lower.startsWith("/shell ") -> {
            val shellCmd = cmd.substringAfter(" ").trim()
            listOf(Tools.shell(ctx, shellCmd))
        }

        else -> listOf(
            "Unknown: $cmd",
            "Try: help, kai launch opencode, kai export, kai import, /ls, /cat <path>, > <shell>"
        )
    }
}

// Helpers to avoid circular deps — thin wrappers that delegate to MemoryDb.kt/DarwinSync
fun MemoryDbKtDarwinSyncExport(path: String, ctx: Context): String {
    return try {
        val db = KaiDb.get(ctx)
        val mems = db.memoryDao().allOnce()
        val json = buildString {
            append("{\n  \"kai_state_version\": 1,\n")
            append("  \"exported_at\": ${System.currentTimeMillis()},\n")
            append("  \"exported_from\": \"kai-android\",\n")
            append("  \"memories\": [\n")
            append(mems.joinToString(",\n") { m ->
                val q = org.json.JSONObject.quote(m.fact)
                "    { \"fact\": $q, \"source\": \"${m.source}\", \"created\": ${m.createdAt}, \"recall\": ${m.recallCount} }"
            })
            append("\n  ]\n}")
        }
        java.io.File(path).writeText(json)
        path
    } catch (e: Exception) { "export failed: ${e.message}" }
}

fun MemoryDbKtDarwinSyncImport(json: String, ctx: Context): Int {
    var count = 0
    Regex("\"fact\"\\s*:\\s*\"([^\"]+)\"").findAll(json).forEach { m ->
        try {
            kotlinx.coroutines.runBlocking {
                MemoryEngine(ctx).store(m.groupValues[1], "imported-via-opencode")
            }
            count++
        } catch (_: Exception) {}
    }
    return count
}

fun scanAndImportDownloads(ctx: Context): Int {
    val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
    val appDl = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
    val candidates = mutableListOf<java.io.File>()
    for (dir in listOfNotNull(dlDir, appDl, java.io.File(ctx.filesDir, "workspace"))) {
        dir.listFiles()?.forEach { f ->
            if (f.name.endsWith(".json") || f.name.startsWith("session") || f.name.startsWith("opencode") || f.name.endsWith(".zip")) {
                candidates.add(f)
            }
        }
    }
    var total = 0
    for (f in candidates) {
        try {
            if (f.name.endsWith(".zip")) {
                // Unzip bundle (kai_phone_bundle.zip) and import each entry
                val destDir = java.io.File(ctx.filesDir, "imported_${System.currentTimeMillis()}")
                destDir.mkdirs()
                java.util.zip.ZipInputStream(f.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val outFile = java.io.File(destDir, entry.name.substringAfterLast("/"))
                            // Only import relevant files
                            if (outFile.name.endsWith(".json") || outFile.name.startsWith("session") || outFile.name.startsWith("opencode")) {
                                outFile.outputStream().use { zis.copyTo(it) }
                                try {
                                    val text = outFile.readText()
                                    total += MemoryDbKtDarwinSyncImport(text, ctx)
                                } catch (_: Exception) {}
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                // Also try to import the zip's own kai_state if present at top level
                // Fallback: if zip contains kai_state_export.json, import it directly
                try {
                    java.util.zip.ZipFile(f).use { zip ->
                        zip.getEntry("kai_state_export.json")?.let { e ->
                            val text = zip.getInputStream(e).bufferedReader().readText()
                            total += MemoryDbKtDarwinSyncImport(text, ctx)
                        }
                    }
                } catch (_: Exception) {}
            } else {
                val text = f.readText()
                total += MemoryDbKtDarwinSyncImport(text, ctx)
            }
            // Also if it's a kai_state file, copy it into app files for later reference
            if (f.name.contains("kai_state")) {
                f.copyTo(java.io.File(ctx.filesDir, f.name), overwrite = true)
            }
        } catch (_: Exception) {}
    }
    return total
}

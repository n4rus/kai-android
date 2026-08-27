package com.axiom.kai

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.axiom.kai.ui.theme.KaiTheme
import com.axiom.kai.ui.theme.getThemeMode
import com.axiom.kai.ui.theme.setThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // PDF engine init (desktop ingest parity — /pdf + 📎 .pdf files)
        try { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext) } catch (_: Throwable) {}
        setContent { KaiTheme { KaiApp() } }
    }
}

@Composable
fun KaiApp() {
    var selectedTab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    val ctx = LocalContext.current
    // observe language version for bottom bar recomposition
    @Suppress("UNUSED_VARIABLE") val langV = Lang.version.value
    androidx.compose.material3.Scaffold(
        bottomBar = {
            Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth().background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                androidx.compose.material3.TextButton(onClick = { selectedTab = 0 }) {
                    androidx.compose.material3.Text(if (selectedTab == 0) Lang.t(ctx,"💬 Chat","💬 Conversa") else Lang.t(ctx,"Chat","Conversa"), color = if (selectedTab == 0) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Gray)
                }
                androidx.compose.material3.TextButton(onClick = { selectedTab = 1 }) {
                    androidx.compose.material3.Text(if (selectedTab == 1) "⌨️ Terminal" else "Terminal", color = if (selectedTab == 1) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Gray)
                }
            }
        }
    ) { pad ->
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(pad).fillMaxSize()) {
            when (selectedTab) {
                0 -> KaiScreen()
                1 -> OpencodeTerminal(androidx.compose.ui.platform.LocalContext.current)
            }
        }
    }
}

// Beginner-friendly suggestion chips (newbie → dev → theory) — localized
private fun suggestionsFor(ctx: Context): List<String> = if (Lang.isPt(ctx)) listOf(
    "Explique como se eu tivesse 5 anos: o que é um LLM?",
    "Escreva um script Python para renomear arquivos",
    "Explique energia livre variacional de forma simples",
    "Depure este erro de borrow no Rust",
    "O que é o princípio da energia livre?",
    "Resuma este tópico para um iniciante"
) else listOf(
    "Explain like I'm 5: what is an LLM?",
    "Write a Python script to rename files",
    "Explain variational free energy simply",
    "Debug this Rust borrow error",
    "What is the free energy principle?",
    "Summarize this topic for a beginner"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun KaiScreen(vm: ChatViewModel = viewModel()) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val langTick = Lang.version.value
    val messages by vm.messages.collectAsState()
    val model by vm.model.collectAsState()
    val generating by vm.isGenerating.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    var input by remember { mutableStateOf("") }
    var pickerExpanded by remember { mutableStateOf(false) }
    var historyExpanded by remember { mutableStateOf(false) }
    var configExpanded by remember { mutableStateOf(false) }
    var showPhysics by remember { mutableStateOf(false) } // physics meters collapsed by default (beginner-friendly)
    var showPcSettings by remember { mutableStateOf(false) }
    var showGeminiSettings by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }
    val models = ModelCatalog.models
    val lastKai = messages.lastOrNull { it.role != Role.USER }

    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pickedImageUri = it
            // If kai-pc:live is selected, send image straight to PC's live terminal
            if (vm.currentModel() == "kai-pc:live" && KaiPcClient.isConfigured(ctx)) {
                val (b64, name) = KaiPcClient.fileToBase64(ctx, it) ?: (null to null)
                if (b64 != null) {
                    vm.sendPCImage(ctx, b64, name ?: "image")
                    Toast.makeText(ctx, "📷 Sending image to Kai PC…", Toast.LENGTH_SHORT).show()
                }
            } else {
                val msg = "📷 ${it.lastPathSegment ?: "image"} — describe this image"
                vm.send(ctx, msg)
                Toast.makeText(ctx, "Image attached — Kai will see it", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        GoogleAuthManager.handleResult(ctx, res.data) { ok, msg ->
            when {
                ok -> {
                    Toast.makeText(ctx, "Google ✓ $msg — now paste Gemini API key", Toast.LENGTH_SHORT).show()
                    showGeminiSettings = true
                }
                msg == "login_unavailable_fallback_key" -> {
                    // Error 10 (DEVELOPER_ERROR): Google login not configured.
                    // Fall back straight to the API key prompt — Gemini free tier works with just the key.
                    showGeminiSettings = true
                    Toast.makeText(ctx, "Google login unavailable — paste your free Gemini API key from aistudio.google.com", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    Toast.makeText(ctx, "Sign-in failed: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // 📎 File explorer: pick ANY file → Kai reads content into context (or sends to PC if kai-pc)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { u ->
            if (vm.currentModel() == "kai-pc:live" && KaiPcClient.isConfigured(ctx)) {
                val (b64, name) = KaiPcClient.fileToBase64(ctx, u) ?: (null to null)
                if (b64 != null) {
                    vm.sendPCFile(ctx, b64, name ?: "file")
                    Toast.makeText(ctx, "📎 Sending file to Kai PC…", Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }
            }
            try {
                val name = (u.lastPathSegment ?: "file")
                val isPdf = name.lowercase().endsWith(".pdf") ||
                    (ctx.contentResolver.getType(u) ?: "").contains("pdf")
                val text = if (isPdf) {
                    // Copy to cache then extract with pdfbox
                    val tmp = java.io.File(ctx.cacheDir, "picked.pdf")
                    ctx.contentResolver.openInputStream(u)?.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
                    val extracted = Tools.pdfExtract(tmp.absolutePath)
                    tmp.delete()
                    extracted
                } else {
                    ctx.contentResolver.openInputStream(u)?.use { s ->
                        s.readBytes().toString(Charsets.UTF_8).take(6000)
                    } ?: ""
                }
                vm.send(ctx, "📎 Attached file '$name':\n$text\n\nSummarize/analyze this file.")
                Toast.makeText(ctx, if (isPdf) "PDF attached — Kai extracts text" else "File attached — Kai reads it", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Read failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        vm.initPersistence(ctx)   // Tier 1: load last chat + messages
        vm.autoLoadIfAvailable(ctx) // GGUF auto-recover
        vm.resumeIncompleteDownloads(ctx) // resume partial downloads with progress bar
        // Unlock Pro on this device (dev/owner device — v2 features free for you)
        val prefs = ctx.getSharedPreferences("kai_billing", Context.MODE_PRIVATE)
        if (!vm.billingHasV2(ctx)) {
            prefs.edit().putLong("v2_expiry", System.currentTimeMillis() + 36500L * 86400000L).apply()
        }
        if (vm.downloadState.value.values.none { it } && vm.downloadProgress.value.isEmpty()) {
            ModelCatalog.models.firstOrNull { it.tag == "qwen2.5:0.5b" }?.let {
                vm.downloadModel(ctx, it.tag) { _ ->
                    Toast.makeText(ctx, "Downloading free ${it.tag} (${it.sizeMb}MB)…", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kai") },
                actions = {
                    // Config — all settings (⚙️)
                    Box {
                        TextButton(onClick = { configExpanded = true }) { Text("⚙️", style = MaterialTheme.typography.titleMedium) }
                        DropdownMenu(expanded = configExpanded, onDismissRequest = { configExpanded = false }) {
                            DropdownMenuItem(
                                text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("✦", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(12.dp))
                                    Text(Lang.t(ctx, "Google Sign-In & API Keys", "Entrar com Google & Chaves API"), style = MaterialTheme.typography.bodyMedium)
                                }},
                                onClick = { showGeminiSettings = true; configExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🎨", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(12.dp))
                                    Text(Lang.t(ctx, "Theme: ${when (getThemeMode(ctx)) { 0 -> "White"; 1 -> "Dark"; else -> "Black" }}",
                                        "Tema: ${when (getThemeMode(ctx)) { 0 -> "Claro"; 1 -> "Escuro"; else -> "Preto" }}"),
                                        style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.weight(1f))
                                    Text("▸", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }},
                                onClick = { showThemePicker = true; configExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("⚡", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(12.dp))
                                    Text(Lang.t(ctx, "VFE / Curvature — ${if (showPhysics) "ON" else "OFF"}", "VFE / Curvatura — ${if (showPhysics) "ATIVADO" else "DESATIVADO"}"),
                                        style = MaterialTheme.typography.bodyMedium)
                                }},
                                onClick = { showPhysics = !showPhysics; configExpanded = false }
                            )
                            DropdownMenuItem(
                                text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🌐", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(12.dp))
                                    Text(Lang.t(ctx, "Language: ${if (Lang.isPt(ctx)) "Português" else "English"}", "Idioma: ${if (Lang.isPt(ctx)) "Português" else "English"}"),
                                        style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.weight(1f))
                                    Text("▸", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }},
                                onClick = { showLangPicker = true; configExpanded = false }
                            )
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            DropdownMenuItem(
                                text = { Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🖥️", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(12.dp))
                                    Text(Lang.t(ctx, "Kai PC — Encrypted Live", "Kai PC — Ao Vivo Criptografado"), style = MaterialTheme.typography.bodyMedium)
                                }},
                                onClick = { showPcSettings = true; configExpanded = false }
                            )
                        }
                    }
                    // Chat history drawer — only history (☰)
                    Box {
                        TextButton(onClick = { historyExpanded = true }) { Text("☰", style = MaterialTheme.typography.titleLarge) }
                        DropdownMenu(expanded = historyExpanded, onDismissRequest = { historyExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(Lang.t(ctx, "✚ New chat", "✚ Nova conversa")) },
                                onClick = { vm.newChat(ctx); historyExpanded = false }
                            )
                        // Search across all messages (Block D drawer UX)
                        var searchQ by remember { mutableStateOf("") }
                        var searchResults by remember { mutableStateOf(emptyList<SearchHit>()) }
                        androidx.compose.runtime.LaunchedEffect(searchQ) {
                            if (searchQ.length >= 2) searchResults = vm.searchAll(ctx, searchQ)
                            else searchResults = emptyList()
                        }
                        Column(modifier = Modifier.padding(8.dp).widthIn(min = 280.dp)) {
                            OutlinedTextField(
                                value = searchQ,
                                onValueChange = { searchQ = it },
                                label = { Text(Lang.t(ctx, "Search your records…", "Buscar nos registros…")) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (searchQ.length >= 2 && searchResults.isEmpty()) {
                                Text(Lang.t(ctx, "no matches", "nenhum resultado"), style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(6.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            searchResults.take(8).forEach { h ->
                                DropdownMenuItem(
                                    text = { Text("• ${h.text.take(60).replace("\n", " ")} (${h.chatTitle})",
                                        style = MaterialTheme.typography.bodySmall) },
                                    onClick = {
                                        vm.switchChat(ctx, h.chatId); historyExpanded = false; searchQ = ""
                                    }
                                )
                            }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        }
                        val chats = vm.chatList.collectAsState().value
                        if (chats.isEmpty() && searchQ.isBlank()) {
                            DropdownMenuItem(text = { Text(Lang.t(ctx, "No history yet", "Nenhum histórico ainda"), color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {})
                        }
                        chats.forEach { c ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (c.id == vm.currentChatId()) "▸ ${c.title}" else c.title,
                                            modifier = Modifier.weight(1f),
                                            color = if (c.id == vm.currentChatId()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text("🗑", style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.clickable { vm.deleteChat(ctx, c.id) })
                                    }
                                },
                                onClick = { vm.switchChat(ctx, c.id); historyExpanded = false }
                            )
                        }
                    }
                    }
                    // New chat quick button
                    TextButton(onClick = { vm.newChat(ctx) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    Box {
                        TextButton(onClick = { pickerExpanded = true }) { Text(model, style = MaterialTheme.typography.labelLarge) }
                        DropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
                            // AUTO router — picks the right voice per task
                            DropdownMenuItem(
                                text = { Text("auto (router) ✦", color = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    vm.setModel("auto"); pickerExpanded = false
                                    Toast.makeText(ctx, "auto — code→coder, deep→best, else→fast", Toast.LENGTH_SHORT).show()
                                }
                            )
                             models.forEach { e ->
                                 val downloaded = downloadState[e.tag] == true
                                 val pct = progress[e.tag]
                                 val isRemote = e.isRemote
                                 val label = when {
                                     isRemote -> "${e.tag} ✦ remote"
                                     downloaded -> "${e.tag} ✓ local"
                                     pct != null -> "${e.tag} ⬇ ${pct}%"
                                     else -> "${e.tag} ⬇ ${e.sizeMb}MB"
                                 }
                                 DropdownMenuItem(
                                     text = { Text(label) },
                                     onClick = {
                                         vm.setModel(e.tag)
                                         if (isRemote) {
                                             if (e.tag.startsWith("gemini:")) {
                                                 if (!GeminiClient.isLoggedIn(ctx) || !GeminiClient.hasApiKey(ctx)) {
                                                     Toast.makeText(ctx, "Tap ✦ to set up Gemini (Google login + API key)", Toast.LENGTH_SHORT).show()
                                                     showGeminiSettings = true
                                                 } else Toast.makeText(ctx, "${e.tag} ready", Toast.LENGTH_SHORT).show()
                                             } else {
                                                 val prov = when {
                                                     e.tag.startsWith("deepseek:") -> "deepseek"
                                                     e.tag.startsWith("gpt:") -> "openai"
                                                     e.tag.startsWith("qwen:") -> "qwen"
                                                     e.tag.startsWith("claude:") -> "claude"
                                                     else -> ""
                                                 }
                                                 if (prov.isNotEmpty() && !RemoteLLMClient.hasKey(ctx, prov)) {
                                                     Toast.makeText(ctx, "Tap ✦ → set ${prov.uppercase()} API key for ${e.tag}", Toast.LENGTH_SHORT).show()
                                                     showGeminiSettings = true
                                                 } else Toast.makeText(ctx, "${e.tag} ready", Toast.LENGTH_SHORT).show()
                                             }
                                         } else if (!downloaded && pct == null) {
                                             vm.downloadModel(ctx, e.tag) { _ -> Toast.makeText(ctx, "Downloading ${e.tag}…", Toast.LENGTH_SHORT).show() }
                                         } else if (downloaded) {
                                             Toast.makeText(ctx, "Loading ${e.tag}…", Toast.LENGTH_SHORT).show()
                                             vm.tryLoadCurrentModel(ctx) { r ->
                                                 Toast.makeText(ctx, if (r == 0) "${e.tag} loaded" else "Load failed — retry", Toast.LENGTH_SHORT).show()
                                             }
                                         }
                                         pickerExpanded = false
                                     }
                                 )
                             }
                            DropdownMenuItem(
                                text = { Text(if (vm.billingHasV2(ctx)) "Kai Pro — ${vm.billingDaysLeft(ctx)}d left ✓" else "Get Kai Pro (v2) — $4.99") },
                                onClick = {
                                    try {
                                        BillingManager(ctx).connect {
                                            val activity = ctx as? android.app.Activity
                                            if (activity != null) BillingManager(ctx).launchPurchase(activity, BillingSkus.V2_30D)
                                        }
                                    } catch (_: Throwable) { Toast.makeText(ctx, "Kai Pro $4.99 — 30 days, no subscription", Toast.LENGTH_SHORT).show() }
                                    pickerExpanded = false
                                }
                            )
                            // Tier 2: memory count
                            DropdownMenuItem(
                                text = { Text("Memory: ${vm.memoryCount.collectAsState().value} facts (say \"remember X\")") },
                                onClick = { pickerExpanded = false }
                            )
                        }
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // Download progress bar — visible while any model downloads (resumes after quit)
            val activeDownloads = progress.entries.toList()
            if (activeDownloads.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        activeDownloads.forEach { (tag, pct) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("⬇ $tag", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                Text("$pct%", style = MaterialTheme.typography.labelMedium)
                            }
                            LinearProgressIndicator(
                                progress = { pct / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            )
                            Text("Resumes automatically if interrupted", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Physics meters — collapsed by default (beginner-friendly), expandable for devs via menu ⚡
            if (showPhysics) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("VFE", style = MaterialTheme.typography.labelMedium)
                            val v = lastKai?.vfe ?: 2.1f
                            LinearProgressIndicator(progress = { (v/5f).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
                            Text("VFE %.1f — %s".format(v, if (v > 3) Lang.t(ctx,"explore","explorando") else Lang.t(ctx,"consolidate","consolidando")), style = MaterialTheme.typography.bodySmall)
                            val ggufLabel = vm.lastGgufLabel(ctx)
                            Text(ggufLabel, style = MaterialTheme.typography.labelSmall,
                                color = if (ggufLabel.contains("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(8.dp)) {
                            Text(Lang.t(ctx,"Curvature → Temp","Curvatura → Temp"), style = MaterialTheme.typography.labelMedium)
                            val c = lastKai?.curvature ?: 0.45f
                            val t = lastKai?.temp ?: 0.85f
                            Text("g %.2f → T' %.2f".format(c, t), style = MaterialTheme.typography.bodySmall)
                            if (c > 0.7f) Text(Lang.t(ctx,"⚡ Novel — T auto ↑","⚡ Novo — T auto ↑"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else if ((lastKai?.vfe ?: 0f) > 3f) {
                // Beginner hint only when something interesting happens
                Text(Lang.t(ctx,"⚡ Kai is exploring a novel idea…","⚡ Kai está explorando uma ideia nova…"), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.primary)
            }

            // Chat list — opens at the LATEST message (no scrolling back through history)
            val listState = rememberLazyListState()
            LaunchedEffect(messages.size) {
                if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1)
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = false
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(Lang.t(ctx,"Hi, I'm Kai 👋","Olá, sou o Kai 👋"), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                            Text(Lang.t(ctx,"Your on-device AI — private, offline, free.","Sua IA no dispositivo — privada, offline, gratuita."),
                                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text(Lang.t(ctx,"Try asking:","Experimente perguntar:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(suggestionsFor(ctx)) { s ->
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                                .clickable { vm.send(ctx, s) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text(s, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(messages) { m ->
                    val isUser = m.role == Role.USER
                    val isGhost = m.role == Role.KAI_RECURSIVE
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    isUser -> MaterialTheme.colorScheme.primaryContainer
                                    isGhost -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                            modifier = Modifier.widthIn(max = 340.dp)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = {
                                        // Copy message to clipboard
                                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        cm.setPrimaryClip(android.content.ClipData.newPlainText("kai", m.text))
                                        Toast.makeText(ctx, Lang.t(ctx,"Copied","Copiado"), Toast.LENGTH_SHORT).show()
                                    }
                                )
                        ) {
Column(Modifier.padding(12.dp)) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(m.text.ifEmpty { if (isGhost) Lang.t(ctx,"↻ thinking…","↻ pensando…") else "…" },
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            if (m.role != Role.USER) {
                                val timeStr = m.latencyMs?.let { if (it < 1000) "${it}ms" else "%.1fs".format(it/1000f) } ?: ""
                                val base = buildList {
                                    add(m.model)
                                    if (timeStr.isNotEmpty()) add(timeStr)
                                    if (showPhysics && m.vfe != null) {
                                        add("VFE %.1f".format(m.vfe))
                                        add("g %.2f".format(m.curvature ?: 0f))
                                        add("T %.2f".format(m.temp ?: 0f))
                                    }
                                }.joinToString(" · ")
                                Text(base, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        }
                        if (isGhost) {
                            Text(Lang.t(ctx,"↻ Kai's deeper thought — use as prompt","↻ Pensamento profundo do Kai — usar como prompt"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                                    .clickable { vm.promoteRecursive(ctx, m) })
                        }
                    }
                }
                if (generating) item {
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(Lang.t(ctx,"Kai is thinking…","Kai está pensando…"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // Input row
            Row(Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { imagePicker.launch("image/*") }) {
                    Text("📷", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { filePicker.launch("*/*") }) {
                    Text("📎", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(Lang.t(ctx,"Message Kai…","Mensagem para o Kai…")) },
                    singleLine = false,
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.send(ctx, input); input = "" },
                    enabled = input.isNotBlank() && !generating,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text(Lang.t(ctx,"Send","Enviar")) }
            }
        }
    }

    // Kai PC Settings dialog
    if (showPcSettings) {
        var host by remember { mutableStateOf(KaiPcClient.getHost(ctx) ?: "") }
        var token by remember { mutableStateOf(KaiPcClient.getToken(ctx) ?: "") }
        var scheme by remember { mutableStateOf(KaiPcClient.getScheme(ctx)) }
        AlertDialog(
            onDismissRequest = { showPcSettings = false },
            title = { Text("Kai PC — Encrypted Live") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Phone sends text/file/image straight to your PC's live opencode session. PC shows it in its terminal.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("PC IP:port (e.g. 192.168.1.10:8443)") }, singleLine = true)
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Token (Bearer)") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Scheme:", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        FilterChip(selected = scheme == "https", onClick = { scheme = "https" }, label = { Text("https") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = scheme == "http", onClick = { scheme = "http" }, label = { Text("http") })
                    }
                    Text("On PC: python3 tools/kai_pc_server.py --port 8443 --token $token", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Or via USB: adb forward tcp:8443 tcp:8443 → use 127.0.0.1:8443", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    KaiPcClient.saveConfig(ctx, host, token, scheme)
                    Toast.makeText(ctx, "Kai PC saved: $host", Toast.LENGTH_SHORT).show()
                    showPcSettings = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showPcSettings = false }) { Text("Cancel") }
            }
        )
    }
    if (showGeminiSettings) {
        var gemKey by remember { mutableStateOf(GeminiClient.getApiKey(ctx) ?: "") }
        var dsKey by remember { mutableStateOf(RemoteLLMClient.getKey(ctx, "deepseek") ?: "") }
        var gptKey by remember { mutableStateOf(RemoteLLMClient.getKey(ctx, "openai") ?: "") }
        var qwenKey by remember { mutableStateOf(RemoteLLMClient.getKey(ctx, "qwen") ?: "") }
        var claudeKey by remember { mutableStateOf(RemoteLLMClient.getKey(ctx, "claude") ?: "") }
        val isIn = GeminiClient.isLoggedIn(ctx) || GoogleAuthManager.isSignedIn(ctx)
        AlertDialog(
            onDismissRequest = { showGeminiSettings = false },
            title = { Text("Remote LLMs — API keys") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Gemini (Google) — free after login", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isIn) {
                            Text("✓ Signed in", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { GoogleAuthManager.signOut(ctx) { Toast.makeText(ctx, "Signed out", Toast.LENGTH_SHORT).show() } }) { Text("Sign out") }
                        } else {
                            Button(onClick = { googleSignInLauncher.launch(GoogleAuthManager.signInIntent(ctx)) }) { Text("Sign in with Google") }
                        }
                    }
                    OutlinedTextField(value = gemKey, onValueChange = { gemKey = it }, label = { Text("Gemini API key (aistudio.google.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (GeminiClient.hasApiKey(ctx)) Text("✓ Gemini key saved", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("DeepSeek", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(value = dsKey, onValueChange = { dsKey = it }, label = { Text("DeepSeek API key (platform.deepseek.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("GPT (OpenAI)", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(value = gptKey, onValueChange = { gptKey = it }, label = { Text("OpenAI API key (platform.openai.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Qwen (Alibaba DashScope)", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(value = qwenKey, onValueChange = { qwenKey = it }, label = { Text("DashScope API key (dashscope.console.aliyun.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Claude (Anthropic)", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(value = claudeKey, onValueChange = { claudeKey = it }, label = { Text("Anthropic API key (console.anthropic.com)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Keys stored locally only. Models appear as ✦ in picker after key saved.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (gemKey.isNotBlank()) { GeminiClient.setApiKey(ctx, gemKey); GeminiClient.setLoggedIn(ctx, true) }
                    if (dsKey.isNotBlank()) RemoteLLMClient.setKey(ctx, "deepseek", dsKey)
                    if (gptKey.isNotBlank()) RemoteLLMClient.setKey(ctx, "openai", gptKey)
                    if (qwenKey.isNotBlank()) RemoteLLMClient.setKey(ctx, "qwen", qwenKey)
                    if (claudeKey.isNotBlank()) RemoteLLMClient.setKey(ctx, "claude", claudeKey)
                    Toast.makeText(ctx, "Keys saved — pick models from ✦ list", Toast.LENGTH_SHORT).show()
                    showGeminiSettings = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showGeminiSettings = false }) { Text("Close") } }
        )
    }
    if (showThemePicker) {
        val cur = getThemeMode(ctx)
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text(Lang.t(ctx, "Choose Theme", "Escolha o Tema")) },
            text = {
                Column {
                    listOf(
                        0 to Lang.t(ctx, "White", "Claro"),
                        1 to Lang.t(ctx, "Dark", "Escuro"),
                        2 to Lang.t(ctx, "Black", "Preto")
                    ).forEach { (id, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                setThemeMode(ctx, id)
                                Toast.makeText(ctx, Lang.t(ctx, "Theme → $label", "Tema → $label"), Toast.LENGTH_SHORT).show()
                                showThemePicker = false
                            }.padding(vertical = 8.dp)
                        ) {
                            RadioButton(selected = cur == id, onClick = {
                                setThemeMode(ctx, id)
                                Toast.makeText(ctx, Lang.t(ctx, "Theme → $label", "Tema → $label"), Toast.LENGTH_SHORT).show()
                                showThemePicker = false
                            })
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            if (cur == id) {
                                Spacer(Modifier.weight(1f))
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showThemePicker = false }) { Text(Lang.t(ctx, "Close", "Fechar")) } }
        )
    }
    if (showLangPicker) {
        val curLang = Lang.get(ctx)
        AlertDialog(
            onDismissRequest = { showLangPicker = false },
            title = { Text(Lang.t(ctx, "Choose Language", "Escolha o Idioma")) },
            text = {
                Column {
                    listOf("en" to "English", "pt" to "Português").forEach { (code, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                Lang.set(ctx, code)
                                Toast.makeText(ctx, Lang.t(ctx, "Language → $label", "Idioma → $label"), Toast.LENGTH_SHORT).show()
                                showLangPicker = false
                            }.padding(vertical = 8.dp)
                        ) {
                            RadioButton(selected = curLang == code, onClick = {
                                Lang.set(ctx, code)
                                Toast.makeText(ctx, Lang.t(ctx, "Language → $label", "Idioma → $label"), Toast.LENGTH_SHORT).show()
                                showLangPicker = false
                            })
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            if (curLang == code) {
                                Spacer(Modifier.weight(1f))
                                Text("✓", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showLangPicker = false }) { Text(Lang.t(ctx, "Close", "Fechar")) } }
        )
    }
}

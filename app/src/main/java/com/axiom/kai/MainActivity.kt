package com.axiom.kai

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KaiTheme { KaiScreen() } }
    }
}

// Beginner-friendly suggestion chips (newbie → dev → theory)
private val SUGGESTIONS = listOf(
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
    val messages by vm.messages.collectAsState()
    val model by vm.model.collectAsState()
    val generating by vm.isGenerating.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    val progress by vm.downloadProgress.collectAsState()
    var input by remember { mutableStateOf("") }
    var pickerExpanded by remember { mutableStateOf(false) }
    var showPhysics by remember { mutableStateOf(false) } // physics meters collapsed by default (beginner-friendly)
    val models = ModelCatalog.models
    val lastKai = messages.lastOrNull { it.role != Role.USER }

    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pickedImageUri = it
            val msg = "📷 ${it.lastPathSegment ?: "image"} — describe this image"
            vm.send(ctx, msg)
            Toast.makeText(ctx, "Image attached — Kai will see it", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.initPersistence(ctx)   // Tier 1: load last chat + messages
        vm.autoLoadIfAvailable(ctx) // GGUF auto-recover
        if (vm.downloadState.value.values.none { it }) {
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
                    // New chat button
                    TextButton(onClick = { vm.newChat(ctx) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
                    Box {
                        TextButton(onClick = { pickerExpanded = true }) { Text(model, style = MaterialTheme.typography.labelLarge) }
                        DropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
                            models.forEach { e ->
                                val downloaded = downloadState[e.tag] == true
                                val pct = progress[e.tag]
                                DropdownMenuItem(
                                    text = {
                                        Text(when {
                                            downloaded -> "${e.tag} ✓"
                                            pct != null -> "${e.tag} ⬇ ${pct}%"
                                            else -> "${e.tag} ⬇ ${e.sizeMb}MB"
                                        })
                                    },
                                    onClick = {
                                        vm.setModel(e.tag)
                                        if (!downloaded && pct == null) {
                                            vm.downloadModel(ctx, e.tag) { _ -> Toast.makeText(ctx, "Downloading ${e.tag}…", Toast.LENGTH_SHORT).show() }
                                        } else if (downloaded) {
                                            val r = vm.tryLoadCurrentModel(ctx)
                                            Toast.makeText(ctx, if (r == 0) "${e.tag} loaded" else "Load failed — retry", Toast.LENGTH_SHORT).show()
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
                    // Physics toggle — hidden by default, devs/theory tap to expand
                    TextButton(onClick = { showPhysics = !showPhysics }) {
                        Text(if (showPhysics) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {

            // Physics meters — collapsed by default (beginner-friendly), expandable for devs
            if (showPhysics) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("VFE", style = MaterialTheme.typography.labelMedium)
                            val v = lastKai?.vfe ?: 2.1f
                            LinearProgressIndicator(progress = { (v/5f).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth())
                            Text("VFE %.1f — %s".format(v, if (v > 3) "explore" else "consolidate"), style = MaterialTheme.typography.bodySmall)
                            val ggufLabel = vm.lastGgufLabel(ctx)
                            Text(ggufLabel, style = MaterialTheme.typography.labelSmall,
                                color = if (ggufLabel.contains("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }
                    Card(Modifier.weight(1f)) {
                        Column(Modifier.padding(8.dp)) {
                            Text("Curvature → Temp", style = MaterialTheme.typography.labelMedium)
                            val c = lastKai?.curvature ?: 0.45f
                            val t = lastKai?.temp ?: 0.85f
                            Text("g %.2f → T' %.2f".format(c, t), style = MaterialTheme.typography.bodySmall)
                            if (c > 0.7f) Text("⚡ Novel — T auto ↑", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            } else if ((lastKai?.vfe ?: 0f) > 3f) {
                // Beginner hint only when something interesting happens
                Text("⚡ Kai is exploring a novel idea…", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.primary)
            }

            // Chat list
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                reverseLayout = false
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Hi, I'm Kai 👋", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                            Text("Your on-device AI — private, offline, free.",
                                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Text("Try asking:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(SUGGESTIONS) { s ->
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
                                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                                    }
                                )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(m.text.ifEmpty { if (isGhost) "↻ thinking…" else "…" }, style = MaterialTheme.typography.bodyMedium)
                                if (showPhysics && m.vfe != null) {
                                    Text("VFE %.1f · g %.2f · T %.2f".format(m.vfe, m.curvature ?: 0f, m.temp ?: 0f),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (isGhost) {
                            Text("↻ Kai's deeper thought — use as prompt",
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
                        Text("Kai is thinking…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // Input row
            Row(Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { imagePicker.launch("image/*") }) {
                    Text("📷", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Kai…") },
                    singleLine = false,
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { vm.send(ctx, input); input = "" },
                    enabled = input.isNotBlank() && !generating,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("Send") }
            }
        }
    }
}

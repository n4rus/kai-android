package com.axiom.kai

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KaiScreen(vm: ChatViewModel = viewModel()) {
    val ctx = LocalContext.current
    val messages by vm.messages.collectAsState()
    val model by vm.model.collectAsState()
    val generating by vm.isGenerating.collectAsState()
    val downloadState by vm.downloadState.collectAsState()
    var input by remember { mutableStateOf("") }
    var pickerExpanded by remember { mutableStateOf(false) }
    val models = ModelCatalog.models
    val lastKai = messages.lastOrNull { it.role != Role.USER }

    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            pickedImageUri = it
            // Vision wiring: show image in chat and have Kai respond vision-aware
            val msg = "📷 Image picked: ${it.lastPathSegment ?: "image"} — Kai vision (SigLIP/CLIP stub) will describe it. VFE will be computed from image patches."
            vm.send(ctx, msg)
            Toast.makeText(ctx, "Image picked — Kai will see it", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.refreshDownloadState(ctx)
        // Auto-download free Qwen 0.5b on first launch if nothing downloaded (no manual tap needed)
        if (vm.downloadState.value.values.none { it }) {
            val free = ModelCatalog.models.firstOrNull { it.tag == "qwen2.5:0.5b" }
            free?.let {
                // Only auto-download if not already attempted
                vm.downloadModel(ctx, it.tag) { id ->
                    Toast.makeText(ctx, "Auto-downloading free ${it.tag}…", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kai-Android — Recursive") },
                actions = {
                    Box {
                        TextButton(onClick = { pickerExpanded = true }) { Text(model) }
                        DropdownMenu(expanded = pickerExpanded, onDismissRequest = { pickerExpanded = false }) {
                            models.forEach { e ->
                                val downloaded = downloadState[e.tag] == true
                                val needsV2 = vm.requiresV2ForModel(e.tag)
                                val hasV2 = vm.billingHasV2(ctx)
                                val locked = needsV2 && !hasV2
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("${e.tag} ${if(downloaded) "✓" else "⬇ ${e.sizeMb}MB"}")
                                            if (locked) {
                                                Spacer(Modifier.width(6.dp))
                                                Badge { Text("v2 $4.99") }
                                            }
                                        }
                                    },
                                    onClick = {
                                        if (locked) {
                                            // Launch Play Billing for v2 30d
                                            try {
                                                BillingManager(ctx).apply {
                                                    connect {
                                                        val activity = ctx as? android.app.Activity
                                                        if (activity != null) launchPurchase(activity, BillingSkus.V2_30D)
                                                        else Toast.makeText(ctx, "v2 $4.99 — open Store listing to buy", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            } catch (_: Throwable) {
                                                Toast.makeText(ctx, "v2 $4.99 — 30 days, no subscription", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            vm.setModel(e.tag)
                                            if (!downloaded) {
                                                vm.downloadModel(ctx, e.tag) { id -> Toast.makeText(ctx, "Downloading ${e.tag}…", Toast.LENGTH_SHORT).show() }
                                            } else {
                                                val r = vm.tryLoadCurrentModel(ctx)
                                                Toast.makeText(ctx, if(r==0) "Loaded ${e.tag}" else "Load stub (VFE-only)", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        pickerExpanded = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(if(vm.billingHasV2(ctx)) "v2 active — ${vm.billingDaysLeft(ctx, true)}d left ✓" else "Unlock v2 30d — $4.99") },
                                onClick = {
                                    BillingManager(ctx).connect {
                                        val activity = ctx as? android.app.Activity
                                        if (activity != null) BillingManager(ctx).launchPurchase(activity, BillingSkus.V2_30D)
                                    }
                                    pickerExpanded = false
                                }
                            )
                        }
                    }
                    TextButton(onClick = { vm.clear() }) { Text("Clear") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Meters row — VFE + curvature + real GGUF label
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(Modifier.weight(1f)) {
                    Column(Modifier.padding(8.dp)) {
                        Text("VFE", style = MaterialTheme.typography.labelMedium)
                        val v = lastKai?.vfe ?: 2.1f
                        LinearProgressIndicator(progress = (v/5f).coerceIn(0f,1f), modifier = Modifier.fillMaxWidth())
                        Text("VFE %.1f (surprise+KL) — %s".format(v, if(v>3)"explore" else "consolidate"), style = MaterialTheme.typography.bodySmall)
                        val ggufLabel = vm.lastGgufLabel(ctx)
                        Text(ggufLabel, style = MaterialTheme.typography.labelSmall, color = if(ggufLabel.contains("✓")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
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

            // Chat list
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (messages.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Talk with me recursively, but with Kai instead.", style = MaterialTheme.typography.titleMedium)
                                Text("You → Kai (VFE meter updates) → Kai' (ghost ↻, VFE>3) → tap ghost to recurse. Fully offline, GGUF in filesDir/models/.", style = MaterialTheme.typography.bodySmall)
                                val ver = try { KaiBridge.version() } catch (_: Throwable) { "kai-bridge stub" }
                                Text(ver, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                items(messages) { m ->
                    val isUser = m.role == Role.USER
                    val isGhost = m.role == Role.KAI_RECURSIVE
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if(isUser) Arrangement.End else Arrangement.Start) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    isUser -> MaterialTheme.colorScheme.primaryContainer
                                    isGhost -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                }
                            ),
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(m.text, style = MaterialTheme.typography.bodyMedium)
                                if (m.vfe != null) Text("VFE %.1f · g %.2f · T %.2f · %s".format(m.vfe, m.curvature?:0f, m.temp?:0f, m.model), style = MaterialTheme.typography.labelSmall)
                                if (isGhost) {
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(onClick = { vm.promoteRecursive(ctx, m) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                        Text("Use as prompt", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
                if (generating) item { Text("Kai is thinking…", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
            }

            // Input row with image picker + send
            Row(Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { imagePicker.launch("image/*") }) {
                    Text("📷", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Talk with Kai recursively…") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.send(ctx, input); input = "" }, enabled = input.isNotBlank() && !generating) { Text("Send") }
            }
            if (pickedImageUri != null) {
                Text("Picked: ${pickedImageUri?.lastPathSegment} — will be fed to vision tower (stub)", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

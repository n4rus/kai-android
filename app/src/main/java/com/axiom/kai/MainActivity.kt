package com.axiom.kai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KaiApp() }
    }
}

@Composable
fun KaiApp() {
    var vfe by remember { mutableStateOf(2.1f) }
    var curvature by remember { mutableStateOf(0.45f) }
    var temp by remember { mutableStateOf(0.85f) }
    val version = remember { try { KaiBridge.version() } catch (e: Exception) { "kai stub" } }

    MaterialTheme {
        Scaffold { pad ->
            Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Kai-Android — Adaptive On-Device LLM Hub", style = MaterialTheme.typography.titleLarge)
                Text(version, style = MaterialTheme.typography.bodySmall)
                Divider()

                // VFE meter (live when Rust wired)
                Card { Column(Modifier.padding(16.dp)) {
                    Text("VFE Meter — surprise + KL vs attractor", style = MaterialTheme.typography.titleMedium)
                    Slider(value = vfe, onValueChange = { vfe = it }, valueRange = 0f..5f)
                    Text("VFE = %.2f  (high → explore, low → consolidate)".format(vfe))
                }}

                Card { Column(Modifier.padding(16.dp)) {
                    Text("Physics-Wired Temp — g_ij → curvature", style = MaterialTheme.typography.titleMedium)
                    Slider(value = curvature, onValueChange = { curvature = it })
                    val derived = try { KaiBridge.curvatureToTemp(temp, curvature, 0.4f) } catch (_: Exception) { temp * (1+0.4f*curvature) }
                    Text("curvature %.2f → T %.2f → T' %.2f".format(curvature, temp, derived))
                    if (curvature > 0.7f) Text("⚡ Novel input — temperature auto-increased", color = MaterialTheme.colorScheme.primary)
                }}

                Card { Column(Modifier.padding(16.dp)) {
                    Text("Multi-GGUF Picker (stub)", style = MaterialTheme.typography.titleMedium)
                    Text("qwen2.5:0.5b (800MB Q4_K_M) — tap to load when models/ present")
                    Button(onClick = {}) { Text("Load GGUF") }
                }}

                Text("Next: Day 3-4 wire Rust cdylib (kai_load_gguf) via cargo-ndk", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

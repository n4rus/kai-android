package com.axiom.kai.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

private val KaiLight = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2E7D32),
    secondary = androidx.compose.ui.graphics.Color(0xFF4CAF50),
    tertiary = androidx.compose.ui.graphics.Color(0xFF81C784)
)

@Composable
fun KaiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KaiLight, content = content)
}

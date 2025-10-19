package com.example.composeble.blelab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.composeble.blelab.data.SettingsRepository

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }

    var uuid by remember { mutableStateOf(repo.getServiceUuid().orEmpty()) }
    var timeout by remember { mutableStateOf(repo.getTimeoutSec()) }
    var autoReconnect by remember { mutableStateOf(repo.getAutoReconnect()) }
    var logLevel by remember { mutableStateOf(repo.getLogLevel()) }
    var saved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        OutlinedTextField(
            value = uuid,
            onValueChange = { uuid = it; saved = false },
            label = { Text("Default Service UUID (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Scan Timeout: $timeout s", modifier = Modifier.weight(1f))
            Slider(
                value = timeout.toFloat(),
                onValueChange = { timeout = it.toInt(); saved = false },
                valueRange = 3f..60f,
                steps = 57,
                modifier = Modifier.weight(2f)
            )
        }

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Auto Reconnect (FG Service)")
            Switch(checked = autoReconnect, onCheckedChange = { autoReconnect = it; saved = false })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Log Level")
            val options = listOf("ERROR","WARN","INFO","DEBUG")
            var expanded by remember { mutableStateOf(false) }
            Button(onClick = { expanded = true }) { Text(logLevel) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { lv ->
                    DropdownMenuItem(text = { Text(lv) }, onClick = {
                        logLevel = lv
                        expanded = false
                        saved = false
                    })
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                repo.setServiceUuid(uuid.ifBlank { null })
                repo.setTimeoutSec(timeout)
                repo.setAutoReconnect(autoReconnect)
                repo.setLogLevel(logLevel)
                saved = true
            }) { Text("Save") }

            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        if (saved) {
            Text("저장됨 ✔", color = MaterialTheme.colorScheme.primary)
        }
    }
}

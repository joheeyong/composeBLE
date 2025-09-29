package com.example.composeble.blelab.ui

import android.bluetooth.BluetoothGattCharacteristic
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

private fun propsToHuman(p: Int): String {
    val L = mutableListOf<String>()
    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) L += "READ"
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) L += "WRITE"
    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) L += "WRITE_NR"
    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) L += "NOTIFY"
    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) L += "INDICATE"
    return L.joinToString(" | ").ifEmpty { "-" }
}

@Composable
fun DeviceDetailScreen(address: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val vm: DeviceDetailViewModel = viewModel(
        factory = viewModelFactory { initializer { DeviceDetailViewModel(ctx.applicationContext, address) } }
    )
    val state by vm.state.collectAsState()

    LaunchedEffect(address) { vm.load() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("연결된 기기: $address", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        state.error?.let { Text("에러: $it", color = MaterialTheme.colorScheme.error) }
        Text("서비스 ${state.services.size}개", fontWeight = FontWeight.Medium)

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(state.services) { svc ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Service: ${svc.uuid}")
                        svc.characteristics.forEach { ch ->
                            val key = svc.uuid to ch.uuid
                            var writeHex by remember(key) { mutableStateOf("") }
                            val last = state.lastValuesHex[key]
                            val notifying = key in state.notifying

                            Column(Modifier.fillMaxWidth().padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Char: ${ch.uuid}")
                                Text("Props: ${propsToHuman(ch.properties)}", style = MaterialTheme.typography.bodySmall)
                                last?.let { Text("Last: $it", style = MaterialTheme.typography.bodySmall) }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled = (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0,
                                        onClick = { vm.onRead(svc.uuid, ch.uuid) }
                                    ) { Text("Read") }

                                    Button(
                                        onClick = { vm.onToggleNotify(svc.uuid, ch.uuid, enable = !notifying) },
                                        enabled =
                                            (ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
                                    ) { Text(if (notifying) "Notify Off" else "Notify On") }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = writeHex,
                                        onValueChange = { writeHex = it },
                                        label = { Text("Hex (예: 01 02 0A FF)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    var noResp by remember(key) { mutableStateOf(false) }
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row {
                                            Checkbox(checked = noResp, onCheckedChange = { noResp = it })
                                            Text("NoResp")
                                        }
                                        Button(
                                            enabled =
                                                (ch.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0,
                                            onClick = { vm.onWrite(svc.uuid, ch.uuid, writeHex, writeNoResp = noResp) }
                                        ) { Text("Write") }
                                    }
                                }
                                Divider()
                            }
                        }
                    }
                }
            }
        }
    }
}

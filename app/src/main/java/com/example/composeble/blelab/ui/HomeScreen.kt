package com.example.composeble.blelab.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

private fun isBluetoothEnabled(): Boolean =
    BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

private fun isLocationEnabled(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (_: Throwable) { false }
}

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val vm: ScanViewModel = viewModel(
        factory = viewModelFactory { initializer { ScanViewModel(ctx.applicationContext) } }
    )
    val state by vm.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_SCAN] == true) else false
        val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_CONNECT] == true) else false
        val hasLoc = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) else true

        Log.d("HomeScreen", "perm result: scan=$hasScan, connect=$hasConnect, loc=$hasLoc")
        vm.evaluatePermissions(hasScan, hasConnect, hasLoc)
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { Log.d("HomeScreen", "ACTION_REQUEST_ENABLE result=$it") }

    val openLocationSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { Log.d("HomeScreen", "LOCATION_SETTINGS result=$it") }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionLauncher.launch(perms)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("BLE 스캔", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        // 필터 & 타임아웃
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.serviceUuidText,
                    onValueChange = vm::onServiceUuidTextChanged,
                    label = { Text("Service UUID (예: 0000180D-0000-1000-8000-00805F9B34FB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("타임아웃(초)")
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        Button(onClick = { expanded = true }) { Text("${state.timeoutSec}s") }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf(5, 10, 15, 30, 60).forEach { s ->
                                DropdownMenuItem(
                                    text = { Text("$s s") },
                                    onClick = {
                                        vm.onTimeoutChanged(s)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val btOn = isBluetoothEnabled()
                val locOn = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) isLocationEnabled(ctx) else true

                Text("권한: ${if (state.missingPermissions.isEmpty()) "OK" else state.missingPermissions.joinToString()}")
                Text("BT: ${if (btOn) "ON" else "OFF"}")
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                    Text("Location(<=R): ${if (locOn) "ON" else "OFF"}")
                }
                state.error?.let { Text("에러: $it", color = MaterialTheme.colorScheme.error) }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        if (state.missingPermissions.isNotEmpty()) {
                            permissionLauncher.launch(state.missingPermissions.toTypedArray())
                        }
                    }) { Text("권한 요청") }

                    Button(onClick = {
                        if (!btOn) {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        } else {
                            vm.toggleScan()
                        }
                    }) { Text(if (state.isScanning) "스캔 중지" else "스캔 시작") }
                }

                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !locOn) {
                    Button(onClick = {
                        openLocationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) { Text("위치 설정 열기 (<=R)") }
                }
            }
        }

        Text("디바이스 (${state.devices.size})", fontWeight = FontWeight.Medium)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.devices) { d ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(d.name ?: "(이름 없음)")
                        Text(d.address ?: "(주소 없음)")
                        Text("RSSI: ${d.rssi ?: "?"}")
                    }
                }
            }
        }
    }
}

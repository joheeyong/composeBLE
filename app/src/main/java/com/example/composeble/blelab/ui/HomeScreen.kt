package com.example.composeble.blelab.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.composeble.blelab.ui.ScanViewModel

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val vm: ScanViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ScanViewModel(ctx.applicationContext) }
        }
    )
    val state by vm.state.collectAsState()

    // 권한 런처
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_SCAN] == true) else false
        val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_CONNECT] == true) else false
        val hasLoc = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) else true

        vm.evaluatePermissions(hasScan, hasConnect, hasLoc)
    }

    // 블루투스 활성 요청 런처
    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* 사용자가 켰다면 버튼 다시 눌러 스캔 */ }

    fun isBluetoothEnabled(): Boolean =
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

    // 최초 진입 시 권한 요청
    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("BLE 스캔", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                if (state.missingPermissions.isEmpty()) {
                    Text("권한 상태: OK")
                } else {
                    Text("권한 필요: ${state.missingPermissions.joinToString()}")
                    Button(onClick = {
                        permissionLauncher.launch(state.missingPermissions.toTypedArray())
                    }) { Text("권한 요청") }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    enabled = state.missingPermissions.isEmpty(),
                    onClick = {
                        if (!isBluetoothEnabled()) {
                            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        } else {
                            vm.toggleScan()
                        }
                    }
                ) { Text(if (state.isScanning) "스캔 중지" else "스캔 시작") }
            }
        }

        Text("디바이스 (${state.devices.size})", fontWeight = FontWeight.Medium)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.devices) { d ->
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(enabled = false) { /* 다음 커밋에서 연결 */ }
                ) {
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

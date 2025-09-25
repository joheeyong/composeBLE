package com.example.composeble.blelab.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
    } catch (_: Throwable) {
        false
    }
}

/**
 * 홈 스크린.
 * - 스캔 시작/중지
 * - 연결/해제
 * - 연결된 경우 "서비스 보기" 버튼으로 상세 화면 이동 (navToDetail)
 */
@Composable
fun HomeScreen(
    navToDetail: (String) -> Unit
) {
    val ctx = LocalContext.current
    val vm: ScanViewModel = viewModel(
        factory = viewModelFactory { initializer { ScanViewModel(ctx.applicationContext) } }
    )
    val state by vm.state.collectAsState()

    // 권한 런처
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_SCAN] == true) else false
        val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            (result[Manifest.permission.BLUETOOTH_CONNECT] == true) else false
        val hasLoc = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) else true
        vm.evaluatePermissions(hasScan, hasConnect, hasLoc)
    }

    // 블루투스 활성화 요청 런처
    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    // (API 30 이하) 위치 설정 열기 런처
    val openLocationSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    // 최초 권한 요청
    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionLauncher.launch(perms)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "BLE 스캔/연결",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        // 권한/상태/액션 카드
        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val btOn = isBluetoothEnabled()
                val locOn =
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) isLocationEnabled(ctx) else true

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
                    }) {
                        Text(if (state.isScanning) "스캔 중지" else "스캔 시작")
                    }
                }

                // 연결 상태 표시 + 서비스 보기
                val conn = state.connectedAddress
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.connectingAddress != null) {
                        AssistChip(
                            onClick = { },
                            label = { Text("연결 중: ${state.connectingAddress}") })
                    }
                    if (conn != null) {
                        AssistChip(
                            onClick = { },
                            label = { Text("연결됨: $conn${if (state.servicesDiscovered) " (Services)" else ""}") }
                        )
                        // 연결된 경우: 서비스 보기
                        Button(onClick = { navToDetail(conn) }) { Text("서비스 보기") }
                    }
                }

                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !locOn) {
                    Button(onClick = {
                        openLocationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) { Text("위치 설정 열기 (<=R)") }
                }
            }
        }

        Text("디바이스 (${state.devices.size})", fontWeight = FontWeight.Medium)

        // 스캔 결과 리스트
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.devices) { d ->
                val addr = d.address ?: return@items
                val isConnecting = state.connectingAddress == addr
                val isConnected = state.connectedAddress == addr

                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.onDeviceClicked(addr) }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(d.name ?: "(이름 없음)")
                            if (isConnected) {
                                AssistChip(onClick = {}, label = { Text("연결됨") })
                            } else if (isConnecting) {
                                AssistChip(onClick = {}, label = { Text("연결중") })
                            }
                        }
                        Text(addr)
                        Text("RSSI: ${d.rssi ?: "?"}")
                        if (isConnected) {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.onDeviceClicked(addr) }) { Text("연결 해제") }
                                Button(onClick = { navToDetail(addr) }) { Text("서비스 보기") }
                            }
                        }
                    }
                }
            }
        }
    }
}

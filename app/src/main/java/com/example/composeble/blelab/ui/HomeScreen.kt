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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.net.Uri

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
fun HomeScreen(
    navToDetail: (String) -> Unit
) {
    val ctx = LocalContext.current
    val vm: ScanViewModel = viewModel(
        factory = viewModelFactory { initializer { ScanViewModel(ctx.applicationContext) } }
    )
    val state by vm.state.collectAsState()

    // --- 런처들 ---
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

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    val openLocationSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    // 앱 상세 설정으로 이동
    val openAppSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}
    fun launchAppSettings() {
        val uri = Uri.parse("package:${ctx.packageName}")
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
        openAppSettings.launch(intent)
    }

    // Android 13+ 알림 권한 (서비스 알림 가이드에 필요할 수 있음)
    val requestPostNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // 최초 권한 요청
    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPermsForScan().toTypedArray())
    }

    // ▼ 추가: 다이얼로그 상태
    var showRationale by rememberSaveable { mutableStateOf(false) }
    var showOpenSettings by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("BLE 스캔/연결", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val btOn = isBluetoothEnabled()
                val locOn = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) isLocationEnabled(ctx) else true

                // ▼ 추가: 권한 상태 배너
                if (state.missingPermissions.isNotEmpty()) {
                    val permDeniedPermanently = isPermanentlyDenied(ctx, state.missingPermissions)
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("권한이 필요합니다", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (permDeniedPermanently)
                                    "이 기능을 사용하려면 앱 설정에서 권한을 허용해야 합니다."
                                else
                                    "BLE 스캔/연결을 위해 권한이 필요합니다. ‘권한 허용’을 눌러 주세요.",
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (!permDeniedPermanently) {
                                    OutlinedButton(onClick = {
                                        showRationale = true
                                    }) { Text("왜 필요한가요?") }

                                    Button(onClick = {
                                        // 재요청
                                        permissionLauncher.launch(state.missingPermissions.toTypedArray())
                                    }) { Text("권한 허용") }
                                } else {
                                    Button(onClick = { launchAppSettings() }) { Text("앱 설정 열기") }
                                }
                            }
                        }
                    }
                }

                Text("권한: ${if (state.missingPermissions.isEmpty()) "OK" else state.missingPermissions.joinToString()}")
                Text("BT: ${if (btOn) "ON" else "OFF"}")
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
                    Text("Location(<=R): ${if (locOn) "ON" else "OFF"}")
                }
                state.error?.let { Text("에러: $it", color = MaterialTheme.colorScheme.error) }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        val missing = state.missingPermissions
                        if (missing.isEmpty()) return@OutlinedButton
                        val permDeniedPermanently = isPermanentlyDenied(ctx, missing)
                        if (permDeniedPermanently) {
                            showOpenSettings = true
                        } else {
                            permissionLauncher.launch(missing.toTypedArray())
                        }
                    }) { Text("권한 다시 요청") }

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

                // 연결 상태 + 서비스 보기
                val conn = state.connectedAddress
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.connectingAddress != null) {
                        AssistChip(onClick = { }, label = { Text("연결 중: ${state.connectingAddress}") })
                    }
                    if (conn != null) {
                        AssistChip(
                            onClick = { },
                            label = { Text("연결됨: $conn${if (state.servicesDiscovered) " (Services)" else ""}") }
                        )
                        Button(onClick = { navToDetail(conn) }) { Text("서비스 보기") }
                    }
                }

                // 위치/BT 설정 바로가기
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && !locOn) {
                    Button(onClick = {
                        openLocationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }) { Text("위치 설정 열기 (<=R)") }
                }
                if (!btOn) {
                    OutlinedButton(onClick = {
                        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    }) { Text("블루투스 켜기") }
                }
            }
        }

        Text("디바이스 (${state.devices.size})", fontWeight = FontWeight.Medium)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.devices) { d ->
                val addr = d.address ?: return@items
                val isConnecting = state.connectingAddress == addr
                val isConnected = state.connectedAddress == addr

                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().clickable { vm.onDeviceClicked(addr) }
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

    // ▼ Rationale 다이얼로그
    if (showRationale) {
        AlertDialog(
            onDismissRequest = { showRationale = false },
            title = { Text("권한이 필요한 이유") },
            text = {
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        "주변 BLE 기기를 스캔하고 연결하려면 BLUETOOTH_SCAN/CONNECT 권한이 필요합니다."
                    else
                        "주변 BLE 기기를 스캔하려면 위치 권한(FINE LOCATION)이 필요합니다."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRationale = false
                    // 즉시 재요청
                    permissionLauncher.launch(requiredPermsForScan().toTypedArray())
                }) { Text("허용") }
            },
            dismissButton = {
                TextButton(onClick = { showRationale = false }) { Text("취소") }
            }
        )
    }

    // ▼ 앱 설정 이동 다이얼로그 (영구 거부)
    if (showOpenSettings) {
        AlertDialog(
            onDismissRequest = { showOpenSettings = false },
            title = { Text("앱 설정에서 권한 허용 필요") },
            text = { Text("이전에 ‘다시 묻지 않음’으로 거부했습니다. 설정에서 권한을 켠 뒤 다시 시도해 주세요.") },
            confirmButton = {
                TextButton(onClick = {
                    showOpenSettings = false
                    launchAppSettings()
                }) { Text("앱 설정 열기") }
            },
            dismissButton = {
                TextButton(onClick = { showOpenSettings = false }) { Text("닫기") }
            }
        )
    }
}

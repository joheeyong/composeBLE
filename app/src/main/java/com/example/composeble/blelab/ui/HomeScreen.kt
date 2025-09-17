package com.luxrobo.blelab.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.composeble.blelab.util.PermissionState

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val permState by remember {
        mutableStateOf(
            PermissionState(
                hasScan = false,
                hasConnect = false,
                hasLocationLegacy = false
            )
        )
    }

    // 초기 커밋은 UI만 – 실제 권한 체크/업데이트 로직은 다음 커밋에서 채움
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("BLE Compose Starter", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

        ElevatedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("권한 상태")
                Text("- SCAN/CONNECT (S+): ${permState.hasScan && permState.hasConnect}")
                Text("- LOCATION (<=R): ${permState.hasLocationLegacy}")
            }
        }

        Button(onClick = {
            // 다음 커밋에서: 스캔 시작 → 리스트 표시
        }) {
            Text("스캔 시작 (다음 커밋에서 동작)")
        }
    }
}

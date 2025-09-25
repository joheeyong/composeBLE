package com.example.composeble.blelab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Context
import android.bluetooth.BluetoothGattCharacteristic
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.composeble.blelab.ble.AndroidBleClient
import com.example.composeble.blelab.ble.BleClient
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private fun propsToHuman(properties: Int): String {
    val list = mutableListOf<String>()
    if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) list += "READ"
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) list += "WRITE"
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) list += "WRITE_NR"
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) list += "NOTIFY"
    if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) list += "INDICATE"
    if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) list += "BROADCAST"
    if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) list += "SIGNED_WRITE"
    if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) list += "EXTENDED"
    return list.joinToString(separator = " | ").ifEmpty { "-" }
}

@Composable
fun DeviceDetailScreen(address: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val vm: DeviceDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                DeviceDetailViewModel(
                    ctx.applicationContext,
                    address
                )
            }
        }
    )

    val state by vm.state.collectAsState()

    LaunchedEffect(address) { vm.load() }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "연결된 기기: $address",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        state.error?.let { Text("에러: $it", color = MaterialTheme.colorScheme.error) }
        Text("서비스 ${state.services.size}개", fontWeight = FontWeight.Medium)

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(state.services) { svc ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Service: ${svc.uuid}")
                        svc.characteristics.forEach { ch ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 8.dp)
                            ) {
                                Text("Char: ${ch.uuid}")
                                Text(
                                    "Props: ${propsToHuman(ch.properties)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class DeviceDetailState(
    val services: List<BleClient.GattService> = emptyList(),
    val error: String? = null
)

class DeviceDetailViewModel(
    private val context: Context,
    private val address: String
) : ViewModel() {

    // Home과 동일 인스턴스 공유가 이상적이지만, 간단화를 위해 새로 생성
    private val ble: BleClient = AndroidBleClient(context)

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(DeviceDetailState())
    val state = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val res = ble.listServices()
            if (res.isSuccess) {
                _state.value = DeviceDetailState(services = res.getOrDefault(emptyList()))
            } else {
                _state.value = DeviceDetailState(error = res.exceptionOrNull()?.message)
            }
        }
    }
}

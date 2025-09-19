package com.example.composeble.blelab.ui

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Context
import com.example.composeble.blelab.ble.AndroidBleClient
import com.example.composeble.blelab.ble.BleClient

data class ScanUiState(
    val isScanning: Boolean = false,
    val devices: List<BleClient.Device> = emptyList(),
    val missingPermissions: List<String> = emptyList()
)

class ScanViewModel(
    private val context: Context
) : ViewModel() {

    private val ble: BleClient = AndroidBleClient(context)

    private val _state = MutableStateFlow(ScanUiState())
    val state = _state.asStateFlow()

    private var scanJob: Job? = null

    fun evaluatePermissions(
        hasScan: Boolean,
        hasConnect: Boolean,
        hasLocationLegacy: Boolean
    ) {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasScan) need += Manifest.permission.BLUETOOTH_SCAN
            if (!hasConnect) need += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            if (!hasLocationLegacy) need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        _state.value = _state.value.copy(missingPermissions = need)
    }

    fun toggleScan() {
        if (_state.value.isScanning) stopScan() else startScan()
    }

    private fun startScan() {
        if (_state.value.missingPermissions.isNotEmpty()) return
        if (_state.value.isScanning) return

        _state.value = _state.value.copy(isScanning = true, devices = emptyList())

        scanJob = viewModelScope.launch {
            val latestByAddress = LinkedHashMap<String?, BleClient.Device>()
            ble.scan().collectLatest { dev ->
                val key = dev.address ?: dev.name
                if (key != null) {
                    // 최신 RSSI 갱신 (중복 제거)
                    latestByAddress[key] = dev
                    _state.value = _state.value.copy(
                        devices = latestByAddress.values
                            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
                    )
                }
            }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.value = _state.value.copy(isScanning = false)
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}

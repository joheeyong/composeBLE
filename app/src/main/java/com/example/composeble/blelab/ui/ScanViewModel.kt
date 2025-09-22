package com.example.composeble.blelab.ui

import android.Manifest
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Context
import com.example.composeble.blelab.ble.AndroidBleClient
import com.example.composeble.blelab.ble.BleClient
import java.util.UUID

data class ScanUiState(
    val isScanning: Boolean = false,
    val devices: List<BleClient.Device> = emptyList(),
    val missingPermissions: List<String> = emptyList(),
    val error: String? = null,
    val serviceUuidText: String = "",
    val timeoutSec: Int = 10
)

class ScanViewModel(
    private val context: Context
) : ViewModel() {

    private val ble: BleClient = AndroidBleClient(context)

    private val _state = MutableStateFlow(ScanUiState())
    val state = _state.asStateFlow()

    private var scanJob: Job? = null
    private var timeoutJob: Job? = null

    fun onServiceUuidTextChanged(text: String) {
        _state.value = _state.value.copy(serviceUuidText = text)
    }

    fun onTimeoutChanged(sec: Int) {
        _state.value = _state.value.copy(timeoutSec = sec.coerceIn(3, 60))
    }

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
        Log.d("ScanViewModel", "evaluatePermissions: missing=$need")
        _state.value = _state.value.copy(missingPermissions = need)
    }

    fun toggleScan() {
        if (_state.value.isScanning) stopScan() else startScan()
    }

    private fun parseServiceUuidOrNull(text: String): UUID? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            UUID.fromString(trimmed)
        } catch (_: Throwable) {
            null
        }
    }

    private fun startScan() {
        if (_state.value.missingPermissions.isNotEmpty()) {
            Log.e("ScanViewModel", "startScan blocked: missing=${_state.value.missingPermissions}")
            _state.value = _state.value.copy(error = "권한 필요: ${_state.value.missingPermissions.joinToString()}")
            return
        }
        if (_state.value.isScanning) return

        val filterUuid = parseServiceUuidOrNull(_state.value.serviceUuidText)
        if (_state.value.serviceUuidText.isNotBlank() && filterUuid == null) {
            _state.value = _state.value.copy(error = "잘못된 UUID 형식입니다.")
            return
        }

        _state.value = _state.value.copy(isScanning = true, devices = emptyList(), error = null)

        // 스캔 수집
        scanJob = viewModelScope.launch {
            val latestByAddress = LinkedHashMap<String?, BleClient.Device>()
            try {
                ble.scan(filterUuid).collectLatest { dev ->
                    val key = dev.address ?: dev.name
                    if (key != null) {
                        latestByAddress[key] = dev
                        _state.value = _state.value.copy(
                            devices = latestByAddress.values
                                .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
                        )
                    }
                }
            } catch (t: Throwable) {
                Log.e("ScanViewModel", "scan error: ${t.message}", t)
                _state.value = _state.value.copy(isScanning = false, error = t.message)
            }
        }

        // 타임아웃
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            val sec = _state.value.timeoutSec
            delay(sec * 1000L)
            if (_state.value.isScanning) {
                Log.d("ScanViewModel", "scan timeout after ${sec}s")
                stopScan()
            }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = _state.value.copy(isScanning = false)
        Log.d("ScanViewModel", "stopScan()")
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
}

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
    val timeoutSec: Int = 10,
    val connectingAddress: String? = null,
    val connectedAddress: String? = null,
    val servicesDiscovered: Boolean = false
)

class ScanViewModel(
    private val context: Context
) : ViewModel() {

    private val ble: BleClient = AndroidBleClient(context)

    private val _state = MutableStateFlow(ScanUiState())
    val state = _state.asStateFlow()

    private var scanJob: Job? = null
    private var timeoutJob: Job? = null
    private var connCollectJob: Job? = null

    init {
        // 연결 상태 수집
        connCollectJob = viewModelScope.launch {
            ble.connectionState().collectLatest { cs ->
                when (cs) {
                    is BleClient.ConnectionState.Disconnected -> {
                        _state.value = _state.value.copy(
                            connectingAddress = null,
                            connectedAddress = null,
                            servicesDiscovered = false
                        )
                    }

                    is BleClient.ConnectionState.Connecting -> {
                        // 유지
                    }

                    is BleClient.ConnectionState.Connected -> {
                        _state.value = _state.value.copy(
                            connectingAddress = null,
                            connectedAddress = cs.address,
                            servicesDiscovered = cs.servicesDiscovered
                        )
                    }

                    is BleClient.ConnectionState.Error -> {
                        _state.value = _state.value.copy(
                            connectingAddress = null,
                            error = cs.message
                        )
                    }
                }
            }
        }
    }

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
        _state.value = _state.value.copy(missingPermissions = need)
    }

    fun toggleScan() {
        if (_state.value.isScanning) stopScan() else startScan()
    }

    private fun parseServiceUuidOrNull(text: String): UUID? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return runCatching { UUID.fromString(t) }.getOrNull()
    }

    private fun startScan() {
        if (_state.value.missingPermissions.isNotEmpty()) {
            _state.value =
                _state.value.copy(error = "권한 필요: ${_state.value.missingPermissions.joinToString()}")
            return
        }
        if (_state.value.isScanning) return

        val filterUuid = parseServiceUuidOrNull(_state.value.serviceUuidText)
        if (_state.value.serviceUuidText.isNotBlank() && filterUuid == null) {
            _state.value = _state.value.copy(error = "잘못된 UUID 형식입니다.")
            return
        }

        _state.value = _state.value.copy(isScanning = true, devices = emptyList(), error = null)

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
                _state.value = _state.value.copy(isScanning = false, error = t.message)
            }
        }

        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            val sec = _state.value.timeoutSec
            delay(sec * 1000L)
            if (_state.value.isScanning) stopScan()
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        _state.value = _state.value.copy(isScanning = false)
    }

    fun onDeviceClicked(address: String?) {
        val addr = address ?: return
        val connected = state.value.connectedAddress
        if (connected == addr) {
            disconnect()
        } else {
            connect(addr)
        }
    }

    private fun connect(address: String) {
        // 연결 전 스캔 중지
        if (_state.value.isScanning) stopScan()
        _state.value = _state.value.copy(connectingAddress = address, error = null)
        viewModelScope.launch {
            val res = ble.connect(address)
            if (res.isFailure) {
                _state.value = _state.value.copy(
                    connectingAddress = null,
                    error = res.exceptionOrNull()?.message
                )
            }
        }
    }

    private fun disconnect() {
        viewModelScope.launch {
            val res = ble.disconnect()
            if (res.isFailure) {
                _state.value = _state.value.copy(error = res.exceptionOrNull()?.message)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        connCollectJob?.cancel()
    }
}

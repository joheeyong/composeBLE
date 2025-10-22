package com.example.composeble.blelab.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.composeble.ble.*
import com.example.composeble.blelab.ble.BleClient
import com.example.composeble.blelab.ble.BleClients
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID

data class DeviceDetailState(
    val services: List<BleClient.GattService> = emptyList(),
    val error: String? = null,
    val lastValuesHex: Map<Pair<UUID, UUID>, String> = emptyMap(),
    val notifying: Set<Pair<UUID, UUID>> = emptySet(),
    val busyKeys: Set<Pair<UUID, UUID>> = emptySet(),
    val snackbarMessage: String? = null
)


class DeviceDetailViewModel(
    context: Context,
    private val address: String
) : ViewModel() {

    // Home의 연결 인스턴스 우선 사용
    private val ble: BleClient = BleClients.shared ?: AndroidBleClient(context)

    private val _state = MutableStateFlow(DeviceDetailState())
    val state = _state.asStateFlow()

    init {
        // Notification 수집
        viewModelScope.launch {
            ble.notifications().collect { n ->
                if (n.address != address) return@collect
                val key = n.serviceUuid to n.charUuid
                val hex = n.value.joinToString("") { "%02X".format(it) }
                _state.update { s -> s.copy(lastValuesHex = s.lastValuesHex + (key to hex)) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val res = ble.listServices()
            if (res.isSuccess) _state.value = _state.value.copy(services = res.getOrDefault(emptyList()), error = null)
            else _state.value = _state.value.copy(error = res.exceptionOrNull()?.message)
        }
    }

    private fun setBusy(key: Pair<UUID, UUID>, busy: Boolean) {
        _state.update { s ->
            val set = s.busyKeys.toMutableSet()
            if (busy) set += key else set -= key
            s.copy(busyKeys = set)
        }
    }

    fun onRead(service: UUID, char: UUID) {
        val key = service to char
        setBusy(key, true)
        viewModelScope.launch {
            val res = ble.read(service, char)
            if (res.isSuccess) {
                val bytes = res.getOrDefault(ByteArray(0))
                val hex = bytes.joinToString("") { "%02X".format(it) }
                _state.update { s -> s.copy(lastValuesHex = s.lastValuesHex + (key to hex), snackbarMessage = "Read OK") }
            } else {
                _state.update { it.copy(error = res.exceptionOrNull()?.message ?: "Read failed") }
            }
            setBusy(key, false)
        }
    }



    fun onToggleNotify(service: UUID, char: UUID, enable: Boolean) {
        val key = service to char
        setBusy(key, true)
        viewModelScope.launch {
            val res = ble.setNotify(service, char, enable)
            if (res.isSuccess) {
                _state.update { s ->
                    val set = s.notifying.toMutableSet()
                    if (enable) set += key else set -= key
                    s.copy(notifying = set, snackbarMessage = if (enable) "Notify On" else "Notify Off")
                }
            } else {
                _state.update { it.copy(error = res.exceptionOrNull()?.message ?: "Notify failed") }
            }
            setBusy(key, false)
        }
    }

    fun onWrite(service: UUID, char: UUID, hex: String, writeNoResp: Boolean) {
        val key = service to char
        val bytes = hexToBytesOrNull(hex) ?: run {
            _state.update { it.copy(error = "Hex 형식이 올바르지 않습니다.") }
            return
        }
        setBusy(key, true)
        viewModelScope.launch {
            val type = if (writeNoResp) BleClient.WriteType.NO_RESPONSE else BleClient.WriteType.DEFAULT
            val res = ble.write(service, char, bytes, type)
            if (res.isSuccess) _state.update { it.copy(snackbarMessage = "Write OK (${bytes.size}B)") }
            else _state.update { it.copy(error = res.exceptionOrNull()?.message ?: "Write failed") }
            setBusy(key, false)
        }
    }

    private fun hexToBytesOrNull(h: String): ByteArray? {
        val clean = h.replace(" ", "").replace("0x", "", ignoreCase = true)
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return runCatching {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }.getOrNull()
    }

    fun consumeSnackbar() { _state.update { it.copy(snackbarMessage = null) } }
}

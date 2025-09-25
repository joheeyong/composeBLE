package com.example.composeble.blelab.ble

import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface BleClient {
    data class Device(
        val address: String?,
        val name: String?,
        val rssi: Int?
    )

    // GATT 모델
    data class GattCharacteristic(
        val uuid: UUID,
        val properties: Int // Android BluetoothGattCharacteristic.properties (bitmask)
    )

    data class GattService(
        val uuid: UUID,
        val characteristics: List<GattCharacteristic>
    )

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val address: String, val servicesDiscovered: Boolean) :
            ConnectionState()

        data class Error(val message: String) : ConnectionState()
    }

    fun scan(serviceUuid: UUID? = null): Flow<Device>
    fun connectionState(): Flow<ConnectionState>

    suspend fun connect(address: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>

    /** 현재 연결된 GATT의 서비스/특성 스냅샷 */
    suspend fun listServices(): Result<List<GattService>>
}

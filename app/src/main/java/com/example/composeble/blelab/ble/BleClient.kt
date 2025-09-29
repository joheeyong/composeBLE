package com.example.composeble.blelab.ble

import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface BleClient {
    data class Device(val address: String?, val name: String?, val rssi: Int?)

    data class GattCharacteristic(val uuid: UUID, val properties: Int)
    data class GattService(val uuid: UUID, val characteristics: List<GattCharacteristic>)

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val address: String, val servicesDiscovered: Boolean) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    enum class WriteType { DEFAULT, NO_RESPONSE, SIGNED }

    data class Notification(
        val address: String,
        val serviceUuid: UUID,
        val charUuid: UUID,
        val value: ByteArray
    )

    fun scan(serviceUuid: UUID? = null): Flow<Device>
    fun connectionState(): Flow<ConnectionState>

    suspend fun connect(address: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
    suspend fun listServices(): Result<List<GattService>>

    suspend fun read(serviceUuid: UUID, charUuid: UUID): Result<ByteArray>
    suspend fun setNotify(serviceUuid: UUID, charUuid: UUID, enable: Boolean): Result<Unit>
    suspend fun write(
        serviceUuid: UUID,
        charUuid: UUID,
        value: ByteArray,
        type: WriteType = WriteType.DEFAULT
    ): Result<Unit>

    /** 특성 변경(Notification/Indication) 스트림 */
    fun notifications(): Flow<Notification>
}

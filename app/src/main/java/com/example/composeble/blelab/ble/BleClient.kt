package com.example.composeble.blelab.ble

import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface BleClient {
    data class Device(
        val address: String?,
        val name: String?,
        val rssi: Int?
    )

    sealed class ConnectionState {
        data object Disconnected : ConnectionState()
        data object Connecting : ConnectionState()
        data class Connected(val address: String, val servicesDiscovered: Boolean) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    /** 광고 스캔 (선택적 서비스 UUID 필터) */
    fun scan(serviceUuid: UUID? = null): Flow<Device>

    /** 현재 연결 상태 스트림 */
    fun connectionState(): Flow<ConnectionState>

    /** 주소로 연결 시도 */
    suspend fun connect(address: String): Result<Unit>

    /** 연결 해제 */
    suspend fun disconnect(): Result<Unit>
}

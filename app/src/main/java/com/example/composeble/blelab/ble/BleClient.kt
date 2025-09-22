package com.example.composeble.blelab.ble

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * BLE 상위 인터페이스.
 * 구현은 이후 커밋에서 Scan → Connect → GATT IO 순으로 확장한다.
 */
interface BleClient {
    data class Device(
        val address: String?,
        val name: String?,
        val rssi: Int?
    )

    /** 광고 스캔 스트림 (선택적 Service UUID 필터) */
    fun scan(serviceUuid: UUID? = null): Flow<Device>

    suspend fun connect(address: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
}
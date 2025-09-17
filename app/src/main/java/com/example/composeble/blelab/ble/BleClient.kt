package com.example.composeble.blelab.ble

import kotlinx.coroutines.flow.Flow

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

    /** 광고 스캔 스트림 */
    fun scan(): Flow<Device>

    /** 주소(또는 객체)로 연결 시도 */
    suspend fun connect(address: String): Result<Unit>

    /** 연결 해제 */
    suspend fun disconnect(): Result<Unit>
}
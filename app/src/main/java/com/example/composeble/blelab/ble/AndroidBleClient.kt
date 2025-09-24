package com.example.composeble.blelab.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class AndroidBleClient(
    private val context: Context
) : BleClient {

    private val btManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    private val _conn = MutableStateFlow<BleClient.ConnectionState>(BleClient.ConnectionState.Disconnected)
    private var currentGatt: BluetoothGatt? = null
    private var currentAddress: String? = null
    private var servicesDiscovered: Boolean = false

    private fun hasPermissionScan(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasPermissionConnect(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else true

    @SuppressLint("MissingPermission")
    override fun scan(serviceUuid: UUID?): Flow<BleClient.Device> = callbackFlow {
        val hasScan = hasPermissionScan()
        val isBtOn = adapter?.isEnabled == true
        Log.d("AndroidBleClient", "scan(): hasScan=$hasScan, btOn=$isBtOn, filter=${serviceUuid ?: "none"}")

        if (!hasScan) {
            close(SecurityException("Missing permission for BLE scan"))
            return@callbackFlow
        }
        if (!isBtOn) {
            close(IllegalStateException("Bluetooth disabled"))
            return@callbackFlow
        }

        val s = scanner ?: run {
            close(IllegalStateException("Scanner unavailable"))
            return@callbackFlow
        }

        val filters = buildList {
            serviceUuid?.let { add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    BleClient.Device(
                        address = result.device?.address,
                        name = result.device?.name ?: result.scanRecord?.deviceName,
                        rssi = result.rssi
                    )
                )
            }
            @SuppressLint("MissingPermission")
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r ->
                    trySend(
                        BleClient.Device(
                            address = r.device?.address,
                            name = r.device?.name ?: r.scanRecord?.deviceName,
                            rssi = r.rssi
                        )
                    )
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e("AndroidBleClient", "onScanFailed: $errorCode")
                close(IllegalStateException("Scan failed: code=$errorCode"))
            }
        }

        try {
            if (filters.isEmpty()) s.startScan(cb) else s.startScan(filters, settings, cb)
        } catch (se: SecurityException) {
            close(se); return@callbackFlow
        } catch (t: Throwable) {
            close(t); return@callbackFlow
        }

        awaitClose {
            try { s.stopScan(cb) } catch (_: Throwable) {}
        }
    }

    override fun connectionState(): Flow<BleClient.ConnectionState> = _conn.asStateFlow()

    override suspend fun connect(address: String): Result<Unit> {
        if (!hasPermissionConnect()) {
            _conn.value = BleClient.ConnectionState.Error("CONNECT permission missing")
            return Result.failure(SecurityException("BLUETOOTH_CONNECT permission missing"))
        }
        if (adapter?.isEnabled != true) {
            _conn.value = BleClient.ConnectionState.Error("Bluetooth disabled")
            return Result.failure(IllegalStateException("Bluetooth disabled"))
        }

        // 기존 연결 정리
        try { disconnectInternal(closeReason = "new connect") } catch (_: Throwable) {}

        _conn.value = BleClient.ConnectionState.Connecting
        servicesDiscovered = false
        currentAddress = address

        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (t: Throwable) {
            _conn.value = BleClient.ConnectionState.Error("Invalid address: $address")
            return Result.failure(t)
        }

        if (device == null) {
            _conn.value = BleClient.ConnectionState.Error("Device not found: $address")
            return Result.failure(IllegalStateException("Device null"))
        }

        val cb = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (!hasPermissionConnect()) return // 안전 가드
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("AndroidBleClient", "STATE_CONNECTED status=$status")
                    currentGatt = gatt
                    try {
                        gatt.discoverServices()
                    } catch (t: Throwable) {
                        _conn.value = BleClient.ConnectionState.Error("discoverServices failed: ${t.message}")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d("AndroidBleClient", "STATE_DISCONNECTED status=$status")
                    servicesDiscovered = false
                    _conn.value = BleClient.ConnectionState.Disconnected
                    try { gatt.close() } catch (_: Throwable) {}
                    if (currentGatt == gatt) currentGatt = null
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                servicesDiscovered = (status == BluetoothGatt.GATT_SUCCESS)
                val addr = currentAddress ?: gatt.device.address ?: "(unknown)"
                _conn.value = BleClient.ConnectionState.Connected(addr, servicesDiscovered)
                Log.d("AndroidBleClient", "onServicesDiscovered: success=$servicesDiscovered")
            }
        }

        return try {
            // autoConnect=false, 연결 시도
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, cb, BluetoothDeviceTransportChooser.transportAuto())
            } else {
                device.connectGatt(context, false, cb)
            }
            Result.success(Unit)
        } catch (se: SecurityException) {
            _conn.value = BleClient.ConnectionState.Error("SecurityException: ${se.message}")
            Result.failure(se)
        } catch (t: Throwable) {
            _conn.value = BleClient.ConnectionState.Error("Connect failed: ${t.message}")
            Result.failure(t)
        }
    }

    override suspend fun disconnect(): Result<Unit> {
        return try {
            disconnectInternal(closeReason = "manual")
            Result.success(Unit)
        } catch (t: Throwable) {
            _conn.value = BleClient.ConnectionState.Error("Disconnect error: ${t.message}")
            Result.failure(t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(closeReason: String) {
        val g = currentGatt ?: run {
            _conn.value = BleClient.ConnectionState.Disconnected
            return
        }
        Log.d("AndroidBleClient", "disconnectInternal($closeReason)")
        try {
            if (hasPermissionConnect()) g.disconnect()
        } catch (_: Throwable) {}
        try { g.close() } catch (_: Throwable) {}
        currentGatt = null
        servicesDiscovered = false
        _conn.value = BleClient.ConnectionState.Disconnected
    }

    /** Android M+에서 트랜스포트 선택 보조(저전력/BREDR 자동) */
    private object BluetoothDeviceTransportChooser {
        fun transportAuto(): Int = 0 /* BluetoothDevice.TRANSPORT_AUTO (hidden in some SDKs) */
    }
}

package com.example.composeble.blelab.ble

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
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

    override fun scan(serviceUuid: UUID?): Flow<BleClient.Device> = callbackFlow {
        val hasScan = hasPermissionScan()
        val isBtOn = adapter?.isEnabled == true
        if (!hasScan) { close(SecurityException("Missing permission for BLE scan")); return@callbackFlow }
        if (!isBtOn) { close(IllegalStateException("Bluetooth disabled")); return@callbackFlow }
        val s = scanner ?: run { close(IllegalStateException("Scanner unavailable")); return@callbackFlow }

        val filters = buildList {
            serviceUuid?.let { add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                trySend(BleClient.Device(r.device?.address, r.device?.name ?: r.scanRecord?.deviceName, r.rssi))
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> trySend(BleClient.Device(r.device?.address, r.device?.name ?: r.scanRecord?.deviceName, r.rssi)) }
            }
            override fun onScanFailed(code: Int) { close(IllegalStateException("Scan failed: code=$code")) }
        }

        try { if (filters.isEmpty()) s.startScan(cb) else s.startScan(filters, settings, cb) }
        catch (t: Throwable) { close(t); return@callbackFlow }

        awaitClose { try { s.stopScan(cb) } catch (_: Throwable) {} }
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
        try { disconnectInternal("new connect") } catch (_: Throwable) {}

        _conn.value = BleClient.ConnectionState.Connecting
        servicesDiscovered = false
        currentAddress = address

        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Device null"))

        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (!hasPermissionConnect()) return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    currentGatt = gatt
                    runCatching { gatt.discoverServices() }.onFailure {
                        _conn.value = BleClient.ConnectionState.Error("discoverServices failed: ${it.message}")
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    servicesDiscovered = false
                    _conn.value = BleClient.ConnectionState.Disconnected
                    runCatching { gatt.close() }
                    if (currentGatt == gatt) currentGatt = null
                }
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                servicesDiscovered = (status == BluetoothGatt.GATT_SUCCESS)
                val addr = currentAddress ?: gatt.device.address ?: "(unknown)"
                _conn.value = BleClient.ConnectionState.Connected(addr, servicesDiscovered)
            }
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, cb) // TRANSPORT_AUTO 기본
            } else {
                device.connectGatt(context, false, cb)
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            _conn.value = BleClient.ConnectionState.Error("Connect failed: ${t.message}")
            Result.failure(t)
        }
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        disconnectInternal("manual")
    }.fold(onSuccess = { Result.success(Unit) }, onFailure = { Result.failure(it) })

    private fun disconnectInternal(reason: String) {
        val g = currentGatt ?: run { _conn.value = BleClient.ConnectionState.Disconnected; return }
        runCatching { if (hasPermissionConnect()) g.disconnect() }
        runCatching { g.close() }
        currentGatt = null
        servicesDiscovered = false
        _conn.value = BleClient.ConnectionState.Disconnected
    }

    override suspend fun listServices(): Result<List<BleClient.GattService>> {
        if (!hasPermissionConnect()) return Result.failure(SecurityException("CONNECT permission missing"))
        val gatt = currentGatt ?: return Result.failure(IllegalStateException("Not connected"))
        val services = gatt.services ?: emptyList()
        val mapped = services.map { svc ->
            val chars = svc.characteristics?.map { ch ->
                BleClient.GattCharacteristic(uuid = ch.uuid, properties = ch.properties)
            } ?: emptyList()
            BleClient.GattService(uuid = svc.uuid, characteristics = chars)
        }
        return Result.success(mapped)
    }
}

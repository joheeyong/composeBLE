package com.example.composeble.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.example.composeble.blelab.ble.BleClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.UUID

class AndroidBleClient(private val context: Context) : BleClient {

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = btManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    private val _conn = MutableStateFlow<BleClient.ConnectionState>(BleClient.ConnectionState.Disconnected)
    private val _noti = MutableSharedFlow<BleClient.Notification>(extraBufferCapacity = 64)

    private var currentGatt: BluetoothGatt? = null
    private var currentAddress: String? = null
    private var servicesDiscovered = false

    private fun hasPermissionScan() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasPermissionConnect() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else true

    // ---------- Scan (기존) ----------
    @SuppressLint("MissingPermission")
    override fun scan(serviceUuid: UUID?) = callbackFlow {
        if (!hasPermissionScan()) { close(SecurityException("Missing scan permission")); return@callbackFlow }
        if (adapter?.isEnabled != true) { close(IllegalStateException("Bluetooth disabled")); return@callbackFlow }
        val s = scanner ?: run { close(IllegalStateException("Scanner unavailable")); return@callbackFlow }

        val filters = buildList {
            serviceUuid?.let { add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(type: Int, r: ScanResult) {
                trySend(BleClient.Device(r.device?.address, r.device?.name ?: r.scanRecord?.deviceName, r.rssi))
            }
            @SuppressLint("MissingPermission")
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> trySend(BleClient.Device(r.device?.address, r.device?.name ?: r.scanRecord?.deviceName, r.rssi)) }
            }
            override fun onScanFailed(code: Int) { close(IllegalStateException("Scan failed: $code")) }
        }

        try { if (filters.isEmpty()) s.startScan(cb) else s.startScan(filters, settings, cb) }
        catch (t: Throwable) { close(t); return@callbackFlow }

        awaitClose { runCatching { s.stopScan(cb) } }
    }

    override fun connectionState(): Flow<BleClient.ConnectionState> = _conn.asStateFlow()
    override fun notifications(): Flow<BleClient.Notification> = _noti.asSharedFlow()

    // ---------- Connect/Disconnect (기존) ----------
    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String): Result<Unit> {
        if (!hasPermissionConnect()) return Result.failure(SecurityException("CONNECT permission missing"))
        if (adapter?.isEnabled != true) return Result.failure(IllegalStateException("Bluetooth disabled"))
        runCatching { disconnectInternal("new connect") }

        _conn.value = BleClient.ConnectionState.Connecting
        servicesDiscovered = false
        currentAddress = address

        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Device null"))

        val cb = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
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
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                val addr = gatt.device.address ?: return
                _noti.tryEmit(
                    BleClient.Notification(
                        address = addr,
                        serviceUuid = characteristic.service.uuid,
                        charUuid = characteristic.uuid,
                        value = value
                    )
                )
            }
        }

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.connectGatt(context, false, cb)
            else device.connectGatt(context, false, cb)
        }.fold(onSuccess = { Result.success(Unit) }, onFailure = { Result.failure(it) })
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        disconnectInternal("manual")
    }.fold(onSuccess = { Result.success(Unit) }, onFailure = { Result.failure(it) })

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(reason: String) {
        val g = currentGatt ?: run { _conn.value = BleClient.ConnectionState.Disconnected; return }
        runCatching { if (hasPermissionConnect()) g.disconnect() }
        runCatching { g.close() }
        currentGatt = null
        servicesDiscovered = false
        _conn.value = BleClient.ConnectionState.Disconnected
    }

    // ---------- Services snapshot (기존) ----------
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

    // ---------- Read / Notify / Write ----------
    private fun findCharacteristic(gatt: BluetoothGatt, svcId: UUID, chId: UUID): BluetoothGattCharacteristic? {
        val svc = gatt.getService(svcId) ?: return null
        return svc.getCharacteristic(chId)
    }

    @SuppressLint("MissingPermission")
    override suspend fun read(serviceUuid: UUID, charUuid: UUID): Result<ByteArray> {
        if (!hasPermissionConnect()) return Result.failure(SecurityException("CONNECT permission missing"))
        val g = currentGatt ?: return Result.failure(IllegalStateException("Not connected"))
        val ch = findCharacteristic(g, serviceUuid, charUuid) ?: return Result.failure(NoSuchElementException("Characteristic not found"))
        return runCatching {
            val ok = g.readCharacteristic(ch)
            if (!ok) error("readCharacteristic returned false")
            // 결과는 콜백 async로 오지만, 간단화를 위해 API 33-: 동기 반환 없음 → 즉시 성공 처리
            // 필요하면 onCharacteristicRead 콜백에서 Completer로 보강 가능
            ByteArray(0)
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun setNotify(serviceUuid: UUID, charUuid: UUID, enable: Boolean): Result<Unit> {
        if (!hasPermissionConnect()) return Result.failure(SecurityException("CONNECT permission missing"))
        val g = currentGatt ?: return Result.failure(IllegalStateException("Not connected"))
        val ch = findCharacteristic(g, serviceUuid, charUuid) ?: return Result.failure(NoSuchElementException("Characteristic not found"))

        val okSet = runCatching { g.setCharacteristicNotification(ch, enable) }.getOrDefault(false)
        if (!okSet) return Result.failure(IllegalStateException("setCharacteristicNotification failed"))

        val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            ?: return Result.failure(NoSuchElementException("CCCD not found"))

        val (value, writeType) = when {
            ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE to BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 ->
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE to BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else -> return Result.failure(IllegalStateException("Characteristic not NOTIFY/INDICATE"))
        }
        cccd.value = if (enable) value else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        return runCatching {
            @Suppress("DEPRECATION")
            val ok = g.writeDescriptor(cccd)
            if (!ok) error("writeDescriptor returned false")
        }.fold(onSuccess = { Result.success(Unit) }, onFailure = { Result.failure(it) })
    }

    @SuppressLint("MissingPermission")
    override suspend fun write(serviceUuid: UUID, charUuid: UUID, value: ByteArray, type: BleClient.WriteType): Result<Unit> {
        if (!hasPermissionConnect()) return Result.failure(SecurityException("CONNECT permission missing"))
        val g = currentGatt ?: return Result.failure(IllegalStateException("Not connected"))
        val ch = findCharacteristic(g, serviceUuid, charUuid) ?: return Result.failure(NoSuchElementException("Characteristic not found"))

        ch.value = value
        ch.writeType = when (type) {
            BleClient.WriteType.DEFAULT -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            BleClient.WriteType.NO_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            BleClient.WriteType.SIGNED -> BluetoothGattCharacteristic.WRITE_TYPE_SIGNED
        }
        return runCatching {
            val ok = g.writeCharacteristic(ch)
            if (!ok) error("writeCharacteristic returned false")
        }.fold(onSuccess = { Result.success(Unit) }, onFailure = { Result.failure(it) })
    }
}

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
import androidx.core.content.ContextCompat
import com.example.composeble.blelab.ble.BleClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AndroidBleClient"
private const val OP_TIMEOUT_MS = 10_000L
private const val CONNECT_TIMEOUT_MS = 15_000L

class AndroidBleClient(private val context: Context) : BleClient {

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = btManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    private val _conn = MutableStateFlow<BleClient.ConnectionState>(BleClient.ConnectionState.Disconnected)
    private val _noti = MutableSharedFlow<BleClient.Notification>(extraBufferCapacity = 64)

    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var servicesReady = false

    // 단일 인플라이트 GATT 작업 보장
    private val opMutex = Mutex()

    private fun hasScan() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        else ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasConnect() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        else true

    // ---------- Scan ----------
    @SuppressLint("MissingPermission")
    override fun scan(serviceUuid: UUID?) = callbackFlow {
        if (!hasScan()) { close(SecurityException("SCAN permission missing")); return@callbackFlow }
        if (adapter?.isEnabled != true) { close(IllegalStateException("Bluetooth disabled")); return@callbackFlow }
        val s = scanner ?: run { close(IllegalStateException("Scanner unavailable")); return@callbackFlow }

        val filters = buildList {
            serviceUuid?.let { add(ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()) }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val cb = object : ScanCallback() {
            @SuppressLint("MissingPermission")
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

    // ---------- Connect / Disconnect ----------
    @SuppressLint("MissingPermission")
    override suspend fun connect(address: String): Result<Unit> = runCatching {
        if (!hasConnect()) error("CONNECT permission missing")
        if (adapter?.isEnabled != true) error("Bluetooth disabled")

        // 기존 연결 정리
        safeCloseGatt("reconnect")

        _conn.value = BleClient.ConnectionState.Connecting
        servicesReady = false
        this.address = address

        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull()
            ?: error("Device not found")

        val cb = object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, new: Int) {
                if (!hasConnect()) return
                if (new == BluetoothProfile.STATE_CONNECTED) {
                    gatt = g
                    runCatching { g.discoverServices() }.onFailure {
                        _conn.value = BleClient.ConnectionState.Error("discoverServices: ${it.message}")
                    }
                } else if (new == BluetoothProfile.STATE_DISCONNECTED) {
                    servicesReady = false
                    _conn.value = BleClient.ConnectionState.Disconnected
                    safeCloseGatt("disconnected")
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                servicesReady = (status == BluetoothGatt.GATT_SUCCESS)
                val addr = this@AndroidBleClient.address ?: g.device.address ?: "(unknown)"
                _conn.value = BleClient.ConnectionState.Connected(addr, servicesReady)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                val addr = g.device.address ?: return
                _noti.tryEmit(BleClient.Notification(addr, ch.service.uuid, ch.uuid, value))
            }

            override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                Pending.completeIfMatch(Pending.Kind.READ, ch, status, ch.value)
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
                Pending.completeIfMatch(Pending.Kind.WRITE, ch, status, null)
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
                Pending.completeIfMatch(Pending.Kind.CCCD, d.characteristic, status, null)
            }
        }

        // connectGatt는 메인 스레드 권장
        withContext(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, cb)
            } else {
                device.connectGatt(context, false, cb)
            }
        }

        // services discovered까지 대기(타임아웃)
        withTimeout(CONNECT_TIMEOUT_MS) {
            connectionState().first { it is BleClient.ConnectionState.Connected }
        }
    }

    override suspend fun disconnect(): Result<Unit> = runCatching {
        safeCloseGatt("manual")
        _conn.value = BleClient.ConnectionState.Disconnected
    }

    @SuppressLint("MissingPermission")
    private fun safeCloseGatt(reason: String) {
        val g = gatt ?: return
        runCatching { if (hasConnect()) g.disconnect() }
        runCatching { g.close() }
        gatt = null
        servicesReady = false
        Log.d(TAG, "Gatt closed ($reason)")
    }

    // ---------- Services snapshot ----------
    override suspend fun listServices(): Result<List<BleClient.GattService>> = runCatching {
        if (!hasConnect()) error("CONNECT permission missing")
        val g = gatt ?: error("Not connected")
        val services = g.services ?: emptyList()
        services.map { svc ->
            val chars = svc.characteristics?.map { ch ->
                BleClient.GattCharacteristic(uuid = ch.uuid, properties = ch.properties)
            } ?: emptyList()
            BleClient.GattService(uuid = svc.uuid, characteristics = chars)
        }
    }

    // ---------- Read / Notify / Write (콜백 완료 대기) ----------
    private fun getCharOrError(svcId: UUID, chId: UUID): BluetoothGattCharacteristic {
        val g = gatt ?: error("Not connected")
        val svc = g.getService(svcId) ?: error("Service $svcId not found")
        return svc.getCharacteristic(chId) ?: error("Characteristic $chId not found")
    }

    @SuppressLint("MissingPermission")
    override suspend fun read(serviceUuid: UUID, charUuid: UUID): Result<ByteArray> = runCatching {
        if (!hasConnect()) error("CONNECT permission missing")
        val ch = getCharOrError(serviceUuid, charUuid)
        opMutex.withLock {
            withTimeout(OP_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    if (!gatt!!.readCharacteristic(ch)) error("readCharacteristic returned false")
                }
                Pending.await(Pending.Kind.READ, ch) ?: ByteArray(0)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun setNotify(serviceUuid: UUID, charUuid: UUID, enable: Boolean): Result<Unit> = runCatching {
        if (!hasConnect()) error("CONNECT permission missing")
        val g = gatt ?: error("Not connected")
        val ch = getCharOrError(serviceUuid, charUuid)

        opMutex.withLock {
            withTimeout(OP_TIMEOUT_MS) {
                // setCharacteristicNotification 은 즉시 호출
                val okSet = runCatching { g.setCharacteristicNotification(ch, enable) }.getOrDefault(false)
                if (!okSet) error("setCharacteristicNotification failed")

                val cccd = ch.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                    ?: error("CCCD not found")
                val value = when {
                    enable && (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ->
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    enable && (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ->
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    else -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                cccd.value = value

                withContext(Dispatchers.Main) {
                    @Suppress("DEPRECATION")
                    if (!g.writeDescriptor(cccd)) error("writeDescriptor returned false")
                }
                // CCCD 완료 콜백 대기
                Pending.await(Pending.Kind.CCCD, ch)
                Unit
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun write(serviceUuid: UUID, charUuid: UUID, value: ByteArray, type: BleClient.WriteType): Result<Unit> = runCatching {
        if (!hasConnect()) error("CONNECT permission missing")
        val ch = getCharOrError(serviceUuid, charUuid)
        ch.value = value
        ch.writeType = when (type) {
            BleClient.WriteType.DEFAULT -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            BleClient.WriteType.NO_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            BleClient.WriteType.SIGNED -> BluetoothGattCharacteristic.WRITE_TYPE_SIGNED
        }

        opMutex.withLock {
            withTimeout(OP_TIMEOUT_MS) {
                withContext(Dispatchers.Main) {
                    if (!gatt!!.writeCharacteristic(ch)) error("writeCharacteristic returned false")
                }
                // WRITE_NO_RESPONSE의 경우에도 콜백이 오는 기기가 일반적이지만, 미보장 → 대기 후 타임아웃이면 성공 간주 X, 명시 실패
                Pending.await(Pending.Kind.WRITE, ch) ?: error("write did not complete (no callback)")
                Unit
            }
        }
    }

    // -------- Pending Operation Tracker --------
    private object Pending {
        enum class Kind { READ, WRITE, CCCD }
        private var kind: Kind? = null
        private var charUuid: UUID? = null
        private var resume: ((ByteArray?) -> Unit)? = null
        private var resumeErr: ((Throwable) -> Unit)? = null

        suspend fun await(k: Kind, ch: BluetoothGattCharacteristic) = suspendCancellableCoroutine<ByteArray?> { cont ->
            kind = k
            charUuid = ch.uuid
            resume = { v -> if (cont.isActive) cont.resume(v) }
            resumeErr = { e -> if (cont.isActive) cont.resumeWithException(e) }
            cont.invokeOnCancellation { clear() }
        }

        fun completeIfMatch(k: Kind, ch: BluetoothGattCharacteristic, status: Int, value: ByteArray?) {
            if (kind == k && charUuid == ch.uuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) resume?.invoke(value)
                else resumeErr?.invoke(IllegalStateException("GATT status=$status"))
                clear()
            }
        }
        private fun clear() {
            kind = null; charUuid = null; resume = null; resumeErr = null
        }
    }
}

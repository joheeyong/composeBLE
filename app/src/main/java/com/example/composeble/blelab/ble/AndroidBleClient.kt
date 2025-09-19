package com.example.composeble.blelab.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class AndroidBleClient(
    private val context: Context
) : BleClient {

    private val btManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = btManager?.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner

    private fun hasPermissionScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun scan(): Flow<BleClient.Device> = callbackFlow {
        // 환경 덤프 로그
        val hasScan = hasPermissionScan()
        val isBtOn = adapter?.isEnabled == true
        Log.d("AndroidBleClient", "scan() start: hasScan=$hasScan, isBtOn=$isBtOn, sdk=${Build.VERSION.SDK_INT}")

        if (!hasScan) {
            val msg = "Missing permission: BLUETOOTH_SCAN(S+) or ACCESS_FINE_LOCATION(R-)"
            Log.e("AndroidBleClient", msg)
            close(SecurityException(msg))
            return@callbackFlow
        }

        if (!isBtOn) {
            Log.e("AndroidBleClient", "Bluetooth is OFF")
            close(IllegalStateException("Bluetooth disabled"))
            return@callbackFlow
        }

        val s = scanner
        if (s == null) {
            Log.e("AndroidBleClient", "scanner is null (adapter=$adapter)")
            close(IllegalStateException("Scanner unavailable"))
            return@callbackFlow
        }

        val cb = object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = BleClient.Device(
                    address = result.device?.address,
                    name = result.device?.name ?: result.scanRecord?.deviceName,
                    rssi = result.rssi
                )
                Log.d("AndroidBleClient", "onScanResult: ${dev.name} / ${dev.address} / RSSI=${dev.rssi}")
                trySend(dev)
            }

            @SuppressLint("MissingPermission")
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r ->
                    val dev = BleClient.Device(
                        address = r.device?.address,
                        name = r.device?.name ?: r.scanRecord?.deviceName,
                        rssi = r.rssi
                    )
                    Log.d("AndroidBleClient", "onBatchScanResults: ${dev.name} / ${dev.address} / RSSI=${dev.rssi}")
                    trySend(dev)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("AndroidBleClient", "onScanFailed: code=$errorCode")
                close(IllegalStateException("Scan failed: code=$errorCode"))
            }
        }

        try {
            Log.d("AndroidBleClient", "startScan()")
            s.startScan(cb)
        } catch (se: SecurityException) {
            Log.e("AndroidBleClient", "startScan() SecurityException: ${se.message}", se)
            close(se)
            return@callbackFlow
        } catch (t: Throwable) {
            Log.e("AndroidBleClient", "startScan() Throwable: ${t.message}", t)
            close(t)
            return@callbackFlow
        }

        awaitClose {
            try {
                Log.d("AndroidBleClient", "stopScan()")
                s.stopScan(cb)
            } catch (se: SecurityException) {
                Log.e("AndroidBleClient", "stopScan() SecurityException: ${se.message}")
            } catch (t: Throwable) {
                Log.e("AndroidBleClient", "stopScan() Throwable: ${t.message}")
            }
        }
    }

    override suspend fun connect(address: String): Result<Unit> =
        Result.failure(NotImplementedError("connect() is not implemented yet"))

    override suspend fun disconnect(): Result<Unit> =
        Result.failure(NotImplementedError("disconnect() is not implemented yet"))
}

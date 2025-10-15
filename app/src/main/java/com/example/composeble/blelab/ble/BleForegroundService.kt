package com.example.composeble.blelab.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.composeble.ble.AndroidBleClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class BleForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "ble_foreground"
        private const val CHANNEL_NAME = "BLE 연결 유지"
        private const val NOTI_ID = 1001

        const val ACTION_START = "com.example.composeble.action.START_FG"
        const val ACTION_STOP = "com.example.composeble.action.STOP_FG"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var reconnectJob: Job? = null

    private val ble: BleClient by lazy {
        // Home/Detail에서 공유한 인스턴스 재사용, 없으면 새로 생성
        BleClients.shared ?: AndroidBleClient(applicationContext).also { BleClients.shared = it }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAsForeground()
            ACTION_STOP -> stopSelf()
            else -> startAsForeground()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        createChannel()
        startForeground(NOTI_ID, buildNotification(content = "연결 대기 중"))

        // 연결 상태 구독
        scope.launch {
            ble.connectionState().collectLatest { st ->
                when (st) {
                    is BleClient.ConnectionState.Connected -> {
                        updateNotification("연결됨: ${st.address}")
                        // 연결 중이면 재연결 루프 중단
                        reconnectJob?.cancel(); reconnectJob = null
                        BleClients.remember(st.address)
                    }
                    is BleClient.ConnectionState.Connecting -> {
                        updateNotification("연결 중…")
                    }
                    is BleClient.ConnectionState.Disconnected -> {
                        updateNotification("연결 끊김")
                        maybeScheduleReconnect()
                    }
                    is BleClient.ConnectionState.Error -> {
                        updateNotification("에러: ${st.message}")
                        maybeScheduleReconnect()
                    }
                }
            }
        }

        // 서비스 시작 시점에도 필요하면 재연결 시도
        maybeScheduleReconnect()
    }

    private fun maybeScheduleReconnect() {
        if (!BleClients.autoReconnect) return
        val target = BleClients.lastAddress ?: return
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            var delayMs = 2_000L
            while (isActive && BleClients.autoReconnect) {
                val st = withTimeoutOrNull(15_000L) { ble.connect(target) }
                // connect()는 즉시 Result 반환, 실제 연결 완료는 콜백 통해 반영됨
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(content: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("ComposeBLE")
            .setContentText(content)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val n = buildNotification(text)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID, n)
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        scope.cancel()
    }
}

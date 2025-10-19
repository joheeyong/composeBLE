package com.example.composeble.blelab.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("composeble_settings", Context.MODE_PRIVATE)

    object Keys {
        const val SERVICE_UUID = "service_uuid"
        const val TIMEOUT_SEC = "timeout_sec"
        const val AUTO_RECONNECT = "auto_reconnect"
        const val LOG_LEVEL = "log_level" // "ERROR","WARN","INFO","DEBUG"
    }

    fun getServiceUuid(): String? = prefs.getString(Keys.SERVICE_UUID, null)
    fun setServiceUuid(v: String?) = prefs.edit { putString(Keys.SERVICE_UUID, v?.trim().orEmpty()) }

    fun getTimeoutSec(): Int = prefs.getInt(Keys.TIMEOUT_SEC, 10)
    fun setTimeoutSec(v: Int) = prefs.edit { putInt(Keys.TIMEOUT_SEC, v.coerceIn(3, 60)) }

    fun getAutoReconnect(): Boolean = prefs.getBoolean(Keys.AUTO_RECONNECT, false)
    fun setAutoReconnect(v: Boolean) = prefs.edit { putBoolean(Keys.AUTO_RECONNECT, v) }

    fun getLogLevel(): String = prefs.getString(Keys.LOG_LEVEL, "INFO") ?: "INFO"
    fun setLogLevel(v: String) = prefs.edit { putString(Keys.LOG_LEVEL, v) }
}

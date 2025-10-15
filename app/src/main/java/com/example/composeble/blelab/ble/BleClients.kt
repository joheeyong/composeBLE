package com.example.composeble.blelab.ble

object BleClients {
    @Volatile var shared: BleClient? = null

    @Volatile var lastAddress: String? = null
    @Volatile var autoReconnect: Boolean = false

    fun remember(address: String) { lastAddress = address }
}
package com.example.composeble.blelab.ble


object BleClients {
    @Volatile var shared: BleClient? = null
}
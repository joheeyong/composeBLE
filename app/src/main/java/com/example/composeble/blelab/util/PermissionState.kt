package com.example.composeble.blelab.util

data class PermissionState(
    val hasScan: Boolean,
    val hasConnect: Boolean,
    val hasLocationLegacy: Boolean
)

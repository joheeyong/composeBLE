package com.example.composeble.blelab.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.view.ContextThemeWrapper

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextThemeWrapper -> baseContext.findActivity()
    else -> null
}

fun hasPermission(context: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

/**
 * 현재 미허용 권한 중, ‘다시 묻지 않음’(영구 거부) 여부를 판단
 */
fun isPermanentlyDenied(context: Context, missing: List<String>): Boolean {
    val activity = context.findActivity() ?: return false
    // 하나라도 “다시 묻지 않음”이면 영구 거부로 간주
    return missing.isNotEmpty() && missing.all { p ->
        // granted면 false, not granted면서 shouldShow=false면 영구 거부
        !hasPermission(context, p) && !ActivityCompat.shouldShowRequestPermissionRationale(activity, p)
    }
}

/**
 * Android 버전에 따른 요구 권한 목록을 반환
 */
fun requiredPermsForScan(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)

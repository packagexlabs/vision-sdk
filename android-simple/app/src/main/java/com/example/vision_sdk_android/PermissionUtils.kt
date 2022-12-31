package com.example.vision_sdk_android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class PermissionUtils(private val activity: Activity) {

    fun getRuntimePermissions() {
        val permissionsToRequest = ArrayList<String>()
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission.let {
                if (!isPermissionGranted(it)) {
                    permissionsToRequest.add(permission)
                }
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity, permissionsToRequest.toTypedArray(), PERMISSION_REQUESTS
            )
        }
    }

    fun allRuntimePermissionsGranted(): Boolean {
        for (permission in REQUIRED_RUNTIME_PERMISSIONS) {
            permission.let {
                if (!isPermissionGranted(it)) {
                    return false
                }
            }
        }
        return true
    }

    private fun isPermissionGranted(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(
                activity, permission
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "Permission granted")
            return true
        }
        Log.i(TAG, "Permission NOT granted")
        return false
    }

    companion object {
        private const val TAG = "MLBarcodeScanner"
        private const val PERMISSION_REQUESTS = 1
        private val REQUIRED_RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }
}
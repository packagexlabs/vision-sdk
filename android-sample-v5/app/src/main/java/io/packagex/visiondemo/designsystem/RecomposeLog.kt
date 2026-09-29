package io.packagex.visiondemo.designsystem

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import io.packagex.visiondemo.BuildConfig

/**
 * Debug-only recomposition counter: logs `"<name> <n>"` under tag [TAG] each time the calling
 * composable's scope (re)composes. Off by default, even in debug builds; turn it on before launching
 * the app with `adb shell setprop log.tag.Recompose DEBUG` (read once per process).
 */
@Composable
fun RecomposeLog(name: String) {
    if (!enabled) return
    val count = remember { IntArray(1) }
    SideEffect { Log.d(TAG, "$name ${++count[0]}") }
}

private const val TAG = "Recompose"
private val enabled by lazy { BuildConfig.DEBUG && Log.isLoggable(TAG, Log.DEBUG) }

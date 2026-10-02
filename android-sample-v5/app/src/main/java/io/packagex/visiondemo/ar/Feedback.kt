package io.packagex.visiondemo.ar

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import io.packagex.arcount.CountView

/** Shortest time between two count ticks (spec 5.5, Feedback) */
internal const val COUNT_TICK_MIN_MS = 500L

/** The total counted that the worker sees: the sum of countLow over the items, or the bracket's when there are none. */
fun countedTotal(view: CountView): Int =
    if (view.items.isNotEmpty()) view.items.sumOf { it.countLow } else view.bracket?.countLow ?: 0

/**
 * When the count ticks (spec 5.5, Feedback): once when the total counted goes up, at most once per [minIntervalMs]
 * however many units a burst counts. A rise inside the interval ticks on the first view after it; a fall (a restart, a
 * new scan) owes nothing. Used on one thread.
 */
class CountTick(private val minIntervalMs: Long = COUNT_TICK_MIN_MS) {
    private var last = 0
    private var owed = false
    private var lastTickMs = Long.MIN_VALUE

    /** The newest [view], at [nowMs]: true when it ticks now. */
    fun onView(view: CountView, nowMs: Long): Boolean = onTotal(countedTotal(view), nowMs)

    fun onTotal(total: Int, nowMs: Long): Boolean {
        if (total > last) owed = true
        if (total < last) owed = false
        last = total
        if (!owed) return false
        if (lastTickMs != Long.MIN_VALUE && nowMs - lastTickMs < minIntervalMs) return false
        owed = false
        lastTickMs = nowMs
        return true
    }
}

/** One short haptic tick, and nothing else: no sound. From any thread. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()?.takeIf { it.hasVibrator() }

    fun tick() {
        val v = vibrator ?: return
        runCatching { v.vibrate(VibrationEffect.createOneShot(TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE)) }
            .onFailure { Log.w(TAG, "no tick", it) }
    }

    private companion object {
        const val TAG = "ArFeedback"
        const val TICK_MS = 25L
    }
}

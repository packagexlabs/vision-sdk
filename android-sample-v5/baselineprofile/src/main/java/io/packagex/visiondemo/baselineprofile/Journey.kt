package io.packagex.visiondemo.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until

const val PACKAGE_NAME = "io.packagex.visiondemo"
private const val TIMEOUT_MS = 10_000L

/** Grant CAMERA up front so the runtime permission dialog never blocks the journey. */
fun MacrobenchmarkScope.grantCamera() {
    device.executeShellCommand("pm grant $packageName android.permission.CAMERA")
}

/** Cold start to the camera screen; the Settings button is the first chrome drawn over the preview. */
fun MacrobenchmarkScope.startToCamera() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.desc("Settings")), TIMEOUT_MS)
}

fun MacrobenchmarkScope.openAndCloseSettings() {
    dismissResult()
    device.findObject(By.desc("Settings"))?.click() ?: error("Settings button not found")
    // "Scanning" is the sheet's first section header.
    device.wait(Until.hasObject(By.text("Scanning")), TIMEOUT_MS) || error("Settings sheet did not open")
    device.findObject(By.desc("Close"))?.click()
    device.wait(Until.gone(By.text("Scanning")), TIMEOUT_MS)
}

/** A code in view auto-scans and the result drawer covers the chrome; dismiss it. */
private fun MacrobenchmarkScope.dismissResult() {
    device.findObject(By.text("New Scan"))?.run { click(); device.waitForIdle() }
}

/** Mode dial labels, in dial order (app `ScanMode`). */
private val DIAL = listOf("Barcode", "QR code", "Vision Scanner", "Price tag", "Item retrieval", "AR Barcode", "Document Acquisition")

/**
 * Selects [label] on the horizontally scrolling mode dial. Compose only exposes the labels that are on
 * screen (clipped ones with clipped bounds), so swipe the dial toward [label] until it sits fully inside.
 */
fun MacrobenchmarkScope.selectMode(label: String) {
    val target = DIAL.indexOf(label)
    repeat(10) {
        dismissResult()
        // The dial recomposes while it animates, so read every label's bounds once, up front.
        val visible = try {
            DIAL.mapNotNull { l -> device.findObject(By.text(l))?.let { l to it.visibleBounds } }
        } catch (_: StaleObjectException) {
            device.waitForIdle()
            return@repeat
        }
        check(visible.isNotEmpty()) { "Mode dial not on screen" }
        val w = device.displayWidth
        val b = visible.firstOrNull { it.first == label }?.second
        if (b != null && b.left > 0 && b.right < w - 1) {
            device.click(b.centerX(), b.centerY())
            // Let the mode's camera pipeline come up before moving on; each mode exercises different code.
            device.waitForIdle()
            Thread.sleep(1_500)
            return
        }
        val y = visible.first().second.centerY()
        // Target to the right of what's shown (or clipped at the right edge): drag the dial left, and vice versa.
        val forward = b?.let { it.centerX() > w / 2 } ?: (target > DIAL.indexOf(visible.last().first))
        // A slow, short drag (100 steps of ~5 ms) so the dial doesn't fling past the next label.
        if (forward) device.swipe(w * 2 / 3, y, w / 3, y, 100) else device.swipe(w / 3, y, w * 2 / 3, y, 100)
        device.waitForIdle()
    }
    error("Could not scroll mode '$label' into view")
}

package io.packagex.visiondemo.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

const val PACKAGE_NAME = "io.vision_sdk_android"
private const val TIMEOUT_MS = 10_000L

/** Grant CAMERA up front so the runtime permission dialog never blocks the journey. */
fun MacrobenchmarkScope.grantCamera() {
    device.executeShellCommand("pm grant $packageName android.permission.CAMERA")
}

/** Cold start to the module cards (v6 entry point). */
fun MacrobenchmarkScope.startToHome() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.text("SCAN CODES")), TIMEOUT_MS) || error("Module cards did not appear")
}

/** Cold start, then the Barcode camera; the Settings button is the first chrome drawn over the preview. */
fun MacrobenchmarkScope.startToCamera() {
    startToHome()
    selectMode("Barcode")
}

fun MacrobenchmarkScope.openAndCloseSettings() {
    dismissResult()
    device.findObject(By.desc("Settings"))?.click() ?: error("Settings button not found")
    // "Scanning" is the sheet's first section header.
    device.wait(Until.hasObject(By.text("Scanning")), TIMEOUT_MS) || error("Settings sheet did not open")
    device.findObject(By.desc("Close"))?.click() ?: error("Settings close button not found")
    device.wait(Until.gone(By.text("Scanning")), TIMEOUT_MS) || error("Settings sheet did not close")
}

/** Result drawer's dismiss button: "Scan next", or "New Scan" for AR (app ResultDrawer). */
private val SCAN_NEXT = Pattern.compile("Scan next|New Scan")

/** A code in view auto-scans and the result drawer covers the chrome; dismiss it. */
private fun MacrobenchmarkScope.dismissResult() {
    device.findObject(By.text(SCAN_NEXT))?.run { click(); device.waitForIdle() }
}

/**
 * Opens [label]'s camera from the module cards (v6: one camera per module), going back to the cards first
 * when a camera is open.
 */
fun MacrobenchmarkScope.selectMode(label: String) {
    dismissResult()
    device.findObject(By.desc("Back to modules"))?.click()
    device.wait(Until.hasObject(By.text("SCAN CODES")), TIMEOUT_MS) || error("Module cards did not appear")
    val card = device.findObject(By.text(label))
        ?: run { device.findObject(By.scrollable(true))?.scrollUntil(Direction.DOWN, Until.findObject(By.text(label))) }
        ?: error("Module card '$label' not found")
    card.click()
    device.wait(Until.hasObject(By.desc("Settings")), TIMEOUT_MS) || error("Camera for '$label' did not appear")
    device.waitForIdle()
}

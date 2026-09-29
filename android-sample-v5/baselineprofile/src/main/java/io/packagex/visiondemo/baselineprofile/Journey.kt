package io.packagex.visiondemo.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
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
    device.findObject(By.desc("Settings"))?.click() ?: error("Settings button not found")
    device.wait(Until.hasObject(By.desc("Close")), TIMEOUT_MS)
    device.findObject(By.desc("Close"))?.click()
    device.wait(Until.gone(By.desc("Close")), TIMEOUT_MS)
}

/**
 * Selects [label] on the horizontally scrolling mode dial. Labels outside the viewport are still in the
 * accessibility tree but clipped to it, so swipe the dial toward them until the label sits fully inside.
 */
fun MacrobenchmarkScope.selectMode(label: String) {
    repeat(6) {
        val item = device.findObject(By.text(label)) ?: error("Mode '$label' not on the dial")
        val dial = item.parent?.visibleBounds ?: error("Mode dial not found")
        val b = item.visibleBounds
        if (b.left > dial.left && b.right < dial.right) {
            item.click()
            // Let the mode's camera pipeline come up before moving on; each mode exercises different code.
            device.waitForIdle()
            Thread.sleep(1_500)
            return
        }
        item.parent.swipe(if (b.centerX() < dial.centerX()) Direction.RIGHT else Direction.LEFT, 0.3f)
        device.waitForIdle()
    }
    error("Could not scroll mode '$label' into view")
}

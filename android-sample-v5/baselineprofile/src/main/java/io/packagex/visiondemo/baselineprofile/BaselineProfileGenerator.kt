package io.packagex.visiondemo.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/release/generated/baselineProfiles/baseline-prof.txt (and startup-prof.txt):
 * `./gradlew :app:generateBaselineProfile` with a device connected (see README).
 *
 * AR Barcode is left out: ARCore session start-up under automation depends on Google Play Services for AR
 * being installed and up to date on the device, and hangs the journey when it isn't.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE_NAME, includeInStartupProfile = true) {
        grantCamera()
        startToCamera()
        openAndCloseSettings()
        listOf("QR code", "Vision Scanner", "Price tag", "Item retrieval", "Document Acquisition", "Barcode")
            .forEach { selectMode(it) }
    }
}

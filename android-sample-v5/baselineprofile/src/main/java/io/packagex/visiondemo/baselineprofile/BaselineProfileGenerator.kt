package io.packagex.visiondemo.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates app/src/release/generated/baselineProfiles/baseline-prof.txt:
 * `./gradlew :app:generateReleaseBaselineProfile` with a device connected (see README).
 *
 * AR Count is left out: ARCore session start-up under automation depends on Google Play Services for AR
 * being installed and up to date on the device, and hangs the journey when it isn't.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE_NAME) {
        // Not a startup profile (includeInStartupProfile): the journey goes well past startup, and a
        // startup profile covering all of it would only dilute the dex layout it drives.
        grantCamera()
        startToCamera()
        openAndCloseSettings()
        listOf("QR code", "Vision Scanner", "Price tag", "AR Item Count", "Document Acquisition", "Barcode")
            .forEach { selectMode(it) }
    }
}

package io.packagex.visiondemo.data

import io.packagex.visionsdk.exceptions.VisionSDKException
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanErrorTest {
    @Test fun noNetworkReadsLikeIos() = assertEquals(
        "Download failed. Check the connection.",
        ScanError.from(java.net.UnknownHostException()).message,
    )

    @Test fun entitlementIsNotLicensed() = assertEquals(
        "Not enabled for this key",
        ScanError.from(VisionSDKException.PriceTagNotEligible("x")).title,
    )

    @Test fun blurAsksToRetake() = assertEquals(
        "Image too blurry",
        ScanError.from(VisionSDKException.BlurImageDetected).title,
    )
}

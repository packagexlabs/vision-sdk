package io.packagex.visiondemo.data

import io.packagex.visionsdk.exceptions.VisionSDKException
import java.io.IOException

/**
 * User-facing scanner error copy, mapped from SDK/network exceptions.
 * Ported from iOS `Model/Types.swift`'s error alerts.
 */
sealed class ScanError(val title: String, val message: String) {
    data object Network : ScanError("Download failed", "Download failed. Check the connection.")
    data class NotLicensed(val detail: String) : ScanError("Not enabled for this key", detail)
    data object Blur : ScanError("Image too blurry", "Hold steady and try again.")
    data class Other(val detail: String) : ScanError("Scanner error", detail)

    companion object {
        fun from(e: Throwable): ScanError = when (e) {
            is IOException -> Network
            is VisionSDKException.PriceTagNotEligible,
            is VisionSDKException.ItemRetrievalNotEligible,
            is VisionSDKException.SubscriptionExpiredException -> NotLicensed(e.message ?: "Not enabled for this key")
            is VisionSDKException.BlurImageDetected -> Blur
            is VisionSDKException -> Other(e.errorMessage)
            else -> Other(e.message ?: "Unknown error")
        }
    }
}

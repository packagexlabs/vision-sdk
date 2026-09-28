package io.packagex.visiondemo.data

import io.packagex.visiondemo.model.ScanMode
import io.packagex.visionsdk.ui.views.VisionCameraView
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Price Tag / Item Retrieval entitlement check. The SDK has no standalone check: calling
 * `enablePriceTagMode` / `enableItemRetrievalMode` on the live camera view *is* the check
 * (cached license first, network refresh on miss).
 */
interface EntitlementRepository {
    suspend fun check(view: VisionCameraView, mode: ScanMode): Result<Unit>
}

@Singleton
class SdkEntitlementRepository @Inject constructor(
    private val secrets: Secrets,
) : EntitlementRepository {
    override suspend fun check(view: VisionCameraView, mode: ScanMode): Result<Unit> = runCatching {
        if (mode == ScanMode.Price) view.enablePriceTagMode(secrets.apiKey) else view.enableItemRetrievalMode(secrets.apiKey)
    }
}

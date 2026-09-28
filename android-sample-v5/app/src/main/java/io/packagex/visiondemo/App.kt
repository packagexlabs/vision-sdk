package io.packagex.visiondemo

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.packagex.visiondemo.data.Secrets
import io.packagex.visionsdk.Environment
import io.packagex.visionsdk.VisionSDK
import io.packagex.visionsdk.modelmanagement.api.ModelManager
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {
    @Inject lateinit var secrets: Secrets

    override fun onCreate() {
        super.onCreate()
        VisionSDK.getInstance().initialize(
            this,
            if (secrets.environment == "production") Environment.PRODUCTION else Environment.STAGING,
        )
        if (!ModelManager.isInitialized()) ModelManager.initialize(this) { maxConcurrentDownloads(2) }
    }
}

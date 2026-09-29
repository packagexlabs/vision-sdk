package io.packagex.visiondemo.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.packagex.visiondemo.BuildConfig
import io.packagex.visiondemo.data.Secrets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun secrets() = Secrets.pick(BuildConfig.VISION_ENV, BuildConfig.STAGING_API_KEY, BuildConfig.PRODUCTION_API_KEY)

    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

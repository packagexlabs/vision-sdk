package io.packagex.visiondemo.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.packagex.visiondemo.camera.Camera
import io.packagex.visiondemo.camera.CameraController
import io.packagex.visiondemo.data.DataStoreItemCatalogRepository
import io.packagex.visiondemo.data.DataStorePreferencesRepository
import io.packagex.visiondemo.data.EntitlementRepository
import io.packagex.visiondemo.data.ExtractionRepository
import io.packagex.visiondemo.data.ItemCatalogRepository
import io.packagex.visiondemo.data.ModelRepository
import io.packagex.visiondemo.data.PreferencesRepository
import io.packagex.visiondemo.data.ReportRepository
import io.packagex.visiondemo.data.SdkEntitlementRepository
import io.packagex.visiondemo.data.SdkExtractionRepository
import io.packagex.visiondemo.data.SdkModelRepository
import io.packagex.visiondemo.data.SdkReportRepository
import io.packagex.visiondemo.data.TextTemplates
import io.packagex.visiondemo.data.TextTemplatesService

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindPreferencesRepository(impl: DataStorePreferencesRepository): PreferencesRepository

    @Binds
    abstract fun bindEntitlementRepository(impl: SdkEntitlementRepository): EntitlementRepository

    @Binds
    abstract fun bindModelRepository(impl: SdkModelRepository): ModelRepository

    @Binds
    abstract fun bindExtractionRepository(impl: SdkExtractionRepository): ExtractionRepository

    @Binds
    abstract fun bindReportRepository(impl: SdkReportRepository): ReportRepository

    @Binds
    abstract fun bindCamera(impl: CameraController): Camera

    @Binds
    abstract fun bindTextTemplates(impl: TextTemplatesService): TextTemplates

    @Binds
    abstract fun bindItemCatalogRepository(impl: DataStoreItemCatalogRepository): ItemCatalogRepository
}

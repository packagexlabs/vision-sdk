package io.packagex.visiondemo.document

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DocumentModule {
    @Binds
    abstract fun bindDocumentCamera(impl: DocumentController): DocumentCamera
}

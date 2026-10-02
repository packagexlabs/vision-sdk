package io.packagex.visiondemo.ar

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ArModule {
    /** No AR Count session yet: the module shows no camera until the shared-camera session lands. */
    @Provides
    fun arCount(): ArCount = NoArCount
}

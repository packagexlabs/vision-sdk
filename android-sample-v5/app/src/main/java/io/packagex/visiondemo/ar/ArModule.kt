package io.packagex.visiondemo.ar

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object ArModule {
    @Provides
    fun arCount(impl: ArSessionController): ArCount = impl

    /** The counter of every AR Count session. Wave 1: [DebugCounter], until the counting core lands. */
    @Provides
    fun arCounterFactory(): ArCounterFactory = ArCounterFactory { DebugCounter() }
}

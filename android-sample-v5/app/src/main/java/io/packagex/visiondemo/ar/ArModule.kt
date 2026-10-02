package io.packagex.visiondemo.ar

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.packagex.arcount.CountingCore

@Module
@InstallIn(SingletonComponent::class)
object ArModule {
    @Provides
    fun arCount(impl: ArSessionController): ArCount = impl

    /** The counter of every AR session (AR Item Count): the counting core of `:arcount` (spec 5.1, 5.4) */
    @Provides
    fun arCounterFactory(): ArCounterFactory = ArCounterFactory { CountingCore() }
}

package com.lensqi.fieldops.wearables

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class GlassesModule {
    @Binds abstract fun glassesSource(impl: DatGlassesSource): GlassesSource

    /** Debug: Meta's Mock Device Kit. Release: a stub that cannot be enabled (src/release). */
    @Binds abstract fun mockGlasses(impl: MockGlassesImpl): MockGlasses
}

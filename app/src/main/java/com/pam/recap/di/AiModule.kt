package com.pam.recap.di

import com.pam.recap.core.di.OnDeviceEngine
import com.pam.recap.core.di.RemoteEngine
import com.pam.recap.data.ai.OnDeviceAnalysisEngine
import com.pam.recap.data.ai.RemoteAnalysisEngine
import com.pam.recap.domain.repository.AnalysisEngine
import com.pam.recap.domain.repository.AnalysisEngineConfiguration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * OI-07: two engines behind the same port. The on-device engine is always available; the remote
 * engine is only *used* when its configuration reports itself ready, and the request use case
 * falls back to the deterministic engine if the backend call fails.
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    @OnDeviceEngine
    fun provideOnDeviceEngine(engine: OnDeviceAnalysisEngine): AnalysisEngine = engine

    @Provides
    @Singleton
    @RemoteEngine
    fun provideRemoteEngine(engine: RemoteAnalysisEngine): AnalysisEngine = engine

    @Provides
    @Singleton
    fun provideRemoteConfiguration(engine: RemoteAnalysisEngine): AnalysisEngineConfiguration = engine
}

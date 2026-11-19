package com.pam.recap.di

import com.pam.recap.core.di.ApplicationScope
import com.pam.recap.core.di.DefaultDispatcher
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.SystemClockProvider
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.core.util.PamJson
import com.pam.recap.core.util.UuidGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Process-wide primitives: time, ids, dispatchers, JSON and the shared HTTP client. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideClockProvider(): ClockProvider = SystemClockProvider()

    @Provides
    @Singleton
    fun provideIdGenerator(): IdGenerator = UuidGenerator()

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    @Singleton
    fun provideJson(): Json = PamJson

    /**
     * NFR-03: plain TLS with the platform trust store — no `TrustManager` override, no debug
     * bypass. Timeouts are bounded so a stalled provider cannot hang a screen.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

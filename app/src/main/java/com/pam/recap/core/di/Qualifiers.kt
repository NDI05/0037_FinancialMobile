package com.pam.recap.core.di

import javax.inject.Qualifier

/** Background dispatcher for disk/network work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** CPU dispatcher for parsing and folding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Process-lifetime scope for fire-and-forget persistence (demo seeding, deletion cleanup). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Deterministic, offline, evidence-bound analysis engine (OI-07 default). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class OnDeviceEngine

/** Optional backend-backed analysis engine, enabled only when a URL is configured (OI-07). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RemoteEngine

package com.pam.recap.di

import com.pam.recap.data.widget.WidgetRefresher
import com.pam.recap.ui.widget.PamWidgetRefresher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** CR-010: the data-side coordinator asks this port to repaint every Glance widget. */
@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {

    @Binds
    @Singleton
    abstract fun bindWidgetRefresher(impl: PamWidgetRefresher): WidgetRefresher
}

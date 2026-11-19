package com.pam.recap.di

import android.content.Context
import androidx.room.Room
import com.pam.recap.core.security.DatabasePassphraseProvider
import com.pam.recap.data.local.PamDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        passphraseProvider: DatabasePassphraseProvider,
    ): PamDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(passphraseProvider.passphrase())
        return Room.databaseBuilder(context, PamDatabase::class.java, PamDatabase.DATABASE_NAME)
            .openHelperFactory(factory)
            .addMigrations()
            .build()
    }

    @Provides
    fun provideAppMetadataDao(database: PamDatabase) = database.appMetadataDao()
}

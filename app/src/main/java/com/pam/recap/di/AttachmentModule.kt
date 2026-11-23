package com.pam.recap.di

import android.content.Context
import com.pam.recap.data.attachment.PrivateAttachmentStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the private attachment root so the plain [File] is unambiguous for Hilt. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER)
annotation class AttachmentRoot

@Module
@InstallIn(SingletonComponent::class)
object AttachmentModule {

    @Provides
    @Singleton
    @AttachmentRoot
    fun attachmentRoot(@ApplicationContext context: Context): File =
        File(context.filesDir, "attachments")

    @Provides
    @Singleton
    fun privateAttachmentStore(@AttachmentRoot root: File): PrivateAttachmentStore =
        PrivateAttachmentStore(root)
}

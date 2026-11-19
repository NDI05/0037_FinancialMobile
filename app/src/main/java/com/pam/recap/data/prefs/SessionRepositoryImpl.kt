package com.pam.recap.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * FR-01/FR-05 session state in DataStore.
 *
 * Signing out removes every session key but keeps the per-owner demo marker, so signing back into
 * the same demo account does not re-seed (A9) — and even if it did, the fingerprint unique index
 * would make the seed idempotent.
 */
@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SessionRepository {

    override fun observe(): Flow<Session?> =
        dataStore.data.map { it.toSession() }.flowOn(ioDispatcher)

    override suspend fun current(): Session? = withContext(ioDispatcher) {
        dataStore.data.first().toSession()
    }

    override suspend fun signIn(session: Session): Unit = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            prefs[SessionKeys.OWNER_ID] = session.ownerId
            prefs[SessionKeys.SIGNED_IN_AT] = session.signedInAt.toEpochMilliseconds()
            prefs[SessionKeys.IS_DEMO] = session.isDemo
            session.displayName?.let { prefs[SessionKeys.DISPLAY_NAME] = it }
                ?: prefs.remove(SessionKeys.DISPLAY_NAME)
            session.mailboxLabel?.let { prefs[SessionKeys.MAILBOX_LABEL] = it }
                ?: prefs.remove(SessionKeys.MAILBOX_LABEL)
        }
    }

    override suspend fun updateMailbox(ownerId: String, mailboxLabel: String?): Unit =
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                if (prefs[SessionKeys.OWNER_ID] != ownerId) return@edit
                if (mailboxLabel.isNullOrBlank()) {
                    prefs.remove(SessionKeys.MAILBOX_LABEL)
                } else {
                    prefs[SessionKeys.MAILBOX_LABEL] = mailboxLabel
                }
            }
        }

    override suspend fun signOut(): Unit = withContext(ioDispatcher) {
        dataStore.edit { prefs ->
            prefs.remove(SessionKeys.OWNER_ID)
            prefs.remove(SessionKeys.DISPLAY_NAME)
            prefs.remove(SessionKeys.MAILBOX_LABEL)
            prefs.remove(SessionKeys.SIGNED_IN_AT)
            prefs.remove(SessionKeys.IS_DEMO)
        }
    }

    override suspend fun isDemoSeeded(ownerId: String): Boolean = withContext(ioDispatcher) {
        dataStore.data.first()[SessionKeys.demoSeeded(ownerId)] == true
    }

    override suspend fun markDemoSeeded(ownerId: String): Unit = withContext(ioDispatcher) {
        dataStore.edit { prefs -> prefs[SessionKeys.demoSeeded(ownerId)] = true }
    }

    private fun Preferences.toSession(): Session? {
        val ownerId = this[SessionKeys.OWNER_ID] ?: return null
        return Session(
            ownerId = ownerId,
            displayName = this[SessionKeys.DISPLAY_NAME],
            mailboxLabel = this[SessionKeys.MAILBOX_LABEL],
            signedInAt = this[SessionKeys.SIGNED_IN_AT]
                ?.let { Instant.fromEpochMilliseconds(it) }
                ?: Instant.fromEpochMilliseconds(0),
            isDemo = this[SessionKeys.IS_DEMO] ?: false,
        )
    }
}

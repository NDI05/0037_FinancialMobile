package com.pam.recap.data.repository

import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.data.local.ConsentDao
import com.pam.recap.data.local.DeletionDao
import com.pam.recap.data.local.GmailConnectionDao
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.DeletionRequest
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.repository.GmailConnectionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * FR-03/FR-49 consent ledger.
 *
 * The ledger lives in Room (not DataStore) because the architecture's schema requires
 * `ConsentEntity` with purpose/noticeVersion/decision/decidedAt and the consent-history screen
 * needs an append-only audit trail. See the Stage A report for this documented deviation from
 * "sessions and consent live in DataStore".
 */
@Singleton
class ConsentRepositoryImpl @Inject constructor(
    private val consentDao: ConsentDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ConsentRepository {

    override fun observeLedger(ownerId: String): Flow<List<Consent>> =
        consentDao.observeLedger(ownerId).map { rows -> rows.map { it.toDomain() } }.flowOn(ioDispatcher)

    override fun observeLatest(ownerId: String, purpose: ConsentPurpose): Flow<Consent?> =
        consentDao.observeLatest(ownerId, purpose.name).map { it?.toDomain() }.flowOn(ioDispatcher)

    override suspend fun latest(ownerId: String, purpose: ConsentPurpose): Consent? =
        withContext(ioDispatcher) { consentDao.latest(ownerId, purpose.name)?.toDomain() }

    override suspend fun append(consent: Consent): Unit = withContext(ioDispatcher) {
        consentDao.append(consent.toEntity())
    }

    /** A withdrawal is itself the newest entry, so "granted" is decided by the latest row only. */
    override suspend fun hasGranted(ownerId: String, purpose: ConsentPurpose): Boolean =
        withContext(ioDispatcher) {
            consentDao.latest(ownerId, purpose.name)?.decision == ConsentDecision.GRANTED
        }

    override suspend fun deleteAll(ownerId: String): Unit = withContext(ioDispatcher) {
        consentDao.deleteAll(ownerId)
    }
}

/** FR-07/FR-49 connection facts. */
@Singleton
class GmailConnectionRepositoryImpl @Inject constructor(
    private val gmailConnectionDao: GmailConnectionDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : GmailConnectionRepository {

    override fun observe(ownerId: String): Flow<GmailConnection?> =
        gmailConnectionDao.observe(ownerId).map { it?.toDomain() }.flowOn(ioDispatcher)

    override suspend fun get(ownerId: String): GmailConnection? = withContext(ioDispatcher) {
        gmailConnectionDao.find(ownerId)?.toDomain()
    }

    override suspend fun save(connection: GmailConnection): Unit = withContext(ioDispatcher) {
        gmailConnectionDao.upsert(connection.toEntity())
    }

    override suspend fun touchLastImport(ownerId: String, at: Instant): Unit = withContext(ioDispatcher) {
        gmailConnectionDao.touchLastImport(ownerId, at.toEpochMilliseconds())
    }

    override suspend fun touchLastAi(ownerId: String, at: Instant): Unit = withContext(ioDispatcher) {
        gmailConnectionDao.touchLastAi(ownerId, at.toEpochMilliseconds())
    }

    override suspend fun deleteAll(ownerId: String): Unit = withContext(ioDispatcher) {
        gmailConnectionDao.deleteAll(ownerId)
    }
}

/** FR-47/FR-48 deletion lifecycle. */
@Singleton
class DeletionRepositoryImpl @Inject constructor(
    private val deletionDao: DeletionDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DeletionRepository {

    override fun observeLatest(ownerId: String): Flow<DeletionRequest?> =
        deletionDao.observeLatest(ownerId).map { it?.toDomain() }.flowOn(ioDispatcher)

    override suspend fun latest(ownerId: String): DeletionRequest? = withContext(ioDispatcher) {
        deletionDao.latest(ownerId)?.toDomain()
    }

    override suspend fun active(ownerId: String): DeletionRequest? = withContext(ioDispatcher) {
        deletionDao.active(ownerId)?.toDomain()
    }

    override suspend fun save(request: DeletionRequest): Unit = withContext(ioDispatcher) {
        deletionDao.upsert(request.toEntity())
    }

    override suspend fun deleteAll(ownerId: String): Unit = withContext(ioDispatcher) {
        deletionDao.deleteAll(ownerId)
    }
}

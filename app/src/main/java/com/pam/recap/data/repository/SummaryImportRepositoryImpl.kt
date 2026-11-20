package com.pam.recap.data.repository

import androidx.room.withTransaction
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.local.ImportJobDao
import com.pam.recap.data.local.MonthlySummaryDao
import com.pam.recap.data.local.PamDatabase
import com.pam.recap.data.local.SkippedCandidateDao
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.SkippedCandidate
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SummaryRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** FR-30/FR-33 summary persistence. */
@Singleton
class SummaryRepositoryImpl @Inject constructor(
    private val database: PamDatabase,
    private val summaryDao: MonthlySummaryDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SummaryRepository {

    override fun observeSummary(ownerId: String, month: JakartaMonth): Flow<MonthlySummary?> =
        summaryDao.observeLatest(ownerId, month.id())
            .map { entity ->
                entity?.let { summaryDao.categoryTotals(it.id).let { totals -> it.toDomain(totals) } }
            }
            .flowOn(ioDispatcher)

    override suspend fun get(ownerId: String, month: JakartaMonth, inputRevision: Int?): MonthlySummary? =
        withContext(ioDispatcher) {
            val entity = if (inputRevision != null) {
                summaryDao.forRevision(ownerId, month.id(), inputRevision)
            } else {
                summaryDao.latest(ownerId, month.id())
            } ?: return@withContext null
            entity.toDomain(summaryDao.categoryTotals(entity.id))
        }

    override suspend fun save(summary: MonthlySummary): Unit = withContext(ioDispatcher) {
        database.withTransaction {
            summaryDao.upsert(summary.toEntity())
            // Replace the split atomically: a half-written split would misreport category totals.
            summaryDao.deleteCategoryTotals(summary.id)
            summaryDao.upsertCategoryTotals(summary.categoryTotalEntities())
        }
    }

    override suspend fun markOutdated(ownerId: String, month: JakartaMonth): Unit = withContext(ioDispatcher) {
        summaryDao.markOutdated(ownerId, month.id())
    }

    override suspend fun deleteAll(ownerId: String): Unit = withContext(ioDispatcher) {
        summaryDao.deleteAll(ownerId)
    }

    override suspend fun delete(ownerId: String, month: JakartaMonth): Int = withContext(ioDispatcher) {
        summaryDao.deleteForMonth(ownerId, month.id())
    }
}

/** FR-19/FR-21 import jobs and redacted skipped candidates. */
@Singleton
class ImportRepositoryImpl @Inject constructor(
    private val database: PamDatabase,
    private val importJobDao: ImportJobDao,
    private val skippedCandidateDao: SkippedCandidateDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ImportRepository {

    override suspend fun create(job: ImportJob): Unit = withContext(ioDispatcher) {
        importJobDao.upsert(job.toEntity())
    }

    override suspend fun update(job: ImportJob): Unit = withContext(ioDispatcher) {
        database.withTransaction { importJobDao.upsert(job.toEntity()) }
    }

    override fun observe(jobId: String): Flow<ImportJob?> =
        importJobDao.observe(jobId).map { it?.toDomain() }.flowOn(ioDispatcher)

    override suspend fun get(jobId: String): ImportJob? = withContext(ioDispatcher) {
        importJobDao.findById(jobId)?.toDomain()
    }

    override suspend fun latestForMonth(ownerId: String, month: JakartaMonth): ImportJob? =
        withContext(ioDispatcher) {
            importJobDao.latestForMonth(ownerId, month.id())?.toDomain()
        }

    override fun observeLatestForMonth(ownerId: String, month: JakartaMonth): Flow<ImportJob?> =
        importJobDao.observeLatestForMonth(ownerId, month.id())
            .map { it?.toDomain() }
            .flowOn(ioDispatcher)

    override suspend fun saveSkipped(candidates: List<SkippedCandidate>): Unit = withContext(ioDispatcher) {
        if (candidates.isEmpty()) return@withContext
        database.withTransaction {
            skippedCandidateDao.insertAll(candidates.map { it.toEntity() })
        }
    }

    override suspend fun skippedFor(jobId: String): List<SkippedCandidate> = withContext(ioDispatcher) {
        skippedCandidateDao.forJob(jobId).map { it.toDomain() }
    }

    override suspend fun deleteAll(ownerId: String): Unit = withContext(ioDispatcher) {
        database.withTransaction {
            skippedCandidateDao.deleteAll(ownerId)
            importJobDao.deleteAll(ownerId)
        }
    }

    override suspend fun delete(ownerId: String, jobId: String): Boolean = withContext(ioDispatcher) {
        importJobDao.deleteById(ownerId, jobId) > 0
    }
}

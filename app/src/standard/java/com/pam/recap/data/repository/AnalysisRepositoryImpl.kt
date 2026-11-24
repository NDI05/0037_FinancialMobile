package com.pam.recap.data.repository

import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.data.local.AnalysisDao
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.repository.AnalysisRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** FR-39/FR-40 analysis persistence. */
@Singleton
class AnalysisRepositoryImpl @Inject constructor(
    private val analysisDao: AnalysisDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AnalysisRepository {

    override fun observeLatest(ownerId: String, monthId: String): Flow<Analysis?> =
        analysisDao.observeLatest(ownerId, monthId).map { it?.toDomain() }.flowOn(ioDispatcher)

    override suspend fun latest(ownerId: String, monthId: String): Analysis? = withContext(ioDispatcher) {
        analysisDao.latest(ownerId, monthId)?.toDomain()
    }

    override suspend fun save(analysis: Analysis): Unit = withContext(ioDispatcher) {
        analysisDao.upsert(analysis.toEntity())
    }

    override suspend fun markOutdated(ownerId: String, monthId: String): Unit = withContext(ioDispatcher) {
        analysisDao.markOutdated(ownerId, monthId)
    }

    override suspend fun markAllHistorical(ownerId: String): Unit = withContext(ioDispatcher) {
        analysisDao.markAllHistorical(ownerId)
    }

    override suspend fun deleteAll(ownerId: String): Unit = withContext(ioDispatcher) {
        analysisDao.deleteAll(ownerId)
    }
}

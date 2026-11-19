package com.pam.recap.data.repository

import androidx.room.withTransaction
import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.data.local.AnalysisDao
import com.pam.recap.data.local.BudgetDao
import com.pam.recap.data.local.PamDatabase
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.BudgetPlan
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.BudgetRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

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

/** FR-42/FR-44 budget plan persistence over the plan + limit tables. */
@Singleton
class BudgetRepositoryImpl @Inject constructor(
    private val database: PamDatabase,
    private val budgetDao: BudgetDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BudgetRepository {

    override fun observeActive(ownerId: String, month: JakartaMonth): Flow<BudgetPlan?> =
        budgetDao.observePlan(ownerId, month.id())
            .map { entity ->
                entity?.let { plan -> budgetDao.limitsFor(plan.id).let { limits -> plan.toDomain(limits) } }
            }
            .flowOn(ioDispatcher)

    override suspend fun active(ownerId: String, month: JakartaMonth): BudgetPlan? =
        withContext(ioDispatcher) {
            val entity = budgetDao.findPlan(ownerId, month.id()) ?: return@withContext null
            entity.toDomain(budgetDao.limitsFor(entity.id))
        }

    override suspend fun save(plan: BudgetPlan): Unit = withContext(ioDispatcher) {
        database.withTransaction {
            budgetDao.upsertPlan(plan.toEntity())
            // Limits are replaced wholesale so a deleted category cannot linger at its old value.
            budgetDao.deleteLimits(plan.id)
            budgetDao.upsertLimits(plan.limitEntities())
        }
    }

    override suspend fun deleteAll(ownerId: String): Unit = withContext(ioDispatcher) {
        budgetDao.deleteAll(ownerId)
    }
}

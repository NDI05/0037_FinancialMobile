package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.repository.ServerAnalysis
import com.pam.recap.domain.repository.ServerAnalysisRepository

/** Test double for offline/local-mode screens: the remote analysis path is switched off. */
internal object DisabledServerAnalysisRepository : ServerAnalysisRepository {
    override val isEnabled: Boolean = false

    override suspend fun latest(ownerId: String, month: JakartaMonth): Outcome<ServerAnalysis?> =
        Outcome.Failure(AppError.Configuration("analysis_backend_not_configured"))

    override suspend fun generate(ownerId: String, month: JakartaMonth): Outcome<Unit> =
        Outcome.Failure(AppError.Configuration("analysis_backend_not_configured"))
}

package com.pam.recap.data.repository

import com.pam.recap.core.money.Money
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.PamJson
import com.pam.recap.data.local.AnalysisEntity
import com.pam.recap.data.local.ConsentEntity
import com.pam.recap.data.local.DeletionRequestEntity
import com.pam.recap.data.local.GmailConnectionEntity
import com.pam.recap.data.local.ImportJobEntity
import com.pam.recap.data.local.MonthTotalsRow
import com.pam.recap.data.local.MonthlySummaryEntity
import com.pam.recap.data.local.SkippedCandidateEntity
import com.pam.recap.data.local.SummaryCategoryTotalEntity
import com.pam.recap.data.local.TransactionEntity
import com.pam.recap.domain.model.Analysis
import com.pam.recap.domain.model.AnalysisUsage
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.DeletionRequest
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.MonthTotals
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Observation
import com.pam.recap.domain.model.SkippedCandidate
import com.pam.recap.domain.model.Suggestion
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.ValidationOutcome
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Instant

/**
 * Entity ⇄ domain mapping (§4/A8). Kept in one file so a schema change and its domain impact are
 * reviewed together.
 */

// --- Transactions ---------------------------------------------------------------------------

fun TransactionEntity.toDomain(): Transaction = Transaction(
    id = id,
    ownerId = ownerId,
    direction = direction,
    source = source,
    amount = amount,
    currency = currency,
    eventAt = eventAt,
    receivedAt = receivedAt,
    description = description,
    merchantLabel = merchantLabel,
    acquirer = acquirer,
    status = status,
    fingerprint = fingerprint,
    parserVersion = parserVersion,
    parserConfidence = parserConfidence,
    category = categoryCode,
    categoryOrigin = categoryOrigin,
    categoryConfidence = categoryConfidence,
    categoryUpdatedAt = categoryUpdatedAt,
    isInternalTransfer = isInternalTransfer,
    linkedRefundOf = linkedRefundOf,
    note = note,
    importJobId = importJobId,
    revision = revision,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Transaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    ownerId = ownerId,
    direction = direction,
    source = source,
    amount = amount,
    currency = currency,
    eventAt = eventAt,
    receivedAt = receivedAt,
    description = description,
    merchantLabel = merchantLabel,
    acquirer = acquirer,
    status = status,
    fingerprint = fingerprint,
    parserVersion = parserVersion,
    parserConfidence = parserConfidence,
    categoryCode = category,
    categoryOrigin = categoryOrigin,
    categoryConfidence = categoryConfidence,
    categoryUpdatedAt = categoryUpdatedAt,
    isInternalTransfer = isInternalTransfer,
    linkedRefundOf = linkedRefundOf,
    note = note,
    importJobId = importJobId,
    revision = revision,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun MonthTotalsRow.toDomain(): MonthTotals = MonthTotals(
    grossInflow = Money(grossInflowMinor),
    grossOutflow = Money(grossOutflowMinor),
    creditCount = creditCount,
    debitCount = debitCount,
    transactionCount = transactionCount,
    nonIdrCount = nonIdrCount,
    uncategorizedCount = uncategorizedCount,
    manualCount = manualCount,
    latestEventAt = latestEventAt?.let { Instant.fromEpochMilliseconds(it) },
    latestUpdatedAt = latestUpdatedAt?.let { Instant.fromEpochMilliseconds(it) },
)

// --- Import ---------------------------------------------------------------------------------

fun ImportJobEntity.toDomain(): ImportJob = ImportJob(
    id = id,
    ownerId = ownerId,
    month = JakartaMonth.parse(monthId) ?: JakartaMonth.of(startedAt),
    state = state,
    pagesProcessed = pagesProcessed,
    pagesExpected = pagesExpected,
    matchedCount = matchedCount,
    importedCount = importedCount,
    duplicateCount = duplicateCount,
    skippedCount = skippedCount,
    unresolvedCount = unresolvedCount,
    templateVersion = templateVersion,
    startedAt = startedAt,
    finishedAt = finishedAt,
    errorCode = errorCode,
)

fun ImportJob.toEntity(): ImportJobEntity = ImportJobEntity(
    id = id,
    ownerId = ownerId,
    monthId = month.id(),
    state = state,
    pagesProcessed = pagesProcessed,
    pagesExpected = pagesExpected,
    matchedCount = matchedCount,
    importedCount = importedCount,
    duplicateCount = duplicateCount,
    skippedCount = skippedCount,
    unresolvedCount = unresolvedCount,
    templateVersion = templateVersion,
    startedAt = startedAt,
    finishedAt = finishedAt,
    errorCode = errorCode,
)

fun SkippedCandidateEntity.toDomain(): SkippedCandidate = SkippedCandidate(
    id = id,
    jobId = jobId,
    ownerId = ownerId,
    reason = reasonCode,
    pageOrdinal = pageOrdinal,
    candidateOrdinal = candidateOrdinal,
    createdAt = createdAt,
)

fun SkippedCandidate.toEntity(): SkippedCandidateEntity = SkippedCandidateEntity(
    id = id,
    jobId = jobId,
    ownerId = ownerId,
    reasonCode = reason,
    pageOrdinal = pageOrdinal,
    candidateOrdinal = candidateOrdinal,
    createdAt = createdAt,
)

// --- Summary --------------------------------------------------------------------------------

fun MonthlySummaryEntity.toDomain(categoryTotals: List<SummaryCategoryTotalEntity>): MonthlySummary =
    MonthlySummary(
        id = id,
        ownerId = ownerId,
        month = JakartaMonth.parse(monthId) ?: JakartaMonth.of(generatedAt),
        mailboxLabel = mailboxLabel,
        grossInflow = grossInflow,
        grossOutflow = grossOutflow,
        netCashFlow = netCashFlow,
        classifiedIncome = classifiedIncome,
        classifiedExpense = classifiedExpense,
        categoryTotals = categoryTotals.map { CategoryTotal(it.categoryCode, it.amount, it.transactionCount) },
        uncategorizedCount = uncategorizedCount,
        manualEntryCount = manualEntryCount,
        nonIdrExcludedCount = nonIdrExcludedCount,
        transactionCount = transactionCount,
        coverage = coverage,
        inputRevision = inputRevision,
        summaryVersion = summaryVersion,
        generatedAt = generatedAt,
        isOutdated = isOutdated,
    )

fun MonthlySummary.toEntity(): MonthlySummaryEntity = MonthlySummaryEntity(
    id = id,
    ownerId = ownerId,
    monthId = month.id(),
    mailboxLabel = mailboxLabel,
    grossInflow = grossInflow,
    grossOutflow = grossOutflow,
    netCashFlow = netCashFlow,
    classifiedIncome = classifiedIncome,
    classifiedExpense = classifiedExpense,
    uncategorizedCount = uncategorizedCount,
    manualEntryCount = manualEntryCount,
    nonIdrExcludedCount = nonIdrExcludedCount,
    transactionCount = transactionCount,
    coverage = coverage,
    inputRevision = inputRevision,
    summaryVersion = summaryVersion,
    generatedAt = generatedAt,
    isOutdated = isOutdated,
)

fun MonthlySummary.categoryTotalEntities(): List<SummaryCategoryTotalEntity> =
    categoryTotals.map { total ->
        SummaryCategoryTotalEntity(
            summaryId = id,
            categoryCode = total.category,
            amount = total.amount,
            transactionCount = total.transactionCount,
        )
    }

// --- Analysis -------------------------------------------------------------------------------

@Serializable
internal data class ObservationDto(
    val text: String,
    val metricKeys: List<String> = emptyList(),
    val templateKey: String? = null,
)

@Serializable
internal data class SuggestionDto(
    val text: String,
    val metricKeys: List<String> = emptyList(),
    val categoryCode: String? = null,
    val suggestedAmountMinor: Long? = null,
    val templateKey: String? = null,
)

@Serializable
internal data class ValidationDto(
    val isValid: Boolean = false,
    val issues: List<String> = emptyList(),
    val regenerationCount: Int = 0,
    val validatedAtMillis: Long? = null,
)

@Serializable
internal data class UsageDto(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val latencyMillis: Long? = null,
)

fun AnalysisEntity.toDomain(): Analysis = Analysis(
    id = id,
    ownerId = ownerId,
    monthId = monthId,
    summaryId = summaryId,
    inputRevision = inputRevision,
    consentId = consentId,
    providerId = providerId,
    modelId = modelId,
    promptVersion = promptVersion,
    observations = PamJson.decodeFromString(
        ListSerializer(ObservationDto.serializer()),
        observationsJson,
    ).map { Observation(it.text, it.metricKeys, it.templateKey) },
    suggestions = PamJson.decodeFromString(
        ListSerializer(SuggestionDto.serializer()),
        suggestionsJson,
    ).map { dto ->
        Suggestion(
            text = dto.text,
            metricKeys = dto.metricKeys,
            category = Category.fromCode(dto.categoryCode),
            suggestedAmount = dto.suggestedAmountMinor?.let(::Money),
            templateKey = dto.templateKey,
        )
    },
    limitations = PamJson.decodeFromString(
        ListSerializer(String.serializer()),
        limitationsJson,
    ),
    status = status,
    validation = PamJson.decodeFromString(ValidationDto.serializer(), validationJson).let { dto ->
        ValidationOutcome(
            isValid = dto.isValid,
            issues = dto.issues,
            regenerationCount = dto.regenerationCount,
            validatedAt = dto.validatedAtMillis?.let { Instant.fromEpochMilliseconds(it) },
        )
    },
    usage = usageJson?.let { raw ->
        PamJson.decodeFromString(UsageDto.serializer(), raw).let {
            AnalysisUsage(it.promptTokens, it.completionTokens, it.latencyMillis)
        }
    },
    createdAt = createdAt,
)

fun Analysis.toEntity(): AnalysisEntity = AnalysisEntity(
    id = id,
    ownerId = ownerId,
    monthId = monthId,
    summaryId = summaryId,
    inputRevision = inputRevision,
    consentId = consentId,
    providerId = providerId,
    modelId = modelId,
    promptVersion = promptVersion,
    observationsJson = PamJson.encodeToString(
        ListSerializer(ObservationDto.serializer()),
        observations.map { ObservationDto(it.text, it.metricKeys, it.templateKey) },
    ),
    suggestionsJson = PamJson.encodeToString(
        ListSerializer(SuggestionDto.serializer()),
        suggestions.map {
            SuggestionDto(
                text = it.text,
                metricKeys = it.metricKeys,
                categoryCode = it.category?.code,
                suggestedAmountMinor = it.suggestedAmount?.minor,
                templateKey = it.templateKey,
            )
        },
    ),
    limitationsJson = PamJson.encodeToString(
        ListSerializer(String.serializer()),
        limitations,
    ),
    status = status,
    validationJson = PamJson.encodeToString(
        ValidationDto.serializer(),
        ValidationDto(
            isValid = validation.isValid,
            issues = validation.issues,
            regenerationCount = validation.regenerationCount,
            validatedAtMillis = validation.validatedAt?.toEpochMilliseconds(),
        ),
    ),
    usageJson = usage?.let {
        PamJson.encodeToString(UsageDto.serializer(), UsageDto(it.promptTokens, it.completionTokens, it.latencyMillis))
    },
    createdAt = createdAt,
)

// --- Consent / connection / deletion --------------------------------------------------------

fun ConsentEntity.toDomain(): Consent = Consent(
    id = id,
    ownerId = ownerId,
    purpose = purpose,
    noticeVersion = noticeVersion,
    decision = decision,
    decidedAt = decidedAt,
)

fun Consent.toEntity(): ConsentEntity = ConsentEntity(
    id = id,
    ownerId = ownerId,
    purpose = purpose,
    noticeVersion = noticeVersion,
    decision = decision,
    decidedAt = decidedAt,
)

fun GmailConnectionEntity.toDomain(): GmailConnection = GmailConnection(
    ownerId = ownerId,
    state = state,
    mailboxLabel = mailboxLabel,
    scope = scope,
    connectedAt = connectedAt,
    lastImportAt = lastImportAt,
    lastAiAt = lastAiAt,
    updatedAt = updatedAt,
)

fun GmailConnection.toEntity(): GmailConnectionEntity = GmailConnectionEntity(
    ownerId = ownerId,
    state = state,
    mailboxLabel = mailboxLabel,
    scope = scope,
    connectedAt = connectedAt,
    lastImportAt = lastImportAt,
    lastAiAt = lastAiAt,
    updatedAt = updatedAt,
)

fun DeletionRequestEntity.toDomain(): DeletionRequest = DeletionRequest(
    id = id,
    ownerId = ownerId,
    state = state,
    requestedAt = requestedAt,
    reauthenticatedAt = reauthenticatedAt,
    completedAt = completedAt,
    failureCode = failureCode,
)

fun DeletionRequest.toEntity(): DeletionRequestEntity = DeletionRequestEntity(
    id = id,
    ownerId = ownerId,
    state = state,
    requestedAt = requestedAt,
    reauthenticatedAt = reauthenticatedAt,
    completedAt = completedAt,
    failureCode = failureCode,
)

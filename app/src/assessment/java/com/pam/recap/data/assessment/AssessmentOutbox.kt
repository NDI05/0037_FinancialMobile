package com.pam.recap.data.assessment

import android.content.Context
import com.pam.recap.core.money.Money
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.InsertResult
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * S4.4 staging store for the assessment variant.
 *
 * The assessment build has no Room/SQLCipher database. Import runs still need one durable thing:
 * the email-derived rows of a committed run, so [com.pam.recap.domain.usecase.ServerImportUploader]
 * can rebuild the exact snapshot for the durable pending journal after a process restart. Those
 * rows are staged here, encrypted with the same Keystore-wrapped [WrappedSecretStore] used by
 * [com.pam.recap.data.security.ImportUploadJournal]; no plaintext finance row touches disk.
 *
 * This is deliberately a *staging outbox*, not a database: it exists to feed the upload journal and
 * its rows are removed with the owner's sign-out/deletion. Reads that the UI serves from
 * [com.pam.recap.domain.repository.ServerFinanceRepository] never consult it.
 */
@Singleton
class AssessmentOutbox @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wrappedSecrets: WrappedSecretStore,
) {

    @Serializable
    data class StagedTransaction(
        val id: String,
        val ownerId: String,
        val direction: String,
        val source: String,
        val amountMinor: Long,
        val currency: String,
        val eventAtMillis: Long,
        val receivedAtMillis: Long? = null,
        val description: String,
        val merchantLabel: String? = null,
        val acquirer: String? = null,
        val status: String,
        val fingerprint: String,
        val parserVersion: String,
        val parserConfidence: Float,
        val categoryCode: String,
        val categoryOrigin: String,
        val categoryConfidence: Float? = null,
        val categoryUpdatedAtMillis: Long? = null,
        val isInternalTransfer: Boolean = false,
        val linkedRefundOf: String? = null,
        val note: String? = null,
        val importJobId: String? = null,
        val revision: Int = 0,
        val createdAtMillis: Long,
        val updatedAtMillis: Long,
        val serverCategoryId: String? = null,
        val serverCategoryLabel: String? = null,
        val attributes: Map<String, String> = emptyMap(),
    ) {
        fun toDomain(): Transaction = Transaction(
            id = id,
            ownerId = ownerId,
            direction = enumOrDefault(direction, Direction.DEBIT),
            source = enumOrDefault(source, TransactionSource.EMAIL),
            amount = Money(amountMinor),
            currency = currency,
            eventAt = Instant.fromEpochMilliseconds(eventAtMillis),
            receivedAt = receivedAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            description = description,
            merchantLabel = merchantLabel,
            acquirer = acquirer,
            status = enumOrDefault(status, ParseStatus.POSTED),
            fingerprint = fingerprint,
            parserVersion = parserVersion,
            parserConfidence = parserConfidence,
            category = Category.fromCodeOrUncategorized(categoryCode),
            categoryOrigin = enumOrDefault(categoryOrigin, CategoryOrigin.IMPORTED),
            categoryConfidence = categoryConfidence,
            categoryUpdatedAt = categoryUpdatedAtMillis?.let { Instant.fromEpochMilliseconds(it) },
            isInternalTransfer = isInternalTransfer,
            linkedRefundOf = linkedRefundOf,
            note = note,
            importJobId = importJobId,
            revision = revision,
            createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
            updatedAt = Instant.fromEpochMilliseconds(updatedAtMillis),
            serverCategoryId = serverCategoryId,
            serverCategoryLabel = serverCategoryLabel,
            attributes = attributes,
        )

        private inline fun <reified T : Enum<T>> enumOrDefault(name: String, fallback: T): T =
            runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)

        companion object {
            fun fromDomain(row: Transaction): StagedTransaction = StagedTransaction(
                id = row.id,
                ownerId = row.ownerId,
                direction = row.direction.name,
                source = row.source.name,
                amountMinor = row.amount.minor,
                currency = row.currency,
                eventAtMillis = row.eventAt.toEpochMilliseconds(),
                receivedAtMillis = row.receivedAt?.toEpochMilliseconds(),
                description = row.description,
                merchantLabel = row.merchantLabel,
                acquirer = row.acquirer,
                status = row.status.name,
                fingerprint = row.fingerprint,
                parserVersion = row.parserVersion,
                parserConfidence = row.parserConfidence,
                categoryCode = row.category.code,
                categoryOrigin = row.categoryOrigin.name,
                categoryConfidence = row.categoryConfidence,
                categoryUpdatedAtMillis = row.categoryUpdatedAt?.toEpochMilliseconds(),
                isInternalTransfer = row.isInternalTransfer,
                linkedRefundOf = row.linkedRefundOf,
                note = row.note,
                importJobId = row.importJobId,
                revision = row.revision,
                createdAtMillis = row.createdAt.toEpochMilliseconds(),
                updatedAtMillis = row.updatedAt.toEpochMilliseconds(),
                serverCategoryId = row.serverCategoryId,
                serverCategoryLabel = row.serverCategoryLabel,
                attributes = row.attributes,
            )
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _changes = MutableStateFlow(0L)

    /** Monotonic tick so `Flow` observers re-read the outbox after every write. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /** All staged rows of [ownerId] (email staging buckets plus the manual bucket). */
    @Synchronized
    fun rows(ownerId: String): List<StagedTransaction> {
        val prefix = bucketPrefix(ownerId)
        return prefs.all.keys
            .filter { it.startsWith(prefix) }
            .flatMap { readBucket(it) }
    }

    /** Rows the upload journal names as one local run: the immutable upload snapshot. */
    @Synchronized
    fun rowsForJob(ownerId: String, jobId: String): List<StagedTransaction> =
        readBucket(bucketKey(ownerId, jobId))

    @Synchronized
    fun find(ownerId: String, fingerprint: String): StagedTransaction? =
        rows(ownerId).firstOrNull { it.fingerprint == fingerprint }

    @Synchronized
    fun get(ownerId: String, id: String): StagedTransaction? =
        rows(ownerId).firstOrNull { it.id == id }

    /**
     * Fingerprint-deduped insert, mirroring Room's unique index on `(ownerId, fingerprint)` — that
     * index is **global per owner**, not per import job. The dedupe set is therefore built from
     * [rows] (every bucket of [ownerId]): a retried import, a manual entry that matches an email
     * row, or a row arriving through another job must be ignored instead of being staged twice,
     * otherwise `monthTotals`/`listMonth`/duplicate counts double-count after a re-fetch.
     *
     * A batch lands in the bucket of its own `importJobId`. The result reports only rows whose
     * durable bucket write actually committed: a failed `commit()` must not be reported as a
     * successful insert.
     */
    @Synchronized
    fun insert(ownerId: String, rows: List<StagedTransaction>): InsertResult {
        val known = rows(ownerId).mapTo(mutableSetOf()) { it.fingerprint }
        val byBucket = rows.filter { it.ownerId == ownerId }.groupBy { it.importJobId }
        var inserted = 0
        var ignored = 0
        for ((jobId, batch) in byBucket) {
            val key = bucketKey(ownerId, jobId)
            val existing = readBucket(key)
            val accepted = mutableListOf<StagedTransaction>()
            for (row in batch) {
                if (known.add(row.fingerprint)) {
                    accepted += row
                } else {
                    ignored++
                }
            }
            if (accepted.isEmpty()) continue
            if (writeBucket(key, existing + accepted)) {
                inserted += accepted.size
            } else {
                // Not durable: do not claim it was inserted, and keep the fingerprints unclaimed so
                // a retry through this process is not silently deduped against phantom rows.
                accepted.forEach { known.remove(it.fingerprint) }
                ignored += accepted.size
            }
        }
        if (inserted > 0) _changes.value += 1
        return InsertResult(inserted, ignored)
    }

    /** Replaces one row; returns `false` when the durable write did not commit. */
    @Synchronized
    fun replace(ownerId: String, row: StagedTransaction): Boolean {
        val key = bucketKey(ownerId, row.importJobId)
        val existing = readBucket(key)
        val index = existing.indexOfFirst { it.id == row.id }
        val next = when {
            index >= 0 -> existing.toMutableList().apply { set(index, row) }
            else -> (existing + row).toMutableList()
        }
        val saved = writeBucket(key, next)
        if (saved) _changes.value += 1
        return saved
    }

    @Synchronized
    fun deleteAll(ownerId: String) {
        val prefix = bucketPrefix(ownerId)
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
            prefs.all.keys.filter { it.startsWith(metaPrefix(ownerId)) }.forEach { remove(it) }
        }.commit()
        _changes.value += 1
    }

    @Synchronized
    fun countByIdPrefix(ownerId: String, prefix: String): Int =
        rows(ownerId).count { it.id.startsWith(prefix) }

    @Synchronized
    fun deleteByIdPrefix(ownerId: String, prefix: String): Int {
        val hits = rows(ownerId).filter { it.id.startsWith(prefix) }
        if (hits.isEmpty()) return 0
        val byBucket = hits.groupBy { it.importJobId }
        for ((jobId, batch) in byBucket) {
            val key = bucketKey(ownerId, jobId)
            val drop = batch.map { it.id }.toSet()
            writeBucket(key, readBucket(key).filterNot { it.id in drop })
        }
        _changes.value += 1
        return hits.size
    }

    // ---------------------------------------------------------------- month revision

    @Synchronized
    fun monthRevision(ownerId: String, month: JakartaMonth): Int =
        readMeta(ownerId, "rev|${month.id()}")?.toIntOrNull() ?: 0

    @Synchronized
    fun bumpMonthRevision(ownerId: String, month: JakartaMonth): Int {
        val current = monthRevision(ownerId, month)
        // Only advance when the wrapped counter actually committed, so a failed write cannot make
        // dependent observers believe a revision was bumped.
        if (!writeMeta(ownerId, "rev|${month.id()}", (current + 1).toString())) return current
        return current + 1
    }

    // ---------------------------------------------------------------- storage

    private fun bucketPrefix(ownerId: String): String = "row|${enc(ownerId)}|"

    private fun bucketKey(ownerId: String, jobId: String?): String =
        bucketPrefix(ownerId) + enc(jobId ?: MANUAL_BUCKET)

    private fun metaPrefix(ownerId: String): String = "meta|${enc(ownerId)}|"

    private fun readMeta(ownerId: String, name: String): String? =
        prefs.getString(metaPrefix(ownerId) + name, null)?.let { unwrap(it) }

    /**
     * Writes the wrapped owner metadata. Returns the actual `commit()` result; callers that need
     * durable semantics (month revision) must not treat a `false` write as success.
     */
    private fun writeMeta(ownerId: String, name: String, value: String): Boolean {
        val wrapped = runCatching { wrappedSecrets.wrap(value.encodeToByteArray()) }.getOrNull()
            ?: return false
        return runCatching {
            prefs.edit().putString(metaPrefix(ownerId) + name, wrapped).commit()
        }.getOrDefault(false)
    }

    private fun readBucket(key: String): List<StagedTransaction> {
        val wrapped = prefs.getString(key, null) ?: return emptyList()
        val decoded = runCatching { wrappedSecrets.unwrap(wrapped) }.getOrNull() ?: return emptyList()
        return runCatching { json.decodeFromString<List<StagedTransaction>>(decoded.decodeToString()) }
            .getOrDefault(emptyList())
    }

    /** Returns the durable outcome of the bucket write ([rows] empty means deletion). */
    private fun writeBucket(key: String, rows: List<StagedTransaction>): Boolean {
        if (rows.isEmpty()) {
            return runCatching { prefs.edit().remove(key).commit() }.getOrDefault(false)
        }
        val wrapped = runCatching {
            wrappedSecrets.wrap(json.encodeToString(rows).encodeToByteArray())
        }.getOrNull() ?: return false
        return runCatching { prefs.edit().putString(key, wrapped).commit() }.getOrDefault(false)
    }

    private fun unwrap(value: String): String? =
        runCatching { wrappedSecrets.unwrap(value).decodeToString() }.getOrNull()

    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.encodeToByteArray())

    private companion object {
        const val PREFS = "pam_assessment_outbox"
        const val MANUAL_BUCKET = "__manual__"
    }
}

package com.pam.recap.data.local

import androidx.room.TypeConverter
import com.pam.recap.core.money.Money
import com.pam.recap.domain.model.AnalysisStatus
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.DeletionState
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.RedactionReason
import com.pam.recap.domain.model.TransactionSource
import kotlin.time.Instant

/**
 * §4 `Converters`: `Instant ⇄ Long`, `Money ⇄ Long`, enums ⇄ String.
 *
 * Enums are stored by name (or by [Category.code]) so SQL can filter on literal values and a
 * renamed Kotlin type is the only thing a future migration has to think about. Amounts are
 * stored as minor units — the SQLite `INTEGER` affinity keeps `SUM()` exact (BR-04).
 */
class Converters {

    // --- Instant -------------------------------------------------------------------
    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilliseconds()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let { Instant.fromEpochMilliseconds(it) }

    // --- Money ---------------------------------------------------------------------
    @TypeConverter
    fun fromMoney(value: Money?): Long? = value?.minor

    @TypeConverter
    fun toMoney(value: Long?): Money? = value?.let(::Money)

    // --- Category (stored by stable code, §4) --------------------------------------
    @TypeConverter
    fun fromCategory(value: Category?): String? = value?.code

    @TypeConverter
    fun toCategory(value: String?): Category? = Category.fromCode(value)

    // --- Enums stored by name ------------------------------------------------------
    @TypeConverter
    fun fromDirection(value: Direction?): String? = value?.name

    @TypeConverter
    fun toDirection(value: String?): Direction? = value?.let { runCatching { Direction.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromTransactionSource(value: TransactionSource?): String? = value?.name

    @TypeConverter
    fun toTransactionSource(value: String?): TransactionSource? =
        value?.let { runCatching { TransactionSource.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromCategoryOrigin(value: CategoryOrigin?): String? = value?.name

    @TypeConverter
    fun toCategoryOrigin(value: String?): CategoryOrigin? =
        value?.let { runCatching { CategoryOrigin.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromParseStatus(value: ParseStatus?): String? = value?.name

    @TypeConverter
    fun toParseStatus(value: String?): ParseStatus? =
        value?.let { runCatching { ParseStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromImportState(value: ImportState?): String? = value?.name

    @TypeConverter
    fun toImportState(value: String?): ImportState? =
        value?.let { runCatching { ImportState.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromRedactionReason(value: RedactionReason?): String? = value?.name

    @TypeConverter
    fun toRedactionReason(value: String?): RedactionReason? =
        value?.let { runCatching { RedactionReason.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromCoverageStatus(value: CoverageStatus?): String? = value?.name

    @TypeConverter
    fun toCoverageStatus(value: String?): CoverageStatus? =
        value?.let { runCatching { CoverageStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromAnalysisStatus(value: AnalysisStatus?): String? = value?.name

    @TypeConverter
    fun toAnalysisStatus(value: String?): AnalysisStatus? =
        value?.let { runCatching { AnalysisStatus.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromConsentPurpose(value: ConsentPurpose?): String? = value?.name

    @TypeConverter
    fun toConsentPurpose(value: String?): ConsentPurpose? =
        value?.let { runCatching { ConsentPurpose.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromConsentDecision(value: ConsentDecision?): String? = value?.name

    @TypeConverter
    fun toConsentDecision(value: String?): ConsentDecision? =
        value?.let { runCatching { ConsentDecision.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromGmailConnectionState(value: GmailConnectionState?): String? = value?.name

    @TypeConverter
    fun toGmailConnectionState(value: String?): GmailConnectionState? =
        value?.let { runCatching { GmailConnectionState.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun fromDeletionState(value: DeletionState?): String? = value?.name

    @TypeConverter
    fun toDeletionState(value: String?): DeletionState? =
        value?.let { runCatching { DeletionState.valueOf(it) }.getOrNull() }

}

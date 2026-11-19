package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.AccountIdentity
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.BudgetPlan
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.RawAnalysis
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Gmail port (FR-06…FR-21, NFR-26), exactly as specified in `docs/03_ARCHITECTURE.md` §5.
 *
 * The domain owns this interface; `data/gmail` provides two implementations behind it —
 * [GoogleGmailSource] (real, gated by `BuildConfig.PAM_GOOGLE_WEB_CLIENT_ID`) and
 * [FixtureGmailSource] (deterministic fixtures). Callers never know which one is bound.
 */
interface GmailSource {

    /** Runs the OAuth consent flow for [GmailConnection.READONLY_SCOPE] only (FR-08). */
    suspend fun authorize(): Outcome<Unit>

    fun connectionState(): Flow<GmailConnectionState>

    suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage>

    /** Fetches one message and extracts text parts in memory; attachments are ignored (FR-14). */
    suspend fun fetchMessage(id: String): Outcome<RawMessage>

    /** Local disconnect: clears tokens and revokes where the platform supports it (FR-46). */
    suspend fun disconnect(): Outcome<Unit>
}

/**
 * Optional capability implemented by both sources so the connection screen can show a mailbox
 * label without widening the Gmail port (FR-49). The DI module binds the same instance.
 */
interface GmailMailboxLabelProvider {
    fun mailboxLabel(): String?
}

/**
 * Allow-listed query built only from the template registry (FR-11): the sender and subject
 * patterns come from configuration, never from user input or free text.
 */
data class LivinQuery(
    val fromInclusive: Instant,
    val toExclusive: Instant,
    val senderPatterns: List<String>,
    val subjectPatterns: List<String>,
    val templateVersion: String,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(fromInclusive < toExclusive) { "invalid search window" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize out of range: $pageSize" }
    }

    companion object {
        /** A6: realistic pagination of 20 messages per page. */
        const val DEFAULT_PAGE_SIZE: Int = 20
        const val MAX_PAGE_SIZE: Int = 100
    }
}

/** One page of message references; [nextPageToken] null means the query is exhausted. */
data class GmailPage(
    val messages: List<GmailMessageRef>,
    val nextPageToken: String?,
    /** Total reported by the provider when it chooses to; never used as a count of spending. */
    val resultSizeEstimate: Int? = null,
) {
    val isEmpty: Boolean get() = messages.isEmpty()
}

/**
 * A message reference. The Gmail id lives in memory only: it is used to fetch the body and to
 * compute the fingerprint, then discarded (hard rule 7).
 */
data class GmailMessageRef(
    val id: String,
    val internalDate: Instant?,
)

/**
 * A fetched message reduced to what the parser may see. Deliveries carry no attachment payload;
 * [attachmentCount] exists only so the parser can reject attachment-only notifications (FR-14).
 */
data class RawMessage(
    val messageId: String,
    val sender: String?,
    val subject: String?,
    val internalDate: Instant?,
    val textParts: List<String>,
    val attachmentCount: Int = 0,
) {
    val hasTextBody: Boolean get() = textParts.any { it.isNotBlank() }
}

/**
 * Analysis port (FR-34…FR-41, NFR-26), exactly as specified in `docs/03_ARCHITECTURE.md` §5.
 * The payload is aggregate-only (FR-35); the engine never receives email content.
 */
interface AnalysisEngine {

    val providerId: String

    val modelId: String

    suspend fun analyze(payload: AiAggregatePayload): Outcome<RawAnalysis>
}

/**
 * Parser port (FR-12/FR-13). `data/gmail` provides the strict Livin implementation; the domain
 * import pipeline only needs this contract, which keeps `domain` free of the parsing details and
 * makes the whole pipeline testable with a fake parser.
 */
interface LivinNotificationParser {
    fun parse(message: RawMessage): ParseResult
}

/**
 * Builds the allow-listed month query from the versioned template registry (FR-11). The registry
 * itself is data; the domain only asks for the resulting [LivinQuery].
 */
interface LivinQueryFactory {
    fun build(fromInclusive: Instant, toExclusive: Instant, templateVersion: String?): LivinQuery
}

/**
 * FR-35 payload port: the domain asks for an aggregate-only payload without knowing how it is
 * projected or serialized.
 */
interface AiPayloadSource {
    fun build(
        summary: MonthlySummary,
        previousMonth: MonthlySummary?,
        plan: BudgetPlan?,
    ): AiAggregatePayload
}

/**
 * A9 demo seeding port. The domain decides *when* a signed-in demo session needs its sample
 * month; `data/seed` owns the fixtures and the insert.
 */
interface DemoDataPort {
    /** Seeds once per owner; a re-run is a no-op thanks to the fingerprint unique index. */
    suspend fun seedIfNeeded(ownerId: String): Boolean
}

/** OI-07: tells the domain whether a backend engine is actually configured before it is chosen. */
interface AnalysisEngineConfiguration {
    val isConfigured: Boolean
}

/**
 * Identity port (FR-01). One implementation talks to Google Identity, and in demo mode it returns
 * the bundled fixture account instead of touching the network (OI-01).
 */
interface AccountIdentitySource {
    val isDemoMode: Boolean

    suspend fun signIn(): Outcome<AccountIdentity>
}

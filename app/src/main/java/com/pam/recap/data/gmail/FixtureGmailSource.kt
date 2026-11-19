package com.pam.recap.data.gmail

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.data.seed.LivinFixtures
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.RawMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Deterministic failure modes the fixture mailbox can reproduce (A6). */
enum class FixtureScenario {
    /** Every page succeeds. */
    NORMAL,

    /** Page 1 succeeds, page 2 returns a network failure — the partial-import path (FR-21). */
    PARTIAL_FAILURE,

    /** The grant was revoked: authorize/search fail and the state becomes reauthorization. */
    REVOKED,

    /** The mailbox genuinely has no matching notification (FR-20). */
    EMPTY,
}

/**
 * `GmailSource` over the sanitized Livin fixtures (OI-01, NFR-26).
 *
 * It is a real adapter, not a stub: it enforces the same preconditions as the Google source
 * (authorization first), honours the allow-listed query, pages at 20 messages and reproduces the
 * failure modes the UI must distinguish. Deterministic by construction: no clock, no network.
 */
@Singleton
class FixtureGmailSource @Inject constructor(
    private val registry: LivinTemplateRegistry,
) : GmailSource, GmailMailboxLabelProvider {

    private val connection = MutableStateFlow(GmailConnectionState.NOT_CONNECTED)
    private val scenario = MutableStateFlow(FixtureScenario.NORMAL)

    private val fixtures: List<LivinFixtures.Fixture> = LivinFixtures.all()
        .sortedByDescending { it.internalDate }

    fun setScenario(value: FixtureScenario) {
        scenario.value = value
    }

    fun currentScenario(): FixtureScenario = scenario.value

    override suspend fun authorize(): Outcome<Unit> {
        if (scenario.value == FixtureScenario.REVOKED) {
            connection.value = GmailConnectionState.REAUTHORIZATION_REQUIRED
            return Outcome.Failure(AppError.PermissionRequired("fixture_revoked"))
        }
        connection.value = GmailConnectionState.CONNECTED
        SafeLogger.event(TAG, "fixture_authorized")
        return Outcome.Success(Unit)
    }

    override fun connectionState(): Flow<GmailConnectionState> = connection.asStateFlow()

    override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> {
        when (scenario.value) {
            FixtureScenario.REVOKED -> {
                connection.value = GmailConnectionState.REAUTHORIZATION_REQUIRED
                return Outcome.Failure(AppError.PermissionRequired("fixture_revoked"))
            }

            FixtureScenario.EMPTY -> return Outcome.Success(GmailPage(emptyList(), null, 0))

            FixtureScenario.NORMAL, FixtureScenario.PARTIAL_FAILURE -> Unit
        }

        if (!connection.value.grantsAccess) {
            return Outcome.Failure(AppError.PermissionRequired("fixture_not_authorized"))
        }

        val offset = parseOffset(pageToken)
        if (scenario.value == FixtureScenario.PARTIAL_FAILURE && offset > 0) {
            // FR-21: page 1 is kept, page 2 fails, so the caller can mark the job Partial.
            SafeLogger.warning(TAG, "fixture_page_failed")
            return Outcome.Failure(AppError.Network("fixture_page_${offset / query.pageSize + 1}"))
        }

        val matching = fixtures.filter { fixture -> matches(fixture, query) }
        val window = matching.drop(offset).take(query.pageSize)
        val nextOffset = offset + window.size
        val nextPageToken = if (nextOffset < matching.size) "offset:$nextOffset" else null

        SafeLogger.event(TAG, "fixture_page_returned")
        return Outcome.Success(
            GmailPage(
                messages = window.map { GmailMessageRef(id = it.id, internalDate = it.internalDate) },
                nextPageToken = nextPageToken,
                resultSizeEstimate = matching.size,
            ),
        )
    }

    override suspend fun fetchMessage(id: String): Outcome<RawMessage> {
        if (scenario.value == FixtureScenario.REVOKED) {
            connection.value = GmailConnectionState.REAUTHORIZATION_REQUIRED
            return Outcome.Failure(AppError.PermissionRequired("fixture_revoked"))
        }
        if (!connection.value.grantsAccess) {
            return Outcome.Failure(AppError.PermissionRequired("fixture_not_authorized"))
        }
        val fixture = fixtures.firstOrNull { it.id == id }
            ?: return Outcome.Failure(AppError.NotFound("fixture_$id"))
        return Outcome.Success(fixture.toRawMessage())
    }

    override suspend fun disconnect(): Outcome<Unit> {
        connection.value = GmailConnectionState.DISCONNECTED
        return Outcome.Success(Unit)
    }

    override fun mailboxLabel(): String = DEMO_MAILBOX_LABEL

    /**
     * Mirrors the allow-listed server-side query: sender address must be allow-listed and the
     * subject must contain an allow-listed fragment, otherwise the message never reaches the
     * parser (FR-11). The window is the widened month range (BR-01).
     */
    private fun matches(fixture: LivinFixtures.Fixture, query: LivinQuery): Boolean {
        val address = LivinTemplateRegistry.extractAddress(fixture.sender) ?: return false
        val senderAllowed = query.senderPatterns.any { it.equals(address, ignoreCase = true) }
        if (!senderAllowed) return false

        val subject = fixture.subject.lowercase()
        val subjectAllowed = query.subjectPatterns.any { subject.contains(it.lowercase()) }
        if (!subjectAllowed) return false

        return fixture.internalDate >= query.fromInclusive && fixture.internalDate < query.toExclusive
    }

    private fun parseOffset(pageToken: String?): Int {
        if (pageToken == null) return 0
        val value = pageToken.substringAfter("offset:", pageToken)
        return value.toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private companion object {
        const val TAG = "FixtureGmail"
        /** Fake mailbox identity of the bundled fixtures (data, not UI copy). */
        const val DEMO_MAILBOX_LABEL = "livin.demo@pam.local"
    }
}

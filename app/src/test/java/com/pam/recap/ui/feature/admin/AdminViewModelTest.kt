package com.pam.recap.ui.feature.admin

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.AdminAccountPage
import com.pam.recap.domain.repository.AdminAccountPatch
import com.pam.recap.domain.repository.AdminAuditEntry
import com.pam.recap.domain.repository.AdminAuditPage
import com.pam.recap.domain.repository.AdminInvitation
import com.pam.recap.domain.repository.AdminRepository
import com.pam.recap.domain.repository.AdminRole
import com.pam.recap.domain.repository.AdminStatus
import com.pam.recap.domain.repository.ServerAdminAccount
import com.pam.recap.domain.repository.ServerAdminInvitation
import com.pam.recap.testing.FakeSessionRepository
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S4.2 (C21–C25) admin ViewModel: role gate before any request, cursor/filter resets, one-time
 * invitation token, revision-conflict draft retention, last-admin rule and confirmation-gated
 * deactivation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var admin: FakeAdminRepository
    private lateinit var sessions: FakeSessionRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        admin = FakeAdminRepository()
        sessions = FakeSessionRepository(
            Session("own_admin", "Admin", null, Instant.parse("2026-11-01T00:00:00Z"), false),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AdminViewModel(sessions, admin)

    @Test
    fun `a USER is forbidden and no admin request is sent`() {
        admin.roleResult = Outcome.Success("USER")
        val viewModel = viewModel()

        val state = viewModel.state.value
        assertTrue(state.forbidden)
        assertEquals("USER", state.role)
        assertFalse(state.checkingRole)
        // The /v1/me role probe is the only request: no account, audit or invitation traffic.
        assertEquals(1, admin.roleCalls)
        assertEquals(0, admin.listCalls.size)
        assertEquals(0, admin.auditCalls.size)
        assertEquals(0, admin.inviteCalls.size)
    }

    @Test
    fun `an ADMIN loads the first page then filters reset the cursor`() {
        admin.page = AdminAccountPage(listOf(account("own_1")), nextCursor = "cur_1")
        val viewModel = viewModel()

        assertEquals(1, admin.listCalls.size)
        val first = admin.listCalls.first()
        assertNull(first.query)
        assertNull(first.role)
        assertNull(first.status)
        assertNull(first.cursor)
        assertEquals(50, first.limit)
        assertEquals(listOf("own_1"), viewModel.state.value.accounts.map { it.ownerId })

        viewModel.onRoleFilterChange(AdminRole.ADMIN)
        viewModel.onStatusFilterChange(AdminStatus.SUSPENDED)
        viewModel.onQueryChange("ani")

        // Every filter/query change starts again from page one (no cursor).
        val last = admin.listCalls.last()
        assertEquals("ani", last.query)
        assertEquals(AdminRole.ADMIN, last.role)
        assertEquals(AdminStatus.SUSPENDED, last.status)
        assertNull(last.cursor)
        assertEquals(4, admin.listCalls.size)
    }

    @Test
    fun `load more appends the next page without duplicating owners`() {
        var pages = 0
        admin.listPage = { _, _, _, _, cursor ->
            pages++
            if (cursor == null) {
                Outcome.Success(AdminAccountPage(listOf(account("own_1"), account("own_2")), "cur_1"))
            } else {
                Outcome.Success(AdminAccountPage(listOf(account("own_2"), account("own_3")), null))
            }
        }
        val viewModel = viewModel()
        assertEquals(2, viewModel.state.value.accounts.size)

        viewModel.loadMore()

        val state = viewModel.state.value
        assertEquals(listOf("own_1", "own_2", "own_3"), state.accounts.map { it.ownerId })
        assertEquals("cur_1", admin.listCalls.last().cursor)
        assertNull(state.nextCursor)
        assertEquals(2, pages)
    }

    @Test
    fun `an invalid cursor resets to the first page`() {
        var calls = 0
        admin.listPage = { _, _, _, _, cursor ->
            calls++
            when {
                calls == 1 -> Outcome.Success(AdminAccountPage(listOf(account("own_1")), "cur_stale"))
                cursor == "cur_stale" -> Outcome.Failure(AppError.Validation("invalid_cursor"))
                else -> Outcome.Success(AdminAccountPage(listOf(account("own_1")), null))
            }
        }
        val viewModel = viewModel()
        viewModel.loadMore()

        val state = viewModel.state.value
        assertEquals(3, admin.listCalls.size)
        assertEquals("cur_stale", admin.listCalls[1].cursor)
        assertNull(admin.listCalls[2].cursor)
        assertNull(state.nextCursor)
        assertEquals(1, state.accounts.size)
    }

    @Test
    fun `invitation token is shown once and discarded on dismissal`() {
        admin.invitation = AdminInvitation(
            invitation = ServerAdminInvitation(
                id = "inv_1",
                email = "baru@example.id",
                role = AdminRole.USER,
                expiresAt = "2026-11-26T00:00:00Z",
            ),
            invitationToken = "tok_once_123",
        )
        val viewModel = viewModel()
        viewModel.startInvite()
        viewModel.onInviteEmailChange("baru@example.id")
        viewModel.onInviteRoleChange(AdminRole.USER)
        viewModel.onInviteExpiryChange("12")
        viewModel.submitInvite()

        val afterCreate = viewModel.state.value
        assertNotNull(afterCreate.invitation)
        assertEquals("tok_once_123", afterCreate.invitation?.invitationToken)
        assertEquals(AdminNotice.INVITATION_CREATED, afterCreate.notice)
        assertEquals(1, admin.inviteCalls.size)
        assertEquals("baru@example.id", admin.inviteCalls.single().email)
        assertEquals(AdminRole.USER, admin.inviteCalls.single().role)
        assertEquals(12, admin.inviteCalls.single().hours)

        viewModel.dismissInvitation()
        // The raw token is gone from state; there is no endpoint to fetch it again.
        assertNull(viewModel.state.value.invitation)
        assertFalse(viewModel.state.value.inviteOpen)
        assertEquals(1, admin.inviteCalls.size)

        viewModel.startInvite()
        viewModel.onInviteEmailChange("baru@example.id")
        viewModel.submitInvite()
        assertEquals(2, admin.inviteCalls.size)
        viewModel.refresh()
        assertNull(viewModel.state.value.invitation)
    }

    @Test
    fun `an invalid invitation form never reaches the repository`() {
        val viewModel = viewModel()
        viewModel.startInvite()
        viewModel.onInviteEmailChange("not-an-email")
        viewModel.submitInvite()

        assertEquals(0, admin.inviteCalls.size)
        assertEquals(AdminFormRules.EMAIL, viewModel.state.value.inviteValidation)

        viewModel.onInviteEmailChange("ok@example.id")
        viewModel.onInviteExpiryChange("0")
        viewModel.submitInvite()
        assertEquals(0, admin.inviteCalls.size)
        assertEquals(AdminFormRules.EXPIRY, viewModel.state.value.inviteValidation)
    }

    @Test
    fun `a revision conflict keeps the draft and sets the conflict flag`() {
        admin.detail = account("own_1", revision = 3)
        admin.updateResult = Outcome.Failure(AppError.Conflict("revision_conflict"))
        val viewModel = viewModel()
        viewModel.startEdit(account("own_1", revision = 3))
        assertEquals(listOf("own_1"), admin.detailCalls)

        viewModel.onEditorDisplayNameChange("Nama Baru")
        viewModel.saveEditor()

        val state = viewModel.state.value
        assertEquals("Nama Baru", state.editor?.displayName)
        assertEquals(3, state.editor?.revision)
        assertTrue(state.editorConflict)
        assertFalse(state.lastAdmin)
        val patch = admin.updateCalls.single().second
        assertEquals(3, patch.revision)
        assertEquals("Nama Baru", patch.displayName)
    }

    @Test
    fun `the last admin rule is distinct from a generic conflict`() {
        admin.detail = account("own_1", revision = 3)
        admin.updateResult = Outcome.Failure(AppError.Conflict("last_admin"))
        val viewModel = viewModel()
        viewModel.startEdit(account("own_1", revision = 3))
        viewModel.onEditorRoleChange(AdminRole.USER)
        viewModel.saveEditor()

        val state = viewModel.state.value
        assertTrue(state.lastAdmin)
        assertFalse(state.editorConflict)
        assertNotNull(state.editor)
    }

    @Test
    fun `deactivation is only sent after confirmation and carries the revision`() {
        admin.page = AdminAccountPage(listOf(account("own_1", revision = 4)), null)
        val viewModel = viewModel()
        viewModel.requestDeactivate(account("own_1", revision = 4))

        assertEquals(0, admin.deactivateCalls.size)
        assertNotNull(viewModel.state.value.pendingDeactivate)

        viewModel.confirmDeactivate()

        val call = admin.deactivateCalls.single()
        assertEquals("own_admin", call.first)
        assertEquals("own_1", call.second)
        assertEquals(4, call.third)
        assertNull(viewModel.state.value.pendingDeactivate)
        assertEquals(AdminNotice.ACCOUNT_DEACTIVATED, viewModel.state.value.notice)

        viewModel.requestDeactivate(account("own_2", revision = 1))
        viewModel.cancelDeactivate()
        assertEquals(1, admin.deactivateCalls.size)
        assertNull(viewModel.state.value.pendingDeactivate)
    }

    @Test
    fun `audit filters reset the cursor and pages append without duplicates`() {
        admin.auditPage = { action, target, cursor ->
            when (cursor) {
                null -> AdminAuditPage(listOf(audit("aud_1")), "aud_cur")
                else -> AdminAuditPage(listOf(audit("aud_1"), audit("aud_2")), null)
            }
        }
        val viewModel = viewModel()

        viewModel.onAuditActionChange("admin.account.update")
        viewModel.onAuditTargetChange("own_9")

        val filtered = admin.auditCalls.last()
        assertEquals("admin.account.update", filtered.action)
        assertEquals("own_9", filtered.target)
        assertNull(filtered.cursor)

        viewModel.loadMoreAudit()
        assertEquals("aud_cur", admin.auditCalls.last().cursor)
        assertEquals(listOf("aud_1", "aud_2"), viewModel.state.value.audit.map { it.id })
    }

    @Test
    fun `a disabled backend and missing session never call the API`() {
        admin.enabled = false
        val disabled = viewModel()
        assertFalse(disabled.state.value.enabled)
        assertEquals(0, admin.roleCalls)

        admin.enabled = true
        runBlocking { sessions.signOut() }
        val signedOut = viewModel()
        assertFalse(signedOut.state.value.enabled)
        assertEquals(0, admin.listCalls.size)
    }

    private fun account(
        ownerId: String,
        revision: Int = 1,
        role: String = AdminRole.USER,
        status: String = AdminStatus.ACTIVE,
    ) = ServerAdminAccount(
        ownerId = ownerId,
        email = "$ownerId@example.id",
        displayName = "Nama $ownerId",
        role = role,
        status = status,
        revision = revision,
    )

    private fun audit(id: String) = AdminAuditEntry(
        id = id,
        actorOwnerId = "own_admin",
        targetOwnerId = "own_1",
        action = "admin.account.update",
        createdAt = "2026-11-23T00:00:00Z",
    )

    private data class ListCall(
        val query: String?,
        val role: String?,
        val status: String?,
        val limit: Int,
        val cursor: String?,
    )

    private data class AuditCall(val action: String?, val target: String?, val cursor: String?)

    private data class InviteCall(val email: String, val role: String, val hours: Int)

    private class FakeAdminRepository : AdminRepository {
        var enabled = true
        override val isEnabled: Boolean get() = enabled

        var roleCalls = 0
        var roleResult: Outcome<String> = Outcome.Success(AdminRole.ADMIN)
        var page: AdminAccountPage = AdminAccountPage()
        var listPage: (suspend (String?, String?, String?, Int, String?) -> Outcome<AdminAccountPage>)? = null
        var detail: ServerAdminAccount? = null
        var updateResult: Outcome<ServerAdminAccount> = Outcome.Success(ServerAdminAccount("own_1", "a@b.id", null, AdminRole.USER, AdminStatus.ACTIVE, 1))
        var invitation: AdminInvitation? = null
        var auditPageResult: AdminAuditPage = AdminAuditPage()

        val listCalls = mutableListOf<ListCall>()
        val detailCalls = mutableListOf<String>()
        val updateCalls = mutableListOf<Pair<String, AdminAccountPatch>>()
        val inviteCalls = mutableListOf<InviteCall>()
        val deactivateCalls = mutableListOf<Triple<String, String, Int>>()
        val auditCalls = mutableListOf<AuditCall>()

        var auditPage: (suspend (String?, String?, String?) -> AdminAuditPage?)? = null

        override suspend fun currentRole(ownerId: String): Outcome<String> {
            roleCalls++
            return roleResult
        }

        override suspend fun listAccounts(
            ownerId: String,
            query: String?,
            role: String?,
            status: String?,
            limit: Int,
            cursor: String?,
        ): Outcome<AdminAccountPage> {
            listCalls += ListCall(query, role, status, limit, cursor)
            val handler = listPage ?: return Outcome.Success(page)
            return handler(query, role, status, limit, cursor)
        }

        override suspend fun getAccount(
            ownerId: String,
            targetId: String,
        ): Outcome<ServerAdminAccount> {
            detailCalls += targetId
            val value = detail ?: return Outcome.Failure(AppError.Network("missing_detail"))
            return Outcome.Success(value)
        }

        override suspend fun createInvitation(
            ownerId: String,
            email: String,
            role: String,
            expiresInHours: Int,
        ): Outcome<AdminInvitation> {
            inviteCalls += InviteCall(email, role, expiresInHours)
            val value = invitation ?: return Outcome.Failure(AppError.Network("missing_invitation"))
            return Outcome.Success(value)
        }

        override suspend fun updateAccount(
            ownerId: String,
            targetId: String,
            patch: AdminAccountPatch,
        ): Outcome<ServerAdminAccount> {
            updateCalls += targetId to patch
            return updateResult
        }

        override suspend fun deactivateAccount(
            ownerId: String,
            targetId: String,
            revision: Int,
        ): Outcome<ServerAdminAccount> {
            deactivateCalls += Triple(ownerId, targetId, revision)
            return Outcome.Success(
                ServerAdminAccount(targetId, "a@b.id", null, AdminRole.USER,
                    AdminStatus.SUSPENDED, revision + 1),
            )
        }

        override suspend fun listAudit(
            ownerId: String,
            targetOwnerId: String?,
            action: String?,
            limit: Int,
            cursor: String?,
        ): Outcome<AdminAuditPage> {
            auditCalls += AuditCall(action, targetOwnerId, cursor)
            val handler = auditPage
            if (handler != null) {
                handler(action, targetOwnerId, cursor)?.let { return Outcome.Success(it) }
            }
            return Outcome.Success(auditPageResult)
        }
    }
}

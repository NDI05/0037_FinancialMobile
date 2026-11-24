package com.pam.recap.ui.feature.admin

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.R
import com.pam.recap.domain.repository.AdminInvitation
import com.pam.recap.domain.repository.AdminRole
import com.pam.recap.domain.repository.AdminStatus
import com.pam.recap.domain.repository.ServerAdminAccount
import com.pam.recap.domain.repository.ServerAdminInvitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4.2 (C21–C25) stateless admin screen: forbidden gate, account rows, search wiring, one-time
 * invitation token, confirmation-gated deactivation and cursor load-more.
 *
 * Every assertion resolves copy through `R.string.admin_*` so the test is locale-safe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "id-rID-w411dp-h891dp-xxhdpi")
class AdminScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun text(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun account(
        ownerId: String = "own_1",
        email: String = "ani@example.id",
        displayName: String? = "Ani",
        role: String = AdminRole.ADMIN,
        status: String = AdminStatus.ACTIVE,
        revision: Int = 3,
    ) = ServerAdminAccount(
        ownerId = ownerId,
        email = email,
        displayName = displayName,
        role = role,
        status = status,
        revision = revision,
    )

    private fun adminState(
        accounts: List<ServerAdminAccount> = emptyList(),
        nextCursor: String? = null,
    ) = AdminUiState(
        enabled = true,
        checkingRole = false,
        forbidden = false,
        role = AdminRole.ADMIN,
        accounts = accounts,
        nextCursor = nextCursor,
    )

    @Test
    fun `a forbidden state hides the admin controls`() {
        compose.setContent {
            AdminScreenContent(state = adminState().copy(forbidden = true))
        }

        compose.onNodeWithText(text(R.string.admin_forbidden)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.admin_search)).assertDoesNotExist()
    }

    @Test
    fun `account rows show identity role status and revision`() {
        compose.setContent {
            AdminScreenContent(state = adminState(listOf(account())))
        }

        compose.onNodeWithText("ani@example.id").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.admin_account_meta, "ADMIN", "ACTIVE", 3))
            .assertIsDisplayed()
        compose.onNodeWithText(text(R.string.admin_account_count, 1)).assertIsDisplayed()
    }

    @Test
    fun `the search field forwards the typed query`() {
        var query = ""
        compose.setContent {
            AdminScreenContent(
                state = adminState(),
                onQueryChange = { query = it },
            )
        }

        compose.onNodeWithTag("admin_search_field").performTextInput("ani")
        assertEquals("ani", query)
    }

    @Test
    fun `the search field stays editable while the first page loads`() {
        var query = ""
        compose.setContent {
            AdminScreenContent(
                state = adminState().copy(loading = true),
                onQueryChange = { query = it },
            )
        }

        compose.onNodeWithTag("admin_search_field").assertIsEnabled()
        compose.onNodeWithTag("admin_search_field").performTextInput("ani")
        assertEquals("ani", query)
    }

    @Test
    fun `the search field keeps earlier keystrokes while the list reloads`() {
        var query = ""
        compose.setContent {
            AdminScreenContent(
                // A reload between keystrokes must not drop what was already typed.
                state = adminState().copy(query = query, loading = true),
                onQueryChange = { query = it },
            )
        }

        compose.onNodeWithTag("admin_search_field").performTextInput("wqa")
        compose.onNodeWithTag("admin_search_field").performTextInput(".ui.b")
        assertEquals("wqa.ui.b", query)
    }

    @Test
    fun `the one-time invitation token is shown with a copy action and warning`() {
        var copied: String? = null
        val invitation = AdminInvitation(
            invitation = ServerAdminInvitation(
                id = "inv_1",
                email = "baru@example.id",
                role = AdminRole.USER,
                expiresAt = "2026-11-26T00:00:00Z",
            ),
            invitationToken = "tok_once_123",
        )
        compose.setContent {
            AdminScreenContent(
                state = adminState().copy(inviteOpen = true, invitation = invitation),
                onCopyToken = { copied = it },
            )
        }

        compose.onNodeWithText(text(R.string.admin_invite_token_warning)).assertIsDisplayed()
        compose.onNodeWithText("tok_once_123").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.admin_invite_token_copy)).performClick()
        assertEquals("tok_once_123", copied)
        assertTrue(copied == "tok_once_123")
        compose.onNodeWithText(text(R.string.admin_invite_token_copied)).assertIsDisplayed()
    }

    @Test
    fun `the invitation form forwards the typed email and expiry`() {
        var email = ""
        var expiry = ""
        compose.setContent {
            Column {
                AdminInvitationForm(
                    // Empty expiry so the typed value is the callback payload itself.
                    state = adminState().copy(inviteOpen = true, inviteExpiresHours = ""),
                    onEmailChange = { email = it },
                    onRoleChange = {},
                    onExpiryChange = { expiry = it },
                )
            }
        }

        compose.onNodeWithTag("admin_invite_email_field").performTextInput("baru@example.id")
        assertEquals("baru@example.id", email)
        compose.onNodeWithTag("admin_invite_expiry_field").performTextInput("12")
        assertEquals("12", expiry)
    }

    @Test
    fun `the invitation form shows validation and disables fields while busy`() {
        compose.setContent {
            Column {
                AdminInvitationForm(
                    state = adminState().copy(
                        inviteOpen = true,
                        busy = true,
                        inviteValidation = AdminFormRules.EMAIL,
                    ),
                    onEmailChange = {},
                    onRoleChange = {},
                    onExpiryChange = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.admin_invite_invalid_email)).assertIsDisplayed()
        compose.onNodeWithTag("admin_invite_email_field").assertIsNotEnabled()
        compose.onNodeWithTag("admin_invite_expiry_field").assertIsNotEnabled()
    }

    @Test
    fun `an account row forwards the edit and deactivate selection`() {
        var edit = 0
        var deactivate = 0
        val account = account()
        compose.setContent {
            AdminScreenContent(
                state = adminState(listOf(account)),
                onEditAccount = { edit++ },
                onRequestDeactivate = { deactivate++ },
            )
        }

        compose.onNodeWithContentDescription(text(R.string.admin_edit_title)).performClick()
        compose.onNodeWithContentDescription(text(R.string.admin_deactivate)).performClick()
        assertEquals(1, edit)
        assertEquals(1, deactivate)
    }

    @Test
    fun `a revision conflict keeps the editor open with a reload action`() {
        var reloads = 0
        val editor = AdminAccountEditor(
            ownerId = "own_1",
            email = "ani@example.id",
            displayName = "Draf Baru",
            role = AdminRole.USER,
            status = AdminStatus.ACTIVE,
            revision = 3,
        )
        compose.setContent {
            Column {
                AdminEditorForm(
                    state = adminState().copy(editor = editor, editorConflict = true),
                    editor = editor,
                    onDisplayNameChange = {},
                    onRoleChange = {},
                    onStatusChange = {},
                    onReload = { reloads++ },
                )
            }
        }

        compose.onNodeWithText(text(R.string.admin_edit_conflict)).assertIsDisplayed()
        compose.onNodeWithTag("admin_editor_display_name_field").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.admin_reload)).performClick()
        assertEquals(1, reloads)
    }

    @Test
    fun `the last admin rule is shown distinctly in the editor`() {
        val editor = AdminAccountEditor(
            ownerId = "own_1",
            email = "ani@example.id",
            displayName = "Ani",
            role = AdminRole.USER,
            status = AdminStatus.ACTIVE,
            revision = 3,
        )
        compose.setContent {
            Column {
                AdminEditorForm(
                    state = adminState().copy(editor = editor, lastAdmin = true),
                    editor = editor,
                    onDisplayNameChange = {},
                    onRoleChange = {},
                    onStatusChange = {},
                    onReload = {},
                )
            }
        }

        compose.onNodeWithText(text(R.string.admin_last_admin)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.admin_edit_conflict)).assertDoesNotExist()
    }

    @Test
    fun `deactivation requires the confirmation dialog`() {
        val state = mutableStateOf(adminState(listOf(account())))
        var cancelled = 0
        var confirmed = 0
        compose.setContent {
            AdminScreenContent(
                state = state.value,
                onRequestDeactivate = { state.value = state.value.copy(pendingDeactivate = it) },
                onCancelDeactivate = {
                    cancelled++
                    state.value = state.value.copy(pendingDeactivate = null)
                },
                onConfirmDeactivate = {
                    confirmed++
                    state.value = state.value.copy(pendingDeactivate = null)
                },
            )
        }

        compose.onNodeWithContentDescription(text(R.string.admin_deactivate)).performClick()
        compose.onNodeWithText(text(R.string.admin_deactivate_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_cancel)).performClick()
        assertEquals(1, cancelled)
        assertEquals(0, confirmed)

        compose.onNodeWithContentDescription(text(R.string.admin_deactivate)).performClick()
        compose.onNodeWithText(text(R.string.admin_deactivate_confirm)).performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `load more requests the next cursor page`() {
        var loadMore = 0
        compose.setContent {
            AdminScreenContent(
                state = adminState(listOf(account()), nextCursor = "cur_1"),
                onLoadMore = { loadMore++ },
            )
        }

        compose.onNodeWithText(text(R.string.admin_load_more)).performClick()
        assertEquals(1, loadMore)
    }
}

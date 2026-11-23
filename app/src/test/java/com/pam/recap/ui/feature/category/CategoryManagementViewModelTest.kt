package com.pam.recap.ui.feature.category

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.MonthlySummary
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.repository.CategoryDraft
import com.pam.recap.domain.repository.CategoryPatch
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerCategoryPage
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.ServerTransactionPage
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.ui.components.DynamicField
import com.pam.recap.ui.components.DynamicFieldRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

/**
 * S3 C06–C10: custom categories persist through the server contract, built-ins are protected and an
 * in-use delete is only completed by an explicit atomic reassignment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CategoryManagementViewModelTest {

    private val session = Session("u_owner", "Andhika", "andhika@example.test", Instant.parse("2026-11-01T00:00:00Z"), false)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(server: FakeCategories) = CategoryManagementViewModel(FakeSessionRepository(session), server)

    @Test
    fun `custom category is created once and survives a fresh view model`() = runTest {
        val server = FakeCategories()
        val first = vm(server)
        assertEquals(2, first.state.first { !it.isLoading }.categories.size)
        first.startCreate()
        first.onEditorChange(first.state.value.editor!!.copy(label = "Kopi", fields = listOf(DynamicField("cabang", "Sudirman"))))
        first.save()
        val saved = first.state.first { it.savedAt != null }
        assertNull(saved.editor)
        assertEquals(mapOf("cabang" to "Sudirman"), server.drafts.single().attributes)
        assertEquals(1, server.drafts.size)
        val reopened = vm(server)
        assertTrue(reopened.state.first { !it.isLoading }.categories.any { it.label == "Kopi" && it.attributes["cabang"] == "Sudirman" })
    }

    @Test
    fun `query filters owner scoped rows and reports a real no-result state`() = runTest {
        val model = vm(FakeCategories())
        model.state.first { !it.isLoading }
        model.onQueryChange("makan")
        advanceUntilIdle()
        assertEquals(listOf("food"), model.state.value.visible.map { it.code })
        model.onQueryChange("tidak-ada")
        advanceUntilIdle()
        assertTrue(model.state.value.visible.isEmpty())
        model.onQueryChange("")
        advanceUntilIdle()
        assertEquals(2, model.state.value.visible.size)
    }

    @Test
    fun `C10 query is answered by the owner-scoped server search envelope`() = runTest {
        val server = FakeCategories()
        val model = vm(server)
        model.state.first { !it.isLoading }

        model.onQueryChange("kopi")
        advanceUntilIdle()

        assertEquals(listOf("kopi"), server.searches)
        assertEquals(listOf<String?>(null), server.searchCursors)
        val state = model.state.value
        assertTrue(state.searchedOnServer)
        assertEquals(2, state.serverScanned)
        assertEquals(listOf("kopi_lama"), state.visible.map { it.code })
    }

    @Test
    fun `a typing burst makes one debounced owner-scoped search from a null cursor`() = runTest {
        val server = FakeCategories()
        val model = vm(server)
        model.state.first { !it.isLoading }

        listOf("k", "ko", "kop", "kopi").forEach { model.onQueryChange(it) }
        assertTrue(server.searches.isEmpty())

        advanceTimeBy(300)
        runCurrent()

        assertEquals(listOf("kopi"), server.searches)
        assertEquals(listOf<String?>(null), server.searchCursors)
    }

    @Test
    fun `a stale search response never overwrites a newer query`() = runTest {
        val server = FakeCategories().apply { gateSearch = true }
        val model = vm(server)
        model.state.first { !it.isLoading }

        model.onQueryChange("lama")
        advanceTimeBy(300)
        runCurrent()
        model.onQueryChange("baru")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("lama", "baru"), server.searches)

        // The newer query answers first (no row matches "baru").
        server.releaseSearch(1)
        runCurrent()
        assertTrue(model.state.value.visible.isEmpty())

        // The older "lama" response lands late and must be dropped by the generation guard.
        server.releaseSearch(0)
        runCurrent()
        assertTrue(model.state.value.visible.isEmpty())
        assertEquals("baru", model.state.value.query)
    }

    @Test
    fun `reload drops an in-flight search response`() = runTest {
        val server = FakeCategories().apply { gateSearch = true }
        val model = vm(server)
        model.state.first { !it.isLoading }

        model.onQueryChange("kopi")
        advanceTimeBy(300)
        runCurrent()
        model.load()
        model.state.first { !it.isLoading }

        server.releaseSearch(0)
        runCurrent()

        val state = model.state.value
        assertFalse(state.searchedOnServer)
        // The reloaded owner-scoped page wins; the search payload never replaced it.
        assertEquals(2, state.categories.size)
        assertNull(state.error)
    }

    @Test
    fun `builtin categories can never be opened for edit or delete`() = runTest {
        val model = vm(FakeCategories())
        val builtin = model.state.first { !it.isLoading }.categories.first { it.isBuiltin }
        model.startEdit(builtin)
        assertNull(model.state.value.editor)
        assertEquals("builtin", model.state.value.validationRule)
        model.requestDelete(builtin)
        assertNull(model.state.value.pendingDelete)
        assertEquals("builtin", model.state.value.validationRule)
    }

    @Test
    fun `deleting an in-use category requires a replacement and moves the rows`() = runTest {
        val server = FakeCategories(inUse = true)
        val model = vm(server)
        val custom = model.state.first { !it.isLoading }.categories.first { !it.isBuiltin }
        model.requestDelete(custom)
        model.confirmDelete()
        assertNotNull(model.state.first { !it.busy && it.deleteNeedsReassign }.pendingDelete)
        assertTrue(server.deletes.isEmpty())
        model.confirmDelete("cat_food")
        model.state.first { it.pendingDelete == null }
        assertEquals("cat_food", server.deletes.single().second)
        assertFalse(model.state.value.categories.any { it.id == custom.id })
    }

    @Test
    fun `a concurrent edit keeps the draft and asks for a reload instead of overwriting`() = runTest {
        val server = FakeCategories(updateConflict = true)
        val model = vm(server)
        val custom = model.state.first { !it.isLoading }.categories.first { !it.isBuiltin }
        model.startEdit(custom)
        model.onEditorChange(model.state.value.editor!!.copy(label = "Kopi Baru"))
        model.save()
        val conflicted = model.state.first { it.validationRule == "conflict" }
        assertEquals("Kopi Baru", conflicted.editor!!.label)
        assertEquals(0, server.patches)
        assertTrue(conflicted.error is AppError.Conflict)
    }

    @Test
    fun `dynamic field limits block the write before any request`() = runTest {
        val server = FakeCategories()
        val model = vm(server)
        model.state.first { !it.isLoading }
        model.startCreate()
        model.onEditorChange(model.state.value.editor!!.copy(label = "Banyak", fields = (1..11).map { DynamicField("k$it", "v") }))
        model.save()
        assertEquals("dynamic_fields_too_many", model.state.value.validationRule)
        model.onEditorChange(model.state.value.editor!!.copy(fields = listOf(DynamicField("k".repeat(41), "v"))))
        model.save()
        assertEquals("dynamic_field_key_too_long", model.state.value.validationRule)
        model.onEditorChange(model.state.value.editor!!.copy(fields = listOf(DynamicField("k", "v".repeat(201)))))
        model.save()
        assertEquals("dynamic_field_value_too_long", model.state.value.validationRule)
        assertTrue(server.drafts.isEmpty())
    }

    @Test
    fun `the payload ceiling counts utf8 bytes not characters`() {
        val big = List(10) { DynamicField("k$it", "é".repeat(190)) }
        assertEquals("dynamic_fields_too_large", DynamicFieldRules.validate(big))
        assertEquals(null, DynamicFieldRules.validate(List(10) { DynamicField("k$it", "v") }))
    }

    private class FakeCategories(
        private val inUse: Boolean = false,
        private val updateConflict: Boolean = false,
    ) : ServerFinanceRepository {
        override val isEnabled = true
        val drafts = mutableListOf<CategoryDraft>()
        val deletes = mutableListOf<Pair<String, String?>>()
        var patches = 0
        private val rows = mutableListOf(
            ServerCategory("cat_food", "food", "Makanan", "EXPENSE", isBuiltin = true, revision = 1),
            ServerCategory("cat_custom", "kopi_lama", "Kopi Lama", "EXPENSE", revision = 2),
        )

        val searches = mutableListOf<String>()
        val searchCursors = mutableListOf<String?>()
        var gateSearch = false
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        fun releaseSearch(index: Int) {
            gates[index].complete(Unit)
        }

        override suspend fun categories(ownerId: String) = Outcome.Success(rows.toList())

        override suspend fun searchCategories(
            ownerId: String,
            query: String,
            kind: String?,
            limit: Int,
            cursor: String?,
        ): Outcome<ServerCategoryPage> {
            searches += query
            searchCursors += cursor
            if (gateSearch) {
                val gate = CompletableDeferred<Unit>()
                gates += gate
                // NonCancellable so a "stale response" scenario can still deliver its payload.
                withContext(NonCancellable) { gate.await() }
            }
            val filtered = rows.filter {
                it.code.contains(query, ignoreCase = true) || it.label.contains(query, ignoreCase = true)
            }
            return Outcome.Success(ServerCategoryPage(filtered, scanned = rows.size))
        }

        override suspend fun createCategory(ownerId: String, draft: CategoryDraft): Outcome<ServerCategory> {
            drafts += draft
            val row = ServerCategory("cat_${draft.code}", draft.code, draft.label, draft.kind, attributes = draft.attributes, revision = 1)
            rows += row
            return Outcome.Success(row)
        }

        override suspend fun updateCategoryMeta(ownerId: String, categoryId: String, patch: CategoryPatch): Outcome<ServerCategory> {
            if (updateConflict) return Outcome.Failure(AppError.Conflict("revision_conflict"))
            patches += 1
            val index = rows.indexOfFirst { it.id == categoryId }
            if (index < 0) return Outcome.Failure(AppError.NotFound("missing"))
            rows[index] = rows[index].copy(label = patch.label ?: rows[index].label, revision = rows[index].revision + 1)
            return Outcome.Success(rows[index])
        }

        override suspend fun deleteCategory(ownerId: String, categoryId: String, reassignTo: String?): Outcome<Unit> {
            if (inUse && reassignTo == null) return Outcome.Failure(AppError.Conflict("category_in_use"))
            deletes += categoryId to reassignTo
            rows.removeAll { it.id == categoryId }
            return Outcome.Success(Unit)
        }

        override suspend fun createManual(ownerId: String, draft: ManualTransactionDraft, idempotencyKey: String) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun listMonth(ownerId: String, month: JakartaMonth, limit: Int, cursor: String?) =
            Outcome.Success(ServerTransactionPage(emptyList()))
        override suspend fun get(ownerId: String, transactionId: String) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun updateCategory(ownerId: String, transactionId: String, categoryId: String, revision: Int) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun summary(ownerId: String, month: JakartaMonth) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun classifyTransactions(ownerId: String, transactionIds: List<String>) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun enqueueCategories(ownerId: String, transactionIds: List<String>) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun drainCategories(ownerId: String) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun createImportJob(ownerId: String, month: JakartaMonth, templateVersion: String) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun importBatch(ownerId: String, jobId: String, batchKey: String, pageOrdinal: Int, candidates: List<com.pam.recap.domain.repository.ServerImportCandidate>) =
            Outcome.Failure(AppError.NotFound("unused"))
        override suspend fun finalizeImportJob(ownerId: String, jobId: String, state: String, errorCode: String?) =
            Outcome.Failure(AppError.NotFound("unused"))
    }
}

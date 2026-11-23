package com.pam.recap.ui.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.CategoryDraft
import com.pam.recap.domain.repository.CategoryPatch
import com.pam.recap.domain.repository.ServerCategory
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.ui.components.DynamicField
import com.pam.recap.ui.components.DynamicFieldRules
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Editor state for both creating and renaming a category. */
data class CategoryEditor(
    val id: String? = null,
    val code: String = "",
    val label: String = "",
    val kind: String = KIND_EXPENSE,
    val color: String = "",
    val revision: Int = 0,
    val fields: List<DynamicField> = listOf(DynamicField()),
    val isBuiltin: Boolean = false,
) {
    val isNew: Boolean get() = id == null

    companion object {
        const val KIND_EXPENSE = "EXPENSE"
        const val KIND_INCOME = "INCOME"
    }
}

data class CategoryManagementUiState(
    val isLoading: Boolean = true,
    val serverEnabled: Boolean = false,
    val categories: List<ServerCategory> = emptyList(),
    val query: String = "",
    /** Rows the server examined for the active search; 0 when no server search ran yet. */
    val serverScanned: Int = 0,
    /** True once the visible rows come from the server `q` search rather than the loaded page. */
    val searchedOnServer: Boolean = false,
    /** The server page was cut by its limit/cursor: more matches exist beyond the visible rows. */
    val hasMore: Boolean = false,
    val editor: CategoryEditor? = null,
    val pendingDelete: ServerCategory? = null,
    val deleteNeedsReassign: Boolean = false,
    val busy: Boolean = false,
    val error: AppError? = null,
    val validationRule: String? = null,
    val savedAt: Long? = null,
) {
    /** Case-insensitive filter over code and label; the list is already owner-scoped. */
    val visible: List<ServerCategory>
        get() {
            val needle = query.trim()
            if (needle.isEmpty()) return categories
            return categories.filter {
                it.code.contains(needle, ignoreCase = true) || it.label.contains(needle, ignoreCase = true)
            }
        }
}

/**
 * S3 category CRUD over the server contract: custom categories persist, built-ins are protected and
 * deleting an in-use category requires an explicit atomic reassignment (the server does the move).
 */
@HiltViewModel
class CategoryManagementViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val server: ServerFinanceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CategoryManagementUiState())
    val state: StateFlow<CategoryManagementUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        // Menaikkan generation membatalkan respons search yang masih in-flight: reload tidak boleh
        // ditimpa hasil query lama.
        searchGeneration++
        queryJob?.cancel()
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    serverScanned = 0,
                    searchedOnServer = false,
                    hasMore = false,
                )
            }
            if (!server.isEnabled) {
                _state.update { it.copy(isLoading = false, serverEnabled = false) }
                return@launch
            }
            val ownerId = ownerId() ?: run {
                _state.update { it.copy(isLoading = false, serverEnabled = false) }
                return@launch
            }
            when (val result = server.categories(ownerId)) {
                is Outcome.Success -> _state.update {
                    it.copy(isLoading = false, serverEnabled = true, categories = result.value)
                }
                is Outcome.Failure -> _state.update {
                    it.copy(isLoading = false, serverEnabled = true, error = result.error)
                }
            }
        }
    }

    private var searchGeneration = 0
    private var queryJob: Job? = null

    /**
     * C10: once a backend search envelope is available the query is answered by the server. Typing
     * is debounced by [QUERY_DEBOUNCE_MS] so a burst of keystrokes produces one request, and a late
     * response for an older query is dropped instead of overwriting newer rows.
     */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        if (!_state.value.serverEnabled) return
        val generation = ++searchGeneration
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(QUERY_DEBOUNCE_MS)
            val ownerId = ownerId() ?: return@launch
            when (val result = server.searchCategories(ownerId, query.trim())) {
                is Outcome.Success -> if (generation == searchGeneration) {
                    _state.update {
                        it.copy(
                            categories = result.value.items,
                            serverScanned = result.value.scanned,
                            searchedOnServer = true,
                            hasMore = result.value.nextCursor != null || result.value.truncated,
                        )
                    }
                }

                is Outcome.Failure -> if (generation == searchGeneration &&
                    result.error !is AppError.Configuration
                ) {
                    // Configuration means this backend predates the envelope: the owner-scoped page
                    // already loaded stays visible and the local filter keeps working.
                    _state.update { it.copy(error = result.error) }
                }
            }
        }
    }

    fun startCreate() = _state.update {
        it.copy(editor = CategoryEditor(), error = null, validationRule = null)
    }

    fun startEdit(category: ServerCategory) = _state.update {
        if (category.isBuiltin) {
            it.copy(error = AppError.Validation("builtin_category"), validationRule = "builtin")
        } else {
            it.copy(
                editor = CategoryEditor(
                    id = category.id,
                    code = category.code,
                    label = category.label,
                    kind = category.kind.uppercase(),
                    color = category.color.orEmpty(),
                    revision = category.revision,
                    fields = DynamicFieldRules.fromMap(category.attributes),
                ),
                error = null,
                validationRule = null,
            )
        }
    }

    fun dismissEditor() = _state.update { it.copy(editor = null) }

    fun onEditorChange(editor: CategoryEditor) = _state.update { it.copy(editor = editor) }

    fun save() {
        val editor = _state.value.editor ?: return
        val fields = editor.fields
        DynamicFieldRules.validate(fields)?.let { rule ->
            _state.update { it.copy(validationRule = rule, error = null) }
            return
        }
        val label = editor.label.trim()
        if (label.isEmpty()) {
            _state.update { it.copy(validationRule = "label_required", error = null) }
            return
        }
        viewModelScope.launch {
            val ownerId = ownerId() ?: return@launch
            _state.update { it.copy(busy = true, error = null, validationRule = null) }
            val attributes = DynamicFieldRules.toMap(fields)
            val result = if (editor.isNew) {
                val code = editor.code.trim().ifEmpty {
                    label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                }
                server.createCategory(
                    ownerId,
                    CategoryDraft(code = code, label = label, kind = editor.kind, color = editor.color.ifBlank { null }, attributes = attributes),
                )
            } else {
                server.updateCategoryMeta(
                    ownerId,
                    editor.id!!,
                    CategoryPatch(
                        revision = editor.revision,
                        label = label,
                        kind = editor.kind,
                        color = editor.color.ifBlank { null },
                        attributes = attributes,
                    ),
                )
            }
            when (result) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(busy = false, editor = null, savedAt = System.currentTimeMillis())
                    }
                    load()
                }
                is Outcome.Failure -> _state.update {
                    it.copy(
                        busy = false,
                        error = result.error,
                        validationRule = if (result.error is AppError.Conflict) "conflict" else it.validationRule,
                    )
                }
            }
        }
    }

    fun requestDelete(category: ServerCategory) = _state.update {
        if (category.isBuiltin) {
            it.copy(error = AppError.Validation("builtin_category"), validationRule = "builtin")
        } else {
            it.copy(pendingDelete = category, deleteNeedsReassign = false, error = null, validationRule = null)
        }
    }

    fun cancelDelete() = _state.update { it.copy(pendingDelete = null, deleteNeedsReassign = false) }

    /**
     * Deletes the pending category. When the server reports "still in use" the dialog switches to a
     * replacement picker instead of deleting transactions or silently detaching them.
     */
    fun confirmDelete(reassignToId: String? = null) {
        val target = _state.value.pendingDelete ?: return
        viewModelScope.launch {
            val ownerId = ownerId() ?: return@launch
            _state.update { it.copy(busy = true, error = null) }
            when (val result = server.deleteCategory(ownerId, target.id, reassignToId)) {
                is Outcome.Success -> {
                    _state.update { it.copy(busy = false, pendingDelete = null, deleteNeedsReassign = false) }
                    load()
                }
                is Outcome.Failure -> _state.update {
                    it.copy(
                        busy = false,
                        error = result.error,
                        deleteNeedsReassign = result.error is AppError.Conflict && reassignToId == null,
                    )
                }
            }
        }
    }

    private suspend fun ownerId(): String? =
        sessions.observe().filterNotNull().first().ownerId

    private companion object {
        /** One request per typing burst instead of one per keystroke. */
        const val QUERY_DEBOUNCE_MS = 250L
    }
}

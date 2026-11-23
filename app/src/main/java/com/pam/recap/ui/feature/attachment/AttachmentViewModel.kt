package com.pam.recap.ui.feature.attachment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.data.attachment.PrivateAttachmentStore
import com.pam.recap.domain.repository.AttachmentDraft
import com.pam.recap.domain.repository.ServerAttachment
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import javax.inject.Inject

/** S4 C16–C19 attachment panel state for one transaction. */
data class AttachmentUiState(
    val isLoading: Boolean = true,
    val serverEnabled: Boolean = false,
    val attachments: List<ServerAttachment> = emptyList(),
    val nextCursor: String? = null,
    val busy: Boolean = false,
    val error: AppError? = null,
    val validationRule: String? = null,
    val notice: String? = null,
    val conflict: Boolean = false,
) {
    val canLoadMore: Boolean get() = nextCursor != null
}

/**
 * Attachment metadata management for a single transaction. File bytes are stored in app-private
 * storage only; this ViewModel uploads metadata and never the content.
 */
@HiltViewModel
class AttachmentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val server: ServerFinanceRepository,
    private val store: PrivateAttachmentStore,
) : ViewModel() {

    val transactionId: String = savedStateHandle.get<String>("transactionId").orEmpty()

    private val _state = MutableStateFlow(AttachmentUiState())
    val state: StateFlow<AttachmentUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(loadMore: Boolean = false) {
        viewModelScope.launch {
            if (!loadMore) _state.update { it.copy(isLoading = true, error = null) }
            if (!server.isEnabled || transactionId.isBlank()) {
                _state.update { it.copy(isLoading = false, serverEnabled = false) }
                return@launch
            }
            val ownerId = ownerId() ?: return@launch
            val cursor = if (loadMore) _state.value.nextCursor else null
            when (val result = server.attachments(ownerId, transactionId, cursor = cursor)) {
                is Outcome.Success -> _state.update {
                    val merged = if (loadMore) it.attachments + result.value.items else result.value.items
                    it.copy(
                        isLoading = false,
                        serverEnabled = true,
                        attachments = merged.distinctBy { row -> row.id },
                        nextCursor = result.value.nextCursor,
                    )
                }
                is Outcome.Failure -> {
                    SafeLogger.warning(TAG, "attachment_load_failed", result.error)
                    _state.update {
                        it.copy(isLoading = false, serverEnabled = true, error = result.error)
                    }
                }
            }
        }
    }

    /**
     * Copies [source] into private storage, then registers the metadata. If the server write fails,
     * the local copy is deleted so an unsynced file cannot look attached; nothing is faked.
     */
    fun attach(fileName: String, mimeType: String, source: InputStream) {
        viewModelScope.launch {
            val ownerId = ownerId() ?: return@launch
            _state.update { it.copy(busy = true, error = null, validationRule = null, notice = null) }
            val imported = store.import(ownerId, transactionId, fileName, mimeType, source)
            if (imported is Outcome.Failure) {
                SafeLogger.warning(TAG, "attachment_import_failed", imported.error)
                _state.update { it.copy(busy = false, error = imported.error) }
                return@launch
            }
            val file = (imported as Outcome.Success).value
            val result = server.createAttachment(
                ownerId,
                AttachmentDraft(
                    transactionId = transactionId,
                    fileName = file.fileName,
                    mimeType = file.mimeType,
                    sizeBytes = file.sizeBytes,
                    sha256 = file.sha256,
                    storageRef = file.storageRef,
                    localFileId = file.localFileId,
                ),
            )
            when (result) {
                is Outcome.Success -> {
                    // Bytes stay in private storage so the attachment can be reopened offline.
                    _state.update { it.copy(busy = false, notice = "attachment_saved") }
                    load()
                }
                is Outcome.Failure -> {
                    SafeLogger.warning(TAG, "attachment_create_failed", result.error)
                    store.delete(file.storageRef)
                    _state.update { it.copy(busy = false, error = result.error) }
                }
            }
        }
    }

    /** Deletes metadata with revision CAS; a stale revision asks the user to reload, never forces. */
    fun remove(attachment: ServerAttachment) {
        viewModelScope.launch {
            val ownerId = ownerId() ?: return@launch
            _state.update { it.copy(busy = true, error = null, conflict = false) }
            when (val result = server.deleteAttachment(ownerId, attachment.id, attachment.revision)) {
                is Outcome.Success -> {
                    _state.update { it.copy(busy = false) }
                    store.delete(attachment.storageRef)
                    load()
                }
                is Outcome.Failure -> {
                    SafeLogger.warning(TAG, "attachment_delete_failed", result.error)
                    _state.update {
                        it.copy(
                            busy = false,
                            error = result.error,
                            conflict = result.error is AppError.Conflict,
                        )
                    }
                }
            }
        }
    }

    private suspend fun ownerId(): String? {
        val owner = sessions.observe().filterNotNull().first().ownerId
        if (transactionId.isBlank()) {
            _state.update { it.copy(isLoading = false, serverEnabled = false) }
            return null
        }
        return owner
    }
}

private const val TAG = "AttachmentVM"

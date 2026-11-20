package com.pam.recap.data.gmail

import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.GmailCredentialValidator
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.RawMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.UIDFolder
import javax.mail.Store
import javax.mail.search.AndTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SubjectTerm
import kotlin.time.Instant

/**
 * Real Gmail over **standard IMAP** (FR-06/07/10/11/16/18/55, CR-001).
 *
 * Portability was the deciding factor: `FROM`, `SUBJECT` and `ReceivedDateTerm` are plain RFC 3501
 * search keys, whereas `X-GM-RAW`/`X-GM-MSGID` are Gmail extensions that would add a vendor lock
 * and are not exposed by javax.mail without hand-written command support. Message identity is
 * therefore `UIDVALIDITY:UID` — stable within a mailbox session and portable to any IMAP server.
 *
 * Guarantees:
 * - TLS only: the `imaps` protocol on port 993 with hostname verification; there is no cleartext
 *   fallback path in this class (NFR-03).
 * - `INBOX` is opened with [Folder.READ_ONLY] and only `search`/`getMessageByUID` are issued, so no
 *   command can mutate message or folder state (FR-55).
 * - credentials come from [AppPasswordVault] only; a login is never attempted without one.
 * - everything runs on the IO dispatcher; one store is reused across an import job and closed on
 *   disconnect, and each folder is closed in `finally`.
 */
@Singleton
class ImapGmailSource @Inject constructor(
    private val vault: AppPasswordVault,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : GmailSource, GmailMailboxLabelProvider, GmailCredentialValidator {

    private val connection = MutableStateFlow(GmailConnectionState.NOT_CONNECTED)
    private val mutex = Mutex()
    private var handle: StoreHandle? = null

    @Volatile
    private var cachedLabel: String? = null

    override fun connectionState(): Flow<GmailConnectionState> = connection.asStateFlow()

    override fun mailboxLabel(): String? = cachedLabel

    override suspend fun authorize(): Outcome<Unit> = withContext(ioDispatcher) {
        val credential = vault.read()
        if (credential == null) {
            connection.value = GmailConnectionState.NOT_CONNECTED
            return@withContext Outcome.Failure(AppError.Configuration("imap_not_configured"))
        }
        mutex.withLock {
            try {
                val store = connect(credential.email, credential.appPassword)
                handle?.let { runCatching { it.store.close() } }
                handle = StoreHandle(credential.email, store)
                cachedLabel = credential.email
                connection.value = GmailConnectionState.CONNECTED
                SafeLogger.event(TAG, "imap_connected")
                Outcome.Success(Unit)
            } catch (error: Throwable) {
                connection.value = stateForFailure(error)
                SafeLogger.failure(TAG, "imap_authorize_failed")
                Outcome.Failure(mapImapFailure(error))
            }
        }
    }

    override suspend fun validate(email: String, appPassword: String): Outcome<Unit> =
        withContext(ioDispatcher) {
            try {
                // FR-54: validate with a real login before the caller stores anything.
                val store = connect(email, appPassword)
                runCatching { store.close() }
                Outcome.Success(Unit)
            } catch (error: Throwable) {
                // The mapped code distinguishes a rejected password (PERMISSION_REQUIRED) from an
                // unreachable server (NETWORK) without logging anything about the credential.
                val mapped = mapImapFailure(error)
                SafeLogger.failure(TAG, "imap_validation_failed", mapped)
                Outcome.Failure(mapped)
            }
        }

    override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> =
        withFolder { folder ->
            val offset = parseOffset(pageToken)
            val limit = query.pageSize.coerceIn(1, LivinQuery.MAX_PAGE_SIZE)
            val matches = folder.search(query.toSearchTerm())
                .sortedByDescending { it.receivedDate?.time ?: 0L }
            val window = matches.drop(offset).take(limit)
            val nextOffset = offset + window.size
            Outcome.Success(
                GmailPage(
                    messages = window.mapNotNull { folder.toRef(it) },
                    nextPageToken = if (nextOffset < matches.size) "offset:$nextOffset" else null,
                    resultSizeEstimate = matches.size,
                ),
            )
        }

    override suspend fun fetchMessage(id: String): Outcome<RawMessage> = withFolder { folder ->
        val uidFolder = folder as? UIDFolder
            ?: return@withFolder Outcome.Failure(AppError.Configuration("imap_no_uid_support"))
        val (uidValidity, uid) = parseMessageId(id)
            ?: return@withFolder Outcome.Failure(AppError.NotFound("imap_bad_reference"))
        if (uidFolder.uidValidity != uidValidity) {
            // The mailbox was recreated since the page was read: the reference is stale, not an error.
            return@withFolder Outcome.Failure(AppError.NotFound("imap_uidvalidity_changed"))
        }
        val message = uidFolder.getMessageByUID(uid)
            ?: return@withFolder Outcome.Failure(AppError.NotFound("imap_message_gone"))
        Outcome.Success(message.toRaw(id))
    }

    override suspend fun disconnect(): Outcome<Unit> = withContext(ioDispatcher) {
        mutex.withLock {
            closeHandle()
            cachedLabel = null
            connection.value = GmailConnectionState.DISCONNECTED
            Outcome.Success(Unit)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    private suspend fun <T> withFolder(block: (Folder) -> Outcome<T>): Outcome<T> =
        withContext(ioDispatcher) {
            val credential = vault.read()
            if (credential == null) {
                connection.value = GmailConnectionState.NOT_CONNECTED
                return@withContext Outcome.Failure(AppError.Configuration("imap_not_configured"))
            }
            mutex.withLock {
                var folder: Folder? = null
                try {
                    val store = currentStore(credential.email, credential.appPassword)
                    folder = store.getFolder(INBOX)
                    // FR-55: EXAMINE semantics — this mailbox is never written to.
                    folder.open(Folder.READ_ONLY)
                    block(folder)
                } catch (error: Throwable) {
                    connection.value = stateForFailure(error)
                    SafeLogger.failure(TAG, "imap_request_failed")
                    closeHandle()
                    Outcome.Failure(mapImapFailure(error))
                } finally {
                    folder?.let { open -> runCatching { if (open.isOpen) open.close(false) } }
                }
            }
        }

    private fun currentStore(email: String, appPassword: String): Store {
        val existing = handle
        if (existing != null && existing.store.isConnected) return existing.store
        val store = connect(email, appPassword)
        handle = StoreHandle(email, store)
        cachedLabel = email
        connection.value = GmailConnectionState.CONNECTED
        return store
    }

    private fun connect(email: String, appPassword: String): Store {
        val session = Session.getInstance(properties())
        val store = session.getStore(PROTOCOL)
        store.connect(HOST, PORT, email, appPassword)
        return store
    }

    private fun closeHandle() {
        val existing = handle ?: return
        handle = null
        runCatching { if (existing.store.isConnected) existing.store.close() }
    }

    private fun properties(): Properties = Properties().apply {
        put("mail.$PROTOCOL.ssl.enable", "true")
        put("mail.$PROTOCOL.ssl.checkserveridentity", "true")
        put("mail.$PROTOCOL.connectiontimeout", CONNECT_TIMEOUT_MILLIS.toString())
        put("mail.$PROTOCOL.timeout", READ_TIMEOUT_MILLIS.toString())
        put("mail.$PROTOCOL.writetimeout", READ_TIMEOUT_MILLIS.toString())
    }

    private fun Folder.toRef(message: Message): GmailMessageRef? {
        val uidFolder = this as? UIDFolder ?: return null
        return runCatching {
            GmailMessageRef(
                id = "${uidFolder.uidValidity}:${uidFolder.getUID(message)}",
                internalDate = message.receivedDate?.let { Instant.fromEpochMilliseconds(it.time) },
            )
        }.getOrNull()
    }

    private fun Message.toRaw(reference: String): RawMessage {
        val body = extractMimeBody(this)
        SafeLogger.counts(
            TAG,
            "imap_mime",
            "parts" to body.visitedParts,
            "plain" to body.plainParts,
            "html" to body.htmlParts,
            "attachments" to body.attachmentCount,
            "chars" to body.text.length,
            *body.kindCounts.map { (kind, count) -> kind to count }.toTypedArray(),
        )
        return RawMessage(
            messageId = reference,
            sender = runCatching { from?.firstOrNull()?.toString() }.getOrNull(),
            subject = subject,
            internalDate = receivedDate?.let { Instant.fromEpochMilliseconds(it.time) },
            textParts = listOf(body.text),
            attachmentCount = body.attachmentCount,
        )
    }

    /** Allow-listed sender + subject + received-date window; every term comes from the registry. */
    private fun LivinQuery.toSearchTerm(): SearchTerm {
        val senders = senderPatterns.filter { it.isNotBlank() }.map(::FromStringTerm)
        val subjects = subjectPatterns.filter { it.isNotBlank() }.map(::SubjectTerm)
        val window = AndTerm(
            ReceivedDateTerm(ComparisonTerm.GE, java.util.Date(fromInclusive.toEpochMilliseconds())),
            ReceivedDateTerm(ComparisonTerm.LT, java.util.Date(toExclusive.toEpochMilliseconds())),
        )
        val senderTerm = if (senders.size == 1) senders.first() else OrTerm(senders.toTypedArray())
        val subjectTerm = if (subjects.size == 1) subjects.first() else OrTerm(subjects.toTypedArray())
        return AndTerm(arrayOf(senderTerm, subjectTerm, window))
    }

    private fun parseOffset(pageToken: String?): Int {
        if (pageToken == null) return 0
        return pageToken.substringAfter("offset:", pageToken).toIntOrNull()?.coerceAtLeast(0) ?: 0
    }

    private fun parseMessageId(id: String): Pair<Long, Long>? {
        val parts = id.split(':')
        if (parts.size != 2) return null
        val uidValidity = parts[0].toLongOrNull() ?: return null
        val uid = parts[1].toLongOrNull() ?: return null
        return uidValidity to uid
    }

    private data class StoreHandle(val email: String, val store: Store)

    private companion object {
        const val TAG = "ImapGmail"
        const val PROTOCOL = "imaps"
        const val HOST = "imap.gmail.com"
        const val PORT = 993
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
        const val INBOX = "INBOX"
    }
}

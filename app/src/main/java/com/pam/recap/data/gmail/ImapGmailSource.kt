package com.pam.recap.data.gmail

import com.pam.recap.core.di.IoDispatcher
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.MailboxWatermark
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.GmailCredentialValidator
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.SessionRepository
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
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
    private val sessions: SessionRepository,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : GmailSource, GmailMailboxLabelProvider, GmailCredentialValidator {

    private val connection = MutableStateFlow(GmailConnectionState.NOT_CONNECTED)
    private val mutex = Mutex()
    private var handle: StoreHandle? = null
    /** Kept open for the whole import job: opening INBOX per message cost ~1.8 s each. */
    private var openFolder: Folder? = null
    private var session: Session? = null
    /** Folder search results, so page 2 does not re-run the same Gmail SEARCH. */
    private var cachedSearch: SearchHit? = null

    @Volatile
    private var cachedLabel: String? = null

    override fun connectionState(): Flow<GmailConnectionState> = connection.asStateFlow()

    override fun mailboxLabel(): String? = cachedLabel

    override suspend fun authorize(): Outcome<Unit> = withContext(ioDispatcher) {
        val ownerId = sessions.current()?.ownerId
        val credential = ownerId?.let { vault.read(it) }
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
            // Offset paging used to re-run SEARCH for every page; the ids are fetched once per job.
            val cacheKey = query.cacheKey(limit)
            val refs = if (pageToken == null) {
                folder.search(query.toSearchTerm())
                    .sortedByDescending { it.receivedDate?.time ?: 0L }
                    .mapNotNull { folder.toRef(it) }
                    .also { cachedSearch = SearchHit(cacheKey, it) }
            } else {
                cachedSearch?.takeIf { it.key == cacheKey }?.refs
                    ?: folder.search(query.toSearchTerm())
                        .sortedByDescending { it.receivedDate?.time ?: 0L }
                        .mapNotNull { folder.toRef(it) }
                        .also { cachedSearch = SearchHit(cacheKey, it) }
            }
            val window = refs.drop(offset).take(limit)
            val nextOffset = offset + window.size
            SafeLogger.counts(
                TAG,
                "imap_page",
                "offset" to offset,
                "window" to window.size,
                "matches" to refs.size,
                "cached" to if (cachedSearch?.key == cacheKey && pageToken != null) 1 else 0,
            )
            Outcome.Success(
                GmailPage(
                    messages = window,
                    nextPageToken = if (nextOffset < refs.size) "offset:$nextOffset" else null,
                    resultSizeEstimate = refs.size,
                ),
            )
        }

    /**
     * Current mailbox high-water mark. `UIDNEXT - 1` is the highest uid the folder has issued, which
     * is a folder-level integer: no message identity is retained.
     */
    override suspend fun watermark(): Outcome<MailboxWatermark> = withFolder { folder ->
        val uidFolder = folder as? UIDFolder
            ?: return@withFolder Outcome.Failure(AppError.Configuration("imap_no_uid_support"))
        val next = runCatching { uidFolder.uidNext }.getOrDefault(-1L)
        if (next <= 0) {
            return@withFolder Outcome.Failure(AppError.NotFound("imap_no_uidnext"))
        }
        Outcome.Success(MailboxWatermark(uidFolder.uidValidity, next - 1))
    }

    /**
     * References above [since] and at most [upTo]. The upper bound is the watermark captured before
     * the run, so a message that lands mid-run is simply left for the next run instead of being
     * half-processed. A mismatched UIDVALIDITY (folder recreated) invalidates the cursor: the delta
     * then starts from the beginning of the query window rather than from a meaningless uid.
     */
    override suspend fun searchDelta(
        query: LivinQuery,
        since: MailboxWatermark?,
        upTo: MailboxWatermark?,
        pageToken: String?,
    ): Outcome<GmailPage> = withFolder { folder ->
        val uidFolder = folder as? UIDFolder
            ?: return@withFolder Outcome.Failure(AppError.Configuration("imap_no_uid_support"))
        val uidValidity = uidFolder.uidValidity
        val floor = since?.takeIf { it.uidValidity == uidValidity }?.highestUid ?: 0L
        val ceiling = upTo?.takeIf { it.uidValidity == uidValidity }?.highestUid
        val limit = query.pageSize.coerceIn(1, LivinQuery.MAX_PAGE_SIZE)
        val offset = parseOffset(pageToken)
        val cacheKey = "delta|$uidValidity|$floor|$ceiling|" + query.cacheKey(limit)
        val refs = cachedSearch?.takeIf { it.key == cacheKey }?.refs
            ?: run {
                val term = query.toSearchTerm()
                val fetched = runCatching {
                    uidFolder.getMessagesByUID(floor + 1, UIDFolder.LASTUID)
                        .asSequence()
                        .filter { message ->
                            val uid = runCatching { uidFolder.getUID(message) }.getOrDefault(-1L)
                            uid > floor && (ceiling == null || uid <= ceiling)
                        }
                        .filter { message -> runCatching { term.match(message) }.getOrDefault(false) }
                        .sortedByDescending { it.receivedDate?.time ?: 0L }
                        .toList()
                }.getOrElse { return@withFolder Outcome.Failure(mapImapFailure(it)) }
                fetched.mapNotNull { folder.toRef(it) }.also { cachedSearch = SearchHit(cacheKey, it) }
            }
        val window = refs.drop(offset).take(limit)
        val nextOffset = offset + window.size
        SafeLogger.counts(
            TAG,
            "imap_delta",
            "floor" to floor.toInt(),
            "window" to window.size,
            "matches" to refs.size,
        )
        Outcome.Success(
            GmailPage(
                messages = window,
                nextPageToken = if (nextOffset < refs.size) "offset:$nextOffset" else null,
                resultSizeEstimate = refs.size,
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
            closeTransport()
            cachedLabel = null
            connection.value = GmailConnectionState.DISCONNECTED
            Outcome.Success(Unit)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------------

    /**
     * Runs [block] against the shared, read-only INBOX.
     *
     * The folder stays open between calls (one session per import job). A watchdog closes the
     * transport if a single operation exceeds [OP_DEADLINE_MILLIS]: closing the socket from another
     * thread turns a stalled read — which the IMAP read timeout alone did not end — into a mapped
     * failure, so the job can reach a terminal state.
     */
    private suspend fun <T> withFolder(block: (Folder) -> Outcome<T>): Outcome<T> =
        withContext(ioDispatcher) {
            val ownerId = sessions.current()?.ownerId
            val credential = ownerId?.let { vault.read(it) }
            if (credential == null) {
                connection.value = GmailConnectionState.NOT_CONNECTED
                return@withContext Outcome.Failure(AppError.Configuration("imap_not_configured"))
            }
            mutex.withLock {
                var watchdog: ScheduledFuture<*>? = null
                try {
                    val folder = ensureFolder(credential)
                    watchdog = WATCHDOG.schedule(
                        { runCatching { closeTransport() } },
                        OP_DEADLINE_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                    block(folder)
                } catch (error: Throwable) {
                    connection.value = stateForFailure(error)
                    SafeLogger.failure(TAG, "imap_request_failed")
                    closeTransport()
                    Outcome.Failure(mapImapFailure(error))
                } finally {
                    watchdog?.cancel(false)
                }
            }
        }

    private fun ensureFolder(credential: com.pam.recap.domain.model.GmailCredential): Folder {
        openFolder?.let { folder ->
            if (folder.isOpen) return folder
        }
        val store = currentStore(credential.email, credential.appPassword)
        val folder = store.getFolder(INBOX)
        // FR-55: EXAMINE semantics — this mailbox is never written to.
        folder.open(Folder.READ_ONLY)
        openFolder = folder
        return folder
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
        val session = Session.getInstance(properties()).also { this.session = it }
        val store = session.getStore(PROTOCOL)
        store.connect(HOST, PORT, email, appPassword)
        return store
    }

    /** Closes the folder and the store; safe to call from the watchdog thread. */
    private fun closeTransport() {
        val folder = openFolder
        openFolder = null
        runCatching { if (folder != null && folder.isOpen) folder.close(false) }
        val existing = handle ?: return
        handle = null
        runCatching { if (existing.store.isConnected) existing.store.close() }
        cachedSearch = null
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
        // Mechanism: an IMAP message fetched by UID can come back with its body untyped (the
        // provider hands an InputStream instead of a parsed part). Re-parsing the raw RFC822 bytes
        // locally makes the MIME tree authoritative — the same fix covers every server quirk.
        // Diagnostic (integer only): what the provider actually handed back for the root part.
        // 1 = InputStream (untyped) 2 = String 3 = Multipart 4 = nested Part 0 = other/null.
        val providerKind = when (runCatching { content }.getOrNull()) {
            is java.io.InputStream -> 1
            is String -> 2
            is javax.mail.Multipart -> 3
            is javax.mail.Part -> 4
            else -> 0
        }
        val extracted = extractIncoming(reference, session)
        val body = extracted.body
        if (com.pam.recap.BuildConfig.DEBUG) {
            // Debug-only shape measurement: which allow-listed labels survive, and whether the
            // recovered text still has separators. Integers only — never the text itself.
            val text = body.text.lowercase()
            fun count(token: String): Int {
                var found = 0
                var index = text.indexOf(token)
                while (index >= 0) { found += 1; index = text.indexOf(token, index + token.length) }
                return found
            }
            val lines = text.lines()
            fun startsWith(label: String): Int = lines.count { it.trim().startsWith(label) }
            fun withColon(label: String): Int = lines.count { line ->
                val trimmed = line.trim()
                trimmed.startsWith(label) && trimmed.indexOf(':') > 0
            }
            val nextLen: Int = lines.firstOrNull { it.trim().startsWith("nominal") }
                ?.let { line -> lines.dropWhile { it != line }.drop(1).firstOrNull { it.isNotBlank() } }
                ?.trim()?.length ?: 0
            fun tailMax(label: String): Int = lines
                .filter { it.trim().startsWith(label) }
                .map { line -> line.trim().let { l -> l.indexOf(':').let { if (it < 0) 0 else l.length - it - 1 } } }
                .maxOrNull() ?: 0
            SafeLogger.counts(
                TAG,
                "imap_mime_shape",
                "lines" to lines.size,
                "colon" to text.count { it == ':' },
                "nominal" to count("nominal"),
                "tanggal" to count("tanggal"),
                "status" to count("status"),
                "merchant" to count("merchant"),
                "berhasil" to count("berhasil"),
                "nom_start" to startsWith("nominal"),
                "nom_colon" to withColon("nominal"),
                "nom_tail" to tailMax("nominal"),
                "tgl_start" to startsWith("tanggal"),
                "tgl_colon" to withColon("tanggal"),
                "tgl_tail" to tailMax("tanggal"),
                "mer_start" to startsWith("merchant"),
                "mer_colon" to withColon("merchant"),
                "mer_tail" to tailMax("merchant"),
                "rp" to count("rp"),
                "next_len" to nextLen,
            )
            // Layout skeleton (integers only): per line, its length and a flag.
            // flag: 1 = contains ':' ; 2 = starts with an allow-listed label ; 3 = both.
            val labels = listOf("nominal", "tanggal", "merchant", "status", "jenis", "jumlah")
            val shapeLens = lines.take(24).joinToString(",") { it.trim().length.toString() }
            val shapeFlags = lines.take(24).joinToString(",") { raw ->
                val line = raw.trim().lowercase()
                val colon = if (line.contains(':')) 1 else 0
                val label = if (labels.any { line.startsWith(it) }) 2 else 0
                (colon + label).toString()
            }
            android.util.Log.i(TAG, "imap_mime_layout lens=$shapeLens flags=$shapeFlags")
        }
        SafeLogger.counts(
            TAG,
            "imap_mime",
            "parts" to body.visitedParts,
            "plain" to body.plainParts,
            "html" to body.htmlParts,
            "attachments" to body.attachmentCount,
            "chars" to body.text.length,
            "reparsed" to if (extracted.reparsed) 1 else 0,
            "provider_kind" to providerKind,
            *body.kindCounts.map { (kind, count) -> kind to count }.toTypedArray(),
        )
        return extracted.raw
    }

    private data class SearchHit(val key: String, val refs: List<GmailMessageRef>)

    private fun LivinQuery.cacheKey(limit: Int): String = buildString {
        append(limit).append('|')
        append(fromInclusive.toEpochMilliseconds()).append('|')
        append(toExclusive.toEpochMilliseconds()).append('|')
        append(senderPatterns.joinToString(",")).append('|')
        append(subjectPatterns.joinToString(","))
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
        /** A single folder operation may not exceed this; the watchdog closes the socket. */
        const val OP_DEADLINE_MILLIS = 45_000L
        val WATCHDOG: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "imap-watchdog").apply { isDaemon = true }
        }
        const val INBOX = "INBOX"
    }
}

package com.pam.recap.data.gmail

import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeMessage

/**
 * The text a notification contributes to the parser, plus how many attachments were skipped
 * (FR-14: attachments are never read, and an attachment-only message is excluded).
 */
internal data class MimeBody(
    val text: String,
    val attachmentCount: Int,
    /** Structural counts, for diagnostics only — no content is ever carried here. */
    val plainParts: Int = 0,
    val htmlParts: Int = 0,
    val visitedParts: Int = 0,
    /** Counts of the structural kinds actually seen, for diagnostics. Never content. */
    val kindCounts: Map<String, Int> = emptyMap(),
)

/**
 * Walks a MIME tree and returns **text parts only**.
 *
 * - `text/plain` wins over `text/html`; HTML is stripped to text only when no plain part exists.
 * - anything with `Content-Disposition: attachment` or a file name is counted and skipped, never
 *   parsed and never downloaded into memory.
 * - `multipart/alternative` and `multipart/mixed` are both handled by recursion; nested
 *   `message/rfc822` is followed.
 *
 * Real Gmail notifications can come back as a single **untyped** part: the provider hands an
 * `InputStream` whose first lines are themselves a MIME entity (`Content-Type: multipart/...`).
 * Dispatching on the content object alone then finds no text at all (`parts=1 plain=0 html=0`).
 * [recoverUntyped] closes that hole; [session] is only needed to re-parse such an embedded entity.
 */
internal fun extractMimeBody(part: Part, session: Session? = null): MimeBody {
    val plain = mutableListOf<String>()
    val html = mutableListOf<String>()
    var attachments = 0

    var visited = 0
    val kinds = mutableMapOf<String, Int>()
    // Filled only when the runtime could not type a body part; used when no typed text was found.
    val recovered = mutableListOf<String>()
    var depth = 0
    fun record(kind: String) { kinds[kind] = (kinds[kind] ?: 0) + 1 }

    /**
     * Dispatches on the **content object** rather than on the `Content-Type` header string.
     *
     * Deciding by header alone silently produced `parts=1 plain=0 html=0` for a real Gmail
     * notification: the header form did not match any branch, so the tree was never walked and
     * every message looked body-less. Asking the part what its content actually is cannot be
     * fooled by header spelling, `multipart/related`, a charset parameter or an unusual casing.
     */
    fun walk(current: Part, level: Int = 0) {
        visited += 1
        val disposition = runCatching { current.disposition }.getOrNull()
        val fileName = runCatching { current.fileName }.getOrNull()
        if (Part.ATTACHMENT.equals(disposition, ignoreCase = true) || !fileName.isNullOrBlank()) {
            attachments += 1
            return
        }
        when (val content = runCatching { current.content }.getOrNull()) {
            is Multipart -> {
                record(
                    when {
                        runCatching { current.isMimeType("multipart/alternative") }.getOrDefault(false) -> "mp_alternative"
                        runCatching { current.isMimeType("multipart/mixed") }.getOrDefault(false) -> "mp_mixed"
                        runCatching { current.isMimeType("multipart/related") }.getOrDefault(false) -> "mp_related"
                        else -> "mp_other"
                    },
                )
                for (index in 0 until content.count) {
                    runCatching { content.getBodyPart(index) }.getOrNull()?.let { child -> walk(child, level) }
                }
            }
            // A nested message/rfc822 part.
            is Part -> walk(content, level)
            is String -> when (textKindOf(current)) {
                TextKind.Html -> { record("text_html"); html += stripHtml(content) }
                TextKind.Plain -> { record("text_plain"); if (content.isNotBlank()) plain += content }
                TextKind.Other -> record("text_other")
            }
            // A body the runtime could not type. It is a *body*, not an attachment (a real
            // attachment was already excluded by disposition/filename), so try to recover its text
            // instead of reporting the misleading ATTACHMENT_ONLY reason.
            is InputStream -> {
                record("untyped_body")
                recoverUntyped(content, current, session, level, kinds, recovered)
            }
            null -> record("unreadable")
            else -> record("other")
        }
    }

    walk(part)
    val chosen = when {
        plain.any { it.isNotBlank() } -> plain
        html.any { it.isNotBlank() } -> html
        recovered.any { it.isNotBlank() } -> recovered
        else -> emptyList()
    }
    return MimeBody(
        text = chosen.joinToString(separator = "\n").trim(),
        attachmentCount = attachments,
        plainParts = plain.size,
        htmlParts = html.size,
        visitedParts = visited,
        kindCounts = kinds,
    )
}

/**
 * Recovers text from a body the provider did not type.
 *
 * Three shapes are handled, in order:
 * 1. **An embedded MIME entity** — the decoded bytes start with MIME headers. Re-parsed with
 *    [MimeMessage] and walked again, bounded to two nested levels.
 * 2. **HTML** — stripped like any other HTML part.
 * 3. **Plain text** — accepted only when the bytes are overwhelmingly printable.
 *
 * A genuinely binary body (a PDF with no filename, an image) is counted and dropped: it must never
 * become parser input, and it must never be reported as attachment-only either.
 */
private fun recoverUntyped(
    stream: InputStream,
    part: Part,
    session: Session?,
    level: Int,
    kinds: MutableMap<String, Int>,
    into: MutableList<String>,
) {
    val bytes = runCatching { stream.use { it.readBounded(MAX_RECOVER_BYTES) } }.getOrNull()
    if (bytes == null || bytes.isEmpty()) {
        kinds.merge("unreadable", 1, Int::plus)
        return
    }
    if (!bytes.isMostlyPrintable()) {
        kinds.merge("binary_skipped", 1, Int::plus)
        return
    }
    val text = String(bytes, charsetOf(part))

    if (level < MAX_EMBEDDED_DEPTH && session != null && text.looksLikeMimeEntity()) {
        val nested = runCatching {
            MimeMessage(session, ByteArrayInputStream(bytes))
        }.getOrNull()
        if (nested != null) {
            kinds.merge("embedded_mime", 1, Int::plus)
            val inner = extractMimeBody(nested, session = null)
            for ((kind, count) in inner.kindCounts) kinds.merge(kind, count, Int::plus)
            if (inner.text.isNotBlank()) into += inner.text
            return
        }
    }

    if (text.looksLikeHtml()) {
        kinds.merge("untyped_html", 1, Int::plus)
        into += stripHtml(text)
    } else {
        kinds.merge("untyped_text", 1, Int::plus)
        into += text
    }
}

/** Reads at most [limit] bytes; the stream is never drained beyond the bound. */
private fun InputStream.readBounded(limit: Int): ByteArray {
    val buffer = ByteArray(8 * 1024)
    val out = java.io.ByteArrayOutputStream()
    var total = 0
    while (total < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - total))
        if (read < 0) break
        out.write(buffer, 0, read)
        total += read
    }
    return out.toByteArray()
}

/** Charset from the part's `Content-Type`, else UTF-8. Unknown names fall back to UTF-8. */
private fun charsetOf(part: Part): java.nio.charset.Charset {
    val declared = runCatching { part.contentType }.getOrNull().orEmpty()
    val match = CHARSET_PARAM.find(declared) ?: return Charsets.UTF_8
    return runCatching { java.nio.charset.Charset.forName(match.groupValues[1].trim()) }
        .getOrDefault(Charsets.UTF_8)
}

private fun ByteArray.isMostlyPrintable(): Boolean {
    if (isEmpty()) return false
    var control = 0
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        // Allow tab, LF and CR; count other C0 controls and 0x7F.
        if ((value in 0x00..0x08) || value == 0x0B || value == 0x0C || (value in 0x0E..0x1F) || value == 0x7F) {
            control += 1
        }
    }
    return control * 100 <= size * MAX_CONTROL_PERCENT
}

/**
 * True when the decoded text is itself a MIME entity: a `Content-Type:` header before the first
 * blank line. The header block must be short — a notification body that happens to contain the
 * word is not treated as an entity.
 */
private fun String.looksLikeMimeEntity(): Boolean {
    val headerEnd = indexOf("\n\n").let { if (it < 0) indexOf("\r\n\r\n") else it }
    val headerBlock = if (headerEnd > 0) substring(0, headerEnd) else substring(0, minOf(length, MIME_SNIFF_CHARS))
    if (headerBlock.length > MIME_HEADER_LIMIT) return false
    return MIME_HEADER.find(headerBlock) != null
}

/** A body that opens with `<html`, `<!doctype` or contains a `<body>` tag near the start. */
private fun String.looksLikeHtml(): Boolean {
    val head = take(HTML_SNIFF_CHARS).lowercase()
    return head.contains("<html") || head.contains("<!doctype html") || head.contains("<body")
}

private fun contentOf(part: Part): String? =
    runCatching { part.content as? String }.getOrNull()?.takeIf { it.isNotBlank() }

/** Minimal HTML→text: drops script/style content, then tags, then the common entities. */
internal fun stripHtml(raw: String): String {
    var text = raw
    text = text.replace(SCRIPT_OR_STYLE, " ")
    text = text.replace(BREAK_TAG, "\n")
    text = text.replace(TAG, " ")
    for ((entity, replacement) in ENTITIES) {
        text = text.replace(entity, replacement)
    }
    return text
        .lineSequence()
        .map { it.replace(WHITESPACE, " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString(separator = "\n")
}

private const val MAX_RECOVER_BYTES = 1024 * 1024
private const val MAX_EMBEDDED_DEPTH = 2
private const val MAX_CONTROL_PERCENT = 5
private const val MIME_SNIFF_CHARS = 2048
private const val MIME_HEADER_LIMIT = 4096
private const val HTML_SNIFF_CHARS = 512
private val CHARSET_PARAM = Regex("(?i)charset=\"?([^\";\\s]+)")
private val MIME_HEADER = Regex("(?im)^(content-type|content-transfer-encoding|mime-version):")

private val SCRIPT_OR_STYLE = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
private val BREAK_TAG = Regex("(?i)<(br|/p|/div|/tr)[^>]*>")
private val TAG = Regex("<[^>]+>")
private val WHITESPACE = Regex("[ \\t\\u00A0]+")

private val ENTITIES = listOf(
    "&nbsp;" to " ",
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&apos;" to "'",
)

/** How a string-valued body part should be treated. */
internal enum class TextKind { Plain, Html, Other }

/**
 * Classifies a text part by its declared type, tolerating parameters, casing and the
 * `text/calendar`-style values that must not be mistaken for the message body.
 */
internal fun textKindOf(part: Part): TextKind {
    val type = runCatching { part.contentType }.getOrNull().orEmpty().lowercase()
    return when {
        type.contains("text/html") -> TextKind.Html
        type.contains("text/plain") -> TextKind.Plain
        type.startsWith("text/") -> TextKind.Plain
        type.isBlank() -> TextKind.Plain
        else -> TextKind.Other
    }
}

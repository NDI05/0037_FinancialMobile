package com.pam.recap.data.gmail

import javax.mail.Multipart
import javax.mail.Part

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
 */
internal fun extractMimeBody(part: Part): MimeBody {
    val plain = mutableListOf<String>()
    val html = mutableListOf<String>()
    var attachments = 0

    var visited = 0
    val kinds = mutableMapOf<String, Int>()
    fun record(kind: String) { kinds[kind] = (kinds[kind] ?: 0) + 1 }

    /**
     * Dispatches on the **content object** rather than on the `Content-Type` header string.
     *
     * Deciding by header alone silently produced `parts=1 plain=0 html=0` for a real Gmail
     * notification: the header form did not match any branch, so the tree was never walked and
     * every message looked body-less. Asking the part what its content actually is cannot be
     * fooled by header spelling, `multipart/related`, a charset parameter or an unusual casing.
     */
    fun walk(current: Part) {
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
                    runCatching { content.getBodyPart(index) }.getOrNull()?.let(::walk)
                }
            }
            // A nested message/rfc822 part.
            is Part -> walk(content)
            is String -> when (textKindOf(current)) {
                TextKind.Html -> { record("text_html"); html += stripHtml(content) }
                TextKind.Plain -> { record("text_plain"); if (content.isNotBlank()) plain += content }
                TextKind.Other -> record("text_other")
            }
            // A binary body without a file name: never parsed, only counted.
            // A binary body without a file name is a *body* the runtime could not type, not an
            // attachment: counting it as one would report the misleading ATTACHMENT_ONLY reason.
            is java.io.InputStream -> record("untyped_body")
            null -> record("unreadable")
            else -> record("other")
        }
    }

    walk(part)
    val chosen = if (plain.any { it.isNotBlank() }) plain else html
    return MimeBody(
        text = chosen.joinToString(separator = "\n").trim(),
        attachmentCount = attachments,
        plainParts = plain.size,
        htmlParts = html.size,
        visitedParts = visited,
        kindCounts = kinds,
    )
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

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

    fun walk(current: Part) {
        val disposition = runCatching { current.disposition }.getOrNull()
        val fileName = runCatching { current.fileName }.getOrNull()
        if (Part.ATTACHMENT.equals(disposition, ignoreCase = true) || !fileName.isNullOrBlank()) {
            attachments += 1
            return
        }
        when {
            current.isMimeType("text/plain") -> contentOf(current)?.let { plain += it }
            current.isMimeType("text/html") -> contentOf(current)?.let { html += stripHtml(it) }
            current.isMimeType("multipart/*") -> {
                val multipart = runCatching { current.content as? Multipart }.getOrNull()
                if (multipart != null) {
                    for (index in 0 until multipart.count) {
                        runCatching { multipart.getBodyPart(index) }.getOrNull()?.let(::walk)
                    }
                }
            }
            current.isMimeType("message/rfc822") -> {
                val nested = runCatching { current.content as? Part }.getOrNull()
                if (nested != null) walk(nested)
            }
        }
    }

    walk(part)
    val chosen = if (plain.any { it.isNotBlank() }) plain else html
    return MimeBody(text = chosen.joinToString(separator = "\n").trim(), attachmentCount = attachments)
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

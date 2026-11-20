package com.pam.recap.core.result

import android.util.Log

/**
 * NFR-18 allow-list logger: only a stable event key and an error code may be logged.
 *
 * Callers pass identifiers such as `"import_page_failed"`; passing a free-form message would
 * defeat the purpose, so the API deliberately offers no `String` message overload and never
 * accepts bodies, subjects, senders, mailbox labels, tokens, PANs or merchant names.
 */
object SafeLogger {

    private const val MAX_TAG_LENGTH = 23

    fun event(tag: String, event: String) {
        Log.i(normalizeTag(tag), event)
    }

    fun warning(tag: String, event: String, error: AppError? = null) {
        Log.w(normalizeTag(tag), event + (error?.let { " code=${it.kind.name}" } ?: ""))
    }

    /**
     * Structured progress line. Values are [Int] only, so a caller cannot accidentally pass a
     * subject, address or amount — the allow-list property of this logger is preserved while still
     * making long-running work observable (`adb logcat -s ImportMonth`).
     */
    fun counts(tag: String, event: String, vararg values: Pair<String, Int>) {
        val rendered = values.joinToString(" ") { (key, value) -> "$key=$value" }
        Log.i(normalizeTag(tag), if (rendered.isEmpty()) event else "$event $rendered")
    }

    fun failure(tag: String, event: String, error: AppError? = null) {
        Log.e(normalizeTag(tag), event + (error?.let { " code=${it.kind.name}" } ?: ""))
    }

    private fun normalizeTag(tag: String): String =
        if (tag.length <= MAX_TAG_LENGTH) tag else tag.substring(0, MAX_TAG_LENGTH)
}

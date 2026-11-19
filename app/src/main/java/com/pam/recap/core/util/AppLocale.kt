package com.pam.recap.core.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Release 1 ships an Indonesian interface (SRS §1.4).
 *
 * Wrapping a base context works per context, not per process: an `Application`'s wrapped base
 * context does **not** change the resources an `Activity` resolves (activity base contexts are
 * created from the system configuration), so both [com.pam.recap.PamApplication] and
 * `MainActivity` apply this wrapper. `res/values-en` stays in the tree for a future in-app
 * language switch.
 */
object AppLocale {

    /** The one supported interface locale (OI-10). */
    val INDONESIAN: Locale = Locale.forLanguageTag("id-ID")

    /** Locale-wrapped base context: every string, plural and date format resolves in Indonesian. */
    fun wrap(base: Context): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(INDONESIAN)
        configuration.setLayoutDirection(INDONESIAN)
        return base.createConfigurationContext(configuration)
    }
}

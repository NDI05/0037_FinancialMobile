package com.pam.recap

import android.app.Application
import android.content.Context
import com.pam.recap.core.util.AppLocale
import dagger.hilt.android.HiltAndroidApp
import java.util.Locale

/**
 * Pins the interface language to Indonesian for release 1 (SRS §1.4).
 *
 * The pin happens in [attachBaseContext] — so every `stringResource`, plural and date formatter is
 * resolved against the Indonesian configuration — and again in [onCreate] so process-wide default
 * locale lookups (`Locale.getDefault()` inside `DateTimeFormatter`, `String.format`,
 * `uppercase`) agree with it. `res/values-en` stays in the tree for a future in-app language
 * switch; nothing else in the app hardcodes a locale.
 */
@HiltAndroidApp
class PamApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        Locale.setDefault(PAM_LOCALE)
        super.onCreate()
    }

    private companion object {
        /** Same locale the activity pins, so process-wide formatting agrees. */
        val PAM_LOCALE: Locale get() = AppLocale.INDONESIAN
    }
}

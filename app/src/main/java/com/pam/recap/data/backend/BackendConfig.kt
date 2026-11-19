package com.pam.recap.data.backend

import com.pam.recap.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Endpoint resolution for the optional analysis backend (OI-07).
 *
 * Precedence, lowest to highest:
 * 1. neither property set → the backend is **disabled** and the on-device engine stays bound;
 * 2. `PAM_ANALYSIS_BACKEND_URL` — a *full* URL to `POST /v1/analyses`, kept for compatibility. Its
 *    origin (`scheme://host[:port]`) is reused for the session/consent/summary endpoints, so an
 *    older build invocation still gets a working session;
 * 3. `PAM_BACKEND_BASE_URL` — the base URL (e.g. `http://10.0.2.2:8080`); every endpoint is derived
 *    from it. When both are set, this one wins.
 *
 * Blank base + blank override must never regress the demo: [isEnabled] is false, so
 * `RequestAnalysisUseCase` keeps choosing the deterministic on-device engine.
 */
interface BackendEndpoints {
    val isEnabled: Boolean
    val analysesUrl: String?

    fun endpoint(path: String): String?
}

@Singleton
class BackendConfig @Inject constructor() : BackendEndpoints {

    private val baseUrl: String = BuildConfig.PAM_BACKEND_BASE_URL.trim().trimEnd('/')

    private val legacyAnalysisUrl: String = BuildConfig.PAM_ANALYSIS_BACKEND_URL.trim()

    /** True when either property is set; drives the engine choice and every server push. */
    override val isEnabled: Boolean get() = baseUrl.isNotBlank() || legacyAnalysisUrl.isNotBlank()

    /**
     * Origin used for session/consent/summary/deletion calls. With only the legacy full URL we
     * strip the `/v1/...` tail, which is exactly where the backend mounted it.
     */
    val effectiveBaseUrl: String
        get() = baseUrl.ifBlank { legacyAnalysisUrl.substringBefore("/v1/") }.trimEnd('/')

    /** Full URL for `POST /v1/analyses`; the legacy override is used verbatim when set alone. */
    override val analysesUrl: String?
        get() = when {
            baseUrl.isNotBlank() -> "$baseUrl/v1/analyses"
            legacyAnalysisUrl.isNotBlank() -> legacyAnalysisUrl
            else -> null
        }

    /** Resolves a path such as `/v1/sessions`; null when no usable base exists. */
    override fun endpoint(path: String): String? {
        val origin = effectiveBaseUrl
        return if (origin.isBlank()) null else "$origin$path"
    }
}

package com.pam.recap.domain.repository

import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailAuthorization
import com.pam.recap.domain.model.GmailLinkStatus

/**
 * Server-side Gmail OAuth link (contract §1–§4, repo `0037-Backend-Financial`).
 *
 * The Android side never sees a Google token and never asks for a Google password: it starts the
 * flow, opens the returned authorization URL in the browser/Custom Tab, and reads the projected
 * status back. [isEnabled] is false when no backend is configured, so the settings surface can
 * explain that linking needs the server instead of showing a dead button.
 */
interface GmailLinkRepository {

    val isEnabled: Boolean

    /** `GET /v1/gmail/status` — the connection card's only source of truth. */
    suspend fun status(ownerId: String): Outcome<GmailLinkStatus>

    /** `POST /v1/gmail/connect/start` — returns the URL to open outside the app. */
    suspend fun startConnect(
        ownerId: String,
        reconnect: Boolean = false,
    ): Outcome<GmailAuthorization>

    /** `POST /v1/gmail/disconnect` — revocation happens on the server, not on this device. */
    suspend fun disconnect(ownerId: String): Outcome<Unit>
}

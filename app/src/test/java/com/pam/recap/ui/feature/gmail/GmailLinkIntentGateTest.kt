package com.pam.recap.ui.feature.gmail

import com.pam.recap.domain.model.GmailLinkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deduplication of `pam://gmail/connected…` intents. Android re-delivers the launching intent on
 * activity recreate and can deliver a duplicate callback; the gate must accept it once, remember the
 * key through [GmailLinkIntentGate.handledKeySnapshot], and ignore the replay after restoration.
 */
class GmailLinkIntentGateTest {

    private val successData = "pam://gmail/connected?status=success"
    private val errorData = "pam://gmail/connected?status=error&reason=state_expired"

    @Test
    fun `an oauth callback is accepted once`() {
        val gate = GmailLinkIntentGate()
        assertEquals(
            GmailLinkResult(success = true),
            gate.accept("android.intent.action.VIEW", successData, "pam", "gmail", "/connected", "success", null),
        )
        assertNull(
            gate.accept("android.intent.action.VIEW", successData, "pam", "gmail", "/connected", "success", null),
        )
    }

    @Test
    fun `a restored gate ignores the intent re-delivered on recreate`() {
        val gate = GmailLinkIntentGate()
        gate.accept("android.intent.action.VIEW", errorData, "pam", "gmail", "/connected", "error", "state_expired")
        val savedKey = gate.handledKeySnapshot

        // Activity recreate: a fresh gate is seeded with the saved key and gets the same intent.
        val recreated = GmailLinkIntentGate(savedKey)
        assertNull(
            recreated.accept("android.intent.action.VIEW", errorData, "pam", "gmail", "/connected", "error", "state_expired"),
        )
        // The snapshot still identifies the consumed callback for the next save.
        assertEquals(savedKey, recreated.handledKeySnapshot)
    }

    @Test
    fun `a different callback after a handled one is still accepted`() {
        val gate = GmailLinkIntentGate()
        gate.accept("android.intent.action.VIEW", successData, "pam", "gmail", "/connected", "success", null)
        assertEquals(
            GmailLinkResult(success = false, reason = "access_denied"),
            gate.accept("android.intent.action.VIEW", errorData, "pam", "gmail", "/connected", "error", "access_denied"),
        )
    }

    @Test
    fun `non gmail intents are ignored`() {
        val gate = GmailLinkIntentGate()
        assertNull(gate.accept("android.intent.action.MAIN", null, null, null, null, null, null))
        assertNull(gate.accept("android.intent.action.VIEW", "https://example.com", "https", "example.com", "/", null, null))
        assertNull(gate.accept("android.intent.action.VIEW", "pam://other", "pam", "other", "/connected", "success", null))
        assertNull(gate.accept("android.intent.action.VIEW", "pam://gmail/elsewhere", "pam", "gmail", "/elsewhere", "success", null))
    }
}

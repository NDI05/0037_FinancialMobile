package com.pam.recap.data.security

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.security.WrappedSecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualPendingActionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `delimiter and unicode survive a restart read`() {
        val pending = ManualPendingActionStore.Pending(
            key = "key", identity = "a|b", amountText = "175000",
            description = "Kopi \u001f khusus — 100%", note = "catatan", dateIso = "2026-11-22",
            direction = "DEBIT", categoryCode = "custom_coffee",
            eventAtEpochMillis = 1795280400000L,
        )
        assertTrue(ManualPendingActionStore(context, XorStore()).save("u_1", pending))
        val restarted = ManualPendingActionStore(context, XorStore())
        assertEquals(pending, restarted.read("u_1"))
    }

    @Test
    fun `payload written before notes existed still restores with an empty note`() {
        // A pre-upgrade payload has seven fields (no note); the retry identity must survive the
        // upgrade instead of being discarded and duplicated on the server.
        val encoded = listOf("key", "a|b", "175000", "Kopi", "2026-11-22", "DEBIT", "food")
            .joinToString("\u001f") {
                Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
            }
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "manual_pending_u_1",
                XorStore().wrap(encoded.toByteArray()),
            )
            .commit()

        val restored = ManualPendingActionStore(context, XorStore()).read("u_1")

        assertEquals("key", restored?.key)
        assertEquals("a|b", restored?.identity)
        assertEquals("", restored?.note)
        assertEquals("food", restored?.categoryCode)
    }

    @Test
    fun `five field legacy payload matches the same draft for a retry`() {
        // Regression (SRS v0.7 P2-2): the pre-note payload identity has five fields, while the
        // current draft identity has six. Field comparison must still recognise the same action so
        // a retry reuses its key instead of minting a new UUID and duplicating the server row.
        val encoded = listOf(
            "legacy-key", "DEBIT|175000|Kopi|2026-11-22|food", "175000", "Kopi",
            "2026-11-22", "DEBIT", "food",
        ).joinToString("\u001f") { Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP) }
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE)
            .edit()
            .putString("manual_pending_u_1", XorStore().wrap(encoded.toByteArray()))
            .commit()

        val restored = ManualPendingActionStore(context, XorStore()).read("u_1")!!

        assertTrue(
            ManualPendingActionStore.matchesDraft(
                restored, direction = "DEBIT", amountText = "175000", description = "Kopi",
                note = "", dateIso = "2026-11-22", categoryCode = "food",
            ),
        )
        assertFalse(
            ManualPendingActionStore.matchesDraft(
                restored, direction = "DEBIT", amountText = "175000", description = "Kopi",
                note = "catatan baru", dateIso = "2026-11-22", categoryCode = "food",
            ),
        )
    }

    @Test
    fun `blank note on either side still matches the same stored draft`() {
        val store = ManualPendingActionStore(context, XorStore())
        assertTrue(store.save("u_1", ManualPendingActionStore.Pending(
            key = "note-key", identity = "DEBIT|175000|Kopi|2026-11-22|food", amountText = "175000",
            description = "Kopi", note = "   ", dateIso = "2026-11-22", direction = "DEBIT",
            categoryCode = "food",
        )))
        val restored = store.read("u_1")!!

        assertTrue(
            ManualPendingActionStore.matchesDraft(
                restored, "DEBIT", "175000", "Kopi", "", "2026-11-22", "food",
            ),
        )
        assertFalse(
            ManualPendingActionStore.matchesDraft(
                restored, "DEBIT", "175000", "Kopi", "catatan lain", "2026-11-22", "food",
            ),
        )
    }

    @Test
    fun `wrapping failure is surfaced before the caller can POST`() {
        val store = ManualPendingActionStore(context, object : WrappedSecretStore {
            override fun wrap(plainSecret: ByteArray): String = error("keystore unavailable")
            override fun unwrap(wrapped: String): ByteArray = error("unavailable")
        })
        val saved = store.save("u_1", ManualPendingActionStore.Pending(
            "key", "id", "1", "d", "", "2026-11-22", "DEBIT", "food",
        ))
        assertFalse(saved)
        assertNull(store.read("u_1"))
    }

    private class XorStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String =
            Base64.encodeToString(plainSecret.map { (it.toInt() xor 0x21).toByte() }.toByteArray(), Base64.NO_WRAP)
        override fun unwrap(wrapped: String): ByteArray =
            Base64.decode(wrapped, Base64.NO_WRAP).map { (it.toInt() xor 0x21).toByte() }.toByteArray()
    }
}

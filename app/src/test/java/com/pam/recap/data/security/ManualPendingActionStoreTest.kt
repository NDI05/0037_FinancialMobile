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
            description = "Kopi \u001f khusus — 100%", dateIso = "2026-11-22",
            direction = "DEBIT", categoryCode = "custom_coffee",
        )
        assertTrue(ManualPendingActionStore(context, XorStore()).save("u_1", pending))
        val restarted = ManualPendingActionStore(context, XorStore())
        assertEquals(pending, restarted.read("u_1"))
    }

    @Test
    fun `wrapping failure is surfaced before the caller can POST`() {
        val store = ManualPendingActionStore(context, object : WrappedSecretStore {
            override fun wrap(plainSecret: ByteArray): String = error("keystore unavailable")
            override fun unwrap(wrapped: String): ByteArray = error("unavailable")
        })
        val saved = store.save("u_1", ManualPendingActionStore.Pending(
            "key", "id", "1", "d", "2026-11-22", "DEBIT", "food",
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

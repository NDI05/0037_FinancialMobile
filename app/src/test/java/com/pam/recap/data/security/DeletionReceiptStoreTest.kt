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
class DeletionReceiptStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `receipt is wrapped and survives a new store instance`() {
        val first = DeletionReceiptStore(context, XorStore())
        first.save("u_1", "receipt-canary-123")
        val prefs = context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.filterIsInstance<String>().joinToString().contains("receipt-canary-123"))

        val restarted = DeletionReceiptStore(context, XorStore())
        assertEquals("receipt-canary-123", restarted.read("u_1"))
        restarted.clear("u_1")
        assertNull(restarted.read("u_1"))
        assertTrue(prefs.all.isEmpty())
    }

    private class XorStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String =
            Base64.encodeToString(plainSecret.map { (it.toInt() xor 0x33).toByte() }.toByteArray(), Base64.NO_WRAP)
        override fun unwrap(wrapped: String): ByteArray =
            Base64.decode(wrapped, Base64.NO_WRAP).map { (it.toInt() xor 0x33).toByte() }.toByteArray()
    }
}

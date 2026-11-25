package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendErrorsCategoryTest {
    @Test
    fun `category unavailable is configuration not a network outage`() {
        val error = backendError(503, BackendErrorCodes.CATEGORY_UNAVAILABLE)

        assertTrue("expected Validation, got $error", error is AppError.Validation)
        assertEquals(AppErrorRules.CATEGORY_DISABLED, (error as AppError.Validation).rule)
    }

    @Test
    fun `other 503 responses keep the generic network mapping`() {
        val error = backendError(503, null)

        assertTrue("expected Network, got $error", error is AppError.Network)
        assertEquals("backend_503", (error as AppError.Network).debugDetail)
    }
}

package com.pam.recap.ui.util

import com.pam.recap.R
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import org.junit.Assert.assertEquals
import org.junit.Test

class AppErrorCopyCategoryTest {
    @Test
    fun `disabled classifier copy tells the user it is a server setting`() {
        val res = appErrorRes(AppError.Validation(AppErrorRules.CATEGORY_DISABLED))

        assertEquals(R.string.error_category_disabled, res)
    }
}

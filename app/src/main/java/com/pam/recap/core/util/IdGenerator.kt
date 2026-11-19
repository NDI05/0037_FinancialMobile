package com.pam.recap.core.util

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Id generation port so tests can produce deterministic ids. Domain code never calls
 * [UUID.randomUUID] directly.
 */
fun interface IdGenerator {
    fun newId(): String
}

@Singleton
class UuidGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

package com.pam.recap.ui.util

import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Session plumbing shared by every screen view model.
 *
 * Screens are owner-scoped by construction (FR-02): the flow only emits while a session exists, so
 * a sign-out tears down every downstream collection instead of leaving another user's data on
 * screen (FR-05).
 */
fun SessionRepository.sessionFlow(scope: CoroutineScope): StateFlow<Session?> =
    observe().stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

/** Emits the current owner id; callers combine it with their own data flow. */
fun SessionRepository.ownerFlow(): Flow<String> =
    observe().filterNotNull().map { it.ownerId }.distinctUntilChanged()

private const val STOP_TIMEOUT_MILLIS = 5_000L

package com.pam.recap.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.pam.recap.domain.repository.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ConnectivityManager-backed [NetworkMonitor].
 *
 * The default-network callback is registered only while a screen collects, so a backgrounded app
 * holds no receiver. It deliberately ignores the "validated" bit: this is a UI hint ("we are likely
 * offline"), never a gate — WorkManager still enforces the real network constraint for work.
 */
@Singleton
class AndroidNetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : NetworkMonitor {

    override fun isOnline(): Boolean = hasNetwork()

    override fun observe(): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(hasNetwork())
            }

            override fun onLost(network: Network) {
                trySend(hasNetwork())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        trySend(hasNetwork())
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()

    private fun hasNetwork(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

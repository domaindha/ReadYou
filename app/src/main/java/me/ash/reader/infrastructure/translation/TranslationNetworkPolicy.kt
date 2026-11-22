package me.ash.reader.infrastructure.translation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

/** Network policy used by automatic translation. Manual translation is never blocked. */
interface TranslationNetworkPolicy {
    fun isWifiConnected(): Boolean
    fun observeWifiConnection(): Flow<Boolean>

    companion object {
        /** Keeps direct JVM unit construction independent from Android framework APIs. */
        val ALLOW_ALL: TranslationNetworkPolicy = object : TranslationNetworkPolicy {
            override fun isWifiConnected(): Boolean = true
            override fun observeWifiConnection(): Flow<Boolean> = flowOf(true)
        }
    }
}

@Singleton
class AndroidTranslationNetworkPolicy @Inject constructor(
    @ApplicationContext context: Context,
) : TranslationNetworkPolicy {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun isWifiConnected(): Boolean {
        val manager = connectivityManager ?: return false
        // Inspect all connected transports so a VPN default network does not hide Wi-Fi underneath.
        @Suppress("DEPRECATION")
        return manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
    }

    override fun observeWifiConnection(): Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isWifiConnected())
            }

            override fun onLost(network: Network) {
                trySend(isWifiConnected())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(isWifiConnected())
            }
        }

        try {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            manager.registerNetworkCallback(wifiRequest, callback)
        } catch (_: SecurityException) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        trySend(isWifiConnected())
        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}

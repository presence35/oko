package ua.ukrainedrones.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Validated network state observer.
 *
 * Fixes the classic Android issue where `onAvailable` triggers before captive portal
 * or internet reachability validation finishes. Only signals `isValidated = true`
 * once `NET_CAPABILITY_VALIDATED` is confirmed.
 */
class NetworkMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isValidated = MutableStateFlow(checkInitialValidation())
    val isValidated: StateFlow<Boolean> = _isValidated.asStateFlow()

    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun checkInitialValidation(): Boolean {
        val manager = cm ?: return true
        val active = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Synchronized
    fun start(onValidatedReturn: (() -> Unit)? = null) {
        if (networkCallback != null || cm == null) return

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Do NOT mark validated immediately. Wait for capabilities check.
            }

            override fun onLost(network: Network) {
                _isValidated.value = false
            }

            override fun onUnavailable() {
                _isValidated.value = false
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = hasInternet && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val wasValidated = _isValidated.value

                _isValidated.value = validated

                if (validated && !wasValidated) {
                    onValidatedReturn?.invoke()
                }
            }
        }

        networkCallback = callback
        runCatching {
            cm!!.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
        }
    }

    @Synchronized
    fun stop() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }
}

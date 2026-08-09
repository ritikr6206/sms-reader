package com.smsreader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Monitors network connectivity changes.
 * When internet becomes available, triggers the ForwarderService
 * to flush any queued messages.
 */
class NetworkMonitor(private val context: Context) {

    companion object {
        private const val TAG = "NetworkMonitor"
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Check if internet is currently available.
     */
    fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Start listening for connectivity changes.
     * Triggers queue processing when internet comes back.
     */
    fun startMonitoring() {
        if (callback != null) return // already monitoring

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available — triggering queue flush")
                triggerQueueFlush()
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    Log.d(TAG, "Network validated — triggering queue flush")
                    triggerQueueFlush()
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback!!)
            Log.d(TAG, "Network monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    /**
     * Stop listening for connectivity changes.
     */
    fun stopMonitoring() {
        callback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister network callback", e)
            }
            callback = null
            Log.d(TAG, "Network monitoring stopped")
        }
    }

    private fun triggerQueueFlush() {
        try {
            val intent = android.content.Intent(context, ForwarderService::class.java).apply {
                action = ForwarderService.ACTION_PROCESS_QUEUE
            }
            context.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger queue flush", e)
        }
    }
}

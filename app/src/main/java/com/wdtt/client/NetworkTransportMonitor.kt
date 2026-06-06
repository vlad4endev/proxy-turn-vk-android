package com.wdtt.client

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

enum class NetworkTransport {
    WIFI,
    CELLULAR,
    UNKNOWN;

    fun recommendedTunnelMode(): String = when (this) {
        WIFI -> "speed"
        CELLULAR -> "whitelist"
        UNKNOWN -> "whitelist"
    }

    fun labelRu(): String = when (this) {
        WIFI -> "Wi‑Fi"
        CELLULAR -> "мобильный интернет"
        UNKNOWN -> "неизвестная сеть"
    }
}

object NetworkTransportMonitor {

    fun currentTransport(context: Context): NetworkTransport {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                return when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.CELLULAR
                    else -> continue
                }
            }
            return NetworkTransport.UNKNOWN
        }
        @Suppress("DEPRECATION")
        val info = cm.activeNetworkInfo ?: return NetworkTransport.UNKNOWN
        if (!info.isConnected) return NetworkTransport.UNKNOWN
        @Suppress("DEPRECATION")
        return when (info.type) {
            ConnectivityManager.TYPE_WIFI -> NetworkTransport.WIFI
            ConnectivityManager.TYPE_MOBILE -> NetworkTransport.CELLULAR
            else -> NetworkTransport.UNKNOWN
        }
    }
}

@Composable
fun rememberNetworkTransport(): State<NetworkTransport> {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val state = remember { mutableStateOf(NetworkTransportMonitor.currentTransport(appContext)) }

    DisposableEffect(appContext) {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun refresh() {
            state.value = NetworkTransportMonitor.currentTransport(appContext)
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                refresh()
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        cm.registerNetworkCallback(request, callback)
        refresh()

        onDispose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    return state
}

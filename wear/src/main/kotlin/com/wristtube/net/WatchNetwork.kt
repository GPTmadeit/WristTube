package com.wristtube.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A watch normally reaches the internet through its phone's Bluetooth link. That's fine for search,
 * but far too slow for video. While WristTube is open or playing, it asks Wear OS for Wi-Fi or LTE
 * (the documented way to get a high-bandwidth network on a watch) and binds the app to it, so every
 * request goes over the fast link. When nobody needs it any more, Wear OS can turn the radio off
 * again.
 *
 * Holders are counted: the screen holds it while visible, playback while playing.
 */
object WatchNetwork {
    private const val TAG = "WristTube/Net"

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private val holders = mutableSetOf<String>()

    private val _network = MutableStateFlow<Network?>(null)

    /** The Wi-Fi or LTE network the app is bound to, or null while it's on the phone's link. */
    val network: StateFlow<Network?> = _network

    /** What the app is on right now, for the settings screen. */
    @Volatile var label: String = "Phone (Bluetooth)"
        private set

    @Synchronized
    fun acquire(ctx: Context, holder: String) {
        holders += holder
        if (callback != null) return
        val manager = ctx.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        cm = manager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                _network.value = n
                val caps = manager.getNetworkCapabilities(n)
                label = when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "LTE"
                    else -> "Network"
                }
                // Binding the process sends every socket (OkHttp, ExoPlayer, Coil) over it.
                manager.bindProcessToNetwork(n)
                Log.i(TAG, "bound to $label")
            }

            override fun onLost(n: Network) {
                if (_network.value == n) {
                    _network.value = null
                    label = "Phone (Bluetooth)"
                    manager.bindProcessToNetwork(null)
                    Log.i(TAG, "high-bandwidth network lost; back to the default")
                }
            }
        }
        callback = cb
        runCatching { manager.requestNetwork(request, cb) }
            .onFailure {
                Log.w(TAG, "Wi-Fi/LTE request refused", it)
                callback = null
            }
    }

    @Synchronized
    fun release(holder: String) {
        holders -= holder
        if (holders.isNotEmpty()) return
        val manager = cm ?: return
        callback?.let { runCatching { manager.unregisterNetworkCallback(it) } }
        callback = null
        if (_network.value != null) manager.bindProcessToNetwork(null)
        _network.value = null
        label = "Phone (Bluetooth)"
    }

    /**
     * Waits (briefly) for Wi-Fi or LTE. YouTube's stream URLs are only good for the address that
     * asked for them, so a video should be looked up over the network that will then stream it.
     */
    suspend fun await(timeoutMs: Long = 3_500): Boolean =
        withTimeoutOrNull(timeoutMs) { network.first { it != null } } != null
}

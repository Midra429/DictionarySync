package me.midra.dictionarysync.data.source

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import me.midra.dictionarysync.data.parser.DictionaryProblem

/** Downloads a dictionary file, conditionally with [validators]. The network seam of fetching; tests replace it. */
fun interface DictionaryDownload {
    fun get(url: String, wifiOnly: Boolean, validators: HttpValidators?): Download

    companion object {
        /** Over the default network, which [wifiOnly] restricts to Wi-Fi. */
        fun android(context: Context) = DictionaryDownload { url, wifiOnly, validators ->
            AndroidDownloadRoute(context.applicationContext, wifiOnly).use { Downloader().get(url, it, validators) }
        }
    }
}

/** Bind each connection to the approved default Network; never fall back to cellular or bypass VPN. */
class AndroidDownloadRoute(context: Context, private val wifiOnly: Boolean) : DownloadRoute {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val network = manager.activeNetwork ?: throw DictionaryProblem("NETWORK_WAIT")
    private val invalid = AtomicBoolean(false)
    @Volatile private var connection: HttpsURLConnection? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(lost: Network) { if (lost == network) invalidate() }
        override fun onAvailable(available: Network) { if (available != network) invalidate() }
        override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
            if (changed == network && !accepted(capabilities)) invalidate()
        }
    }
    init {
        check()
        manager.registerDefaultNetworkCallback(callback)
        try { check() } catch (error: Exception) { manager.unregisterNetworkCallback(callback); throw error }
    }
    private fun accepted(c: NetworkCapabilities): Boolean = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && (!wifiOnly ||
        (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)))
    private fun invalidate() { invalid.set(true); connection?.disconnect() }
    override fun check() {
        if (invalid.get() || manager.activeNetwork != network || manager.getNetworkCapabilities(network)?.let(::accepted) != true)
            throw DictionaryProblem(if (wifiOnly) "WIFI_WAIT" else "NETWORK_WAIT")
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
    }
    override fun open(uri: URI): HttpsURLConnection {
        check()
        val opened = network.openConnection(uri.toURL()) as HttpsURLConnection
        connection = opened
        try { check() } catch (error: Exception) { opened.disconnect(); throw error }
        return opened
    }
    override fun close() { invalidate(); manager.unregisterNetworkCallback(callback) }
}

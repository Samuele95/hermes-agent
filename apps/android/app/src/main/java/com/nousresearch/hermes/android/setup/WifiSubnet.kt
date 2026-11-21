package com.nousresearch.hermes.android.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** The active Wi-Fi network's IPv4 address with its prefix (`192.168.1.37/24`), or null off Wi-Fi. */
fun wifiSubnet(context: Context): String? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = cm.activeNetwork ?: return null
    if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return null
    return cm.getLinkProperties(network)?.linkAddresses
        ?.firstOrNull { it.address is Inet4Address }
        ?.let { "${it.address.hostAddress}/${it.prefixLength}" }
}

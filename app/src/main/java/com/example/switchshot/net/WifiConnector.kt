package com.example.switchshot.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Suppress("UnusedParameter")
@RequiresApi(Build.VERSION_CODES.Q)
suspend fun connectToWifiSpecifier(
    context: Context,
    connectivityManager: ConnectivityManager,
    ssid: String,
    password: String?,
    security: String
) {
    // 一部端末ではWi-Fi接続に位置情報サービスの有効化が求められる点に留意する。
    val specBuilder = WifiNetworkSpecifier.Builder()
        .setSsid(ssid)

    val normalizedSecurity = security.uppercase(Locale.US)
    if (!password.isNullOrEmpty()) {
        when {
            normalizedSecurity.contains("WPA3") -> specBuilder.setWpa3Passphrase(password)
            normalizedSecurity.contains("WPA2") || normalizedSecurity.contains("WPA") ->
                specBuilder.setWpa2Passphrase(password)
            else -> specBuilder.setWpa2Passphrase(password)
        }
    }

    val networkSpecifier = specBuilder.build()
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .setNetworkSpecifier(networkSpecifier)
        .build()

    suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (completed.compareAndSet(false, true)) {
                    // Wi-Fi接続中はSwitchのローカルAPに閉じるため、インターネットへは出られない。
                    connectivityManager.bindProcessToNetwork(network)
                    try {
                        connectivityManager.unregisterNetworkCallback(this)
                    } catch (_: Exception) {
                        // ignored
                    }
                    continuation.resume(Unit)
                }
            }

            override fun onUnavailable() {
                if (completed.compareAndSet(false, true)) {
                    try {
                        connectivityManager.unregisterNetworkCallback(this)
                    } catch (_: Exception) {
                    }
                    continuation.resumeWithException(IOException("Nintendo Switch のAPへ接続できませんでした"))
                }
            }

            override fun onLost(network: Network) {
                if (completed.compareAndSet(false, true)) {
                    connectivityManager.bindProcessToNetwork(null)
                    try {
                        connectivityManager.unregisterNetworkCallback(this)
                    } catch (_: Exception) {
                    }
                    continuation.resumeWithException(IOException("Nintendo Switch のAP接続が切断されました"))
                }
            }
        }

        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) {
                connectivityManager.bindProcessToNetwork(null)
            }
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }

        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (error: Exception) {
            if (completed.compareAndSet(false, true)) {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                continuation.resumeWithException(error)
            }
        }
    }
}

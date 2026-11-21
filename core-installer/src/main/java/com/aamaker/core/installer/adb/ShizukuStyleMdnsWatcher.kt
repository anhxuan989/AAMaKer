package com.aamaker.core.installer.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Reliable Shizuku-style local mDNS service discovery for Android 11+ Wireless Debugging
 * (`_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp`).
 *
 * Fixes key limitations in third-party mDNS wrappers:
 * 1. Matches local network interface addresses reliably even when IPv6 addresses carry zone suffixes (`%wlan0`).
 * 2. Checks port occupancy on both `127.0.0.1` and the resolved interface IP so local `adbd` ports
 *    bound to Wi-Fi IP or loopback are always detected immediately when "Pair device with pairing code" opens.
 * 3. Uses Android 14+ `registerServiceInfoCallback` when available for instant, non-blocking resolution
 *    without `resolveService` listener collisions.
 */
class ShizukuStyleMdnsWatcher(
    context: Context,
    private val serviceType: String,
    private val onPortFound: (host: String, port: Int) -> Unit,
    private val onPortLost: (() -> Unit)? = null
) {

    companion object {
        const val SERVICE_TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_TLS_CONNECT = "_adb-tls-connect._tcp"
        private val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "AAMaker-MdnsResolver").apply { isDaemon = true }
        }
    }

    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val multicastLock: WifiManager.MulticastLock? = runCatching {
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wm?.createMulticastLock("aamaker:mdns:$serviceType")?.apply { setReferenceCounted(false) }
    }.getOrNull()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var registered = false

    @Volatile
    private var activeServiceName: String? = null

    private val activeInfoCallbacks = mutableListOf<NsdManager.ServiceInfoCallback>()

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            registered = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            registered = false
        }

        override fun onDiscoveryStarted(serviceType: String) {
            registered = true
        }

        override fun onDiscoveryStopped(serviceType: String) {
            registered = false
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!running) return
            resolveFoundService(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            if (serviceInfo.serviceName == activeServiceName) {
                activeServiceName = null
                mainHandler.post { onPortLost?.invoke() }
            }
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        val mgr = nsdManager ?: return
        running = true
        runCatching { multicastLock?.acquire() }
        if (!registered) {
            runCatching {
                mgr.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }.onFailure {
                running = false
                runCatching { multicastLock?.release() }
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock.release()
            }
        }
        val mgr = nsdManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (cb in activeInfoCallbacks) {
                runCatching { mgr.unregisterServiceInfoCallback(cb) }
            }
            activeInfoCallbacks.clear()
        }
        if (registered) {
            runCatching { mgr.stopServiceDiscovery(discoveryListener) }
            registered = false
        }
        activeServiceName = null
    }

    private fun resolveFoundService(serviceInfo: NsdServiceInfo) {
        val mgr = nsdManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {}
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    ioExecutor.execute { runCatching { handleResolvedInfo(info) } }
                }
                override fun onServiceLost() {
                    if (activeServiceName == serviceInfo.serviceName) {
                        activeServiceName = null
                        mainHandler.post { onPortLost?.invoke() }
                    }
                }
                override fun onServiceInfoCallbackUnregistered() {}
            }
            synchronized(this) {
                if (!running) return
                activeInfoCallbacks.add(callback)
            }
            runCatching {
                mgr.registerServiceInfoCallback(serviceInfo, ioExecutor, callback)
            }.onFailure {
                synchronized(this) { activeInfoCallbacks.remove(callback) }
                resolveLegacy(mgr, serviceInfo)
            }
        } else {
            resolveLegacy(mgr, serviceInfo)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(mgr: NsdManager, serviceInfo: NsdServiceInfo) {
        runCatching {
            mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    // Retry once after brief delay if NsdManager was busy resolving another service
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && running) {
                        mainHandler.postDelayed({
                            if (running) resolveLegacy(mgr, serviceInfo)
                        }, 180L)
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    ioExecutor.execute { runCatching { handleResolvedInfo(info) } }
                }
            })
        }
    }

    private fun handleResolvedInfo(info: NsdServiceInfo) {
        if (!running) return
        val port = info.port
        if (port <= 0) return

        val addresses: List<InetAddress> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses.ifEmpty { listOfNotNull(info.host) }
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }

        val matchedHost = findMatchingLocalAddress(addresses)
        if (matchedHost != null || isPortListeningLocally("127.0.0.1", port)) {
            val targetHost = if (isPortListeningLocally("127.0.0.1", port)) {
                "127.0.0.1"
            } else {
                matchedHost ?: "127.0.0.1"
            }
            activeServiceName = info.serviceName
            mainHandler.post {
                onPortFound(targetHost, port)
            }
        }
    }

    private fun findMatchingLocalAddress(serviceAddresses: List<InetAddress>): String? {
        if (serviceAddresses.isEmpty()) return null
        val localIps = HashSet<String>()
        localIps.add("127.0.0.1")
        localIps.add("::1")
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (nif in Collections.list(interfaces)) {
                    for (addr in Collections.list(nif.inetAddresses)) {
                        val raw = addr.hostAddress ?: continue
                        localIps.add(stripZoneIndex(raw))
                    }
                }
            }
        }

        for (sAddr in serviceAddresses) {
            val raw = sAddr.hostAddress ?: continue
            val clean = stripZoneIndex(raw)
            if (clean in localIps) {
                return if (clean.contains(':')) "127.0.0.1" else clean
            }
        }
        return null
    }

    private fun stripZoneIndex(ip: String): String {
        val pct = ip.indexOf('%')
        return if (pct > 0) ip.substring(0, pct) else ip
    }

    private fun isPortListeningLocally(host: String, port: Int): Boolean {
        return try {
            ServerSocket().use { ss ->
                ss.bind(InetSocketAddress(host, port), 1)
                false
            }
        } catch (_: IOException) {
            true
        } catch (_: Throwable) {
            false
        }
    }
}

package com.aamaker.app.stage

import android.content.Context
import android.os.Build
import com.aamaker.core.installer.adb.LocalAdbConnectionManager
import com.aamaker.core.installer.adb.ShizukuStyleMdnsWatcher

/**
 * Built-in Local Wireless ADB discovery, SPAKE2 + TLSv1.3 pairing & shell client
 * powered by `LocalAdbConnectionManager` (`libadb-android` + `Conscrypt`) and `ShizukuStyleMdnsWatcher`.
 *
 * Works as a complete drop-in replacement for Shizuku on Android 11+ (Wireless Debugging)
 * and Android 8–10 (ADB TCP 5555).
 */
class LocalWirelessAdbClient {

    private var connectMdns: ShizukuStyleMdnsWatcher? = null
    private var pairingMdns: ShizukuStyleMdnsWatcher? = null

    fun isConnected(context: Context?): Boolean {
        val ctx = context ?: return false
        return runCatching { LocalAdbConnectionManager.getInstance(ctx).isConnected }.getOrDefault(false)
    }

    fun hasPairedKeys(context: Context?): Boolean {
        val ctx = context ?: return false
        return runCatching { LocalAdbConnectionManager.getInstance(ctx).hasPairedKeys() }.getOrDefault(false)
    }

    /**
     * Starts mDNS discovery for Android 11+ Wireless Debugging connect & pairing ports
     * using `ShizukuStyleMdnsWatcher` (same mechanism as Shizuku).
     */
    fun startMdnsDiscovery(
        context: Context,
        onConnectPortFound: (host: String, port: Int) -> Unit,
        onPairingPortFound: (host: String, port: Int) -> Unit,
        onLog: (String) -> Unit
    ) {
        stopMdnsDiscovery()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onLog("mDNS Wireless Debugging requires Android 11+ (API 30+)")
            return
        }
        val appCtx = context.applicationContext

        connectMdns = ShizukuStyleMdnsWatcher(
            context = appCtx,
            serviceType = ShizukuStyleMdnsWatcher.SERVICE_TLS_CONNECT,
            onPortFound = { host, port ->
                if (port > 0) {
                    onLog("Found Wireless ADB (connect): $host:$port")
                    onConnectPortFound(host, port)
                }
            }
        ).also {
            runCatching {
                it.start()
                onLog("mDNS scan started (connect)")
            }.onFailure { err ->
                onLog("mDNS connect scan error: ${err.message}")
            }
        }

        pairingMdns = ShizukuStyleMdnsWatcher(
            context = appCtx,
            serviceType = ShizukuStyleMdnsWatcher.SERVICE_TLS_PAIRING,
            onPortFound = { host, port ->
                if (port > 0) {
                    onLog("Found Wireless ADB (pairing): $host:$port")
                    onPairingPortFound(host, port)
                }
            }
        ).also {
            runCatching {
                it.start()
                onLog("mDNS scan started (pairing)")
            }.onFailure { err ->
                onLog("mDNS pairing scan error: ${err.message}")
            }
        }
    }

    fun stopMdnsDiscovery() {
        runCatching { connectMdns?.stop() }
        runCatching { pairingMdns?.stop() }
        connectMdns = null
        pairingMdns = null
    }

    fun pair(context: Context, host: String, port: Int, pairingCode: String): Result<Boolean> {
        return LocalAdbConnectionManager.getInstance(context).pairWithCode(host, port, pairingCode)
    }

    fun connect(context: Context, host: String, port: Int, timeoutMs: Long = 5000L): Result<Int> {
        return LocalAdbConnectionManager.getInstance(context).ensureConnected(
            host = host,
            port = port,
            autoDiscoverTimeoutMs = timeoutMs
        )
    }

    fun disconnect(context: Context?) {
        val ctx = context ?: return
        runCatching { LocalAdbConnectionManager.getInstance(ctx).disconnect() }
    }

    fun execShell(context: Context?, command: String): Result<String> {
        val ctx = context ?: return Result.failure(IllegalStateException("Context unavailable"))
        return LocalAdbConnectionManager.getInstance(ctx).execShell(command)
    }

    fun execBackgroundShell(context: Context?, command: String): Boolean {
        val ctx = context ?: return false
        return LocalAdbConnectionManager.getInstance(ctx).execBackgroundShell(command)
    }
}

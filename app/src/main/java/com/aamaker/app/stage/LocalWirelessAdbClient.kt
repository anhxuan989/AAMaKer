package com.aamaker.app.stage

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Built-in Local Wireless ADB discovery & lightweight ADB-over-TCP client
 * for the isolated Car Stage feature (runs in parallel with Shizuku).
 */
class LocalWirelessAdbClient {

    companion object {
        private const val TAG = "CarStageLocalAdb"
        private const val SERVICE_ADB_TLS_CONNECT = "_adb-tls-connect._tcp."
        private const val SERVICE_ADB_TLS_PAIRING = "_adb-tls-pairing._tcp."

        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_VERSION = 0x01000000
        private const val A_MAXDATA = 256 * 1024
    }

    private var nsdManager: NsdManager? = null
    private var connectListener: NsdManager.DiscoveryListener? = null
    private var pairingListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var nextLocalId = 1

    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    /**
     * Starts mDNS discovery for Android 11+ Wireless Debugging connect & pairing ports.
     */
    fun startMdnsDiscovery(
        context: Context,
        onConnectPortFound: (host: String, port: Int) -> Unit,
        onPairingPortFound: (host: String, port: Int) -> Unit,
        onLog: (String) -> Unit
    ) {
        stopMdnsDiscovery()
        val mgr = context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            onLog("NsdManager unavailable")
            return
        }
        nsdManager = mgr

        connectListener = createDiscoveryListener(mgr, "connect", onConnectPortFound, onLog)
        pairingListener = createDiscoveryListener(mgr, "pairing", onPairingPortFound, onLog)

        runCatching {
            mgr.discoverServices(SERVICE_ADB_TLS_CONNECT, NsdManager.PROTOCOL_DNS_SD, connectListener)
        }.onFailure { onLog("mDNS connect scan error: ${it.message}") }

        runCatching {
            mgr.discoverServices(SERVICE_ADB_TLS_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairingListener)
        }.onFailure { onLog("mDNS pairing scan error: ${it.message}") }
    }

    fun stopMdnsDiscovery() {
        val mgr = nsdManager ?: return
        connectListener?.let { runCatching { mgr.stopServiceDiscovery(it) } }
        pairingListener?.let { runCatching { mgr.stopServiceDiscovery(it) } }
        connectListener = null
        pairingListener = null
    }

    private fun createDiscoveryListener(
        mgr: NsdManager,
        label: String,
        onFound: (host: String, port: Int) -> Unit,
        onLog: (String) -> Unit
    ): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                onLog("mDNS scan started ($label)")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                mgr.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Resolve failed ($label): $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: "127.0.0.1"
                        val port = info.port
                        if (port > 0) {
                            onLog("Found Wireless ADB ($label): $host:$port")
                            onFound(host, port)
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                onLog("mDNS start failed ($label): $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
    }

    @Synchronized
    fun connect(host: String, port: Int, timeoutMs: Int = 4000): Result<String> {
        disconnect()
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 5000
            val inp = DataInputStream(s.getInputStream())
            val out = DataOutputStream(s.getOutputStream())

            sendPacket(out, A_CNXN, A_VERSION, A_MAXDATA, "host::aamaker-car-stage\u0000".toByteArray())
            val resp = readPacket(inp)
                ?: return Result.failure(IllegalStateException("ADB connection closed by peer"))

            when (resp.command) {
                A_CNXN -> {
                    socket = s
                    input = inp
                    output = out
                    Result.success(String(resp.payload).trimEnd('\u0000'))
                }
                A_AUTH -> {
                    s.close()
                    Result.failure(
                        IllegalStateException(
                            "ADB requires authorization/TLS pairing on $host:$port. Enable ADB TCP (5555) or pair first."
                        )
                    )
                }
                else -> {
                    s.close()
                    Result.failure(IllegalStateException("Unexpected ADB response: 0x${resp.command.toString(16)}"))
                }
            }
        } catch (t: Throwable) {
            disconnect()
            Result.failure(t)
        }
    }

    @Synchronized
    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    @Synchronized
    fun execShell(command: String): Result<String> {
        val inp = input ?: return Result.failure(IllegalStateException("Not connected"))
        val out = output ?: return Result.failure(IllegalStateException("Not connected"))
        return try {
            val localId = nextLocalId++
            sendPacket(out, A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())

            val buffer = ByteArrayOutputStream()
            var remoteId = 0
            var done = false
            while (!done) {
                val pkt = readPacket(inp) ?: break
                when (pkt.command) {
                    A_OKAY -> {
                        if (remoteId == 0) remoteId = pkt.arg0
                    }
                    A_WRTE -> {
                        remoteId = pkt.arg0
                        buffer.write(pkt.payload)
                        sendPacket(out, A_OKAY, localId, remoteId, ByteArray(0))
                    }
                    A_CLSE -> {
                        if (remoteId != 0) {
                            sendPacket(out, A_CLSE, localId, remoteId, ByteArray(0))
                        }
                        done = true
                    }
                }
            }
            Result.success(buffer.toString("UTF-8").trim())
        } catch (t: Throwable) {
            disconnect()
            Result.failure(t)
        }
    }

    private data class AdbPacket(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    private fun sendPacket(out: DataOutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        var checksum = 0
        for (b in payload) {
            checksum += (b.toInt() and 0xFF)
        }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(cmd)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(checksum)
        header.putInt(cmd xor -1)
        out.write(header.array())
        if (payload.isNotEmpty()) {
            out.write(payload)
        }
        out.flush()
    }

    private fun readPacket(inp: DataInputStream): AdbPacket? {
        val headerBytes = ByteArray(24)
        return try {
            inp.readFully(headerBytes)
            val bb = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = bb.int
            val arg0 = bb.int
            val arg1 = bb.int
            val dataLen = bb.int
            bb.int // checksum
            bb.int // magic
            val payload = if (dataLen > 0) {
                ByteArray(dataLen).also { inp.readFully(it) }
            } else {
                ByteArray(0)
            }
            AdbPacket(cmd, arg0, arg1, payload)
        } catch (_: Throwable) {
            null
        }
    }
}

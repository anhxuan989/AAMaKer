package com.aamaker.core.installer.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared Local Wireless ADB Manager (TLSv1.3 + SPAKE2 Pairing + Shell Stream Execution)
 * for both `core-installer` and `app`.
 *
 * Implements the exact Android 11+ Wireless Debugging pairing & connection protocol used by Shizuku:
 * - mDNS discovery for `_adb-tls-pairing._tcp` and `_adb-tls-connect._tcp`
 * - SPAKE2 + TLSv1.3 pairing via 6-digit pairing code
 * - Persistent RSA-2048 keypair & self-signed X.509 certificate stored in app filesDir
 * - Auto-connect over TLSv1.3 or TCP (port 5555 fallback)
 * - Multiplexed `AdbStream` shell command execution with optional stdin streaming
 */
class LocalAdbConnectionManager private constructor(
    private val appContext: Context
) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: X509Certificate

    @Volatile
    var lastConnectedHost: String = "127.0.0.1"
        private set

    @Volatile
    var lastConnectedPort: Int = -1
        private set

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        setApi(Build.VERSION.SDK_INT)
        setTimeout(8, TimeUnit.SECONDS)
        setThrowOnUnauthorised(false)
        val (pk, cert) = loadOrGenerateAdbKeyPair(appContext)
        privateKey = pk
        certificate = cert
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "AAMaKer@${Build.MODEL}"

    fun hasPairedKeys(): Boolean {
        return File(File(appContext.filesDir, "local_adb_keys"), "adb_paired.ok").exists() || isConnected
    }

    private fun markPaired() {
        runCatching {
            val dir = File(appContext.filesDir, "local_adb_keys").apply { mkdirs() }
            File(dir, "adb_paired.ok").writeText("1")
        }
    }

    @Synchronized
    fun pairWithCode(host: String, port: Int, pairingCode: String): Result<Boolean> {
        val cleanHost = host.ifBlank { "127.0.0.1" }
        val cleanCode = pairingCode.trim()
        if (port <= 0 || cleanCode.length < 6) {
            return Result.failure(IllegalArgumentException("Invalid pairing port or 6-digit code"))
        }
        return try {
            val ok = pair(cleanHost, port, cleanCode)
            if (ok) {
                markPaired()
                Result.success(true)
            } else {
                Result.failure(IllegalStateException("ADB SPAKE2 pairing rejected by daemon on $cleanHost:$port"))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    @JvmOverloads
    fun tryEnsureConnected(
        host: String = lastConnectedHost,
        port: Int = lastConnectedPort,
        autoDiscoverTimeoutMs: Long = 5000L
    ): Boolean = ensureConnected(host, port, autoDiscoverTimeoutMs).isSuccess

    @Synchronized
    fun ensureConnected(
        host: String = lastConnectedHost,
        port: Int = lastConnectedPort,
        autoDiscoverTimeoutMs: Long = 5000L
    ): Result<Int> {
        return try {
            if (isConnected) {
                markPaired()
                return Result.success(lastConnectedPort.takeIf { it > 0 } ?: port)
            }
            val targetHost = host.ifBlank { "127.0.0.1" }

            // 1. If a valid port is known, try connecting directly first
            if (port > 0) {
                val directOk = runCatching { connect(targetHost, port) }.getOrDefault(false)
                if (directOk && isConnected) {
                    lastConnectedHost = targetHost
                    lastConnectedPort = port
                    markPaired()
                    return Result.success(port)
                }
            }

            // 2. On Android 11+ (API 30+), use autoConnect / connectTls via mDNS discovery
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val discoveredPort = discoverConnectPortBlocking(autoDiscoverTimeoutMs)
                if (discoveredPort > 0) {
                    val mdnsOk = connect(targetHost, discoveredPort)
                    if (mdnsOk && isConnected) {
                        lastConnectedHost = targetHost
                        lastConnectedPort = discoveredPort
                        markPaired()
                        return Result.success(discoveredPort)
                    }
                }
                val autoOk = runCatching { autoConnect(appContext, autoDiscoverTimeoutMs) }.getOrDefault(false)
                if (autoOk && isConnected) {
                    lastConnectedHost = hostAddress ?: targetHost
                    markPaired()
                    return Result.success(lastConnectedPort.takeIf { it > 0 } ?: discoveredPort.coerceAtLeast(5555))
                }
            }

            // 3. Fallback to standard TCP port 5555
            val tcpOk = runCatching { connect(targetHost, 5555) }.getOrDefault(false)
            if (tcpOk && isConnected) {
                lastConnectedHost = targetHost
                lastConnectedPort = 5555
                markPaired()
                return Result.success(5555)
            }

            Result.failure(
                IllegalStateException(
                    "Unable to connect to Local Wireless ADB. Please enable Wireless Debugging or pair first."
                )
            )
        } catch (e: AdbPairingRequiredException) {
            Result.failure(
                IllegalStateException(
                    "Wireless ADB requires pairing first. Use 'Pair with pairing code' in Wireless Debugging.",
                    e
                )
            )
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private fun discoverConnectPortBlocking(timeoutMs: Long): Int {
        var foundPort = -1
        val latch = CountDownLatch(1)
        val watcher = ShizukuStyleMdnsWatcher(
            context = appContext,
            serviceType = ShizukuStyleMdnsWatcher.SERVICE_TLS_CONNECT,
            onPortFound = { _, port ->
                if (port > 0) {
                    foundPort = port
                    latch.countDown()
                }
            }
        )
        return try {
            watcher.start()
            latch.await(timeoutMs.coerceIn(1000L, 15000L), TimeUnit.MILLISECONDS)
            foundPort
        } catch (_: Throwable) {
            foundPort
        } finally {
            runCatching { watcher.stop() }
        }
    }

    /**
     * Executes an ADB shell command over an opened AdbStream.
     * Supports optional `stdinFile` streaming (e.g. for `pm install -S <size>`).
     */
    fun execShell(
        command: String,
        stdinFile: File? = null,
        timeoutMs: Long = 30_000L
    ): Result<String> {
        if (!isConnected) {
            val connRes = ensureConnected()
            if (connRes.isFailure) {
                return Result.failure(connRes.exceptionOrNull() ?: IllegalStateException("Local ADB not connected"))
            }
        }
        return try {
            val serviceStr = if (stdinFile != null) {
                "exec:$command"
            } else {
                "shell:$command"
            }
            openStream(serviceStr).use { stream ->
                if (stdinFile != null) {
                    val os = stream.openOutputStream()
                    stdinFile.inputStream().use { fis ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = fis.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                        }
                        os.flush()
                    }
                }
                val outputBytes = ByteArrayOutputStream()
                val ins = stream.openInputStream()
                readStreamWithTimeout(ins, outputBytes, timeoutMs)
                Result.success(outputBytes.toString("UTF-8").trim())
            }
        } catch (t: Throwable) {
            // Reconnect once if the stream failed due to stale socket
            runCatching { disconnect() }
            Result.failure(t)
        }
    }

    /**
     * Launches a detached background command (e.g. `app_process` server daemon) over Local ADB:
     * - Uses `shell:sh` + stdin script (just like `Shizuku.newProcess(["sh"])`) so `/proc/<sh_pid>/cmdline`
     *   contains only `sh` and NEVER matches `pkill -f` patterns inside the script.
     * - Masks `SIGHUP` via `trap '' HUP` and redirects stdio to `/dev/null` so closing the `AdbStream`
     *   never terminates the spawned `app_process` server.
     */
    fun execBackgroundShell(command: String): Boolean {
        if (!isConnected) {
            if (ensureConnected().isFailure) return false
        }
        return try {
            openStream("shell:sh").use { stream ->
                val os = stream.openOutputStream()
                val script = "(trap '' HUP; $command) </dev/null >/dev/null 2>&1 &\necho __AAMAKER_BG_OK__\nexit 0\n"
                os.write(script.toByteArray(Charsets.UTF_8))
                os.flush()

                val out = ByteArrayOutputStream()
                val ins = stream.openInputStream()
                val buf = ByteArray(1024)
                val deadline = System.currentTimeMillis() + 5000L
                while (System.currentTimeMillis() < deadline) {
                    val n = try {
                        ins.read(buf)
                    } catch (_: Throwable) {
                        -1
                    }
                    if (n < 0) break
                    if (n > 0) {
                        out.write(buf, 0, n)
                        if (out.toString("UTF-8").contains("__AAMAKER_BG_OK__")) break
                    }
                }
                out.toString("UTF-8").contains("__AAMAKER_BG_OK__")
            }
        } catch (_: Throwable) {
            runCatching { disconnect() }
            false
        }
    }

    private fun readStreamWithTimeout(
        ins: InputStream,
        out: ByteArrayOutputStream,
        timeoutMs: Long
    ) {
        val buf = ByteArray(8192)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = try {
                ins.read(buf)
            } catch (_: Throwable) {
                -1
            }
            if (n < 0) break
            if (n > 0) {
                out.write(buf, 0, n)
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: LocalAdbConnectionManager? = null

        @JvmStatic
        fun getInstance(context: Context): LocalAdbConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocalAdbConnectionManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun loadOrGenerateAdbKeyPair(context: Context): Pair<PrivateKey, X509Certificate> {
            val keyDir = File(context.filesDir, "local_adb_keys").apply { mkdirs() }
            val pkFile = File(keyDir, "adb_rsa.pk8")
            val certFile = File(keyDir, "adb_cert.der")

            if (pkFile.exists() && certFile.exists()) {
                try {
                    val pkBytes = pkFile.readBytes()
                    val privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(pkBytes))
                    val cert = certFile.inputStream().use {
                        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                    }
                    return privateKey to cert
                } catch (_: Throwable) {
                    pkFile.delete()
                    certFile.delete()
                }
            }

            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048)
            val keyPair = kpg.generateKeyPair()
            val certBytes = generateAdbSelfSignedDerCert(keyPair)

            pkFile.writeBytes(keyPair.private.encoded)
            certFile.writeBytes(certBytes)

            val cert = certBytes.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            return keyPair.private to cert
        }

        private fun generateAdbSelfSignedDerCert(keyPair: KeyPair): ByteArray {
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 86400_000L)
            val notAfter = Date(now + 25L * 365 * 86400_000L)

            val sigAlgId = derSequence(
                derOid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
                derNull()
            )

            val cRdn = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x06)), derPrintableString("US")))
            val oRdn = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x0A)), derUtf8String("AAMaKer Local ADB")))
            val cnRdn = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x03)), derUtf8String("AAMaKer Wireless ADB")))
            val name = derSequence(cRdn, oRdn, cnRdn)

            val validity = derSequence(derUtcTime(notBefore), derUtcTime(notAfter))
            val versionV3 = derExplicitTag(0, derInteger(BigInteger.valueOf(2)))
            val serialNumber = derInteger(BigInteger.valueOf((now xor 0x3F6A9C21L) and 0x7FFFFFFFL))
            val subjectPublicKeyInfo = keyPair.public.encoded

            val tbsCertificate = derSequence(
                versionV3,
                serialNumber,
                sigAlgId,
                name,
                validity,
                name,
                subjectPublicKeyInfo
            )

            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbsCertificate)
            }.sign()

            return derSequence(
                tbsCertificate,
                sigAlgId,
                derBitString(signature)
            )
        }

        private fun derSequence(vararg items: ByteArray): ByteArray = derTag(0x30, concat(*items))
        private fun derSet(vararg items: ByteArray): ByteArray = derTag(0x31, concat(*items))
        private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
        private fun derOid(content: ByteArray): ByteArray = derTag(0x06, content)
        private fun derUtf8String(str: String): ByteArray = derTag(0x0C, str.toByteArray(Charsets.UTF_8))
        private fun derPrintableString(str: String): ByteArray = derTag(0x13, str.toByteArray(Charsets.US_ASCII))
        private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())
        private fun derExplicitTag(tagNo: Int, content: ByteArray): ByteArray = derTag(0xA0 or tagNo, content)

        private fun derBitString(bytes: ByteArray): ByteArray {
            val padded = ByteArray(bytes.size + 1)
            padded[0] = 0x00
            System.arraycopy(bytes, 0, padded, 1, bytes.size)
            return derTag(0x03, padded)
        }

        private fun derUtcTime(date: Date): ByteArray {
            val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return derTag(0x17, sdf.format(date).toByteArray(Charsets.US_ASCII))
        }

        private fun derTag(tag: Int, content: ByteArray): ByteArray {
            val lenBytes = encodeLength(content.size)
            val out = ByteArray(1 + lenBytes.size + content.size)
            out[0] = tag.toByte()
            System.arraycopy(lenBytes, 0, out, 1, lenBytes.size)
            System.arraycopy(content, 0, out, 1 + lenBytes.size, content.size)
            return out
        }

        private fun encodeLength(length: Int): ByteArray {
            return when {
                length < 128 -> byteArrayOf(length.toByte())
                length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
                length <= 0xFFFF -> byteArrayOf(
                    0x82.toByte(),
                    (length shr 8).toByte(),
                    (length and 0xFF).toByte()
                )
                else -> byteArrayOf(
                    0x83.toByte(),
                    (length shr 16).toByte(),
                    ((length shr 8) and 0xFF).toByte(),
                    (length and 0xFF).toByte()
                )
            }
        }

        private fun concat(vararg arrays: ByteArray): ByteArray {
            val total = arrays.sumOf { it.size }
            val result = ByteArray(total)
            var pos = 0
            for (arr in arrays) {
                System.arraycopy(arr, 0, result, pos, arr.size)
                pos += arr.size
            }
            return result
        }
    }
}

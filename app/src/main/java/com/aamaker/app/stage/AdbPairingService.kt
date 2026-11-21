package com.aamaker.app.stage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.aamaker.app.R
import com.aamaker.app.i18n.AppLanguage
import com.aamaker.app.i18n.CarStageLocalization
import com.aamaker.core.installer.adb.ShizukuStyleMdnsWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shizuku-identical Wireless ADB Pairing Foreground Service + BroadcastReceiver:
 *
 * Exact Shizuku pairing flow:
 * 1. User taps "Pair (Shizuku Mode)": starts foreground service with a high-priority notification
 *    ("Searching for pairing service...") + "Wireless Debugging" shortcut button so the user can jump
 *    straight into Developer Options > Wireless Debugging > Pair device with pairing code.
 * 2. `ShizukuStyleMdnsWatcher` detects `_adb-tls-pairing._tcp` on Wi-Fi IP or loopback (`127.0.0.1`)
 *    the instant the system pairing dialog opens.
 * 3. Immediately posts a heads-up notification with an inline `androidx.core.app.RemoteInput` field
 *    ("Enter 6-digit pairing code") routed via `AdbPairingReplyReceiver` (`PendingIntent.getBroadcast`),
 *    which works reliably across stock Android, Samsung OneUI, Pixel, Xiaomi HyperOS, Oppo ColorOS, and Vivo.
 *    (Also allows typing "port:code" or 6-digit code even before mDNS finishes if the user expands the notification).
 * 4. Performs SPAKE2 + TLSv1.3 pairing, auto-connects to `_adb-tls-connect._tcp`, and starts the
 *    privileged UID 2000 servers (`CarStageDisplayServer`, `AAMakerPrivilegedDisplayServer`, `DisplayService`).
 */
class AdbPairingService : Service() {

    companion object {
        const val CHANNEL_ID = "aamaker_adb_pairing_v2"
        const val NOTIFICATION_ID = 4209
        const val ACTION_START_PAIRING = "com.aamaker.app.stage.action.START_PAIRING"
        const val ACTION_STOP_PAIRING = "com.aamaker.app.stage.action.STOP_PAIRING"
        const val ACTION_SUBMIT_CODE = "com.aamaker.app.stage.action.SUBMIT_PAIRING_CODE"
        const val EXTRA_PAIRING_CODE = "extra_pairing_code"
        const val KEY_REMOTE_INPUT_CODE = "adb_pairing_code_remote_input"

        fun startIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_START_PAIRING)

        fun stopIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP_PAIRING)
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pairingWatcher: ShizukuStyleMdnsWatcher? = null
    private var connectWatcher: ShizukuStyleMdnsWatcher? = null

    @Volatile
    private var discoveredPairingHost: String = "127.0.0.1"

    @Volatile
    private var discoveredPairingPort: Int = -1

    @Volatile
    private var discoveredConnectPort: Int = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        when (intent?.action) {
            ACTION_STOP_PAIRING -> {
                stopMdnsWatchers()
                CarStageBridge.onPairingServiceStateChanged(isRunning = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SUBMIT_CODE -> {
                val remoteBundle = RemoteInput.getResultsFromIntent(intent)
                val codeFromRemote = remoteBundle?.getCharSequence(KEY_REMOTE_INPUT_CODE)?.toString()?.trim()
                val code = codeFromRemote.takeUnless { it.isNullOrEmpty() }
                    ?: intent.getStringExtra(EXTRA_PAIRING_CODE).orEmpty().trim()
                if (code.isNotEmpty()) {
                    handlePairingCodeSubmitted(code)
                }
                return START_REDELIVER_INTENT
            }
            else -> {
                startPairingFlow()
                return START_REDELIVER_INTENT
            }
        }
    }

    private fun startPairingFlow() {
        val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(this))
        // Immediately include RemoteInput on the initial notification too (supports `port:code` fallback
        // if an OEM router/VPN blocks mDNS, while updating automatically to `Pairing service found (port)` as soon as mDNS resolves!)
        val notif = buildSearchingOrInputNotification(
            title = strings.notifPairSearchingTitle,
            text = strings.notifPairSearchingText,
            inputLabel = strings.notifPairInputLabel,
            devOptionsLabel = strings.btnOpenDevOptions,
            stopLabel = strings.btnStopPairing,
            alertHeadsUp = true
        )
        startForegroundCompat(notif)
        CarStageBridge.onPairingServiceStateChanged(isRunning = true)

        stopMdnsWatchers()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pairingWatcher = ShizukuStyleMdnsWatcher(
                context = this,
                serviceType = ShizukuStyleMdnsWatcher.SERVICE_TLS_PAIRING,
                onPortFound = { host, port ->
                    if (port > 0) {
                        val isNewPort = discoveredPairingPort != port
                        discoveredPairingHost = host.ifBlank { "127.0.0.1" }
                        discoveredPairingPort = port
                        CarStageBridge.onMdnsPairingPortDiscovered(discoveredPairingHost, port)
                        val s = CarStageLocalization.of(AppLanguage.loadSavedLanguage(this))
                        val foundNotif = buildSearchingOrInputNotification(
                            title = "${s.notifPairFoundTitle} ($port)",
                            text = s.notifPairFoundText,
                            inputLabel = s.notifPairInputLabel,
                            devOptionsLabel = null,
                            stopLabel = s.btnStopPairing,
                            alertHeadsUp = isNewPort
                        )
                        notifyCompat(foundNotif)
                    }
                },
                onPortLost = {
                    discoveredPairingPort = -1
                    CarStageBridge.onMdnsPairingPortDiscovered("127.0.0.1", -1)
                    val s = CarStageLocalization.of(AppLanguage.loadSavedLanguage(this))
                    val searchingAgain = buildSearchingOrInputNotification(
                        title = s.notifPairSearchingTitle,
                        text = s.notifPairSearchingText,
                        inputLabel = s.notifPairInputLabel,
                        devOptionsLabel = s.btnOpenDevOptions,
                        stopLabel = s.btnStopPairing,
                        alertHeadsUp = false
                    )
                    notifyCompat(searchingAgain)
                }
            ).also { it.start() }

            connectWatcher = ShizukuStyleMdnsWatcher(
                context = this,
                serviceType = ShizukuStyleMdnsWatcher.SERVICE_TLS_CONNECT,
                onPortFound = { host, port ->
                    if (port > 0) {
                        discoveredConnectPort = port
                        CarStageBridge.onMdnsConnectPortDiscovered(host.ifBlank { "127.0.0.1" }, port)
                    }
                }
            ).also { it.start() }
        }
    }

    private fun handlePairingCodeSubmitted(rawInput: String) {
        val s = CarStageLocalization.of(AppLanguage.loadSavedLanguage(this))
        val workingNotif = buildProgressNotification(
            title = s.notifPairFoundTitle,
            text = s.notifPairWorkingText,
            stopLabel = s.btnStopPairing
        )
        notifyCompat(workingNotif)

        // Support both standard 6-digit code ("123456") when mDNS found the port,
        // AND "port:code" ("39123:123456" or "39123 123456") if the user typed port manually
        var explicitPort = -1
        var cleanCode = rawInput.trim()
        val parts = cleanCode.split(':', ' ', '-', limit = 2)
        if (parts.size == 2) {
            val pCandidate = parts[0].filter { it.isDigit() }.toIntOrNull()
            val cCandidate = parts[1].filter { it.isDigit() }
            if (pCandidate != null && pCandidate in 1024..65535 && cCandidate.length == 6) {
                explicitPort = pCandidate
                cleanCode = cCandidate
            }
        }
        if (explicitPort <= 0) {
            cleanCode = cleanCode.filter { it.isDigit() }.take(6)
        }

        val port = explicitPort.takeIf { it > 0 }
            ?: discoveredPairingPort.takeIf { it > 0 }
            ?: CarStageBridge.state.value.discoveredPairingPort
            ?: -1
        val host = discoveredPairingHost.ifBlank { "127.0.0.1" }

        serviceScope.launch {
            if (port <= 0) {
                val latestStrings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(applicationContext))
                val retryNotif = buildSearchingOrInputNotification(
                    title = latestStrings.notifPairFailedTitle,
                    text = "${latestStrings.notifPairSearchingText} (Port?)",
                    inputLabel = latestStrings.notifPairInputLabel,
                    devOptionsLabel = latestStrings.btnOpenDevOptions,
                    stopLabel = latestStrings.btnStopPairing,
                    alertHeadsUp = true
                )
                notifyCompat(retryNotif)
                return@launch
            }

            val pairResult = CarStageBridge.pairAndConnectBlocking(
                context = applicationContext,
                host = host,
                pairingPort = port,
                pairingCode = cleanCode,
                knownConnectPort = discoveredConnectPort
            )
            pairResult.onSuccess {
                stopMdnsWatchers()
                CarStageBridge.onPairingServiceStateChanged(isRunning = false)
                val latestStrings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(applicationContext))
                val doneNotif = buildResultNotification(
                    title = latestStrings.notifPairSuccessTitle,
                    text = latestStrings.notifPairSuccessText
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                notifyCompat(doneNotif)
                stopSelf()
            }.onFailure { err ->
                val latestStrings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(applicationContext))
                val retryNotif = buildSearchingOrInputNotification(
                    title = latestStrings.notifPairFailedTitle,
                    text = err.message ?: latestStrings.notifPairFoundText,
                    inputLabel = latestStrings.notifPairInputLabel,
                    devOptionsLabel = null,
                    stopLabel = latestStrings.btnStopPairing,
                    alertHeadsUp = true
                )
                notifyCompat(retryNotif)
            }
        }
    }

    private fun stopMdnsWatchers() {
        runCatching { pairingWatcher?.stop() }
        runCatching { connectWatcher?.stop() }
        pairingWatcher = null
        connectWatcher = null
    }

    override fun onDestroy() {
        stopMdnsWatchers()
        CarStageBridge.onPairingServiceStateChanged(isRunning = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val strings = CarStageLocalization.of(AppLanguage.loadSavedLanguage(this))
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                strings.notifPairChannelName,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = strings.adbPairingGuide
                enableVibration(true)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildSearchingOrInputNotification(
        title: String,
        text: String,
        inputLabel: String,
        devOptionsLabel: String?,
        stopLabel: String,
        alertHeadsUp: Boolean
    ): Notification {
        val replyBroadcastIntent = Intent(this, AdbPairingReplyReceiver::class.java).apply {
            action = ACTION_SUBMIT_CODE
            setPackage(packageName)
        }
        val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPending = PendingIntent.getBroadcast(this, 201, replyBroadcastIntent, replyFlags)

        val remoteInput = RemoteInput.Builder(KEY_REMOTE_INPUT_CODE)
            .setLabel(inputLabel)
            .build()

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_shell,
            inputLabel,
            replyPending
        )
            .addRemoteInput(remoteInput)
            .setShowsUserInterface(false)
            .build()

        val stopPending = PendingIntent.getService(
            this,
            202,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_close,
            stopLabel,
            stopPending
        ).build()

        val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        val devPending = PendingIntent.getActivity(
            this,
            203,
            devIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!alertHeadsUp)
            .setContentIntent(devPending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(replyAction)

        if (!devOptionsLabel.isNullOrBlank()) {
            val devAction = NotificationCompat.Action.Builder(
                R.drawable.ic_shell,
                devOptionsLabel,
                devPending
            ).build()
            builder.addAction(devAction)
        }

        builder.addAction(stopAction)
        return builder.build()
    }

    private fun buildProgressNotification(
        title: String,
        text: String,
        stopLabel: String
    ): Notification {
        val stopPending = PendingIntent.getService(
            this,
            202,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.ic_close,
            stopLabel,
            stopPending
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shell)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(stopAction)
            .build()
    }

    private fun buildResultNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shell)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val startedConnected = runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            }.isSuccess
            if (!startedConnected) {
                val startedSpecial = runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                }.isSuccess
                if (!startedSpecial) {
                    notifyCompat(notification)
                }
            }
        } else {
            runCatching {
                startForeground(NOTIFICATION_ID, notification)
            }.onFailure {
                notifyCompat(notification)
            }
        }
    }

    private fun notifyCompat(notification: Notification) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }
}

/**
 * Dedicated BroadcastReceiver for `NotificationCompat.Action` + `RemoteInput` inline reply,
 * matching Shizuku's notification reply architecture so Android SystemUI always delivers
 * the typed 6-digit pairing code immediately without background-service PendingIntent drops.
 */
class AdbPairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val remoteResults = RemoteInput.getResultsFromIntent(intent)
        val code = remoteResults?.getCharSequence(AdbPairingService.KEY_REMOTE_INPUT_CODE)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (code.isEmpty()) return

        val svcIntent = Intent(context, AdbPairingService::class.java).apply {
            action = AdbPairingService.ACTION_SUBMIT_CODE
            putExtra(AdbPairingService.EXTRA_PAIRING_CODE, code)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        }
    }
}

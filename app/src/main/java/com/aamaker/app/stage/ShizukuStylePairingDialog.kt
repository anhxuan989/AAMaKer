package com.aamaker.app.stage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aamaker.app.i18n.CarStageStrings

/**
 * Checks if both Android 13+ POST_NOTIFICATIONS runtime permission AND system NotificationManager
 * toggle are enabled so the Shizuku-style RemoteInput pairing notification can appear.
 */
fun arePairingNotificationsEnabled(context: Context): Boolean {
    val runtimeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    return runtimeOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

fun openAppNotificationSettings(context: Context) {
    runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        context.startActivity(intent)
    }
}

/**
 * Shizuku-identical Wireless ADB Pairing Dialog & Live Status Card:
 * 1. Requests `POST_NOTIFICATIONS` permission automatically if not granted, or provides a direct
 *    one-tap shortcut to System App Notification Settings if notifications were blocked/denied.
 * 2. Starts `AdbPairingService` + live mDNS scan immediately when opened.
 * 3. Shows live Shizuku-identical status:
 *    - Spinner + "Searching for pairing service..." while waiting for user to open Wireless Debugging > Pair with code.
 *    - Checkmark + "Pairing service found (<port>)" + 6-digit Pairing Code input box as soon as mDNS detects `_adb-tls-pairing._tcp`
 *      (and simultaneously posts the `RemoteInput` notification in the shade!).
 *    - Spinner + "Pairing with device via SPAKE2..." while authenticating, then auto-closes or shows "Pairing successful".
 */
@Composable
fun ShizukuStylePairingDialog(
    strings: CarStageStrings,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val stageState by CarStageBridge.state.collectAsState()

    var notifEnabled by remember { mutableStateOf(arePairingNotificationsEnabled(context)) }
    var codeText by remember { mutableStateOf("") }
    var manualPortText by remember(stageState.discoveredPairingPort) {
        mutableStateOf(stageState.discoveredPairingPort?.toString().orEmpty())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifEnabled = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (notifEnabled) {
            CarStageBridge.startShizukuStylePairingService(context)
        } else {
            // User previously tapped "Don't ask again" or system toggle is off -> open App Notification Settings
            openAppNotificationSettings(context)
        }
    }

    // Re-check notification status whenever returning from Settings and restart foreground notification if newly granted
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val nowEnabled = arePairingNotificationsEnabled(context)
                val wasDisabled = !notifEnabled
                notifEnabled = nowEnabled
                if (nowEnabled && (wasDisabled || !stageState.isPairingServiceRunning) && !stageState.isBound) {
                    CarStageBridge.startShizukuStylePairingService(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Start mDNS scan + Foreground Pairing Notification Service immediately when dialog opens
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        if (arePairingNotificationsEnabled(context)) {
            CarStageBridge.startShizukuStylePairingService(context)
        } else {
            CarStageBridge.startMdnsScan(context)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.pairDialogTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 1. Notification permission warning banner (if disabled)
                if (!notifEnabled) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = strings.notifDisabledWarning,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            FilledTonalButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.POST_NOTIFICATIONS
                                        ) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        openAppNotificationSettings(context)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Notifications,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.btnNotifPermission)
                            }
                        }
                    }
                }

                // 2. Live Shizuku-style Discovery / Pairing Status Box
                val foundPairingPort = stageState.discoveredPairingPort
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (stageState.isBound) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else if (foundPairingPort != null && foundPairingPort > 0) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        when {
                            stageState.isBound -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.notifPairSuccessTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        text = strings.notifPairSuccessText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            stageState.isBinding -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.statusConnecting,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = strings.notifPairWorkingText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            foundPairingPort != null && foundPairingPort > 0 -> {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${strings.notifPairFoundTitle} ($foundPairingPort)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = strings.notifPairFoundText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                            else -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.notifPairSearchingTitle,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = strings.notifPairSearchingText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Step-by-step instructions (identical to Shizuku's AdbPairingTutorial)
                if (!stageState.isBound) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = strings.pairStep1, style = MaterialTheme.typography.bodySmall)
                        Text(text = strings.pairStep2, style = MaterialTheme.typography.bodySmall)
                        Text(text = strings.pairStep3, style = MaterialTheme.typography.bodySmall)
                    }

                    OutlinedButton(
                        onClick = {
                            if (arePairingNotificationsEnabled(context)) {
                                CarStageBridge.startShizukuStylePairingService(context)
                            }
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(strings.btnOpenDevOptions)
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    // 4. Direct in-dialog 6-digit pairing code input (works in Split-Screen / Floating Window or after checking code)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = manualPortText,
                            onValueChange = { valClean ->
                                manualPortText = valClean.filter { it.isDigit() }.take(5)
                            },
                            label = { Text(strings.adbPortLabel) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.9f)
                        )
                        OutlinedTextField(
                            value = codeText,
                            onValueChange = { valClean ->
                                val digits = valClean.filter { it.isDigit() }.take(6)
                                codeText = digits
                                val targetPort = manualPortText.toIntOrNull() ?: stageState.discoveredPairingPort ?: -1
                                if (digits.length == 6 && targetPort > 0 && !stageState.isBinding) {
                                    CarStageBridge.pairWithCodeFromUi(context, targetPort, digits)
                                }
                            },
                            label = { Text(strings.adbPairingCodeLabel) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1.3f)
                        )
                    }

                    if (stageState.lastError.isNotBlank()) {
                        Text(
                            text = stageState.lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (stageState.isBound) {
                Button(onClick = onDismiss) {
                    Text(strings.btnClose)
                }
            } else {
                val targetPort = manualPortText.toIntOrNull() ?: stageState.discoveredPairingPort ?: -1
                Button(
                    onClick = {
                        if (targetPort > 0 && codeText.length == 6) {
                            CarStageBridge.pairWithCodeFromUi(context, targetPort, codeText)
                        }
                    },
                    enabled = !stageState.isBinding && targetPort > 0 && codeText.length == 6
                ) {
                    Text(strings.btnConfirmPair)
                }
            }
        },
        dismissButton = {
            if (!stageState.isBound) {
                TextButton(onClick = onDismiss) {
                    Text(strings.btnClose)
                }
            }
        }
    )
}

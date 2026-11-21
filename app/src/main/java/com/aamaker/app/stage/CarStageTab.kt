package com.aamaker.app.stage

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aamaker.app.data.TargetAppCandidate
import com.aamaker.app.i18n.CarStageStrings

@Composable
fun CarStageTab(
    candidates: List<TargetAppCandidate>,
    strings: CarStageStrings,
    onRequestShizuku: () -> Unit
) {
    val context = LocalContext.current
    val stageState by CarStageBridge.state.collectAsState()
    var showAppPicker by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }

    if (showPairingDialog) {
        ShizukuStylePairingDialog(
            strings = strings,
            onDismiss = { showPairingDialog = false }
        )
    }

    LaunchedEffect(Unit) {
        CarStageBridge.init(context)
    }

    val shizukuReady = remember(stageState.isBound, stageState.isBinding, stageState.lastError) {
        CarStageBridge.isShizukuReady()
    }

    val installedByPkg = remember(candidates) {
        candidates.associateBy { it.packageName }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = strings.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = strings.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 1. Bridge Status Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.bridgeCardTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (stageState.isBound) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (stageState.isBound) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                stageState.isBound -> strings.statusConnected
                                stageState.isBinding -> strings.statusConnecting
                                else -> strings.statusDisconnected
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (stageState.isBound) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Bridge mode selector: Shizuku vs Local Wireless ADB
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilterChip(
                        selected = stageState.config.bridgeMode == StageBridgeMode.SHIZUKU,
                        onClick = { CarStageBridge.setBridgeMode(context, StageBridgeMode.SHIZUKU) },
                        label = { Text(strings.bridgeModeShizuku, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = stageState.config.bridgeMode == StageBridgeMode.LOCAL_WIRELESS_ADB,
                        onClick = { CarStageBridge.setBridgeMode(context, StageBridgeMode.LOCAL_WIRELESS_ADB) },
                        label = { Text(strings.bridgeModeLocalAdb, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.weight(1f)
                    )
                }

                val engineLabel = when (stageState.engineMode) {
                    "VIRTUAL_DEVICE" -> strings.engineVirtualDevice
                    "TRUSTED_DISPLAY" -> strings.engineTrustedDisplay
                    "LOCAL_ADB" -> strings.engineLocalAdb
                    else -> strings.engineStopped
                }
                Text(
                    text = if (stageState.activeDisplayId > 0) {
                        "$engineLabel • ${strings.displayIdLabel}: ${stageState.activeDisplayId}"
                    } else {
                        engineLabel
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (stageState.config.bridgeMode == StageBridgeMode.LOCAL_WIRELESS_ADB) {
                    var hostText by remember(stageState.config.adbHost) { mutableStateOf(stageState.config.adbHost) }
                    var portText by remember(stageState.config.adbPort) { mutableStateOf(stageState.config.adbPort.toString()) }
                    var pairingPortText by remember(stageState.discoveredPairingPort) {
                        mutableStateOf(stageState.discoveredPairingPort?.toString().orEmpty())
                    }
                    var pairingCodeText by remember { mutableStateOf("") }

                    Text(
                        text = strings.adbPairingGuide,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilledTonalButton(
                            onClick = {
                                if (stageState.isPairingServiceRunning) {
                                    CarStageBridge.stopShizukuStylePairingService(context)
                                } else {
                                    showPairingDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (stageState.isPairingServiceRunning) strings.btnStopPairing else strings.btnPairAdb,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(strings.btnOpenDevOptions, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    // Live Shizuku-identical searching & code entry panel right inside the card when Scan or Pairing is active!
                    if (stageState.isScanningMdns || stageState.isPairingServiceRunning || stageState.discoveredPairingPort != null) {
                        val foundPairingPort = stageState.discoveredPairingPort
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (foundPairingPort != null && foundPairingPort > 0) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (foundPairingPort != null && foundPairingPort > 0 && !stageState.isBinding) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = when {
                                                stageState.isBinding -> strings.statusConnecting
                                                foundPairingPort != null && foundPairingPort > 0 ->
                                                    "${strings.notifPairFoundTitle} ($foundPairingPort)"
                                                else -> strings.notifPairSearchingTitle
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = when {
                                                stageState.isBinding -> strings.notifPairWorkingText
                                                foundPairingPort != null && foundPairingPort > 0 -> strings.notifPairFoundText
                                                else -> strings.notifPairSearchingText
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    OutlinedTextField(
                                        value = pairingPortText,
                                        onValueChange = { value ->
                                            pairingPortText = value.filter { it.isDigit() }.take(5)
                                        },
                                        label = { Text(strings.adbPortLabel) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(0.9f)
                                    )
                                    OutlinedTextField(
                                        value = pairingCodeText,
                                        onValueChange = { value ->
                                            val clean = value.filter { it.isDigit() }.take(6)
                                            pairingCodeText = clean
                                            val pPort = pairingPortText.toIntOrNull() ?: stageState.discoveredPairingPort ?: -1
                                            if (clean.length == 6 && pPort > 0 && !stageState.isBinding) {
                                                CarStageBridge.pairWithCodeFromUi(context, pPort, clean)
                                            }
                                        },
                                        label = { Text(strings.adbPairingCodeLabel) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1.3f)
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = hostText,
                            onValueChange = { value ->
                                hostText = value
                                CarStageBridge.updateConfig(context) { it.copy(adbHost = value.trim()) }
                            },
                            label = { Text(strings.adbHostLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f)
                        )
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { value ->
                                portText = value.filter { it.isDigit() }.take(5)
                                portText.toIntOrNull()?.let { p ->
                                    CarStageBridge.updateConfig(context) { it.copy(adbPort = p) }
                                }
                            },
                            label = { Text(strings.adbPortLabel) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (stageState.discoveredAdbPort != null) {
                        Text(
                            text = "${strings.adbFoundPortFormat}: ${stageState.discoveredAdbPort}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (stageState.isScanningMdns || stageState.isPairingServiceRunning) {
                                    CarStageBridge.stopMdnsScan()
                                    CarStageBridge.stopShizukuStylePairingService(context)
                                } else {
                                    showPairingDialog = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (stageState.isScanningMdns || stageState.isPairingServiceRunning) {
                                    strings.btnStopScan
                                } else {
                                    strings.btnScanMdns
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (stageState.lastError.isNotBlank()) {
                    Text(
                        text = stageState.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (stageState.config.bridgeMode == StageBridgeMode.SHIZUKU && !shizukuReady) {
                        Button(
                            onClick = onRequestShizuku,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(strings.btnGrantShizuku, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else if (!stageState.isBound) {
                        Button(
                            onClick = { CarStageBridge.connect(context) },
                            enabled = !stageState.isBinding,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(strings.btnConnect, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        FilledTonalButton(
                            onClick = { CarStageBridge.startDisplay() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(strings.btnStartDisplay, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = { CarStageBridge.disconnect() }
                        ) {
                            Text(strings.btnDisconnect, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // 2. Car Screen Layout & Minimalist Controls Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.layoutCardTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (stageState.config.layoutPreset.isSplit) {
                        TextButton(onClick = { CarStageBridge.swapStageSlots(context) }) {
                            Text(strings.btnSwapPanes, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // Split ratio chips
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val presets = listOf(
                        StageLayoutPreset.SINGLE to strings.layoutSingle,
                        StageLayoutPreset.SPLIT_50_50 to "50:50",
                        StageLayoutPreset.SPLIT_60_40 to "60:40",
                        StageLayoutPreset.SPLIT_70_30 to "70:30"
                    )
                    presets.forEach { (preset, label) ->
                        FilterChip(
                            selected = stageState.config.layoutPreset == preset,
                            onClick = {
                                CarStageBridge.applySplitLayout(context, preset)
                            },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.dockEdgeTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = true,
                        onClick = {},
                        label = { Text(strings.dockEdgeLeft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.autoHideTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(3, 4, 8).forEach { sec ->
                            FilterChip(
                                selected = stageState.config.autoHideSeconds == sec,
                                onClick = {
                                    CarStageBridge.updateConfig(context) { it.copy(autoHideSeconds = sec) }
                                },
                                label = { Text("${sec}s") }
                            )
                        }
                    }
                }

                Text(
                    text = strings.minimalistHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 3. Projection Engine Options Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.optionsCardTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                StageToggleRow(
                    title = strings.optVirtualDeviceTitle,
                    description = strings.optVirtualDeviceDesc,
                    checked = stageState.config.preferVirtualDevice,
                    onCheckedChange = { enabled ->
                        CarStageBridge.updateConfig(context) { it.copy(preferVirtualDevice = enabled) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                StageToggleRow(
                    title = strings.optMoveTaskTitle,
                    description = strings.optMoveTaskDesc,
                    checked = stageState.config.moveExistingTask,
                    onCheckedChange = { enabled ->
                        CarStageBridge.updateConfig(context) { it.copy(moveExistingTask = enabled) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                StageToggleRow(
                    title = strings.optOrientationTitle,
                    description = strings.optOrientationDesc,
                    checked = stageState.config.autoOrientationSync,
                    onCheckedChange = { enabled ->
                        CarStageBridge.updateConfig(context) { it.copy(autoOrientationSync = enabled) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                StageToggleRow(
                    title = strings.optPlaybackTitle,
                    description = strings.optPlaybackDesc,
                    checked = stageState.config.keepPlaybackAlive,
                    onCheckedChange = { enabled ->
                        CarStageBridge.updateConfig(context) { it.copy(keepPlaybackAlive = enabled) }
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                StageToggleRow(
                    title = strings.optImeOnPhoneTitle,
                    description = strings.optImeOnPhoneDesc,
                    checked = stageState.config.imeOnPhone,
                    onCheckedChange = { enabled ->
                        CarStageBridge.updateConfig(context) { it.copy(imeOnPhone = enabled) }
                    }
                )
            }
        }

        // 3. Pinned Car Stage Apps Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = strings.pinnedAppsTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FilledTonalButton(onClick = { showAppPicker = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(strings.btnAddApp)
                    }
                }

                val pinnedList = stageState.config.pinnedPackages.sorted()
                if (pinnedList.isEmpty()) {
                    Text(
                        text = strings.pinnedAppsEmpty,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    pinnedList.forEach { pkg ->
                        val appLabel = installedByPkg[pkg]?.appName ?: pkg
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = appLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { CarStageBridge.launchOrMoveApp(pkg) },
                                enabled = stageState.isBound
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = strings.btnLaunch,
                                    tint = if (stageState.isBound) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    }
                                )
                            }
                            IconButton(onClick = { CarStageBridge.togglePinnedPackage(context, pkg) }) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = strings.btnRemove,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Diagnostics Log Card
        if (stageState.logs.isNotEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = strings.logsTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stageState.logs.takeLast(8).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showAppPicker) {
        var query by remember { mutableStateOf("") }
        val filtered = remember(candidates, query, stageState.config.pinnedPackages) {
            val q = query.trim().lowercase()
            candidates.filter { c ->
                !c.isFromExternalFile &&
                    !stageState.config.pinnedPackages.contains(c.packageName) &&
                    (q.isEmpty() || c.appName.lowercase().contains(q) || c.packageName.lowercase().contains(q))
            }
        }

        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text(strings.pickerTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(strings.pickerSearchHint) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filtered, key = { it.packageName }) { app ->
                            TextButton(
                                onClick = {
                                    CarStageBridge.togglePinnedPackage(context, app.packageName)
                                    showAppPicker = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text(strings.btnClose)
                }
            }
        )
    }
}

@Composable
private fun StageToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

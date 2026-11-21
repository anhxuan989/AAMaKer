package com.aamaker.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aamaker.app.BuildConfig
import com.aamaker.app.i18n.AboutStrings
import com.aamaker.app.update.GitHubUpdateChecker
import com.aamaker.app.update.UpdateCheckResult
import kotlinx.coroutines.launch

private data class OpenSourceLibrary(
    val name: String,
    val authorAndLicense: String,
    val url: String
)

private val openSourceLibraries = listOf(
    OpenSourceLibrary(
        name = "Jetpack Compose & AndroidX",
        authorAndLicense = "Google · Apache-2.0",
        url = "https://developer.android.com/jetpack/compose"
    ),
    OpenSourceLibrary(
        name = "Android for Cars App Library",
        authorAndLicense = "Google · Apache-2.0",
        url = "https://developer.android.com/training/cars"
    ),
    OpenSourceLibrary(
        name = "Shizuku",
        authorAndLicense = "RikkaApps · Apache-2.0",
        url = "https://github.com/RikkaApps/Shizuku"
    ),
    OpenSourceLibrary(
        name = "libsu",
        authorAndLicense = "topjohnwu · Apache-2.0",
        url = "https://github.com/topjohnwu/libsu"
    ),
    OpenSourceLibrary(
        name = "ARSCLib",
        authorAndLicense = "REAndroid · Apache-2.0",
        url = "https://github.com/REAndroid/ARSCLib"
    ),
    OpenSourceLibrary(
        name = "apksig",
        authorAndLicense = "Android Open Source Project · Apache-2.0",
        url = "https://android.googlesource.com/platform/tools/apksig"
    ),
    OpenSourceLibrary(
        name = "libadb-android",
        authorAndLicense = "MuntashirAkon · GPL-3.0 / Apache-2.0",
        url = "https://github.com/MuntashirAkon/libadb-android"
    ),
    OpenSourceLibrary(
        name = "Conscrypt",
        authorAndLicense = "Google · Apache-2.0",
        url = "https://github.com/google/conscrypt"
    ),
    OpenSourceLibrary(
        name = "AndroidHiddenApiBypass",
        authorAndLicense = "LSPosed · Apache-2.0",
        url = "https://github.com/LSPosed/AndroidHiddenApiBypass"
    ),
    OpenSourceLibrary(
        name = "libxposed / Xposed API",
        authorAndLicense = "LSPosed & rovo89 · Apache-2.0",
        url = "https://github.com/libxposed/api"
    ),
    OpenSourceLibrary(
        name = "Kotlin Coroutines",
        authorAndLicense = "JetBrains · Apache-2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines"
    )
)

private fun openUrl(context: Context, url: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

@Composable
fun AboutScreen(
    strings: AboutStrings,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val versionName = remember { BuildConfig.VERSION_NAME.trim() }
    val versionCode = remember { BuildConfig.VERSION_CODE }

    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }

    fun triggerUpdateCheck() {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        updateResult = null
        scope.launch {
            updateResult = GitHubUpdateChecker.checkLatestRelease(versionName)
            isCheckingUpdate = false
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // App Info Header Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AAMaKer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${strings.versionLabel}: v$versionName ($versionCode)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = strings.shortDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Developer & GitHub Project
        AboutSectionCard(title = strings.developerSectionTitle) {
            AboutLinkRow(
                icon = Icons.Default.Person,
                label = strings.developerLabel,
                value = GitHubUpdateChecker.DEVELOPER_NAME,
                openTooltip = strings.openInBrowserTooltip,
                onClick = { openUrl(context, GitHubUpdateChecker.DEVELOPER_URL) }
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                modifier = Modifier.padding(horizontal = 14.dp)
            )
            AboutLinkRow(
                icon = Icons.Default.Code,
                label = strings.githubProjectLabel,
                value = "${GitHubUpdateChecker.DEVELOPER_NAME}/${GitHubUpdateChecker.REPO_NAME}",
                openTooltip = strings.openInBrowserTooltip,
                onClick = { openUrl(context, GitHubUpdateChecker.REPO_URL) }
            )
        }

        // Check for Updates
        AboutSectionCard(title = strings.updateSectionTitle) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                when {
                    isCheckingUpdate -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = strings.checkingUpdate,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    updateResult is UpdateCheckResult.UpToDate -> {
                        val res = updateResult as UpdateCheckResult.UpToDate
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = strings.upToDateFormat.format(res.latestRelease.tagName),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        OutlinedButton(
                            onClick = { triggerUpdateCheck() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = strings.checkUpdateButton)
                        }
                    }

                    updateResult is UpdateCheckResult.UpdateAvailable -> {
                        val res = updateResult as UpdateCheckResult.UpdateAvailable
                        Text(
                            text = strings.updateAvailableFormat.format(res.latestRelease.tagName),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (res.latestRelease.releaseNotes.isNotBlank()) {
                            Text(
                                text = strings.releaseNotesLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = res.latestRelease.releaseNotes.take(400),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val apkUrl = res.latestRelease.apkDownloadUrl
                            if (apkUrl != null) {
                                Button(
                                    onClick = { openUrl(context, apkUrl) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = strings.downloadApkButton,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedButton(
                                    onClick = { openUrl(context, res.latestRelease.htmlUrl) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = strings.openReleaseButton,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { openUrl(context, res.latestRelease.htmlUrl) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(text = strings.openReleaseButton)
                                }
                            }
                        }
                    }

                    updateResult is UpdateCheckResult.NoReleases -> {
                        Text(
                            text = strings.noReleasesFound,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { triggerUpdateCheck() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = strings.retryButton)
                        }
                    }

                    updateResult is UpdateCheckResult.Error -> {
                        val res = updateResult as UpdateCheckResult.Error
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = strings.updateCheckFailedFormat.format(res.message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        OutlinedButton(
                            onClick = { triggerUpdateCheck() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = strings.retryButton)
                        }
                    }

                    else -> {
                        FilledTonalButton(
                            onClick = { triggerUpdateCheck() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = strings.checkUpdateButton)
                        }
                    }
                }
            }
        }

        // Open Source Libraries
        AboutSectionCard(title = strings.openSourceSectionTitle) {
            openSourceLibraries.forEachIndexed { index, lib ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openUrl(context, lib.url) }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = lib.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = lib.authorAndLicense,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = strings.openInBrowserTooltip,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun AboutLinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    openTooltip: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = openTooltip,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

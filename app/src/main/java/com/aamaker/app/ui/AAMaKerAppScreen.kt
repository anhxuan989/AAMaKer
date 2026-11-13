package com.aamaker.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aamaker.app.data.TargetAppCandidate
import com.aamaker.app.i18n.AppLanguage
import com.aamaker.app.i18n.AppStrings
import com.aamaker.app.i18n.AppTranslations
import com.aamaker.core.installer.model.EnvironmentStatus
import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.patcher.model.CarAppCategory
import com.aamaker.core.patcher.model.PatchStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

private object AppIconCache {
    private val cache = LruCache<String, ImageBitmap>(180)
    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }
}

private fun Drawable.toImageBitmap(targetPx: Int = 128): ImageBitmap {
    if (this is BitmapDrawable && bitmap != null) {
        return bitmap.asImageBitmap()
    }
    val w = intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(targetPx) ?: targetPx
    val h = intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(targetPx) ?: targetPx
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp.asImageBitmap()
}

@Composable
private fun AppCandidateIcon(
    candidate: TargetAppCandidate,
    size: Dp = 42.dp,
    cornerRadius: Dp = 10.dp
) {
    val context = LocalContext.current
    val cacheKey = remember(candidate.packageName, candidate.isFromExternalFile, candidate.apkFiles) {
        val fileRef = candidate.apkFiles.firstOrNull()?.absolutePath ?: ""
        "${candidate.packageName}:${candidate.isFromExternalFile}:$fileRef"
    }

    var iconBitmap by remember(cacheKey) {
        mutableStateOf(AppIconCache.get(cacheKey))
    }

    LaunchedEffect(cacheKey) {
        if (iconBitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            var drawable: Drawable? = null

            if (!candidate.isFromExternalFile) {
                drawable = try {
                    pm.getApplicationIcon(candidate.packageName)
                } catch (_: Throwable) {
                    null
                }
            }

            if (drawable == null) {
                val firstFile = candidate.apkFiles.firstOrNull()
                if (firstFile != null && firstFile.exists()) {
                    val archiveInfo = pm.getPackageArchiveInfo(
                        firstFile.absolutePath,
                        PackageManager.GET_META_DATA
                    )
                    if (archiveInfo?.applicationInfo != null) {
                        val appInfo = archiveInfo.applicationInfo!!
                        appInfo.sourceDir = firstFile.absolutePath
                        appInfo.publicSourceDir = firstFile.absolutePath
                        drawable = try {
                            appInfo.loadIcon(pm)
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        // Bundle (.apks / .xapk): extract base apk icon
                        try {
                            ZipFile(firstFile).use { zip ->
                                val entries = zip.entries()
                                var bestEntry: java.util.zip.ZipEntry? = null
                                while (entries.hasMoreElements()) {
                                    val entry = entries.nextElement()
                                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                                        val name = File(entry.name).name
                                        if (name.equals("base.apk", ignoreCase = true) || name.contains("base", ignoreCase = true)) {
                                            bestEntry = entry
                                            break
                                        }
                                        if (bestEntry == null && !name.startsWith("split_config", ignoreCase = true)) {
                                            bestEntry = entry
                                        }
                                    }
                                }
                                if (bestEntry != null) {
                                    val tempApk = File(context.cacheDir, "icon_probe_${candidate.packageName.hashCode()}.apk")
                                    try {
                                        zip.getInputStream(bestEntry).use { zIn ->
                                            FileOutputStream(tempApk).use { fOut -> zIn.copyTo(fOut) }
                                        }
                                        val bInfo = pm.getPackageArchiveInfo(tempApk.absolutePath, 0)
                                        val bAppInfo = bInfo?.applicationInfo
                                        if (bAppInfo != null) {
                                            bAppInfo.sourceDir = tempApk.absolutePath
                                            bAppInfo.publicSourceDir = tempApk.absolutePath
                                            drawable = bAppInfo.loadIcon(pm)
                                        }
                                    } finally {
                                        tempApk.delete()
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            if (drawable == null) {
                drawable = try {
                    pm.getApplicationIcon(candidate.packageName)
                } catch (_: Throwable) {
                    null
                }
            }

            drawable?.toImageBitmap()?.also { AppIconCache.put(cacheKey, it) }
        }
        iconBitmap = loaded
    }

    val currentBitmap = iconBitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = candidate.appName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
        )
    } else {
        Surface(
            shape = RoundedCornerShape(cornerRadius),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(size)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Android,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(size * 0.56f)
                )
            }
        }
    }
}

@Composable
private fun SectionGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                content = content
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AAMaKerAppScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()
    val strings = remember(state.selectedLanguage) {
        AppTranslations.forLanguage(state.selectedLanguage)
    }
    val layoutDirection = if (state.selectedLanguage == AppLanguage.AR) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

    var currentTab by remember { mutableIntStateOf(0) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var pendingDirectInstallOnPick by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importApkFromUri(uri, preferDirectInstall = pendingDirectInstallOnPick)
            currentTab = 1
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        if (showLanguageDialog) {
            LanguagePickerDialog(
                currentLanguage = state.selectedLanguage,
                strings = strings,
                onSelectLanguage = { lang ->
                    viewModel.selectLanguage(lang)
                    showLanguageDialog = false
                },
                onDismiss = { showLanguageDialog = false }
            )
        }

        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    title = {
                        Column {
                            Text(
                                text = "AAMaKer",
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = strings.appSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = { showLanguageDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = strings.languageDialogTitle,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = state.selectedLanguage.code.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        IconButton(onClick = {
                            viewModel.refreshEnvironment()
                            viewModel.loadInstalledApps()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = strings.refreshTooltip
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = {
                            Icon(
                                imageVector = if (currentTab == 0) Icons.Default.DirectionsCar else Icons.Outlined.DirectionsCar,
                                contentDescription = strings.navSelectApp
                            )
                        },
                        label = {
                            Text(
                                text = strings.navSelectApp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = {
                            Icon(
                                imageVector = if (currentTab == 1) Icons.Default.Build else Icons.Outlined.Build,
                                contentDescription = strings.navPatchInstall
                            )
                        },
                        label = {
                            Text(
                                text = strings.navPatchInstall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = { currentTab = 2 },
                        icon = {
                            Icon(
                                imageVector = if (currentTab == 2) Icons.Default.Security else Icons.Outlined.Security,
                                contentDescription = strings.navEnvironment
                            )
                        },
                        label = {
                            Text(
                                text = strings.navEnvironment,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    0 -> AppSelectorTab(
                        state = state,
                        strings = strings,
                        onSearchChange = viewModel::updateSearchQuery,
                        onFilterAAChange = viewModel::toggleFilterOnlyAAApps,
                        onSystemAppsChange = viewModel::toggleIncludeSystemApps,
                        onSelectCandidate = { candidate ->
                            viewModel.selectCandidate(candidate)
                            currentTab = 1
                        }
                    )
                    1 -> PatchAndInstallTab(
                        state = state,
                        strings = strings,
                        viewModel = viewModel,
                        onNavigateToApps = { currentTab = 0 },
                        onPickExternalFile = {
                            pendingDirectInstallOnPick = false
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        onPickExternalFileDirectInstall = {
                            pendingDirectInstallOnPick = true
                            filePickerLauncher.launch(arrayOf("*/*"))
                        }
                    )
                    2 -> EnvironmentDiagnosticTab(
                        env = state.envStatus,
                        strings = strings,
                        onRequestShizuku = viewModel::requestShizukuPermission,
                        onRefresh = viewModel::refreshEnvironment
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    currentLanguage: AppLanguage,
    strings: AppStrings,
    onSelectLanguage: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.languageDialogTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                itemsIndexed(AppLanguage.entries, key = { _, lang -> lang.code }) { index, lang ->
                    val isSelected = lang == currentLanguage
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onSelectLanguage(lang) }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = lang.nativeName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${lang.englishName} (${lang.code.uppercase()})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = lang.flagEmoji,
                            fontSize = 18.sp
                        )
                    }
                    if (index < AppLanguage.entries.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.closeButton)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppSelectorTab(
    state: MainUiState,
    strings: AppStrings,
    onSearchChange: (String) -> Unit,
    onFilterAAChange: (Boolean) -> Unit,
    onSystemAppsChange: (Boolean) -> Unit,
    onSelectCandidate: (TargetAppCandidate) -> Unit
) {
    val filteredApps = remember(state.installedApps, state.searchQuery, state.filterOnlyAAApps) {
        state.installedApps.filter { app ->
            val matchesQuery = state.searchQuery.isBlank() ||
                app.appName.contains(state.searchQuery, ignoreCase = true) ||
                app.packageName.contains(state.searchQuery, ignoreCase = true)
            val matchesAA = !state.filterOnlyAAApps || app.hasExistingCarMeta
            matchesQuery && matchesAA
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            placeholder = {
                Text(
                    text = strings.searchPlaceholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null
                )
            },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = strings.clearSearch
                        )
                    }
                }
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            FilterChip(
                selected = state.filterOnlyAAApps,
                onClick = { onFilterAAChange(!state.filterOnlyAAApps) },
                label = {
                    Text(
                        text = strings.filterExistingAAChip,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
            FilterChip(
                selected = state.includeSystemApps,
                onClick = { onSystemAppsChange(!state.includeSystemApps) },
                label = {
                    Text(
                        text = strings.filterSystemAppsChip,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (state.isLoadingApps) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filteredApps, key = { _, item -> item.packageName }) { index, candidate ->
                    val isFirst = index == 0
                    val isLast = index == filteredApps.lastIndex
                    val itemShape = when {
                        isFirst && isLast -> RoundedCornerShape(16.dp)
                        isFirst -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                        isLast -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                        else -> RoundedCornerShape(0.dp)
                    }

                    CandidateAppRow(
                        candidate = candidate,
                        strings = strings,
                        shape = itemShape,
                        showDivider = !isLast,
                        onClick = { onSelectCandidate(candidate) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateAppRow(
    candidate: TargetAppCandidate,
    strings: AppStrings,
    shape: RoundedCornerShape,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val isPlaySource = candidate.currentInstallerSource == "com.android.vending"

    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                AppCandidateIcon(
                    candidate = candidate,
                    size = 44.dp,
                    cornerRadius = 10.dp
                )

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = candidate.appName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (candidate.apkFiles.size > 1) {
                            Spacer(modifier = Modifier.width(6.dp))
                            TagPill(
                                text = strings.splitsBadgeFormat(candidate.apkFiles.size),
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "${candidate.packageName} • v${candidate.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (candidate.hasExistingCarMeta) {
                            TagPill(
                                text = strings.badgeExistingAA,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        } else {
                            TagPill(
                                text = strings.badgeNeedInjectAA,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        TagPill(
                            text = if (isPlaySource) {
                                strings.badgeSourcePlayStore
                            } else {
                                "${strings.badgeSourceOtherPrefix}${candidate.currentInstallerSource ?: strings.badgeSourceSideload}"
                            },
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (showDivider) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 74.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun TagPill(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ImportApkSection(
    strings: AppStrings,
    onPickExternalFile: () -> Unit,
    onPickExternalFileDirectInstall: () -> Unit
) {
    SectionGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.bannerExternalTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.bannerExternalSubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(
                    onClick = onPickExternalFile,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.chooseFileButton,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onPickExternalFileDirectInstall,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.chooseFileDirectInstallButton,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatchAndInstallTab(
    state: MainUiState,
    strings: AppStrings,
    viewModel: MainViewModel,
    onNavigateToApps: () -> Unit,
    onPickExternalFile: () -> Unit,
    onPickExternalFileDirectInstall: () -> Unit
) {
    val candidate = state.selectedCandidate
    val saveApkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { targetUri ->
        if (targetUri != null) {
            viewModel.exportLastPatchedApkToUri(targetUri)
        }
    }

    if (candidate == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ImportApkSection(
                strings = strings,
                onPickExternalFile = onPickExternalFile,
                onPickExternalFileDirectInstall = onPickExternalFileDirectInstall
            )

            SectionGroup {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DirectionsCar,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = strings.emptySelectionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = onNavigateToApps,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(text = strings.backToAppListButton)
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ImportApkSection(
            strings = strings,
            onPickExternalFile = onPickExternalFile,
            onPickExternalFileDirectInstall = onPickExternalFileDirectInstall
        )

        // Selected App Card with real App Icon
        SectionGroup {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                AppCandidateIcon(
                    candidate = candidate,
                    size = 48.dp,
                    cornerRadius = 12.dp
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${strings.targetLabelPrefix}${candidate.appName}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = strings.originalPackageFormat(candidate.packageName, candidate.apkFiles.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // Section 1: Patch Strategy (Grouped Seamless List)
        SectionGroup(title = strings.section1PatchStrategyTitle) {
            val strategies = PatchStrategy.entries
            strategies.forEachIndexed { index, strategy ->
                val selected = state.selectedStrategy == strategy
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.updateStrategy(strategy) }
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.strategyTitle(strategy),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = strings.strategyDescription(strategy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (index < strategies.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
            }

            if (state.selectedStrategy != PatchStrategy.DIRECT_INSTALL_NO_PATCH) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = state.customPackageName,
                        onValueChange = viewModel::updateCustomPackageName,
                        label = {
                            Text(
                                text = strings.outputPackageLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = viewModel::randomizePackageNameSuffix) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = strings.randomizeTooltip
                                )
                            }
                        },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = state.customAppName,
                        onValueChange = viewModel::updateCustomAppName,
                        label = {
                            Text(
                                text = strings.carDisplayNameLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (state.selectedStrategy != PatchStrategy.PARKED_APP_MODE) {
                        Text(
                            text = strings.automotiveDescConfigLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilterChip(
                                selected = state.injectProjection,
                                onClick = {
                                    viewModel.updateUsesFlags(!state.injectProjection, state.injectTemplate, state.injectMedia)
                                },
                                label = {
                                    Text(
                                        text = strings.projectionFullScreenLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                            FilterChip(
                                selected = state.injectTemplate,
                                onClick = {
                                    viewModel.updateUsesFlags(state.injectProjection, !state.injectTemplate, state.injectMedia)
                                },
                                label = {
                                    Text(
                                        text = strings.carTemplatesLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }

                    Text(
                        text = strings.compatOptionsHeaderLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = state.selectedStrategy == PatchStrategy.PARKED_APP_MODE || state.injectParkedGameManifest,
                            enabled = state.selectedStrategy != PatchStrategy.PARKED_APP_MODE,
                            onClick = {
                                viewModel.updateCompatFlags(parkedGameManifest = !state.injectParkedGameManifest)
                            },
                            label = {
                                Text(
                                    text = strings.parkedGameManifestChip,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        FilterChip(
                            selected = state.bypassDrivingRestriction,
                            onClick = {
                                viewModel.updateCompatFlags(bypassDriving = !state.bypassDrivingRestriction)
                            },
                            label = {
                                Text(
                                    text = strings.bypassDrivingRestrictionChip,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        FilterChip(
                            selected = state.enableGamepadSupport,
                            onClick = {
                                viewModel.updateCompatFlags(gamepadSupport = !state.enableGamepadSupport)
                            },
                            label = {
                                Text(
                                    text = strings.gamepadSupportChip,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        FilterChip(
                            selected = state.unlockScreenOrientation,
                            onClick = {
                                viewModel.updateCompatFlags(unlockOrientation = !state.unlockScreenOrientation)
                            },
                            label = {
                                Text(
                                    text = strings.unlockOrientationChip,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }

                    if (state.selectedStrategy == PatchStrategy.UNIVERSAL_INJECTION) {
                        Text(
                            text = strings.carLauncherCategoryLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                val categories = CarAppCategory.entries
                                categories.forEachIndexed { idx, cat ->
                                    val isCatSelected = state.selectedCategory == cat
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.updateCategory(cat) }
                                            .padding(horizontal = 12.dp, vertical = 10.dp)
                                    ) {
                                        RadioButton(
                                            selected = isCatSelected,
                                            onClick = null
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = strings.categoryName(cat),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (idx < categories.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 46.dp, end = 12.dp),
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable { viewModel.updateUseRootShellForTouch(!state.useRootShellForTouch) }
                                .padding(vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = strings.touchModeRootLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = strings.touchModeRootDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = state.useRootShellForTouch,
                                onCheckedChange = viewModel::updateUseRootShellForTouch
                            )
                        }
                    }
                }
            }
        }

        // Section 2: Install Mode (Grouped Seamless List)
        SectionGroup(title = strings.section2InstallModeTitle) {
            val modes = InstallMode.entries
            modes.forEachIndexed { index, mode ->
                val selected = state.selectedInstallMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.updateInstallMode(mode) }
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.installModeTitle(mode),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = strings.installModeSubtitle(mode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (index < modes.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 52.dp, end = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }

        // Status banner
        state.statusBannerEntry?.let { bannerEntry ->
            val bannerText = remember(bannerEntry, strings) {
                bannerEntry(strings)
            }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = bannerText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Action Buttons
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val isDirectNoPatch = state.selectedStrategy == PatchStrategy.DIRECT_INSTALL_NO_PATCH
            Button(
                onClick = { viewModel.startPatchAndInstall(installImmediately = true) },
                enabled = !state.isWorking,
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(vertical = 12.dp, horizontal = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = strings.processingButton,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isDirectNoPatch) strings.directInstallNoPatchButton else strings.patchAndInstallButton,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (!isDirectNoPatch) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(
                        onClick = { viewModel.startPatchAndInstall(installImmediately = false) },
                        enabled = !state.isWorking,
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(vertical = 11.dp, horizontal = 12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.patchAndSaveButton,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = viewModel::startDirectInstallOriginalApk,
                        enabled = !state.isWorking,
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(vertical = 11.dp, horizontal = 12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.directInstallNoPatchButton,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Saved storage card
        state.lastSavedStoragePath?.let { savedPath ->
            SectionGroup {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = strings.savedInStorageLabel,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = savedPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        if (state.lastPatchedApk != null && !state.isWorking) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = viewModel::saveLastPatchedApkToDownloads,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.saveToDownloadButton,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = {
                        val pkg = state.lastPatchedPackageName ?: candidate.packageName
                        saveApkLauncher.launch("${pkg}_AA_Patched.apk")
                    },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.chooseSaveFolderButton,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            FilledTonalButton(
                onClick = viewModel::installLastPatchedApk,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = strings.reinstallLastPatchedFormat(state.lastPatchedApk.name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Clean Android System Log Viewer (no fake macOS window dots)
        SectionGroup {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = strings.consoleTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    TextButton(
                        onClick = { viewModel.clearLogs() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = strings.clearLogsButton,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.padding(12.dp)) {
                        if (state.logEntries.isEmpty()) {
                            Text(
                                text = strings.readyToExecute,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            val logs = remember(state.logEntries, strings) {
                                state.logEntries.map { entry ->
                                    "[${entry.time}] ${entry.render(strings)}"
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                logs.forEach { logLine ->
                                    Text(
                                        text = logLine,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentDiagnosticTab(
    env: EnvironmentStatus?,
    strings: AppStrings,
    onRequestShizuku: () -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (env == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        SectionGroup(title = strings.envHeaderTitle) {
            DiagItemRow(
                title = strings.diagAndroidOsTitle,
                value = "Android ${env.androidReleaseName} (API ${env.androidVersion})",
                isOk = true
            )
            DiagDivider()

            DiagItemRow(
                title = strings.diagGearheadTitle,
                value = if (env.isAndroidAutoInstalled) {
                    strings.diagGearheadInstalledFormat(env.androidAutoVersion)
                } else {
                    strings.diagGearheadNotInstalled
                },
                isOk = env.isAndroidAutoInstalled
            )
            DiagDivider()

            DiagItemRow(
                title = strings.diagShizukuTitle,
                value = when {
                    env.isShizukuPermissionGranted -> strings.diagShizukuGranted
                    env.isShizukuAvailable -> strings.diagShizukuRunningNotGranted
                    else -> strings.diagShizukuNotRunning
                },
                isOk = env.isShizukuPermissionGranted,
                actionLabel = if (env.isShizukuAvailable && !env.isShizukuPermissionGranted) {
                    strings.diagGrantShizukuButton
                } else {
                    null
                },
                onAction = onRequestShizuku
            )
            DiagDivider()

            DiagItemRow(
                title = strings.diagRootTitle,
                value = if (env.isRootAvailable) strings.diagRootAvailable else strings.diagRootNotDetected,
                isOk = env.isRootAvailable
            )
            DiagDivider()

            DiagItemRow(
                title = strings.diagXposedTitle,
                value = if (env.isXposedModuleActive) strings.diagXposedActive else strings.diagXposedInactive,
                isOk = env.isXposedModuleActive
            )
            DiagDivider()

            DiagItemRow(
                title = strings.drivingHookDiagTitle,
                value = if (env.isXposedModuleActive) strings.drivingHookDiagActive else strings.drivingHookDiagManifestOnly,
                isOk = true
            )
        }

        // Developer Guide Section
        SectionGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = strings.unknownSourcesGuideTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = strings.unknownSourcesGuideSteps,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_MAIN).apply {
                                    setClassName(
                                        "com.google.android.projection.gearhead",
                                        "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
                                    )
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val fallback = context.packageManager.getLaunchIntentForPackage(
                                    "com.google.android.projection.gearhead"
                                )
                                if (fallback != null) context.startActivity(fallback)
                            }
                        },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.openAASettingsButton,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onRefresh,
                        shape = MaterialTheme.shapes.medium,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = strings.recheckButton,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 50.dp, end = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

@Composable
private fun DiagItemRow(
    title: String,
    value: String,
    isOk: Boolean,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (isOk) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onAction,
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

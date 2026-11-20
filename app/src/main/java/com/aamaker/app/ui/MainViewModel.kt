package com.aamaker.app.ui

import android.app.Application
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import com.aamaker.app.data.InstalledAppsRepository
import com.aamaker.app.data.TargetAppCandidate
import com.aamaker.app.i18n.AppLanguage
import com.aamaker.app.i18n.AppStrings
import com.aamaker.app.i18n.AppTranslations
import com.aamaker.core.installer.MultiModeInstallerManager
import com.aamaker.core.installer.model.EnvironmentStatus
import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.installer.model.InstallResult
import com.aamaker.core.patcher.AAMakerPatcherPipeline
import com.aamaker.core.patcher.model.CarAppCategory
import com.aamaker.core.patcher.model.PatchConfig
import com.aamaker.core.patcher.model.PatchResult
import com.aamaker.core.patcher.model.PatchStrategy
import com.aamaker.core.patcher.model.SplitRatioPreset
import com.aamaker.core.patcher.security.PackageNameValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ConsoleLogEntry(
    val time: String,
    val rawMessage: String
) {
    fun render(strings: AppStrings): String =
        AppTranslations.translateLogOrMessage(rawMessage, strings)
}

data class MainUiState(
    val selectedLanguage: AppLanguage = AppLanguage.EN,
    val envStatus: EnvironmentStatus? = null,
    val isLoadingApps: Boolean = false,
    val installedApps: List<TargetAppCandidate> = emptyList(),
    val searchQuery: String = "",
    val filterOnlyAAApps: Boolean = false,
    val includeSystemApps: Boolean = false,
    val selectedCandidate: TargetAppCandidate? = null,
    val selectedStrategy: PatchStrategy = PatchStrategy.PARKED_APP_MODE,
    val selectedCategory: CarAppCategory = CarAppCategory.NAVIGATION,
    val selectedInstallMode: InstallMode = InstallMode.STANDARD_NON_ROOT,
    val customPackageName: String = "",
    val customAppName: String = "",
    val injectProjection: Boolean = true,
    val injectTemplate: Boolean = true,
    val injectMedia: Boolean = false,
    val injectParkedGameManifest: Boolean = true,
    val bypassDrivingRestriction: Boolean = true,
    val enableGamepadSupport: Boolean = true,
    val unlockScreenOrientation: Boolean = true,
    val useRootShellForTouch: Boolean = false,
    val secondaryPackageName: String = "",
    val secondaryAppLabel: String = "",
    val selectedSplitPreset: SplitRatioPreset = SplitRatioPreset.EQUAL_50_50,
    val mixDualAudioFocus: Boolean = true,
    val isWorking: Boolean = false,
    val lastPatchedApk: File? = null,
    val lastPatchedPackageName: String? = null,
    val lastSavedStoragePath: String? = null,
    val logEntries: List<ConsoleLogEntry> = emptyList(),
    val statusBannerEntry: ((AppStrings) -> String)? = null,
    val showDisclaimerDialog: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appsRepository = InstalledAppsRepository(application)
    private val installerManager = MultiModeInstallerManager(application)
    private val patcherPipeline = AAMakerPatcherPipeline(application)
    private var loadAppsJob: Job? = null

    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedLanguage = AppLanguage.loadSavedLanguage(application).also { lang ->
                AppLanguage.applyAppLocale(application, lang, syncSystemLocaleManager = false)
            },
            showDisclaimerDialog = !application
                .getSharedPreferences(PREFS_APP_GENERAL, android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_DISCLAIMER_DONT_SHOW_AGAIN, false)
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun acceptDisclaimer(dontShowAgain: Boolean) {
        if (dontShowAgain) {
            getApplication<Application>()
                .getSharedPreferences(PREFS_APP_GENERAL, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_DISCLAIMER_DONT_SHOW_AGAIN, true)
                .apply()
        }
        _uiState.update { it.copy(showDisclaimerDialog = false) }
    }

    fun selectLanguage(language: AppLanguage) {
        val app = getApplication<Application>()
        AppLanguage.saveLanguage(app, language)
        val changed = _uiState.value.selectedLanguage != language
        _uiState.update { it.copy(selectedLanguage = language) }
        if (changed) {
            reloadInstalledAppsSilently(language)
        }
    }

    fun syncLanguageFromSystemIfChanged() {
        val app = getApplication<Application>()
        val latest = AppLanguage.loadSavedLanguage(app)
        if (_uiState.value.selectedLanguage != latest) {
            AppLanguage.applyAppLocale(app, latest, syncSystemLocaleManager = false)
            _uiState.update { it.copy(selectedLanguage = latest) }
            reloadInstalledAppsSilently(latest)
        }
    }

    private fun reloadInstalledAppsSilently(language: AppLanguage) {
        viewModelScope.launch {
            val apps = appsRepository.loadInstalledApps(
                includeSystemApps = _uiState.value.includeSystemApps,
                languageCode = language.code,
                forceRefresh = false
            )
            _uiState.update { it.copy(installedApps = apps) }
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            appendLog("[Shizuku] Đã được cấp quyền Shizuku thành công!")
        } else {
            appendLog("[Shizuku] Người dùng từ chối cấp quyền Shizuku.")
        }
        refreshEnvironment()
    }

    init {
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
        refreshEnvironment()
        loadInstalledApps()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {
        }
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            val env = installerManager.detectEnvironment()
            _uiState.update { state ->
                state.copy(
                    envStatus = env,
                    selectedInstallMode = env.recommendedInstallMode
                )
            }
            if (env.isShizukuPermissionGranted || env.isRootAvailable) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        com.aamaker.app.input.PrivilegedServerCoordinator.getOrStartServer(getApplication())
                    }
                }
            }
        }
    }

    fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                appendLog("[Shizuku] Dịch vụ Shizuku chưa chạy trên thiết bị.")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(1001)
            } else {
                appendLog("[Shizuku] Đã có sẵn quyền Shizuku.")
                refreshEnvironment()
            }
        } catch (e: Throwable) {
            appendLog("[Shizuku] Lỗi yêu cầu quyền: ${e.message}")
        }
    }

    fun loadInstalledApps(forceRefresh: Boolean = false) {
        loadAppsJob?.cancel()
        loadAppsJob = viewModelScope.launch {
            val stateSnap = _uiState.value
            val includeSystem = stateSnap.includeSystemApps
            val langCode = stateSnap.selectedLanguage.code

            if (!forceRefresh) {
                val cached = appsRepository.loadCachedInstalledApps(
                    includeSystemApps = includeSystem,
                    languageCode = langCode
                )
                if (!cached.isNullOrEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoadingApps = false,
                            installedApps = cached
                        )
                    }
                    val fresh = appsRepository.loadInstalledApps(
                        includeSystemApps = includeSystem,
                        languageCode = langCode,
                        forceRefresh = true
                    )
                    if (_uiState.value.includeSystemApps == includeSystem) {
                        _uiState.update { it.copy(installedApps = fresh) }
                    }
                    return@launch
                }
            }

            _uiState.update { it.copy(isLoadingApps = true) }
            val apps = appsRepository.loadInstalledApps(
                includeSystemApps = includeSystem,
                languageCode = langCode,
                forceRefresh = true
            )
            if (_uiState.value.includeSystemApps == includeSystem) {
                _uiState.update {
                    it.copy(
                        isLoadingApps = false,
                        installedApps = apps
                    )
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleFilterOnlyAAApps(onlyAA: Boolean) {
        _uiState.update { it.copy(filterOnlyAAApps = onlyAA) }
    }

    fun toggleIncludeSystemApps(include: Boolean) {
        _uiState.update { it.copy(includeSystemApps = include) }
        loadInstalledApps()
    }

    fun selectCandidate(candidate: TargetAppCandidate?, preferDirectInstall: Boolean = false) {
        if (candidate == null) {
            _uiState.update { it.copy(selectedCandidate = null) }
            return
        }
        val defaultStrategy = when {
            preferDirectInstall -> PatchStrategy.DIRECT_INSTALL_NO_PATCH
            candidate.hasExistingCarMeta -> PatchStrategy.SPOOF_AND_ACTIVATE
            else -> PatchStrategy.PARKED_APP_MODE
        }
        _uiState.update {
            it.copy(
                selectedCandidate = candidate,
                selectedStrategy = defaultStrategy,
                customPackageName = generateRandomizedPackageName(candidate.packageName),
                customAppName = candidate.appName,
                lastPatchedApk = null,
                lastSavedStoragePath = null,
                statusBannerEntry = null
            )
        }
    }

    fun importApkFromUri(uri: Uri, preferDirectInstall: Boolean = false) {
        viewModelScope.launch {
            appendLog("Đang nhập file APK/APKS từ bộ nhớ...")
            val candidate = appsRepository.importExternalApkUri(uri)
            if (candidate != null) {
                appendLog("Đã nạp: ${candidate.appName} (${candidate.packageName})")
                selectCandidate(candidate, preferDirectInstall = preferDirectInstall)
            } else {
                appendLog("Không thể đọc được file APK đã chọn.")
            }
        }
    }

    fun updateStrategy(strategy: PatchStrategy) {
        _uiState.update { state ->
            val nextCustomPkg = if (
                strategy != PatchStrategy.DIRECT_INSTALL_NO_PATCH &&
                state.customPackageName.isBlank() &&
                state.selectedCandidate != null
            ) {
                generateRandomizedPackageName(state.selectedCandidate.packageName)
            } else {
                state.customPackageName
            }
            state.copy(
                selectedStrategy = strategy,
                customPackageName = nextCustomPkg
            )
        }
    }

    fun updateCategory(category: CarAppCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun updateInstallMode(mode: InstallMode) {
        _uiState.update { it.copy(selectedInstallMode = mode) }
    }

    fun updateCustomPackageName(pkg: String) {
        _uiState.update { it.copy(customPackageName = pkg.trim()) }
    }

    fun randomizePackageNameSuffix() {
        val current = _uiState.value.selectedCandidate?.packageName ?: return
        val nextPkg = generateRandomizedPackageName(
            basePackageName = current,
            avoidPackageName = _uiState.value.customPackageName
        )
        _uiState.update { it.copy(customPackageName = nextPkg) }
    }

    private fun generateRandomizedPackageName(
        basePackageName: String,
        avoidPackageName: String? = null
    ): String {
        val stripped = basePackageName.replace(TRAILING_AA_SUFFIX_REGEX, "")
        val cleanBase = if (PackageNameValidator.isValidPackageName(stripped)) {
            stripped
        } else {
            basePackageName
        }
        if (cleanBase.length + 6 > 255) {
            return basePackageName
        }
        var candidatePkg: String
        var attempts = 0
        do {
            val randomSuffix = (100..999).random()
            candidatePkg = "${cleanBase}.aa$randomSuffix"
            attempts++
        } while (candidatePkg == avoidPackageName && attempts < 10)
        return if (PackageNameValidator.isValidPackageName(candidatePkg)) {
            candidatePkg
        } else {
            basePackageName
        }
    }

    fun updateCustomAppName(name: String) {
        _uiState.update { it.copy(customAppName = name) }
    }

    fun updateUsesFlags(projection: Boolean, template: Boolean, media: Boolean) {
        _uiState.update {
            it.copy(
                injectProjection = projection,
                injectTemplate = template,
                injectMedia = media
            )
        }
    }

    fun updateCompatFlags(
        parkedGameManifest: Boolean = _uiState.value.injectParkedGameManifest,
        bypassDriving: Boolean = _uiState.value.bypassDrivingRestriction,
        gamepadSupport: Boolean = _uiState.value.enableGamepadSupport,
        unlockOrientation: Boolean = _uiState.value.unlockScreenOrientation
    ) {
        _uiState.update {
            it.copy(
                injectParkedGameManifest = parkedGameManifest,
                bypassDrivingRestriction = bypassDriving,
                enableGamepadSupport = gamepadSupport,
                unlockScreenOrientation = unlockOrientation
            )
        }
    }

    fun updateUseRootShellForTouch(enabled: Boolean) {
        _uiState.update { it.copy(useRootShellForTouch = enabled) }
    }

    fun selectSecondaryApp(candidate: TargetAppCandidate?) {
        _uiState.update { state ->
            val secPkg = candidate?.packageName.orEmpty()
            val secLabel = candidate?.appName.orEmpty()
            val suggestedName = if (
                state.selectedStrategy == PatchStrategy.DUAL_WINDOW_MODE &&
                state.selectedCandidate != null &&
                secLabel.isNotBlank() &&
                (state.customAppName.isBlank() || state.customAppName == state.selectedCandidate.appName)
            ) {
                "${state.selectedCandidate.appName} + $secLabel"
            } else {
                state.customAppName
            }
            state.copy(
                secondaryPackageName = secPkg,
                secondaryAppLabel = secLabel,
                customAppName = suggestedName
            )
        }
    }

    fun updateSplitPreset(preset: SplitRatioPreset) {
        _uiState.update { it.copy(selectedSplitPreset = preset) }
    }

    fun updateMixDualAudioFocus(enabled: Boolean) {
        _uiState.update { it.copy(mixDualAudioFocus = enabled) }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logEntries = emptyList(), statusBannerEntry = null) }
    }

    fun startPatchAndInstall(installImmediately: Boolean = true) {
        val state = _uiState.value
        val candidate = state.selectedCandidate ?: return
        if (state.isWorking) return

        if (!PackageNameValidator.isValidPackageName(candidate.packageName)) {
            val invalidMsg = "Tên gói APK gốc không hợp lệ: '${candidate.packageName}'"
            appendLog("[Cảnh báo] $invalidMsg")
            _uiState.update {
                it.copy(
                    statusBannerEntry = { s ->
                        "⚠️ ${AppTranslations.translateLogOrMessage(invalidMsg, s)}"
                    }
                )
            }
            return
        }

        if (state.selectedStrategy == PatchStrategy.DIRECT_INSTALL_NO_PATCH) {
            startDirectInstallOriginalApk()
            return
        }

        val targetPkgToValidate = state.customPackageName.takeIf { it.isNotBlank() } ?: candidate.packageName
        if (!PackageNameValidator.isValidPackageName(targetPkgToValidate)) {
            val invalidMsg = "Tên gói (Package Name) không hợp lệ: '$targetPkgToValidate'. Định dạng đúng ví dụ: com.example.app"
            appendLog("[Cảnh báo] $invalidMsg")
            _uiState.update {
                it.copy(
                    statusBannerEntry = { s ->
                        s.statusPatchFailedFormat(AppTranslations.translateLogOrMessage(invalidMsg, s))
                    }
                )
            }
            return
        }

        val app = getApplication<Application>()
        val explicitLangCode = if (AppLanguage.hasExplicitSavedLanguage(app)) {
            state.selectedLanguage.code
        } else {
            null
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isWorking = true,
                    statusBannerEntry = { s -> s.statusPatchingApk }
                )
            }

            val config = PatchConfig(
                sourceApkFiles = candidate.apkFiles,
                originalPackageName = candidate.packageName,
                appName = candidate.appName,
                strategy = state.selectedStrategy,
                customPackageName = state.customPackageName.takeIf { it.isNotBlank() && it != candidate.packageName },
                customAppName = state.customAppName.takeIf { it.isNotBlank() && it != candidate.appName },
                category = state.selectedCategory,
                injectProjectionUses = state.injectProjection,
                injectTemplateUses = state.injectTemplate,
                injectMediaUses = state.injectMedia,
                injectParkedGameManifest = state.injectParkedGameManifest,
                bypassDrivingRestriction = state.bypassDrivingRestriction,
                enableGamepadSupport = state.enableGamepadSupport,
                unlockScreenOrientation = state.unlockScreenOrientation,
                useRootShellForTouch = state.useRootShellForTouch,
                preferredLanguageCode = explicitLangCode,
                secondaryPackageName = state.secondaryPackageName.takeIf { it.isNotBlank() },
                defaultSplitRatio = state.selectedSplitPreset.ratioValue,
                mixDualAudioFocus = state.mixDualAudioFocus,
                autoStartDualWindow = state.selectedStrategy == PatchStrategy.DUAL_WINDOW_MODE
            )

            when (val patchRes = patcherPipeline.executePatch(config) { appendLog(it) }) {
                is PatchResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isWorking = false,
                            statusBannerEntry = { s ->
                                s.statusPatchFailedFormat(
                                    AppTranslations.translateLogOrMessage(patchRes.errorMessage, s)
                                )
                            }
                        )
                    }
                }
                is PatchResult.Success -> {
                    val savedPath = exportFileToDownloads(patchRes.outputApkFile, patchRes.finalPackageName)
                    _uiState.update {
                        it.copy(
                            lastPatchedApk = patchRes.outputApkFile,
                            lastPatchedPackageName = patchRes.finalPackageName,
                            lastSavedStoragePath = savedPath,
                            statusBannerEntry = { s ->
                                if (savedPath != null) {
                                    s.statusPatchedAndSavedFormat(savedPath, patchRes.elapsedMs)
                                } else {
                                    s.statusPatchedSuccessFormat(patchRes.elapsedMs)
                                }
                            }
                        )
                    }

                    if (installImmediately) {
                        installApkFile(patchRes.outputApkFile, patchRes.finalPackageName)
                    } else {
                        _uiState.update { it.copy(isWorking = false) }
                    }
                }
            }
        }
    }

    fun saveLastPatchedApkToDownloads() {
        val apk = _uiState.value.lastPatchedApk ?: return
        val pkg = _uiState.value.lastPatchedPackageName ?: "patched_app"
        if (_uiState.value.isWorking) return

        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val savedPath = exportFileToDownloads(apk, pkg)
            _uiState.update {
                it.copy(
                    isWorking = false,
                    lastSavedStoragePath = savedPath ?: it.lastSavedStoragePath,
                    statusBannerEntry = { s ->
                        if (savedPath != null) {
                            s.statusSavedToStorageFormat(savedPath)
                        } else {
                            s.statusSaveDownloadFailed
                        }
                    }
                )
            }
        }
    }

    fun exportLastPatchedApkToUri(targetUri: Uri) {
        val apk = _uiState.value.lastPatchedApk ?: return
        if (_uiState.value.isWorking) return

        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true) }
            val ok = withContext(Dispatchers.IO) {
                try {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(targetUri)?.use { outStream ->
                        FileInputStream(apk).use { inStream ->
                            inStream.copyTo(outStream, bufferSize = 64 * 1024)
                            outStream.flush()
                        }
                    } ?: return@withContext false
                    true
                } catch (e: Exception) {
                    appendLog("[Export] Error saving file: ${e.message}")
                    false
                }
            }
            if (ok) {
                appendLog("[Export] Exported patched APK successfully (${apk.length() / 1024} KB).")
            }
            _uiState.update {
                it.copy(
                    isWorking = false,
                    statusBannerEntry = { s ->
                        if (ok) {
                            s.statusExportedSuccess
                        } else {
                            s.statusExportFailed
                        }
                    }
                )
            }
        }
    }

    private suspend fun exportFileToDownloads(sourceApk: File, packageName: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val safePkg = packageName.replace(SAFE_PKG_NAME_REGEX, "_")
                val fileName = "${safePkg}_AA_Patched.apk"
                val app = getApplication<Application>()

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = app.contentResolver
                    try {
                        resolver.delete(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                            arrayOf(fileName, "%Download/AAMaKer%")
                        )
                    } catch (_: Throwable) {
                    }

                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AAMaKer")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }

                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { outStream ->
                            FileInputStream(sourceApk).use { inStream ->
                                inStream.copyTo(outStream, bufferSize = 64 * 1024)
                                outStream.flush()
                            }
                        }
                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)

                        val displayPath = "/sdcard/Download/AAMaKer/$fileName"
                        appendLog("[Storage] Đã lưu APK đã patch vào bộ nhớ máy: $displayPath (${sourceApk.length() / 1024} KB)")
                        return@withContext displayPath
                    }
                }

                val downloadDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AAMaKer"
                ).apply { mkdirs() }
                val destFile = File(downloadDir, fileName)
                FileInputStream(sourceApk).use { inStream ->
                    FileOutputStream(destFile).use { outStream ->
                        inStream.copyTo(outStream, bufferSize = 64 * 1024)
                        outStream.flush()
                    }
                }
                appendLog("[Storage] Đã lưu APK đã patch vào: ${destFile.absolutePath}")
                destFile.absolutePath
            } catch (e: Exception) {
                appendLog("[Storage] Cảnh báo khi lưu vào Download/AAMaKer: ${e.message}")
                null
            }
        }

    fun startDirectInstallOriginalApk() {
        val state = _uiState.value
        val candidate = state.selectedCandidate ?: return
        if (state.isWorking) return

        if (!PackageNameValidator.isValidPackageName(candidate.packageName)) {
            val invalidMsg = "Tên gói APK gốc không hợp lệ: '${candidate.packageName}'"
            appendLog("[Cảnh báo] $invalidMsg")
            _uiState.update {
                it.copy(
                    statusBannerEntry = { s ->
                        "⚠️ ${AppTranslations.translateLogOrMessage(invalidMsg, s)}"
                    }
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isWorking = true,
                    statusBannerEntry = { s -> s.statusDirectInstallingApk }
                )
            }
            val mode = state.selectedInstallMode
            appendLog("--- Bắt đầu cài đặt lên hệ thống với chế độ: ${mode.title} ---")
            when (val res = installerManager.installOriginalApks(
                sourceFiles = candidate.apkFiles,
                expectedPackageName = candidate.packageName,
                mode = mode
            ) { appendLog(it) }) {
                is InstallResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isWorking = false,
                            statusBannerEntry = { s ->
                                "✅ ${AppTranslations.translateLogOrMessage(res.message, s)}"
                            }
                        )
                    }
                    loadInstalledApps(forceRefresh = true)
                }
                is InstallResult.PendingUserAction -> {
                    _uiState.update {
                        it.copy(
                            isWorking = false,
                            statusBannerEntry = { s ->
                                "⏳ ${AppTranslations.translateLogOrMessage(res.message, s)}"
                            }
                        )
                    }
                }
                is InstallResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isWorking = false,
                            statusBannerEntry = { s ->
                                "⚠️ ${AppTranslations.translateLogOrMessage(res.reason, s)}"
                            }
                        )
                    }
                }
            }
        }
    }

    fun installLastPatchedApk() {
        val apk = _uiState.value.lastPatchedApk ?: return
        val pkg = _uiState.value.lastPatchedPackageName ?: return
        if (_uiState.value.isWorking) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isWorking = true,
                    statusBannerEntry = { s -> s.statusInstallingPatchedApk }
                )
            }
            installApkFile(apk, pkg)
        }
    }

    private suspend fun installApkFile(apkFile: File, expectedPkg: String) {
        val mode = _uiState.value.selectedInstallMode
        appendLog("--- Bắt đầu cài đặt lên hệ thống với chế độ: ${mode.title} ---")

        when (val res = installerManager.installPatchedApk(apkFile, expectedPkg, mode) { appendLog(it) }) {
            is InstallResult.Success -> {
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        statusBannerEntry = { s ->
                            "✅ ${AppTranslations.translateLogOrMessage(res.message, s)}"
                        }
                    )
                }
                loadInstalledApps(forceRefresh = true)
            }
            is InstallResult.PendingUserAction -> {
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        statusBannerEntry = { s ->
                            "⏳ ${AppTranslations.translateLogOrMessage(res.message, s)}"
                        }
                    )
                }
            }
            is InstallResult.Error -> {
                _uiState.update {
                    it.copy(
                        isWorking = false,
                        statusBannerEntry = { s ->
                            "⚠️ ${AppTranslations.translateLogOrMessage(res.reason, s)}"
                        }
                    )
                }
            }
        }
    }

    private fun appendLog(line: String) {
        val time = timeFormatter.get()?.format(Date()) ?: ""
        _uiState.update { state ->
            val nextList = if (state.logEntries.size >= 500) {
                state.logEntries.subList(state.logEntries.size - 499, state.logEntries.size) + ConsoleLogEntry(time = time, rawMessage = line)
            } else {
                state.logEntries + ConsoleLogEntry(time = time, rawMessage = line)
            }
            state.copy(logEntries = nextList)
        }
    }

    companion object {
        private const val PREFS_APP_GENERAL = "aamaker_app_prefs"
        private const val KEY_DISCLAIMER_DONT_SHOW_AGAIN = "disclaimer_dont_show_again"
        private val SAFE_PKG_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")
        private val TRAILING_AA_SUFFIX_REGEX = Regex("\\.aa\\d{3}$")
        private val timeFormatter = ThreadLocal.withInitial {
            SimpleDateFormat("HH:mm:ss", Locale.US)
        }
    }
}

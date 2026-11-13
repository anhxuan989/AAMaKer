package com.aamaker.core.installer

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.aamaker.core.installer.backend.RootInstaller
import com.aamaker.core.installer.backend.ShizukuInstaller
import com.aamaker.core.installer.backend.StandardSessionInstaller
import com.aamaker.core.installer.model.EnvironmentStatus
import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.installer.model.InstallResult
import com.aamaker.core.installer.util.ApkBundleExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MultiModeInstallerManager(private val context: Context) {

    private val standardInstaller = StandardSessionInstaller(context)
    val shizukuInstaller = ShizukuInstaller()
    val rootInstaller = RootInstaller()

    fun isXposedModuleActive(): Boolean = false

    suspend fun detectEnvironment(): EnvironmentStatus = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        var aaInstalled = false
        var aaVersion: String? = null
        try {
            val info = pm.getPackageInfo(GEARHEAD_PACKAGE, 0)
            aaInstalled = true
            aaVersion = info.versionName
        } catch (_: PackageManager.NameNotFoundException) {
            aaInstalled = false
        }

        val shizukuAvail = shizukuInstaller.isAvailable()
        val shizukuGranted = shizukuInstaller.hasPermission()
        val rootAvail = rootInstaller.isRootAvailable()
        val xposedActive = isXposedModuleActive()

        val recommended = when {
            rootAvail -> InstallMode.ROOT
            shizukuAvail && shizukuGranted -> InstallMode.SHIZUKU
            xposedActive -> InstallMode.LSPOSED
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && shizukuAvail -> InstallMode.SHIZUKU
            else -> InstallMode.STANDARD_NON_ROOT
        }

        EnvironmentStatus(
            androidVersion = Build.VERSION.SDK_INT,
            androidReleaseName = Build.VERSION.RELEASE,
            isAndroidAutoInstalled = aaInstalled,
            androidAutoVersion = aaVersion,
            isShizukuAvailable = shizukuAvail,
            isShizukuPermissionGranted = shizukuGranted,
            isRootAvailable = rootAvail,
            isXposedModuleActive = xposedActive,
            recommendedInstallMode = recommended
        )
    }

    suspend fun installPatchedApk(
        apkFile: File,
        expectedPackageName: String,
        mode: InstallMode,
        onLog: (String) -> Unit
    ): InstallResult {
        return when (mode) {
            InstallMode.ROOT -> rootInstaller.installApk(apkFile, expectedPackageName, true, onLog)
            InstallMode.SHIZUKU -> shizukuInstaller.installApk(apkFile, expectedPackageName, onLog)
            InstallMode.LSPOSED, InstallMode.STANDARD_NON_ROOT -> {
                standardInstaller.installApk(apkFile, expectedPackageName, onLog)
            }
        }
    }

    suspend fun installOriginalApks(
        sourceFiles: List<File>,
        expectedPackageName: String,
        mode: InstallMode,
        onLog: (String) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        val payload = try {
            ApkBundleExtractor.prepareApksForDirectInstall(
                inputFiles = sourceFiles,
                cacheRoot = context.cacheDir,
                onLog = onLog
            )
        } catch (e: Exception) {
            onLog("[Direct Install] Lỗi chuẩn bị APK gốc: ${e.message}")
            return@withContext InstallResult.Error("Lỗi đọc file APK gốc: ${e.message}", e)
        }

        try {
            onLog("[Direct Install] Cài đặt nguyên bản ${payload.apkFiles.size} file APK (giữ nguyên chữ ký số gốc) cho gói $expectedPackageName...")
            when (mode) {
                InstallMode.ROOT -> rootInstaller.installApks(payload.apkFiles, expectedPackageName, true, onLog)
                InstallMode.SHIZUKU -> shizukuInstaller.installApks(payload.apkFiles, expectedPackageName, onLog)
                InstallMode.LSPOSED, InstallMode.STANDARD_NON_ROOT -> {
                    standardInstaller.installApks(payload.apkFiles, expectedPackageName, onLog)
                }
            }
        } finally {
            payload.cleanup()
        }
    }

    fun getCurrentInstallerSource(packageName: String): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = context.packageManager.getInstallSourceInfo(packageName)
                info.installingPackageName ?: info.initiatingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(packageName)
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val GEARHEAD_PACKAGE = "com.google.android.projection.gearhead"
    }
}

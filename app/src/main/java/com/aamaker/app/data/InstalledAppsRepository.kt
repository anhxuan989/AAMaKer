package com.aamaker.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

data class TargetAppCandidate(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val apkFiles: List<File>,
    val isSystemApp: Boolean,
    val hasExistingCarMeta: Boolean,
    val currentInstallerSource: String?,
    val isFromExternalFile: Boolean = false
)

class InstalledAppsRepository(private val context: Context) {

    suspend fun loadInstalledApps(includeSystemApps: Boolean = false): List<TargetAppCandidate> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val flags = PackageManager.GET_META_DATA
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }

            packages.mapNotNull { pkgInfo ->
                val appInfo = pkgInfo.applicationInfo ?: return@mapNotNull null
                if (pkgInfo.packageName == context.packageName) return@mapNotNull null

                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

                if (isSystem && !includeSystemApps) return@mapNotNull null

                val baseApk = File(appInfo.sourceDir)
                if (!baseApk.exists()) return@mapNotNull null

                val splits = appInfo.splitSourceDirs?.map { File(it) }?.filter { it.exists() }.orEmpty()
                val allApkFiles = listOf(baseApk) + splits

                val metaBundle = appInfo.metaData
                val hasCarMeta = metaBundle != null && (
                    metaBundle.containsKey("com.google.android.gms.car.application") ||
                        metaBundle.containsKey("com.android.automotive") ||
                        metaBundle.containsKey("androidx.car.app.minCarApiLevel")
                    )

                val installerSource = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val src = pm.getInstallSourceInfo(pkgInfo.packageName)
                        src.installingPackageName ?: src.initiatingPackageName
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstallerPackageName(pkgInfo.packageName)
                    }
                } catch (_: Exception) {
                    null
                }

                val label = try {
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    pkgInfo.packageName
                }

                TargetAppCandidate(
                    appName = label,
                    packageName = pkgInfo.packageName,
                    versionName = pkgInfo.versionName ?: "1.0",
                    apkFiles = allApkFiles,
                    isSystemApp = isSystem,
                    hasExistingCarMeta = hasCarMeta,
                    currentInstallerSource = installerSource,
                    isFromExternalFile = false
                )
            }.sortedWith(
                compareByDescending<TargetAppCandidate> { it.hasExistingCarMeta }
                    .thenBy { it.appName.lowercase() }
            )
        }

    suspend fun importExternalApkUri(uri: Uri): TargetAppCandidate? = withContext(Dispatchers.IO) {
        try {
            val importDir = File(context.cacheDir, "imported_apks").apply { mkdirs() }
            val fileName = queryFileName(uri) ?: "external_${System.currentTimeMillis()}.apk"
            val targetFile = File(importDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            val pm = context.packageManager
            val archiveInfo = pm.getPackageArchiveInfo(
                targetFile.absolutePath,
                PackageManager.GET_META_DATA
            )

            if (archiveInfo != null) {
                val appInfo = archiveInfo.applicationInfo
                appInfo?.sourceDir = targetFile.absolutePath
                appInfo?.publicSourceDir = targetFile.absolutePath
                val label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: archiveInfo.packageName
                val hasCarMeta = appInfo?.metaData?.let {
                    it.containsKey("com.google.android.gms.car.application") ||
                        it.containsKey("androidx.car.app.minCarApiLevel")
                } ?: false

                TargetAppCandidate(
                    appName = label,
                    packageName = archiveInfo.packageName,
                    versionName = archiveInfo.versionName ?: "1.0",
                    apkFiles = listOf(targetFile),
                    isSystemApp = false,
                    hasExistingCarMeta = hasCarMeta,
                    currentInstallerSource = null,
                    isFromExternalFile = true
                )
            } else {
                var bundlePkg = "com.aamaker.bundle.${fileName.substringBeforeLast('.').replace(SAFE_BUNDLE_NAME_REGEX, "").lowercase()}"
                var bundleLabel = fileName.substringBeforeLast('.')
                var bundleVersion = "Bundle"
                var bundleHasCarMeta = false

                try {
                    ZipFile(targetFile).use { zip ->
                        var bestEntry: java.util.zip.ZipEntry? = null
                        var bestScore = 0

                        val enumEntries = zip.entries()
                        while (enumEntries.hasMoreElements()) {
                            val entry = enumEntries.nextElement()
                            if (entry.isDirectory || !entry.name.endsWith(".apk", ignoreCase = true)) continue

                            val entryBaseName = File(entry.name).name
                            val score = when {
                                entryBaseName.equals("base.apk", ignoreCase = true) || entryBaseName.contains("base", ignoreCase = true) -> 3
                                !entryBaseName.startsWith("split_config", ignoreCase = true) && !entryBaseName.startsWith("config.", ignoreCase = true) -> 2
                                else -> 1
                            }

                            if (score > bestScore) {
                                bestScore = score
                                bestEntry = entry
                                if (score == 3) break
                            }
                        }

                        if (bestEntry != null) {
                            val tempBase = File(importDir, "temp_base_${System.currentTimeMillis()}.apk")
                            try {
                                zip.getInputStream(bestEntry).use { zIn ->
                                    FileOutputStream(tempBase).use { fOut ->
                                        zIn.copyTo(fOut)
                                    }
                                }
                                val baseInfo = pm.getPackageArchiveInfo(
                                    tempBase.absolutePath,
                                    PackageManager.GET_META_DATA
                                )
                                if (baseInfo != null) {
                                    bundlePkg = baseInfo.packageName
                                    bundleVersion = "${baseInfo.versionName ?: "1.0"} (Bundle)"
                                    val bAppInfo = baseInfo.applicationInfo
                                    bAppInfo?.sourceDir = tempBase.absolutePath
                                    bAppInfo?.publicSourceDir = tempBase.absolutePath
                                    bundleLabel = bAppInfo?.let { pm.getApplicationLabel(it).toString() } ?: bundlePkg
                                    bundleHasCarMeta = bAppInfo?.metaData?.let {
                                        it.containsKey("com.google.android.gms.car.application") ||
                                            it.containsKey("androidx.car.app.minCarApiLevel")
                                    } ?: false
                                }
                            } finally {
                                tempBase.delete()
                            }
                        }
                    }
                } catch (_: Throwable) {
                }

                TargetAppCandidate(
                    appName = bundleLabel,
                    packageName = bundlePkg,
                    versionName = bundleVersion,
                    apkFiles = listOf(targetFile),
                    isSystemApp = false,
                    hasExistingCarMeta = bundleHasCarMeta,
                    currentInstallerSource = null,
                    isFromExternalFile = true
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIdx >= 0 && cursor.moveToFirst()) cursor.getString(nameIdx) else null
        }
    }

    companion object {
        private val SAFE_BUNDLE_NAME_REGEX = Regex("[^a-zA-Z0-9_]")
    }
}

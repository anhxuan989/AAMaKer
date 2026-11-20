package com.aamaker.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.aamaker.core.patcher.security.PackageNameValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
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

    @Volatile
    private var cachedUserApps: List<TargetAppCandidate>? = null

    @Volatile
    private var cachedAllApps: List<TargetAppCandidate>? = null

    suspend fun loadCachedInstalledApps(
        includeSystemApps: Boolean = false,
        languageCode: String? = null
    ): List<TargetAppCandidate>? = withContext(Dispatchers.IO) {
        val fromMemory = getMemoryCache(includeSystemApps)
        if (fromMemory != null) {
            return@withContext sortCandidates(fromMemory, languageCode)
        }

        val fromDisk = readDiskCache(includeSystemApps)
            ?: if (!includeSystemApps) {
                readDiskCache(includeSystemApps = true)?.filterNot { it.isSystemApp }
            } else {
                null
            }

        if (fromDisk != null) {
            if (includeSystemApps) {
                cachedAllApps = fromDisk
                cachedUserApps = fromDisk.filterNot { it.isSystemApp }
            } else {
                cachedUserApps = fromDisk
            }
            return@withContext sortCandidates(fromDisk, languageCode)
        }

        null
    }

    suspend fun loadInstalledApps(
        includeSystemApps: Boolean = false,
        languageCode: String? = null,
        forceRefresh: Boolean = false
    ): List<TargetAppCandidate> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                getMemoryCache(includeSystemApps)?.let { cached ->
                    return@withContext sortCandidates(cached, languageCode)
                }
            }

            val pm = context.packageManager
            val flags = PackageManager.GET_META_DATA
            val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(flags)
            }

            val scanned = packages.mapNotNull { pkgInfo ->
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
            }

            val sorted = sortCandidates(scanned, languageCode)
            if (includeSystemApps) {
                cachedAllApps = sorted
                val userOnly = sorted.filterNot { it.isSystemApp }
                cachedUserApps = userOnly
                runCatching { writeDiskCache(includeSystemApps = true, apps = sorted) }
                runCatching { writeDiskCache(includeSystemApps = false, apps = userOnly) }
            } else {
                cachedUserApps = sorted
                runCatching { writeDiskCache(includeSystemApps = false, apps = sorted) }
            }

            sorted
        }

    fun invalidateCache() {
        cachedUserApps = null
        cachedAllApps = null
        runCatching { cacheFile(includeSystemApps = false).delete() }
        runCatching { cacheFile(includeSystemApps = true).delete() }
    }

    private fun getMemoryCache(includeSystemApps: Boolean): List<TargetAppCandidate>? {
        return if (includeSystemApps) {
            cachedAllApps
        } else {
            cachedUserApps ?: cachedAllApps?.filterNot { it.isSystemApp }
        }
    }

    private fun sortCandidates(
        candidates: List<TargetAppCandidate>,
        languageCode: String?
    ): List<TargetAppCandidate> {
        val sortLocale = languageCode?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()
        return candidates.sortedWith(
            compareByDescending<TargetAppCandidate> { it.hasExistingCarMeta }
                .thenBy { it.appName.lowercase(sortLocale) }
        )
    }

    private fun cacheFile(includeSystemApps: Boolean): File {
        val fileName = if (includeSystemApps) CACHE_FILE_ALL else CACHE_FILE_USER
        return File(context.cacheDir, fileName)
    }

    private fun readDiskCache(includeSystemApps: Boolean): List<TargetAppCandidate>? {
        return runCatching {
            val file = cacheFile(includeSystemApps)
            if (!file.isFile) return null
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return null
            val array = JSONArray(raw)
            val result = ArrayList<TargetAppCandidate>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val packageName = obj.optString("packageName", "")
                if (packageName.isBlank()) continue
                val apkPathsJson = obj.optJSONArray("apkPaths") ?: continue
                val apkFiles = ArrayList<File>(apkPathsJson.length())
                for (j in 0 until apkPathsJson.length()) {
                    val path = apkPathsJson.optString(j, "")
                    if (path.isNotBlank()) {
                        apkFiles.add(File(path))
                    }
                }
                if (apkFiles.isEmpty()) continue
                result.add(
                    TargetAppCandidate(
                        appName = obj.optString("appName", packageName),
                        packageName = packageName,
                        versionName = obj.optString("versionName", "1.0"),
                        apkFiles = apkFiles,
                        isSystemApp = obj.optBoolean("isSystemApp", false),
                        hasExistingCarMeta = obj.optBoolean("hasExistingCarMeta", false),
                        currentInstallerSource = obj.optString("currentInstallerSource", "").ifBlank { null },
                        isFromExternalFile = false
                    )
                )
            }
            result
        }.getOrNull()
    }

    private fun writeDiskCache(includeSystemApps: Boolean, apps: List<TargetAppCandidate>) {
        val array = JSONArray()
        for (app in apps) {
            val obj = JSONObject()
            obj.put("appName", app.appName)
            obj.put("packageName", app.packageName)
            obj.put("versionName", app.versionName)
            val apkPaths = JSONArray()
            for (file in app.apkFiles) {
                apkPaths.put(file.absolutePath)
            }
            obj.put("apkPaths", apkPaths)
            obj.put("isSystemApp", app.isSystemApp)
            obj.put("hasExistingCarMeta", app.hasExistingCarMeta)
            if (app.currentInstallerSource != null) {
                obj.put("currentInstallerSource", app.currentInstallerSource)
            }
            array.put(obj)
        }
        val target = cacheFile(includeSystemApps)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(array.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(array.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    suspend fun importExternalApkUri(uri: Uri): TargetAppCandidate? = withContext(Dispatchers.IO) {
        try {
            val importDir = File(context.cacheDir, "imported_apks").apply { mkdirs() }
            // Clean up previously imported temporary APKs/Bundles so internal cache never fills up
            importDir.listFiles()?.forEach { oldFile ->
                runCatching { oldFile.deleteRecursively() }
            }

            val rawFileName = queryFileName(uri)
            val safeFileName = PackageNameValidator.sanitizeSafeFileName(
                rawFileName,
                "external_${System.currentTimeMillis()}.apk"
            )
            val targetFile = File(importDir, safeFileName)
            val canonicalRoot = importDir.canonicalFile
            val canonicalTarget = targetFile.canonicalFile
            if (!canonicalTarget.path.startsWith(canonicalRoot.path + File.separator)) {
                return@withContext null
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
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
                val cleanStem = safeFileName.substringBeforeLast('.')
                    .replace(SAFE_BUNDLE_NAME_REGEX, "")
                    .lowercase()
                    .let { if (it.isBlank() || !it.first().isLetter()) "app$it" else it }
                var bundlePkg = "com.aamaker.bundle.$cleanStem"
                var bundleLabel = safeFileName.substringBeforeLast('.')
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
        private const val CACHE_FILE_USER = "installed_apps_cache_user_v1.json"
        private const val CACHE_FILE_ALL = "installed_apps_cache_all_v1.json"
    }
}

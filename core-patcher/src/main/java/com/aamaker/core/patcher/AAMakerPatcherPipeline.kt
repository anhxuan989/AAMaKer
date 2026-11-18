package com.aamaker.core.patcher

import android.content.Context
import com.aamaker.core.patcher.engine.AnyAutoCompanionEngine
import com.aamaker.core.patcher.engine.ApkSignerEngine
import com.aamaker.core.patcher.engine.ManifestAndArscPatcher
import com.aamaker.core.patcher.engine.SplitApkMerger
import com.aamaker.core.patcher.model.PatchConfig
import com.aamaker.core.patcher.model.PatchResult
import com.aamaker.core.patcher.model.PatchStrategy
import com.aamaker.core.patcher.security.PackageNameValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AAMakerPatcherPipeline(private val context: Context) {

    private val splitMerger = SplitApkMerger()
    private val manifestPatcher = ManifestAndArscPatcher(context)
    private val anyAutoEngine = AnyAutoCompanionEngine(context)
    private val apkSigner = ApkSignerEngine(context)

    suspend fun executePatch(
        config: PatchConfig,
        onLog: (String) -> Unit
    ): PatchResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val workDir = File(context.cacheDir, "patch_workspace_${startTime}").apply { mkdirs() }
        val outputDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "patched_apks").apply { mkdirs() }

        try {
            val requestedPkg = config.customPackageName?.takeIf { it.isNotBlank() } ?: config.originalPackageName
            require(PackageNameValidator.isValidPackageName(requestedPkg)) {
                "Tên gói (Package Name) không hợp lệ: '$requestedPkg'. Vui lòng nhập định dạng chuẩn Android (ví dụ: com.example.app)."
            }

            onLog("=== BẮT ĐẦU QUY TRÌNH PATCH ANDROID AUTO ===")
            onLog("Ứng dụng gốc: ${config.appName} (${config.originalPackageName})")
            onLog("Chế độ Patch: ${config.strategy.title}")

            val unsignedApk = File(workDir, "unsigned_patched.apk")
            val outcome = if (config.strategy == PatchStrategy.ANYAUTO_SHIZUKU_MODE) {
                anyAutoEngine.generateCompanionApk(
                    config = config,
                    outputUnsignedApk = unsignedApk,
                    workDir = workDir,
                    onLog = onLog
                )
            } else {
                splitMerger.prepareApkModule(
                    inputFiles = config.sourceApkFiles,
                    workDir = workDir,
                    onLog = onLog
                ).use { session ->
                    manifestPatcher.patchApkModule(
                        apkModule = session.apkModule,
                        outputUnsignedApk = unsignedApk,
                        config = config,
                        onLog = onLog
                    )
                }
            }

            val finalPkg = requestedPkg
            val finalAppName = config.customAppName?.takeIf { it.isNotBlank() } ?: config.appName
            val safeOutFileName = PackageNameValidator.sanitizeSafeFileName(
                "${finalPkg}_AA_Patched.apk",
                "patched_app_AA_Patched.apk"
            )
            val signedApkFile = File(outputDir, safeOutFileName)
            if (signedApkFile.exists()) signedApkFile.delete()
            outputDir.listFiles()?.forEach { oldFile ->
                if (oldFile.isFile && oldFile != signedApkFile &&
                    System.currentTimeMillis() - oldFile.lastModified() > 24L * 3600_000L
                ) {
                    runCatching { oldFile.delete() }
                }
            }

            apkSigner.signApk(
                unsignedApk = unsignedApk,
                signedOutputApk = signedApkFile,
                onLog = onLog,
                minSdkVersion = outcome.minSdkVersion
            )
            runCatching { unsignedApk.delete() }

            val elapsed = System.currentTimeMillis() - startTime
            onLog("=== HOÀN TẤT PATCH TRONG ${elapsed}ms ===")
            onLog("File xuất ra: ${signedApkFile.absolutePath}")

            PatchResult.Success(
                outputApkFile = signedApkFile,
                finalPackageName = finalPkg,
                finalAppName = finalAppName,
                dexInjected = outcome.dexInjected,
                elapsedMs = elapsed
            )
        } catch (e: Throwable) {
            onLog("[LỖI NGHIÊM TRỌNG] ${e::class.java.simpleName}: ${e.message}")
            PatchResult.Failure(
                errorMessage = "${e::class.java.simpleName}: ${e.message ?: "Lỗi không xác định khi patch APK"}",
                exception = e
            )
        } finally {
            workDir.deleteRecursively()
        }
    }
}

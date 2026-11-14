package com.aamaker.core.patcher

import android.content.Context
import com.aamaker.core.patcher.engine.ApkSignerEngine
import com.aamaker.core.patcher.engine.ManifestAndArscPatcher
import com.aamaker.core.patcher.engine.SplitApkMerger
import com.aamaker.core.patcher.model.PatchConfig
import com.aamaker.core.patcher.model.PatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AAMakerPatcherPipeline(private val context: Context) {

    private val splitMerger = SplitApkMerger()
    private val manifestPatcher = ManifestAndArscPatcher(context)
    private val apkSigner = ApkSignerEngine(context)

    suspend fun executePatch(
        config: PatchConfig,
        onLog: (String) -> Unit
    ): PatchResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val workDir = File(context.cacheDir, "patch_workspace_${startTime}").apply { mkdirs() }
        val outputDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "patched_apks").apply { mkdirs() }

        try {
            onLog("=== BẮT ĐẦU QUY TRÌNH PATCH ANDROID AUTO ===")
            onLog("Ứng dụng gốc: ${config.appName} (${config.originalPackageName})")
            onLog("Chế độ Patch: ${config.strategy.title}")

            val unsignedApk = File(workDir, "unsigned_patched.apk")
            val outcome = splitMerger.prepareApkModule(
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

            val finalPkg = config.customPackageName?.takeIf { it.isNotBlank() } ?: config.originalPackageName
            val finalAppName = config.customAppName?.takeIf { it.isNotBlank() } ?: config.appName
            val signedApkFile = File(outputDir, "${finalPkg}_AA_Patched.apk")
            if (signedApkFile.exists()) signedApkFile.delete()

            apkSigner.signApk(
                unsignedApk = unsignedApk,
                signedOutputApk = signedApkFile,
                onLog = onLog,
                minSdkVersion = outcome.minSdkVersion
            )

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

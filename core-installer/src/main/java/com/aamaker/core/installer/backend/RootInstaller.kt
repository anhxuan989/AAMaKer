package com.aamaker.core.installer.backend

import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.installer.model.InstallResult
import com.aamaker.core.installer.util.InstallerSecurityValidator
import com.aamaker.core.installer.util.PlayProtectSnapshot
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RootInstaller {

    fun isRootAvailable(): Boolean {
        return try {
            val cachedGranted = Shell.isAppGrantedRoot()
            if (cachedGranted != null) return cachedGranted
            val probePool = Executors.newSingleThreadExecutor()
            try {
                probePool.submit(Callable { Shell.getShell().isRoot }).get(6, TimeUnit.SECONDS)
            } finally {
                probePool.shutdownNow()
            }
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun installApk(
        apkFile: File,
        expectedPackageName: String,
        autoGrantPermissions: Boolean = true,
        onLog: (String) -> Unit
    ): InstallResult = installApks(listOf(apkFile), expectedPackageName, autoGrantPermissions, onLog)

    suspend fun installApks(
        apkFiles: List<File>,
        expectedPackageName: String,
        autoGrantPermissions: Boolean = true,
        onLog: (String) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!InstallerSecurityValidator.isValidPackageName(expectedPackageName)) {
            return@withContext InstallResult.Error("Tên gói cài đặt không hợp lệ: '$expectedPackageName'")
        }
        if (!isRootAvailable()) {
            return@withContext InstallResult.Error("Thiết bị chưa cấp quyền Root (Superuser) cho AAMaKer.")
        }
        if (apkFiles.isEmpty()) {
            return@withContext InstallResult.Error("Danh sách file APK đầu vào trống!")
        }

        val timestamp = System.currentTimeMillis()
        val remoteTmpPaths = mutableListOf<String>()
        try {
            apkFiles.forEachIndexed { idx, file ->
                val remotePath = "/data/local/tmp/aamaker_${timestamp}_${idx}.apk"
                remoteTmpPaths.add(remotePath)
                onLog("[Root] Đang sao chép APK vào $remotePath...")
                val safeSrc = InstallerSecurityValidator.shellSingleQuote(file.absolutePath)
                val safeDst = InstallerSecurityValidator.shellSingleQuote(remotePath)
                val copyResult = execRootBounded(
                    timeoutSeconds = 90L,
                    "cp $safeSrc $safeDst",
                    "chmod 644 $safeDst"
                )

                if (copyResult == null || !copyResult.isSuccess) {
                    cleanupRemoteTmpFiles(remoteTmpPaths)
                    val errText = copyResult?.err?.joinToString("\n") ?: "Quá thời gian chờ (Timeout)"
                    return@withContext InstallResult.Error(
                        "Không thể chép APK vào /data/local/tmp: $errText"
                    )
                }
            }

            onLog("[Root] Đang cài đặt với nguồn com.android.vending...")
            var playProtectSnapshot: PlayProtectSnapshot? = null
            try {
                val backupRes = execRootBounded(15L, PlayProtectSnapshot.buildBackupAndDisableShellCommand())
                if (backupRes != null) {
                    playProtectSnapshot = PlayProtectSnapshot.parseSnapshot(backupRes.out)
                }
            } catch (_: Throwable) {
            }

            val installResult = try {
                if (remoteTmpPaths.size == 1) {
                    val safeRemote = InstallerSecurityValidator.shellSingleQuote(remoteTmpPaths.first())
                    execRootBounded(
                        INSTALL_TIMEOUT_SECONDS,
                        "pm install -r -d -i com.android.vending --user 0 $safeRemote"
                    )
                } else {
                    val joinedPaths = remoteTmpPaths.joinToString(" ") {
                        InstallerSecurityValidator.shellSingleQuote(it)
                    }
                    execRootBounded(
                        INSTALL_TIMEOUT_SECONDS,
                        "pm install-multiple -r -d -i com.android.vending --user 0 $joinedPaths"
                    )
                }
            } finally {
                playProtectSnapshot?.let { snapshot ->
                    try {
                        execRootBounded(15L, snapshot.buildRestoreShellCommand())
                        onLog("[Root] Đã khôi phục trạng thái Play Protect ban đầu.")
                    } catch (_: Throwable) {
                    }
                }
            }

            cleanupRemoteTmpFiles(remoteTmpPaths)

            if (installResult == null) {
                return@withContext InstallResult.Error(
                    "Quá thời gian chờ (${INSTALL_TIMEOUT_SECONDS}s) khi chạy pm install qua Root."
                )
            }

            val output = (installResult.out + installResult.err).joinToString("\n").trim()
            onLog("[Root] Kết quả pm install: $output")

            if (!installResult.isSuccess || !output.contains("Success", ignoreCase = true)) {
                val hint = buildConflictHint(output)
                return@withContext InstallResult.Error("Lỗi pm install qua Root: $output$hint")
            }

            if (autoGrantPermissions) {
                val safePkg = InstallerSecurityValidator.shellSingleQuote(expectedPackageName)
                onLog("[Root] Đang cấp quyền SYSTEM_ALERT_WINDOW, PROJECT_MEDIA & bật Touch Bridge cho $expectedPackageName...")
                execRootBounded(
                    20L,
                    "appops set $safePkg SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1",
                    "appops set $safePkg PROJECT_MEDIA allow >/dev/null 2>&1",
                    "appops set $safePkg RUN_IN_BACKGROUND allow >/dev/null 2>&1",
                    "appops set $safePkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1",
                    "appops set $safePkg WAKE_LOCK allow >/dev/null 2>&1",
                    "dumpsys deviceidle whitelist +$expectedPackageName >/dev/null 2>&1",
                    "cmd app_hibernation set-state $safePkg false >/dev/null 2>&1",
                    "am set-standby-bucket $safePkg active >/dev/null 2>&1",
                    "am force-stop com.google.android.projection.gearhead >/dev/null 2>&1"
                )
                onLog("[Root] Đã khởi động lại dịch vụ Android Auto (Gearhead) để cập nhật danh sách ứng dụng ngay lập tức.")
            }

            InstallResult.Success(
                packageName = expectedPackageName,
                installerSource = "com.android.vending",
                modeUsed = InstallMode.ROOT,
                message = "Đã cài đặt thành công qua Root (với nguồn Google Play Store & tự động cấp quyền AppOps)!"
            )
        } catch (e: Exception) {
            cleanupRemoteTmpFiles(remoteTmpPaths)
            onLog("[Root] Ngoại lệ: ${e.message}")
            InstallResult.Error("Lỗi cài đặt qua Root: ${e.message}", e)
        }
    }

    private fun cleanupRemoteTmpFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        val cmds = paths.map { "rm -f ${InstallerSecurityValidator.shellSingleQuote(it)}" }.toTypedArray()
        runCatching { execRootBounded(15L, *cmds) }
    }

    private fun execRootBounded(timeoutSeconds: Long, vararg commands: String): Shell.Result? {
        val pool = Executors.newSingleThreadExecutor()
        return try {
            pool.submit(Callable { Shell.cmd(*commands).exec() }).get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            null
        } finally {
            pool.shutdownNow()
        }
    }

    private fun buildConflictHint(output: String): String {
        val upper = output.uppercase()
        return if (
            upper.contains("UPDATE_INCOMPATIBLE") ||
            upper.contains("CONFLICTING_PROVIDER") ||
            upper.contains("DUPLICATE_PERMISSION")
        ) {
            "\nGợi ý: Ứng dụng gốc trên máy có chữ ký khác (Play Store). Hãy gỡ cài đặt bản gốc trước khi cài đè, hoặc bấm nút Tạo ngẫu nhiên (Shuffle) để đổi Package Name cài song song."
        } else {
            ""
        }
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 180L
    }
}

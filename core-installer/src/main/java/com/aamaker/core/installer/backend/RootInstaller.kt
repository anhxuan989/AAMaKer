package com.aamaker.core.installer.backend

import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.installer.model.InstallResult
import com.aamaker.core.installer.util.PlayProtectSnapshot
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class RootInstaller {

    fun isRootAvailable(): Boolean {
        return try {
            Shell.getShell().isRoot
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
                val copyResult = Shell.cmd(
                    "cp \"${file.absolutePath}\" \"$remotePath\"",
                    "chmod 644 \"$remotePath\""
                ).exec()

                if (!copyResult.isSuccess) {
                    remoteTmpPaths.forEach { Shell.cmd("rm -f \"$it\"").exec() }
                    return@withContext InstallResult.Error(
                        "Không thể chép APK vào /data/local/tmp: ${copyResult.err.joinToString("\n")}"
                    )
                }
            }

            onLog("[Root] Đang cài đặt với nguồn com.android.vending...")
            var playProtectSnapshot: PlayProtectSnapshot? = null
            try {
                val backupRes = Shell.cmd(PlayProtectSnapshot.buildBackupAndDisableShellCommand()).exec()
                playProtectSnapshot = PlayProtectSnapshot.parseSnapshot(backupRes.out)
            } catch (_: Throwable) {
            }
            val installResult = try {
                if (remoteTmpPaths.size == 1) {
                    Shell.cmd(
                        "pm install -r -d -i com.android.vending --user 0 \"${remoteTmpPaths.first()}\""
                    ).exec()
                } else {
                    val joinedPaths = remoteTmpPaths.joinToString(" ") { "\"$it\"" }
                    Shell.cmd(
                        "pm install-multiple -r -d -i com.android.vending --user 0 $joinedPaths"
                    ).exec()
                }
            } finally {
                playProtectSnapshot?.let { snapshot ->
                    try {
                        Shell.cmd(snapshot.buildRestoreShellCommand()).exec()
                        onLog("[Root] Đã khôi phục trạng thái Play Protect ban đầu.")
                    } catch (_: Throwable) {
                    }
                }
            }

            remoteTmpPaths.forEach { Shell.cmd("rm -f \"$it\"").exec() }

            val output = (installResult.out + installResult.err).joinToString("\n").trim()
            onLog("[Root] Kết quả pm install: $output")

            if (!installResult.isSuccess || !output.contains("Success", ignoreCase = true)) {
                val hint = buildConflictHint(output)
                return@withContext InstallResult.Error("Lỗi pm install qua Root: $output$hint")
            }

            if (autoGrantPermissions) {
                onLog("[Root] Đang cấp quyền SYSTEM_ALERT_WINDOW, PROJECT_MEDIA & bật Touch Bridge cho $expectedPackageName...")
                Shell.cmd(
                    "appops set $expectedPackageName SYSTEM_ALERT_WINDOW allow",
                    "appops set $expectedPackageName PROJECT_MEDIA allow",
                    "appops set $expectedPackageName RUN_IN_BACKGROUND allow",
                    "appops set $expectedPackageName RUN_ANY_IN_BACKGROUND allow",
                    "appops set $expectedPackageName WAKE_LOCK allow",
                    "dumpsys deviceidle whitelist +$expectedPackageName >/dev/null 2>&1",
                    "cmd app_hibernation set-state $expectedPackageName false >/dev/null 2>&1",
                    "am set-standby-bucket $expectedPackageName active >/dev/null 2>&1",
                    "am force-stop com.google.android.projection.gearhead"
                ).exec()
                onLog("[Root] Đã khởi động lại dịch vụ Android Auto (Gearhead) để cập nhật danh sách ứng dụng ngay lập tức.")
            }

            InstallResult.Success(
                packageName = expectedPackageName,
                installerSource = "com.android.vending",
                modeUsed = InstallMode.ROOT,
                message = "Đã cài đặt thành công qua Root (với nguồn Google Play Store & tự động cấp quyền AppOps)!"
            )
        } catch (e: Exception) {
            remoteTmpPaths.forEach { Shell.cmd("rm -f \"$it\"").exec() }
            onLog("[Root] Ngoại lệ: ${e.message}")
            InstallResult.Error("Lỗi cài đặt qua Root: ${e.message}", e)
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
}

package com.aamaker.core.installer.backend

import android.content.pm.PackageManager
import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.installer.model.InstallResult
import com.aamaker.core.installer.util.PlayProtectSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.lang.reflect.Method

class ShizukuInstaller {

    private var cachedNewProcessMethod: Method? = null

    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            if (!isAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun installApk(
        apkFile: File,
        expectedPackageName: String,
        onLog: (String) -> Unit
    ): InstallResult = installApks(listOf(apkFile), expectedPackageName, onLog)

    suspend fun installApks(
        apkFiles: List<File>,
        expectedPackageName: String,
        onLog: (String) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext InstallResult.Error("Dịch vụ Shizuku chưa chạy. Vui lòng mở app Shizuku và kích hoạt trước.")
        }
        if (!hasPermission()) {
            return@withContext InstallResult.Error("Chưa cấp quyền Shizuku cho AAMaKer.")
        }
        if (apkFiles.isEmpty()) {
            return@withContext InstallResult.Error("Danh sách file APK đầu vào trống!")
        }

        try {
            onLog("[Shizuku] Đang khởi tạo phiên cài đặt với UID 2000 (ADB Shell)...")

            val stdout: String
            val stderr: String
            val exitCode: Int

            var playProtectSnapshot: PlayProtectSnapshot? = null
            try {
                val backupProc = createShizukuProcess(
                    arrayOf(
                        "sh",
                        "-c",
                        PlayProtectSnapshot.buildBackupAndDisableShellCommand()
                    )
                )
                val backupLines = BufferedReader(InputStreamReader(backupProc.inputStream)).readLines()
                backupProc.waitFor()
                playProtectSnapshot = PlayProtectSnapshot.parseSnapshot(backupLines)
            } catch (_: Throwable) {
            }

            try {
                if (apkFiles.size == 1) {
                    val apkFile = apkFiles.first()
                    val fileSize = apkFile.length()
                    onLog("[Shizuku] Thực thi: pm install-create -r -i com.android.vending --user 0 -S $fileSize")

                    val createCmd = arrayOf(
                        "sh", "-c",
                        "pm install -r -d -i com.android.vending --user 0 -S $fileSize"
                    )
                    val process = createShizukuProcess(createCmd)

                    FileInputStream(apkFile).use { fis ->
                        process.outputStream.use { os ->
                            fis.copyTo(os, bufferSize = 64 * 1024)
                            os.flush()
                        }
                    }

                    stdout = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
                    stderr = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
                    exitCode = process.waitFor()
                } else {
                    onLog("[Shizuku] Thực thi: pm install-create -r -d -i com.android.vending --user 0 (${apkFiles.size} APKs)")
                    val createProc = createShizukuProcess(
                        arrayOf("sh", "-c", "pm install-create -r -d -i com.android.vending --user 0")
                    )
                    val createOut = BufferedReader(InputStreamReader(createProc.inputStream)).readText().trim()
                    val createErr = BufferedReader(InputStreamReader(createProc.errorStream)).readText().trim()
                    createProc.waitFor()

                    val sessionId = SESSION_ID_REGEX.find(createOut)?.groupValues?.get(1)
                        ?: return@withContext InstallResult.Error("Cài đặt qua Shizuku thất bại: $createOut $createErr")

                    apkFiles.forEachIndexed { idx, file ->
                        val size = file.length()
                        val splitName = "split_${idx}_${file.name.replace(SAFE_SPLIT_NAME_REGEX, "_")}"
                        onLog("[Shizuku] Thực thi: pm install-write -S $size $sessionId $splitName -")
                        val writeProc = createShizukuProcess(
                            arrayOf("sh", "-c", "pm install-write -S $size $sessionId $splitName -")
                        )
                        FileInputStream(file).use { fis ->
                            writeProc.outputStream.use { os ->
                                fis.copyTo(os, bufferSize = 64 * 1024)
                                os.flush()
                            }
                        }
                        val writeOut = BufferedReader(InputStreamReader(writeProc.inputStream)).readText().trim()
                        val writeErr = BufferedReader(InputStreamReader(writeProc.errorStream)).readText().trim()
                        val writeExit = writeProc.waitFor()
                        if (writeExit != 0 || !writeOut.contains("Success", ignoreCase = true)) {
                            createShizukuProcess(arrayOf("sh", "-c", "pm install-abandon $sessionId")).waitFor()
                            return@withContext InstallResult.Error("Cài đặt qua Shizuku thất bại: $writeOut $writeErr")
                        }
                    }

                    val commitProc = createShizukuProcess(
                        arrayOf("sh", "-c", "pm install-commit $sessionId")
                    )
                    stdout = BufferedReader(InputStreamReader(commitProc.inputStream)).readText().trim()
                    stderr = BufferedReader(InputStreamReader(commitProc.errorStream)).readText().trim()
                    exitCode = commitProc.waitFor()
                }
            } finally {
                playProtectSnapshot?.let { snapshot ->
                    try {
                        createShizukuProcess(
                            arrayOf("sh", "-c", snapshot.buildRestoreShellCommand())
                        ).waitFor()
                        onLog("[Shizuku] Đã khôi phục trạng thái Play Protect ban đầu.")
                    } catch (_: Throwable) {
                    }
                }
            }

            onLog("[Shizuku] Kết quả: $stdout $stderr (ExitCode=$exitCode)")

            if (exitCode == 0 && stdout.contains("Success", ignoreCase = true)) {
                try {
                    createShizukuProcess(
                        arrayOf(
                            "sh", "-c",
                            "appops set $expectedPackageName SYSTEM_ALERT_WINDOW allow; " +
                                "appops set $expectedPackageName PROJECT_MEDIA allow; " +
                                "appops set $expectedPackageName RUN_IN_BACKGROUND allow; " +
                                "appops set $expectedPackageName RUN_ANY_IN_BACKGROUND allow; " +
                                "appops set $expectedPackageName WAKE_LOCK allow; " +
                                "dumpsys deviceidle whitelist +$expectedPackageName >/dev/null 2>&1; " +
                                "cmd app_hibernation set-state $expectedPackageName false >/dev/null 2>&1; " +
                                "am set-standby-bucket $expectedPackageName active >/dev/null 2>&1; " +
                                "am force-stop com.google.android.projection.gearhead"
                        )
                    ).waitFor()
                    onLog("[Shizuku] Đã cấp quyền SYSTEM_ALERT_WINDOW, kích hoạt Touch Bridge & làm mới cache ứng dụng Android Auto (Gearhead).")
                } catch (_: Throwable) {
                }
                InstallResult.Success(
                    packageName = expectedPackageName,
                    installerSource = "com.android.vending",
                    modeUsed = InstallMode.SHIZUKU,
                    message = "Cài đặt qua Shizuku thành công với nguồn Google Play Store!"
                )
            } else {
                val combined = "$stdout $stderr".trim()
                val hint = buildConflictHint(combined)
                InstallResult.Error("Cài đặt qua Shizuku thất bại: $combined$hint")
            }
        } catch (e: Exception) {
            onLog("[Shizuku] Ngoại lệ: ${e.message}")
            InstallResult.Error("Lỗi thực thi Shizuku: ${e.message}", e)
        }
    }

    @Suppress("PrivateApi")
    private fun createShizukuProcess(cmd: Array<String>): Process {
        var method = cachedNewProcessMethod
        if (method == null) {
            method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            cachedNewProcessMethod = method
        }
        return method.invoke(null, cmd, null, null) as Process
    }

    companion object {
        private val SESSION_ID_REGEX = Regex("""\[(\d+)]""")
        private val SAFE_SPLIT_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")

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
}

package com.aamaker.core.installer.backend

import android.content.Context
import android.content.pm.PackageManager
import com.aamaker.core.installer.adb.LocalAdbConnectionManager
import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.installer.model.InstallResult
import com.aamaker.core.installer.util.InstallerSecurityValidator
import com.aamaker.core.installer.util.PlayProtectSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShizukuInstaller(
    private val context: Context? = null
) {

    private var cachedNewProcessMethod: Method? = null

    private data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false
    )

    fun isShizukuNativeAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasShizukuNativePermission(): Boolean {
        return try {
            if (!isShizukuNativeAvailable()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun isLocalAdbAvailable(): Boolean {
        val ctx = context ?: return false
        return try {
            LocalAdbConnectionManager.getInstance(ctx).isConnected
        } catch (_: Throwable) {
            false
        }
    }

    fun isAvailable(): Boolean {
        return isShizukuNativeAvailable() || isLocalAdbAvailable()
    }

    fun hasPermission(): Boolean {
        return hasShizukuNativePermission() || isLocalAdbAvailable()
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
        if (!InstallerSecurityValidator.isValidPackageName(expectedPackageName)) {
            return@withContext InstallResult.Error("Tên gói cài đặt không hợp lệ: '$expectedPackageName'")
        }
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

            val finalRes: ProcessResult

            var playProtectSnapshot: PlayProtectSnapshot? = null
            try {
                val backupRes = runShizukuShellBounded(
                    shellCmd = PlayProtectSnapshot.buildBackupAndDisableShellCommand(),
                    timeoutSeconds = 15L
                )
                if (!backupRes.timedOut) {
                    playProtectSnapshot = PlayProtectSnapshot.parseSnapshot(backupRes.stdout.lines())
                }
            } catch (_: Throwable) {
            }

            try {
                if (apkFiles.size == 1) {
                    val apkFile = apkFiles.first()
                    val fileSize = apkFile.length()
                    onLog("[Shizuku] Thực thi: pm install -r -d -i com.android.vending --user 0 -S $fileSize")

                    finalRes = runShizukuShellBounded(
                        shellCmd = "pm install -r -d -i com.android.vending --user 0 -S $fileSize",
                        stdinFile = apkFile,
                        timeoutSeconds = INSTALL_TIMEOUT_SECONDS
                    )
                } else {
                    onLog("[Shizuku] Thực thi: pm install-create -r -d -i com.android.vending --user 0 (${apkFiles.size} APKs)")
                    val createRes = runShizukuShellBounded(
                        shellCmd = "pm install-create -r -d -i com.android.vending --user 0",
                        timeoutSeconds = 30L
                    )

                    val sessionId = SESSION_ID_REGEX.find(createRes.stdout)?.groupValues?.get(1)
                        ?: return@withContext InstallResult.Error(
                            "Cài đặt qua Shizuku thất bại: ${createRes.stdout} ${createRes.stderr}".trim()
                        )

                    for ((idx, file) in apkFiles.withIndex()) {
                        val size = file.length()
                        val splitName = "split_${idx}_${file.name.replace(SAFE_SPLIT_NAME_REGEX, "_").take(100)}"
                        onLog("[Shizuku] Thực thi: pm install-write -S $size $sessionId $splitName -")
                        val writeRes = runShizukuShellBounded(
                            shellCmd = "pm install-write -S $size $sessionId $splitName -",
                            stdinFile = file,
                            timeoutSeconds = INSTALL_TIMEOUT_SECONDS
                        )
                        if (writeRes.timedOut || writeRes.exitCode != 0 || !writeRes.stdout.contains("Success", ignoreCase = true)) {
                            runCatching {
                                runShizukuShellBounded("pm install-abandon $sessionId", timeoutSeconds = 15L)
                            }
                            return@withContext InstallResult.Error(
                                "Cài đặt qua Shizuku thất bại: ${writeRes.stdout} ${writeRes.stderr}".trim()
                            )
                        }
                    }

                    finalRes = runShizukuShellBounded(
                        shellCmd = "pm install-commit $sessionId",
                        timeoutSeconds = INSTALL_TIMEOUT_SECONDS
                    )
                }
            } finally {
                playProtectSnapshot?.let { snapshot ->
                    try {
                        runShizukuShellBounded(
                            shellCmd = snapshot.buildRestoreShellCommand(),
                            timeoutSeconds = 15L
                        )
                        onLog("[Shizuku] Đã khôi phục trạng thái Play Protect ban đầu.")
                    } catch (_: Throwable) {
                    }
                }
            }

            if (finalRes.timedOut) {
                return@withContext InstallResult.Error(
                    "Quá thời gian chờ (${INSTALL_TIMEOUT_SECONDS}s) khi cài đặt qua Shizuku. Vui lòng kiểm tra thông báo xác nhận cài đặt hoặc khởi động lại Shizuku."
                )
            }

            onLog("[Shizuku] Kết quả: ${finalRes.stdout} ${finalRes.stderr} (ExitCode=${finalRes.exitCode})")

            if (finalRes.exitCode == 0 && finalRes.stdout.contains("Success", ignoreCase = true)) {
                val safePkg = InstallerSecurityValidator.shellSingleQuote(expectedPackageName)
                try {
                    runShizukuShellBounded(
                        shellCmd = "appops set $safePkg SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1; " +
                            "appops set $safePkg PROJECT_MEDIA allow >/dev/null 2>&1; " +
                            "appops set $safePkg RUN_IN_BACKGROUND allow >/dev/null 2>&1; " +
                            "appops set $safePkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; " +
                            "appops set $safePkg WAKE_LOCK allow >/dev/null 2>&1; " +
                            "appops set com.aamaker.app SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1; " +
                            "appops set com.aamaker.app PROJECT_MEDIA allow >/dev/null 2>&1; " +
                            "appops set com.aamaker.app RUN_IN_BACKGROUND allow >/dev/null 2>&1; " +
                            "appops set com.aamaker.app RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1; " +
                            "dumpsys deviceidle whitelist +com.aamaker.app >/dev/null 2>&1; " +
                            "cmd app_hibernation set-state com.aamaker.app false >/dev/null 2>&1; " +
                            "am set-standby-bucket com.aamaker.app active >/dev/null 2>&1; " +
                            "settings put global force_resizable_activities 1 >/dev/null 2>&1; " +
                            "settings put global enable_non_resizable_multi_window 1 >/dev/null 2>&1; " +
                            "dumpsys deviceidle whitelist +$expectedPackageName >/dev/null 2>&1; " +
                            "cmd app_hibernation set-state $safePkg false >/dev/null 2>&1; " +
                            "am set-standby-bucket $safePkg active >/dev/null 2>&1; " +
                            "am force-stop com.google.android.projection.gearhead >/dev/null 2>&1",
                        timeoutSeconds = 20L
                    )
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
                val combined = "${finalRes.stdout} ${finalRes.stderr}".trim()
                val hint = buildConflictHint(combined)
                InstallResult.Error("Cài đặt qua Shizuku thất bại: $combined$hint")
            }
        } catch (e: Exception) {
            onLog("[Shizuku] Ngoại lệ: ${e.message}")
            InstallResult.Error("Lỗi thực thi Shizuku: ${e.message}", e)
        }
    }

    private fun runShizukuShellBounded(
        shellCmd: String,
        stdinFile: File? = null,
        timeoutSeconds: Long = 120L
    ): ProcessResult {
        if (!hasShizukuNativePermission()) {
            val ctx = context ?: return ProcessResult(exitCode = -1, stdout = "", stderr = "Local ADB context missing")
            val adbMgr = LocalAdbConnectionManager.getInstance(ctx)
            val res = adbMgr.execShell(
                command = shellCmd,
                stdinFile = stdinFile,
                timeoutMs = timeoutSeconds * 1000L
            )
            return res.fold(
                onSuccess = { out ->
                    val isFailure = out.contains("Failure [", ignoreCase = true) ||
                        out.contains("Exception:", ignoreCase = true)
                    ProcessResult(
                        exitCode = if (isFailure) 1 else 0,
                        stdout = out,
                        stderr = "",
                        timedOut = false
                    )
                },
                onFailure = { err ->
                    ProcessResult(
                        exitCode = -1,
                        stdout = "",
                        stderr = err.message ?: "Local ADB shell failed",
                        timedOut = false
                    )
                }
            )
        }
        val process = createShizukuProcess(arrayOf("sh", "-c", shellCmd))
        val streamPool = Executors.newFixedThreadPool(2)
        try {
            val outFuture = streamPool.submit(Callable { readBoundedText(process.inputStream) })
            val errFuture = streamPool.submit(Callable { readBoundedText(process.errorStream) })

            try {
                if (stdinFile != null) {
                    FileInputStream(stdinFile).use { fis ->
                        process.outputStream.use { os ->
                            fis.copyTo(os, bufferSize = 64 * 1024)
                            os.flush()
                        }
                    }
                } else {
                    runCatching { process.outputStream.close() }
                }
            } catch (_: Throwable) {
                runCatching { process.outputStream.close() }
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { process.destroyForcibly() }
                val out = runCatching { outFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
                val err = runCatching { errFuture.get(2, TimeUnit.SECONDS) }.getOrDefault("")
                return ProcessResult(exitCode = -1, stdout = out, stderr = err, timedOut = true)
            }

            val stdout = runCatching { outFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            val stderr = runCatching { errFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
            return ProcessResult(
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr,
                timedOut = false
            )
        } finally {
            runCatching { process.destroy() }
            streamPool.shutdownNow()
        }
    }

    private fun readBoundedText(stream: InputStream, maxChars: Int = 64 * 1024): String {
        return try {
            stream.use { ins ->
                val reader = BufferedReader(InputStreamReader(ins))
                val sb = StringBuilder()
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    val remaining = maxChars - sb.length
                    if (remaining > 0) {
                        sb.append(buf, 0, minOf(n, remaining))
                    }
                }
                sb.toString().trim()
            }
        } catch (_: Throwable) {
            ""
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
        private const val INSTALL_TIMEOUT_SECONDS = 180L
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

package com.aamaker.core.installer.backend

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.aamaker.core.installer.InstallResultReceiver
import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.installer.model.InstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.Method

class StandardSessionInstaller(private val context: Context) {

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
        if (apkFiles.isEmpty()) {
            return@withContext InstallResult.Error("Danh sách file APK đầu vào trống!")
        }
        try {
            onLog("[Non-Root Session] Khởi tạo PackageInstaller.SessionParams...")
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

            params.setAppPackageName(expectedPackageName)
            params.setInstallerPackageName(PLAY_STORE_PACKAGE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }

            try {
                var method = cachedSetRequestUpdateOwnershipMethod
                if (method == null) {
                    method = params.javaClass.getMethod("setRequestUpdateOwnership", Boolean::class.javaPrimitiveType)
                    cachedSetRequestUpdateOwnershipMethod = method
                }
                method.invoke(params, false)
            } catch (_: Throwable) {
            }

            val totalBytes = apkFiles.sumOf { it.length() }
            val sessionId = packageInstaller.createSession(params)
            onLog("[Non-Root Session] Đã tạo Session #$sessionId, đang ghi dữ liệu APK (${totalBytes / 1024} KB)...")

            packageInstaller.openSession(sessionId).use { session ->
                apkFiles.forEachIndexed { idx, file ->
                    val entryName = if (apkFiles.size == 1) {
                        "base.apk"
                    } else {
                        "split_${idx}_${file.name.replace(SAFE_SPLIT_NAME_REGEX, "_")}"
                    }
                    FileInputStream(file).use { input ->
                        session.openWrite(entryName, 0, file.length()).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                            session.fsync(output)
                        }
                    }
                }

                val callbackIntent = Intent(context, InstallResultReceiver::class.java).apply {
                    action = "com.aamaker.core.installer.ACTION_INSTALL_COMMIT_$sessionId"
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)

                onLog("[Non-Root Session] Đang gửi yêu cầu xác nhận cài đặt tới hệ thống...")
                session.commit(pendingIntent.intentSender)
            }

            val event = withTimeoutOrNull(60_000L) {
                InstallResultReceiver.installEvents.first()
            }

            if (event == null) {
                return@withContext InstallResult.PendingUserAction(
                    "Đang chờ người dùng xác nhận hộp thoại cài đặt trên màn hình..."
                )
            }

            if (event.success) {
                onLog("[Non-Root Session] Cài đặt hoàn tất: ${event.packageName}")
                InstallResult.Success(
                    packageName = event.packageName,
                    installerSource = PLAY_STORE_PACKAGE,
                    modeUsed = InstallMode.STANDARD_NON_ROOT,
                    message = "Cài đặt thành công gói ${event.packageName}"
                )
            } else {
                InstallResult.Error("Cài đặt thất bại (Mã ${event.statusCode}): ${event.statusMessage}")
            }
        } catch (e: Exception) {
            onLog("[Non-Root Session] Lỗi: ${e.message}")
            InstallResult.Error("Lỗi cài đặt qua Session API: ${e.message}", e)
        }
    }

    companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
        private val SAFE_SPLIT_NAME_REGEX = Regex("[^a-zA-Z0-9._-]")
        private var cachedSetRequestUpdateOwnershipMethod: Method? = null
    }
}

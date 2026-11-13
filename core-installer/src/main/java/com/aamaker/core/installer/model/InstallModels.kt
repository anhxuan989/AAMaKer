package com.aamaker.core.installer.model

enum class InstallMode(
    val title: String,
    val subtitle: String,
    val requiresPrivileged: Boolean
) {
    SHIZUKU(
        title = "Shizuku (Khuyên dùng)",
        subtitle = "Cài qua ADB (UID 2000), gán nguồn Play chuẩn cho Android 11+",
        requiresPrivileged = true
    ),
    ROOT(
        title = "Root (Magisk / KernelSU / APatch)",
        subtitle = "Cài trực tiếp với nguồn Google Play & tự cấp quyền",
        requiresPrivileged = true
    ),
    LSPOSED(
        title = "LSPosed / Xposed",
        subtitle = "Cài thường, mở khóa trực tiếp trong AA qua module Xposed",
        requiresPrivileged = false
    ),
    STANDARD_NON_ROOT(
        title = "Không Root (Cơ bản)",
        subtitle = "Giả lập nguồn Play qua Session (Tốt nhất cho Android 10–13)",
        requiresPrivileged = false
    )
}

sealed class InstallResult {
    data class Success(
        val packageName: String,
        val installerSource: String,
        val modeUsed: InstallMode,
        val message: String
    ) : InstallResult()

    data class PendingUserAction(
        val message: String
    ) : InstallResult()

    data class Error(
        val reason: String,
        val throwable: Throwable? = null
    ) : InstallResult()
}

data class EnvironmentStatus(
    val androidVersion: Int,
    val androidReleaseName: String,
    val isAndroidAutoInstalled: Boolean,
    val androidAutoVersion: String?,
    val isShizukuAvailable: Boolean,
    val isShizukuPermissionGranted: Boolean,
    val isRootAvailable: Boolean,
    val isXposedModuleActive: Boolean,
    val recommendedInstallMode: InstallMode
)

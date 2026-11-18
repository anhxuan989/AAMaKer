package com.aamaker.core.patcher.model

import java.io.File

enum class PatchStrategy(
    val title: String,
    val description: String
) {
    DIRECT_INSTALL_NO_PATCH(
        title = "Cài nguyên gốc (Không Patch)",
        description = "Giữ nguyên APK & chữ ký gốc. Chỉ gán nguồn cài đặt Google Play."
    ),
    SPOOF_AND_ACTIVATE(
        title = "Mở khóa App AA sẵn có",
        description = "Sửa cấu hình AA & đổi tên gói để vượt chặn."
    ),
    PARKED_APP_MODE(
        title = "Chế độ Đỗ xe / Game",
        description = "Mở trực tiếp giao diện gốc trên AA (Android 15+, gộp Split & bật chế độ Game)."
    ),
    UNIVERSAL_INJECTION(
        title = "Chuyển App thường sang AA",
        description = "Đưa mọi ứng dụng (Video, Bản đồ, Web...) lên màn hình xe, hỗ trợ cảm ứng."
    ),
    DUAL_WINDOW_MODE(
        title = "Chia đôi màn hình (2 App)",
        description = "Chạy song song 2 ứng dụng trên 1 màn hình Android Auto, tùy chỉnh tỷ lệ & trộn âm thanh."
    ),
    ANYAUTO_SHIZUKU_MODE(
        title = "Trình chiếu AnyAuto (Shizuku)",
        description = "Màn hình ảo Trusted VirtualDisplay qua Shizuku, 60 FPS GPU, giữ nguyên app gốc, tạo launcher riêng trên xe."
    )
}

enum class SplitRatioPreset(
    val ratioValue: Float,
    val displayLabel: String
) {
    EQUAL_50_50(0.50f, "50 : 50"),
    LEFT_60_40(0.60f, "60 : 40"),
    LEFT_70_30(0.70f, "70 : 30"),
    RIGHT_40_60(0.40f, "40 : 60")
}

enum class CarAppCategory(
    val xmlValue: String,
    val displayName: String
) {
    NAVIGATION("androidx.car.app.category.NAVIGATION", "Dẫn đường (Navigation)"),
    IOT("androidx.car.app.category.IOT", "Điều khiển (IoT / Dashboard)"),
    MEDIA("android.intent.category.CAR_MODE", "Đa phương tiện (Media)"),
    POI("androidx.car.app.category.POI", "Điểm đến (POI)")
}

data class PatchConfig(
    val sourceApkFiles: List<File>,
    val originalPackageName: String,
    val appName: String,
    val strategy: PatchStrategy = PatchStrategy.PARKED_APP_MODE,
    val customPackageName: String? = null,
    val customAppName: String? = null,
    val category: CarAppCategory = CarAppCategory.IOT,
    val injectProjectionUses: Boolean = true,
    val injectTemplateUses: Boolean = true,
    val injectMediaUses: Boolean = false,
    val injectParkedGameManifest: Boolean = true,
    val bypassDrivingRestriction: Boolean = true,
    val enableGamepadSupport: Boolean = true,
    val unlockScreenOrientation: Boolean = true,
    val useRootShellForTouch: Boolean = false,
    val removeSignatureVerification: Boolean = true,
    val preferredLanguageCode: String? = null,
    val secondaryPackageName: String? = null,
    val defaultSplitRatio: Float = 0.50f,
    val mixDualAudioFocus: Boolean = true,
    val autoStartDualWindow: Boolean = false
)

sealed class PatchResult {
    data class Success(
        val outputApkFile: File,
        val finalPackageName: String,
        val finalAppName: String,
        val dexInjected: Boolean,
        val elapsedMs: Long
    ) : PatchResult()

    data class Failure(
        val errorMessage: String,
        val exception: Throwable? = null
    ) : PatchResult()
}

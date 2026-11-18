package com.aamaker.app.i18n

import com.aamaker.app.ui.ConsoleLogEntry
import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.patcher.model.CarAppCategory
import com.aamaker.core.patcher.model.PatchStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLocalizationTest {

    // Characters exclusive to Vietnamese orthography (not used in ES/FR/PT/DE)
    private val vietnameseExclusiveRegex = Regex(
        "[ăâđơưĂÂĐƠƯảạấầẩẫậắằẳẵặẻẽẹếềểễệỉịỏọốồổỗộớờởỡợủụứừửữựỳỷỹỵẢẠẤẦẨẪẬẮẰẲẴẶẺẼẸẾỀỂỄỆỈỊỎỌỐỒỔỖỘỚỜỞỠỢỦỤỨỪỬỮỰỲỶỸỴ]"
    )

    @Test
    fun `all 11 languages provide complete non-blank UI strings without leaked Vietnamese`() {
        assertEquals(11, AppLanguage.entries.size)

        for (lang in AppLanguage.entries) {
            assertEquals(lang, AppLanguage.fromCode(lang.code))
            assertEquals(lang, AppLanguage.fromCode("${lang.code}-REGION"))
            assertEquals(lang, AppLanguage.fromCode("${lang.code}_REGION"))

            val s = AppTranslations.forLanguage(lang)
            assertEquals(lang, s.language)

            val uiValues = buildList {
                add(s.appSubtitle)
                add(s.refreshTooltip)
                add(s.languageDialogTitle)
                add(s.closeButton)
                add(s.navSelectApp)
                add(s.navPatchInstall)
                add(s.navEnvironment)
                add(s.bannerExternalTitle)
                add(s.bannerExternalSubtitle)
                add(s.chooseFileButton)
                add(s.chooseFileDirectInstallButton)
                add(s.searchPlaceholder)
                add(s.clearSearch)
                add(s.filterExistingAAChip)
                add(s.filterSystemAppsChip)
                add(s.badgeExistingAA)
                add(s.badgeNeedInjectAA)
                add(s.badgeSourcePlayStore)
                add(s.badgeSourceOtherPrefix)
                add(s.badgeSourceSideload)
                add(s.splitsBadgeFormat(4))
                add(s.emptySelectionTitle)
                add(s.backToAppListButton)
                add(s.originalPackageFormat("com.example.app", 3))
                add(s.section1PatchStrategyTitle)
                PatchStrategy.entries.forEach { strategy ->
                    add(s.strategyTitle(strategy))
                    add(s.strategyDescription(strategy))
                }
                add(s.dualWindowSectionTitle)
                add(s.dualSecondaryAppLabel)
                add(s.dualSecondaryChooseOnCarHint)
                add(s.dualPickSecondaryButton)
                add(s.dualSplitRatioLabel)
                add(s.dualMixAudioTitle)
                add(s.dualMixAudioDesc)
                add(s.outputPackageLabel)
                add(s.randomizeTooltip)
                add(s.carDisplayNameLabel)
                add(s.automotiveDescConfigLabel)
                add(s.projectionFullScreenLabel)
                add(s.carTemplatesLabel)
                add(s.compatOptionsHeaderLabel)
                add(s.parkedGameManifestChip)
                add(s.gamepadSupportChip)
                add(s.unlockOrientationChip)
                add(s.bypassDrivingRestrictionChip)
                add(s.carLauncherCategoryLabel)
                CarAppCategory.entries.forEach { cat ->
                    add(s.categoryName(cat))
                }
                add(s.touchModeRootLabel)
                add(s.touchModeRootDesc)
                add(s.section2InstallModeTitle)
                InstallMode.entries.forEach { mode ->
                    add(s.installModeTitle(mode))
                    add(s.installModeSubtitle(mode))
                }
                add(s.processingButton)
                add(s.directInstallNoPatchButton)
                add(s.patchAndInstallButton)
                add(s.patchAndSaveButton)
                add(s.savedInStorageLabel)
                add(s.saveToDownloadButton)
                add(s.chooseSaveFolderButton)
                add(s.reinstallLastPatchedFormat("app.apk"))
                add(s.consoleTitle)
                add(s.clearLogsButton)
                add(s.readyToExecute)
                add(s.envHeaderTitle)
                add(s.diagAndroidOsTitle)
                add(s.diagGearheadTitle)
                add(s.diagGearheadInstalledFormat("12.8"))
                add(s.diagGearheadNotInstalled)
                add(s.diagShizukuTitle)
                add(s.diagShizukuGranted)
                add(s.diagShizukuRunningNotGranted)
                add(s.diagShizukuNotRunning)
                add(s.diagGrantShizukuButton)
                add(s.diagRootTitle)
                add(s.diagRootAvailable)
                add(s.diagRootNotDetected)
                add(s.diagXposedTitle)
                add(s.diagXposedActive)
                add(s.diagXposedInactive)
                add(s.drivingHookDiagTitle)
                add(s.drivingHookDiagActive)
                add(s.drivingHookDiagManifestOnly)
                add(s.unknownSourcesGuideTitle)
                add(s.unknownSourcesGuideSteps)
                add(s.openAASettingsButton)
                add(s.recheckButton)
                add(s.statusPatchingApk)
                add(s.statusDirectInstallingApk)
                add(s.statusInstallingPatchedApk)
                add(s.statusPatchFailedFormat("err"))
                add(s.statusPatchedAndSavedFormat("/sdcard/Download/a.apk", 1200L))
                add(s.statusPatchedSuccessFormat(950L))
                add(s.statusSavedToStorageFormat("/sdcard/Download/a.apk"))
                add(s.statusSaveDownloadFailed)
                add(s.statusExportedSuccess)
                add(s.statusExportFailed)
            }

            for (value in uiValues) {
                assertTrue("Blank UI string in ${lang.code}", value.isNotBlank())
                if (lang != AppLanguage.VI) {
                    assertFalse(
                        "Leaked Vietnamese characters in ${lang.code}: $value",
                        vietnameseExclusiveRegex.containsMatchIn(value)
                    )
                }
            }
        }
    }

    @Test
    fun `all pipeline and installer logs errors warnings and multiline hints translate cleanly across all 11 languages`() {
        val conflictHint = "\nGợi ý: Ứng dụng gốc trên máy có chữ ký khác (Play Store). Hãy gỡ cài đặt bản gốc trước khi cài đè, hoặc bấm nút Tạo ngẫu nhiên (Shuffle) để đổi Package Name cài song song."

        val allRealMessages = listOf(
            // Shizuku & ViewModel messages
            "[Shizuku] Đã được cấp quyền Shizuku thành công!",
            "[Shizuku] Người dùng từ chối cấp quyền Shizuku.",
            "[Shizuku] Dịch vụ Shizuku chưa chạy trên thiết bị.",
            "[Shizuku] Đã có sẵn quyền Shizuku.",
            "[Shizuku] Lỗi yêu cầu quyền: Binder died",
            "Đang nhập file APK/APKS từ bộ nhớ...",
            "Đã nạp: DemoApp (com.demo.app)",
            "Không thể đọc được file APK đã chọn.",
            "Tên gói APK gốc không hợp lệ: 'bad_pkg'",
            "[Cảnh báo] Tên gói APK gốc không hợp lệ: 'bad_pkg'",
            "Tên gói (Package Name) không hợp lệ: '123.bad'. Định dạng đúng ví dụ: com.example.app",
            "[Cảnh báo] Tên gói (Package Name) không hợp lệ: '123.bad'. Định dạng đúng ví dụ: com.example.app",
            "[Export] Error saving file: Permission denied",
            "[Export] Exported patched APK successfully (15420 KB).",
            "[Storage] Đã lưu APK đã patch vào bộ nhớ máy: /sdcard/Download/AAMaKer/app.apk (15420 KB)",
            "[Storage] Đã lưu APK đã patch vào: /storage/emulated/0/Download/AAMaKer/app.apk",
            "[Storage] Cảnh báo khi lưu vào Download/AAMaKer: No space left",
            "--- Bắt đầu cài đặt lên hệ thống với chế độ: ${InstallMode.SHIZUKU.title} ---",
            "--- Bắt đầu cài đặt lên hệ thống với chế độ: ${InstallMode.STANDARD_NON_ROOT.title} ---",

            // Patcher pipeline & Merger & ManifestAndArscPatcher & Signer
            "=== BẮT ĐẦU QUY TRÌNH PATCH ANDROID AUTO ===",
            "Ứng dụng gốc: YouTube (com.google.android.youtube)",
            "Chế độ Patch: ${PatchStrategy.PARKED_APP_MODE.title}",
            "Chế độ Patch: ${PatchStrategy.UNIVERSAL_INJECTION.title}",
            "Chế độ Patch: ${PatchStrategy.DUAL_WINDOW_MODE.title}",
            "[Merger] Phát hiện gói Bundle (app_bundle.apks), đang giải nén các thành phần...",
            "[Merger] Phát hiện 4 Split APKs, đang hợp nhất cấu trúc nhị phân (DEX + resources.arsc + Native Libs + Assets)...",
            "[Merger] + Nạp APK nền (base): base.apk",
            "[Merger] + Nạp Split APK: split_config.arm64_v8a.apk",
            "[Merger] + Đã hợp nhất 3 thành phần Feature Split vào AndroidManifest.xml.",
            "[Merger] + Đã dọn thư mục ABI không đầy đủ 'lib/x86' (1/5 thư viện .so) để tránh lỗi UnsatisfiedLinkError.",
            "[Merger] + Gộp dữ liệu OBB: main.100.com.demo.obb",
            "[Merger] Đã hợp nhất hoàn chỉnh 4 APK thành 1 module duy nhất (3 DEX).",
            "[Patcher] Đang xử lý cấu trúc APK nhị phân bằng ARSCLib...",
            "[Patcher] + Đã chuẩn hóa 2 bảng tài nguyên nén (Sparse/Compact TypeBlock) sang định dạng chuẩn để bảo toàn ID tài nguyên.",
            "[Patcher] Đang đổi tên gói: com.demo.app -> com.demo.app.aa123 (cập nhật Application, Component, Permission & Authorities)...",
            "[Patcher] Đang cập nhật tên hiển thị ứng dụng thành: Demo Car",
            "[Patcher] + Đã dọn sạch an toàn 5 thuộc tính/meta-data Split APK (bảo toàn tài nguyên dùng chung).",
            "[Patcher] + Đã vô hiệu hóa 1 thành phần kiểm tra giấy phép Play Licensing.",
            "[Patcher] ! Cảnh báo: Phát hiện Google Play PairIP VM (libpairipcore.so / Pairip Application) — giữ nguyên thành phần Manifest của PairIP để không kích hoạt bẫy chống sửa đổi. Nếu ứng dụng tự đóng sau khi ký lại APK, cần bật giả lập chữ ký gốc (LSPosed + CorePatch / pairipfix) hoặc dùng bản APK độc lập ngoài Play Store.",
            "[Patcher] ! Cảnh báo: Phát hiện cơ chế kiểm tra chữ ký gốc tầng Native/C++ (/libDexHelper.so) — nếu ứng dụng tự đóng khi khởi chạy sau khi đổi chữ ký APK, cần bật LSPosed (CorePatch / Signature Spoofing) hoặc dùng gói cài đặt gốc chưa bọc DRM.",
            "[Patcher] + Thêm quyền: androidx.car.app.ACCESS_SURFACE",
            "[Patcher] APK đã có sẵn khai báo 'com.google.android.gms.car.application' (@0x7f050001).",
            "[Patcher] Đang tạo Binary XML 'res/xml/automotive_app_desc.xml' và đăng ký vào bảng resources.arsc...",
            "[Patcher] + Đã cấp phát Resource ID @0x7f050001 -> res/xml/automotive_app_desc.xml",
            "[Patcher] + Đã gắn <meta-data android:name=\"com.google.android.gms.car.application\" android:resource=\"@0x7f050001\" />",
            "[Patcher] Đang tiêm <service android:name=\"com.aamaker.stub.runtime.AAMakerCarAppService\"> vào AndroidManifest.xml...",
            "[Patcher] + Đã kích hoạt chế độ Parked App (appCategory=game, CAR_LAUNCHER & distractionOptimized) cho 1 Launcher Activity.",
            "[Patcher] + Đã tiêm cờ mở khóa khi xe di chuyển (distractionOptimized) cho toàn bộ 6 Activity.",
            "[Patcher] + Đã tối ưu hóa phần cứng: bật hỗ trợ Gamepad=true, gỡ bỏ bắt buộc cho 3 uses-feature.",
            "[Patcher] Chế độ PARKED_APP_MODE: Kích hoạt đầy đủ CAR_LAUNCHER & appCategory=\"game\" chuẩn Android Auto Parked App (không tiêm DEX).",
            "[Patcher] Chế độ SPOOF_AND_ACTIVATE: Giữ nguyên CarAppService/MediaBrowserService gốc của APK.",
            "[Patcher] ! Không tìm thấy payload Universal AA Runtime trong assets, giữ nguyên DEX hiện tại.",
            "[Patcher] + Đã tiêm Bootstrap Service DEX cô lập thành 'classes2.dex' (4128 bytes — không xung đột AndroidX/Kotlin/Guava).",
            "[Patcher] + Đã đóng gói 1 phân đoạn Isolated Car Runtime vào assets/aa_car_res.dat.",
            "[Patcher] Cảnh báo khi tiêm DEX: IO error",
            "[Patcher] Đang đóng gói lại cấu trúc APK nhị phân (căn chỉnh 16KB Page Size cho thư viện Native .so)...",
            "[Signer] Đang chuẩn bị khóa RSA-2048 & chứng chỉ X.509 để ký APK (Scheme V1 + V2 + V3, minSdk=26)...",
            "[Signer] Đã ký APK hoàn tất (18400 KB).",
            "=== HOÀN TẤT PATCH TRONG 1450ms ===",
            "File xuất ra: /data/user/0/com.aamaker.app/files/patched_apks/out.apk",

            // Exceptions & Critical Errors
            "[LỖI NGHIÊM TRỌNG] IllegalArgumentException: Tên gói (Package Name) không hợp lệ: 'bad'. Vui lòng nhập định dạng chuẩn Android (ví dụ: com.example.app).",
            "IllegalArgumentException: Tên gói (Package Name) không hợp lệ: 'bad'. Vui lòng nhập định dạng chuẩn Android (ví dụ: com.example.app).",
            "[LỖI NGHIÊM TRỌNG] IllegalArgumentException: Package Name đích không hợp lệ: 'bad'",
            "IllegalStateException: APK không chứa AndroidManifest.xml hợp lệ!",
            "IllegalStateException: AndroidManifest.xml thiếu thẻ gốc <manifest>!",
            "IllegalStateException: Không tìm thấy PackageBlock trong resources.arsc!",
            "IllegalArgumentException: Danh sách file APK đầu vào trống!",
            "IllegalArgumentException: Không tìm thấy file .apk nào bên trong test.xapk",
            "IllegalStateException: Gói Bundle chứa quá nhiều mục (2049), hủy giải nén để tránh treo máy.",
            "IllegalStateException: Gói Bundle chứa quá nhiều mục (2049)!",
            "IllegalStateException: Dung lượng giải nén vượt quá giới hạn an toàn (4 GB)!",
            "SecurityException: Phát hiện đường dẫn giải nén không an toàn: evil.apk",
            "SecurityException: Phát hiện đường dẫn giải nén không hợp lệ: evil.apk",
            "RuntimeException: Lỗi không xác định khi patch APK",

            // Direct Install, Root, Shizuku & Session Installers (including multiline & hints!)
            "[Direct Install] Phát hiện gói Bundle (bundle.apks), đang trích xuất các APK gốc (giữ nguyên chữ ký)...",
            "[Direct Install] Cài đặt nguyên bản 3 file APK (giữ nguyên chữ ký số gốc) cho gói com.demo.app...",
            "[Direct Install] Lỗi chuẩn bị APK gốc: Không tìm thấy file .apk nào bên trong empty.apks",
            "Lỗi đọc file APK gốc: Dung lượng giải nén vượt quá giới hạn an toàn (4 GB)!",
            "Tên gói cài đặt không hợp lệ: 'bad_pkg'",
            "Danh sách file APK đầu vào trống!",
            "Thiết bị chưa cấp quyền Root (Superuser) cho AAMaKer.",
            "[Root] Đang sao chép APK vào /data/local/tmp/aamaker_1.apk...",
            "Không thể chép APK vào /data/local/tmp: Quá thời gian chờ (Timeout)",
            "[Root] Đang cài đặt với nguồn com.android.vending...",
            "[Root] Đã khôi phục trạng thái Play Protect ban đầu.",
            "Quá thời gian chờ (180s) khi chạy pm install qua Root.",
            "[Root] Kết quả pm install: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]\nPkg mismatch",
            "Lỗi pm install qua Root: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]$conflictHint",
            "[Root] Đang cấp quyền SYSTEM_ALERT_WINDOW, PROJECT_MEDIA & bật Touch Bridge cho com.demo.app...",
            "[Root] Đã khởi động lại dịch vụ Android Auto (Gearhead) để cập nhật danh sách ứng dụng ngay lập tức.",
            "Đã cài đặt thành công qua Root (với nguồn Google Play Store & tự động cấp quyền AppOps)!",
            "[Root] Ngoại lệ: Broken pipe",
            "Lỗi cài đặt qua Root: Broken pipe",
            "Dịch vụ Shizuku chưa chạy. Vui lòng mở app Shizuku và kích hoạt trước.",
            "Chưa cấp quyền Shizuku cho AAMaKer.",
            "[Shizuku] Đang khởi tạo phiên cài đặt với UID 2000 (ADB Shell)...",
            "[Shizuku] Thực thi: pm install -r -d -i com.android.vending --user 0 -S 10240",
            "[Shizuku] Đã khôi phục trạng thái Play Protect ban đầu.",
            "Quá thời gian chờ (180s) khi cài đặt qua Shizuku. Vui lòng kiểm tra thông báo xác nhận cài đặt hoặc khởi động lại Shizuku.",
            "[Shizuku] Kết quả: Success (ExitCode=0)",
            "[Shizuku] Đã cấp quyền SYSTEM_ALERT_WINDOW, kích hoạt Touch Bridge & làm mới cache ứng dụng Android Auto (Gearhead).",
            "Cài đặt qua Shizuku thành công với nguồn Google Play Store!",
            "Cài đặt qua Shizuku thất bại: Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]$conflictHint",
            "[Shizuku] Ngoại lệ: RemoteException",
            "Lỗi thực thi Shizuku: RemoteException",
            "[Non-Root Session] Khởi tạo PackageInstaller.SessionParams...",
            "[Non-Root Session] Đã tạo Session #42, đang ghi dữ liệu APK (12500 KB)...",
            "[Non-Root Session] Đang gửi yêu cầu xác nhận cài đặt tới hệ thống...",
            "Đang chờ người dùng xác nhận hộp thoại cài đặt trên màn hình...",
            "[Non-Root Session] Cài đặt hoàn tất: com.demo.app",
            "Cài đặt thành công gói com.demo.app",
            "Cài đặt thất bại (Mã 5): INSTALL_FAILED_UPDATE_INCOMPATIBLE$conflictHint",
            "Cài đặt thất bại (Mã 3): Không thể mở hộp thoại xác nhận cài đặt",
            "[Non-Root Session] Lỗi: IOException",
            "Lỗi cài đặt qua Session API: IOException"
        )

        for (rawMsg in allRealMessages) {
            val entry = ConsoleLogEntry(time = "12:00:00", rawMessage = rawMsg)
            for (lang in AppLanguage.entries) {
                val strings = AppTranslations.forLanguage(lang)
                val rendered = entry.render(strings)
                assertTrue("Empty translation for [$rawMsg] in ${lang.code}", rendered.isNotBlank())
                assertFalse("Malformed @0x0x in [$rendered]", rendered.contains("@0x0x", ignoreCase = true))

                if (lang != AppLanguage.VI) {
                    assertFalse(
                        "Untranslated Vietnamese remained for ${lang.code}:\n  RAW: $rawMsg\n  OUT: $rendered",
                        vietnameseExclusiveRegex.containsMatchIn(rendered)
                    )
                }
            }

            // Also verify English [Export] logs translate into Vietnamese when VI is selected
            if (rawMsg.startsWith("[Export]")) {
                val viRendered = entry.render(AppTranslations.forLanguage(AppLanguage.VI))
                assertTrue(
                    "Expected Vietnamese translation for Export log, got: $viRendered",
                    vietnameseExclusiveRegex.containsMatchIn(viRendered)
                )
            }
        }
    }

    @Test
    fun `resource id and bootstrap dex translations preserve exact hex and units`() {
        val en = AppTranslations.forLanguage(AppLanguage.EN)

        val resAlloc = AppTranslations.translateLogOrMessage(
            "[Patcher] + Đã cấp phát Resource ID @0x7f050001 -> res/xml/automotive_app_desc.xml",
            en
        )
        assertEquals(
            "[Patcher] + Allocated Resource ID @0x7f050001 -> res/xml/automotive_app_desc.xml",
            resAlloc
        )

        val existingDecl = AppTranslations.translateLogOrMessage(
            "[Patcher] APK đã có sẵn khai báo 'com.google.android.gms.car.application' (@0x7f050001).",
            en
        )
        assertEquals(
            "[Patcher] APK already declares 'com.google.android.gms.car.application' (@0x7f050001).",
            existingDecl
        )

        val bootstrapLog = AppTranslations.translateLogOrMessage(
            "[Patcher] + Đã tiêm Bootstrap Service DEX cô lập thành 'classes2.dex' (4128 bytes — không xung đột AndroidX/Kotlin/Guava).",
            en
        )
        assertTrue("Expected '4128 bytes' in: $bootstrapLog", bootstrapLog.contains("4128 bytes"))
    }
}

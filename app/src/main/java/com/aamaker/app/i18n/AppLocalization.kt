package com.aamaker.app.i18n

import android.content.Context
import com.aamaker.core.installer.model.InstallMode
import com.aamaker.core.patcher.model.CarAppCategory
import com.aamaker.core.patcher.model.PatchStrategy

enum class AppLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val flagEmoji: String
) {
    EN("en", "English", "English", "🇺🇸"),
    VI("vi", "Tiếng Việt", "Vietnamese", "🇻🇳"),
    ZH("zh", "中文 (简体)", "Chinese", "🇨🇳"),
    ES("es", "Español", "Spanish", "🇪🇸"),
    HI("hi", "हिन्दी", "Hindi", "🇮🇳"),
    AR("ar", "العربية", "Arabic", "🇸🇦"),
    PT("pt", "Português", "Portuguese", "🇧🇷"),
    RU("ru", "Русский", "Russian", "🇷🇺"),
    JA("ja", "日本語", "Japanese", "🇯🇵"),
    DE("de", "Deutsch", "German", "🇩🇪"),
    FR("fr", "Français", "French", "🇫🇷");

    companion object {
        private const val PREFS_NAME = "aamaker_i18n_prefs"
        private const val KEY_LANG = "selected_language_code"

        fun fromCode(code: String?): AppLanguage {
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN
        }

        fun loadSavedLanguage(context: Context): AppLanguage {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val savedCode = prefs.getString(KEY_LANG, EN.code)
            return fromCode(savedCode)
        }

        fun saveLanguage(context: Context, language: AppLanguage) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANG, language.code)
                .apply()
        }
    }
}

data class AppStrings(
    val language: AppLanguage,

    val appSubtitle: String,
    val refreshTooltip: String,
    val languageDialogTitle: String,
    val closeButton: String,
    val navSelectApp: String,
    val navPatchInstall: String,
    val navEnvironment: String,

    val bannerExternalTitle: String,
    val bannerExternalSubtitle: String,
    val chooseFileButton: String,
    val chooseFileDirectInstallButton: String,
    val searchPlaceholder: String,
    val clearSearch: String,
    val filterExistingAAChip: String,
    val filterSystemAppsChip: String,
    val badgeExistingAA: String,
    val badgeNeedInjectAA: String,
    val badgeSourcePlayStore: String,
    val badgeSourceOtherPrefix: String,
    val badgeSourceSideload: String,
    val splitsBadgeFormat: (Int) -> String,

    val emptySelectionTitle: String,
    val backToAppListButton: String,
    val targetLabelPrefix: String,
    val originalPackageFormat: (String, Int) -> String,
    val section1PatchStrategyTitle: String,
    val strategyDirectNoPatchTitle: String,
    val strategyDirectNoPatchDesc: String,
    val strategySpoofTitle: String,
    val strategySpoofDesc: String,
    val strategyUniversalTitle: String,
    val strategyUniversalDesc: String,
    val outputPackageLabel: String,
    val randomizeTooltip: String,
    val carDisplayNameLabel: String,
    val automotiveDescConfigLabel: String,
    val projectionFullScreenLabel: String,
    val carTemplatesLabel: String,
    val carLauncherCategoryLabel: String,
    val categoryNavigation: String,
    val categoryIot: String,
    val categoryMedia: String,
    val categoryPoi: String,
    val touchModeRootLabel: String,
    val touchModeRootDesc: String,
    val section2InstallModeTitle: String,
    val modeShizukuTitle: String,
    val modeShizukuSubtitle: String,
    val modeRootTitle: String,
    val modeRootSubtitle: String,
    val modeLsposedTitle: String,
    val modeLsposedSubtitle: String,
    val modeStandardTitle: String,
    val modeStandardSubtitle: String,
    val processingButton: String,
    val directInstallNoPatchButton: String,
    val patchAndInstallButton: String,
    val patchAndSaveButton: String,
    val savedInStorageLabel: String,
    val saveToDownloadButton: String,
    val chooseSaveFolderButton: String,
    val reinstallLastPatchedFormat: (String) -> String,
    val consoleTitle: String,
    val clearLogsButton: String,
    val readyToExecute: String,

    val envHeaderTitle: String,
    val diagAndroidOsTitle: String,
    val diagGearheadTitle: String,
    val diagGearheadInstalledFormat: (String?) -> String,
    val diagGearheadNotInstalled: String,
    val diagShizukuTitle: String,
    val diagShizukuGranted: String,
    val diagShizukuRunningNotGranted: String,
    val diagShizukuNotRunning: String,
    val diagGrantShizukuButton: String,
    val diagRootTitle: String,
    val diagRootAvailable: String,
    val diagRootNotDetected: String,
    val diagXposedTitle: String,
    val diagXposedActive: String,
    val diagXposedInactive: String,
    val unknownSourcesGuideTitle: String,
    val unknownSourcesGuideSteps: String,
    val openAASettingsButton: String,
    val recheckButton: String,

    val statusPatchingApk: String,
    val statusDirectInstallingApk: String,
    val statusPatchFailedFormat: (String) -> String,
    val statusPatchedAndSavedFormat: (String, Long) -> String,
    val statusPatchedSuccessFormat: (Long) -> String,
    val statusSavedToStorageFormat: (String) -> String,
    val statusSaveDownloadFailed: String,
    val statusExportedSuccess: String,
    val statusExportFailed: String
) {
    fun strategyTitle(strategy: PatchStrategy): String = when (strategy) {
        PatchStrategy.DIRECT_INSTALL_NO_PATCH -> strategyDirectNoPatchTitle
        PatchStrategy.SPOOF_AND_ACTIVATE -> strategySpoofTitle
        PatchStrategy.PARKED_APP_MODE -> when (language) {
            AppLanguage.EN -> "Parked / Game Mode"
            AppLanguage.VI -> "Chế độ Đỗ xe / Game"
            AppLanguage.ZH -> "驻车 / 游戏模式"
            AppLanguage.ES -> "Modo Estacionado / Juego"
            AppLanguage.HI -> "पार्क / गेम मोड"
            AppLanguage.AR -> "وضع التوقف / الألعاب"
            AppLanguage.PT -> "Modo Estacionado / Jogo"
            AppLanguage.RU -> "Режим парковки / Игры"
            AppLanguage.JA -> "駐車 / ゲームモード"
            AppLanguage.DE -> "Park- / Spielmodus"
            AppLanguage.FR -> "Mode Arrêt / Jeu"
        }
        PatchStrategy.UNIVERSAL_INJECTION -> strategyUniversalTitle
    }

    fun strategyDescription(strategy: PatchStrategy): String = when (strategy) {
        PatchStrategy.DIRECT_INSTALL_NO_PATCH -> strategyDirectNoPatchDesc
        PatchStrategy.SPOOF_AND_ACTIVATE -> strategySpoofDesc
        PatchStrategy.PARKED_APP_MODE -> when (language) {
            AppLanguage.EN -> "Run native UI on Android Auto (Android 15+, merges splits & game mode)."
            AppLanguage.VI -> "Mở trực tiếp giao diện gốc trên AA (Android 15+, gộp Split & bật chế độ Game)."
            AppLanguage.ZH -> "在 AA 上原生运行界面 (Android 15+，合并分包并开启游戏模式)。"
            AppLanguage.ES -> "Interfaz nativa en AA (Android 15+, une splits y activa modo juego)."
            AppLanguage.HI -> "AA पर मूल इंटरफ़ेस चलाएं (Android 15+, स्प्लिट मर्ज और गेम मोड)।"
            AppLanguage.AR -> "تشغيل الواجهة الأصلية على AA (أندرويد 15+، دمج الحزم ووضع الألعاب)."
            AppLanguage.PT -> "Interface nativa no AA (Android 15+, une splits e ativa modo jogo)."
            AppLanguage.RU -> "Нативный запуск в AA (Android 15+, слияние Split и игровой режим)."
            AppLanguage.JA -> "AAでネイティブUIを実行 (Android 15+、分割APK統合＆ゲームモード)。"
            AppLanguage.DE -> "Natives UI auf AA ausführen (Android 15+, Splits & Spielmodus)."
            AppLanguage.FR -> "Interface native sur AA (Android 15+, fusion splits & mode jeu)."
        }
        PatchStrategy.UNIVERSAL_INJECTION -> strategyUniversalDesc
    }

    val compatOptionsHeaderLabel: String
        get() = when (language) {
            AppLanguage.EN -> "Compatibility Options:"
            AppLanguage.VI -> "Tùy chọn tương thích:"
            AppLanguage.ZH -> "兼容选项:"
            AppLanguage.ES -> "Opciones de compatibilidad:"
            AppLanguage.HI -> "संगतता विकल्प:"
            AppLanguage.AR -> "خيارات التوافق:"
            AppLanguage.PT -> "Opções de compatibilidade:"
            AppLanguage.RU -> "Опции совместимости:"
            AppLanguage.JA -> "互換性オプション:"
            AppLanguage.DE -> "Kompatibilitätsoptionen:"
            AppLanguage.FR -> "Options de compatibilité :"
        }

    val parkedGameManifestChip: String
        get() = when (language) {
            AppLanguage.EN -> "Parked Game Mode"
            AppLanguage.VI -> "Chế độ Đỗ xe"
            AppLanguage.ZH -> "驻车游戏模式"
            AppLanguage.ES -> "Modo Juego"
            AppLanguage.HI -> "पार्क गेम मोड"
            AppLanguage.AR -> "وضع الألعاب"
            AppLanguage.PT -> "Modo Jogo"
            AppLanguage.RU -> "Режим парковки"
            AppLanguage.JA -> "駐車ゲーム"
            AppLanguage.DE -> "Park-Spiel"
            AppLanguage.FR -> "Mode Jeu"
        }

    val gamepadSupportChip: String
        get() = when (language) {
            AppLanguage.EN -> "Gamepad"
            AppLanguage.VI -> "Hỗ trợ Tay cầm"
            AppLanguage.ZH -> "手柄支持"
            AppLanguage.ES -> "Gamepad"
            AppLanguage.HI -> "गेमपैड"
            AppLanguage.AR -> "ذراع التحكم"
            AppLanguage.PT -> "Gamepad"
            AppLanguage.RU -> "Геймпад"
            AppLanguage.JA -> "ゲームパッド"
            AppLanguage.DE -> "Gamepad"
            AppLanguage.FR -> "Manette"
        }

    val unlockOrientationChip: String
        get() = when (language) {
            AppLanguage.EN -> "Widescreen / Rotate"
            AppLanguage.VI -> "Xoay & Toàn màn hình"
            AppLanguage.ZH -> "解锁横屏/宽屏"
            AppLanguage.ES -> "Pantalla Ancha / Rotar"
            AppLanguage.HI -> "चौड़ी स्क्रीन / रोटेट"
            AppLanguage.AR -> "شاشة عريضة / تدوير"
            AppLanguage.PT -> "Tela Larga / Girar"
            AppLanguage.RU -> "Поворот / Широкий экран"
            AppLanguage.JA -> "画面回転・ワイド"
            AppLanguage.DE -> "Breitbild / Drehung"
            AppLanguage.FR -> "Écran Large / Rotation"
        }

    val bypassDrivingRestrictionChip: String
        get() = when (language) {
            AppLanguage.EN -> "Use While Driving"
            AppLanguage.VI -> "Dùng khi xe chạy"
            AppLanguage.ZH -> "行车可用"
            AppLanguage.ES -> "Usar en Movimiento"
            AppLanguage.HI -> "चलती कार में चलाएं"
            AppLanguage.AR -> "الاستخدام أثناء القيادة"
            AppLanguage.PT -> "Usar em Movimento"
            AppLanguage.RU -> "Работа в движении"
            AppLanguage.JA -> "走行中も使用"
            AppLanguage.DE -> "Während der Fahrt"
            AppLanguage.FR -> "Utiliser en roulant"
        }

    val drivingHookDiagTitle: String
        get() = when (language) {
            AppLanguage.EN -> "Driving Bypass"
            AppLanguage.VI -> "Mở khóa khi xe chạy"
            AppLanguage.ZH -> "行车限制解除"
            AppLanguage.ES -> "Desbloqueo en Movimiento"
            AppLanguage.HI -> "ड्राइविंग अनलॉक"
            AppLanguage.AR -> "تجاوز قفل القيادة"
            AppLanguage.PT -> "Desbloqueio em Movimento"
            AppLanguage.RU -> "Обход блокировки"
            AppLanguage.JA -> "走行制限解除"
            AppLanguage.DE -> "Fahrsperre-Bypass"
            AppLanguage.FR -> "Déblocage en roulant"
        }

    val drivingHookDiagActive: String
        get() = when (language) {
            AppLanguage.EN -> "LSPosed Active ✓ (Full sensor bypass)"
            AppLanguage.VI -> "Đã bật qua LSPosed ✓ (Giả lập xe đỗ hoàn toàn)"
            AppLanguage.ZH -> "LSPosed 已激活 ✓ (完全解除传感器限制)"
            AppLanguage.ES -> "Activo en LSPosed ✓ (Bypass completo)"
            AppLanguage.HI -> "LSPosed सक्रिय ✓ (पूर्ण सेंसर बाईपास)"
            AppLanguage.AR -> "مفعّل عبر LSPosed ✓ (تجاوز كامل للحساسات)"
            AppLanguage.PT -> "Ativo no LSPosed ✓ (Bypass completo)"
            AppLanguage.RU -> "Активен в LSPosed ✓ (Полный обход датчиков)"
            AppLanguage.JA -> "LSPosed 有効 ✓ (センサー制限を完全解除)"
            AppLanguage.DE -> "LSPosed aktiv ✓ (Vollständiger Sensor-Bypass)"
            AppLanguage.FR -> "LSPosed actif ✓ (Bypass complet des capteurs)"
        }

    val drivingHookDiagManifestOnly: String
        get() = when (language) {
            AppLanguage.EN -> "APK Flag Only (Enable in LSPosed for full bypass)"
            AppLanguage.VI -> "Mới bật cờ APK (Bật thêm trong LSPosed để mở khóa hoàn toàn)"
            AppLanguage.ZH -> "仅 APK 标记 (在 LSPosed 中启用以完全解除)"
            AppLanguage.ES -> "Solo APK (Active en LSPosed para bypass total)"
            AppLanguage.HI -> "केवल APK फ़्लैग (पूर्ण बाईपास के लिए LSPosed में चालू करें)"
            AppLanguage.AR -> "في APK فقط (فعّل في LSPosed للتجاوز الكامل)"
            AppLanguage.PT -> "Apenas APK (Ative no LSPosed para bypass total)"
            AppLanguage.RU -> "Только в APK (Включите в LSPosed для полного обхода)"
            AppLanguage.JA -> "APKフラグのみ (完全解除にはLSPosedで有効化)"
            AppLanguage.DE -> "Nur APK-Flag (In LSPosed für vollen Bypass aktivieren)"
            AppLanguage.FR -> "APK seul (Activez dans LSPosed pour un bypass complet)"
        }

    fun categoryName(category: CarAppCategory): String = when (category) {
        CarAppCategory.NAVIGATION -> categoryNavigation
        CarAppCategory.IOT -> categoryIot
        CarAppCategory.MEDIA -> categoryMedia
        CarAppCategory.POI -> categoryPoi
    }

    fun installModeTitle(mode: InstallMode): String = when (mode) {
        InstallMode.SHIZUKU -> modeShizukuTitle
        InstallMode.ROOT -> modeRootTitle
        InstallMode.LSPOSED -> modeLsposedTitle
        InstallMode.STANDARD_NON_ROOT -> modeStandardTitle
    }

    fun installModeSubtitle(mode: InstallMode): String = when (mode) {
        InstallMode.SHIZUKU -> modeShizukuSubtitle
        InstallMode.ROOT -> modeRootSubtitle
        InstallMode.LSPOSED -> modeLsposedSubtitle
        InstallMode.STANDARD_NON_ROOT -> modeStandardSubtitle
    }
}

object AppTranslations {
    private val REGEX_PAT_0 = Regex("""^\[Shizuku\] Lỗi yêu cầu quyền: (.*)$""")
    private val REGEX_PAT_1 = Regex("""^Đã nạp: (.*)$""")
    private val REGEX_PAT_2 = Regex("""^\[Export\] Error saving file: (.*)$""")
    private val REGEX_PAT_3 = Regex("""^\[Export\] Exported patched APK successfully \((.*) KB\)\.$""")
    private val REGEX_PAT_4 = Regex("""^\[Storage\] Đã lưu APK đã patch vào bộ nhớ máy: (.*) \((.*) KB\)$""")
    private val REGEX_PAT_5 = Regex("""^\[Storage\] Đã lưu APK đã patch vào: (.*)$""")
    private val REGEX_PAT_6 = Regex("""^\[Storage\] Cảnh báo khi lưu vào Download/AAMaKer: (.*)$""")
    private val REGEX_PAT_7 = Regex("""^--- Bắt đầu cài đặt lên hệ thống với chế độ: (.*) ---$""")
    private val REGEX_PAT_8 = Regex("""^Ứng dụng gốc: (.*)$""")
    private val REGEX_PAT_9 = Regex("""^Chế độ Patch: (.*)$""")
    private val REGEX_PAT_10 = Regex("""^=== HOÀN TẤT PATCH TRONG (\d+)ms ===$""")
    private val REGEX_PAT_11 = Regex("""^File xuất ra: (.*)$""")
    private val REGEX_PAT_12 = Regex("""^\[Direct Install\] Phát hiện gói Bundle \((.*)\), đang trích xuất các APK gốc \(giữ nguyên chữ ký\)\.\.\.$""")
    private val REGEX_PAT_13 = Regex("""^\[Direct Install\] Cài đặt nguyên bản (\d+) file APK \(giữ nguyên chữ ký số gốc\) cho gói (.*)\.\.\.$""")
    private val REGEX_PAT_14 = Regex("""^\[Direct Install\] Lỗi chuẩn bị APK gốc: (.*)$""")
    private val REGEX_PAT_15 = Regex("""^Lỗi đọc file APK gốc: (.*)$""")
    private val REGEX_PAT_16 = Regex("""^\[Merger\] Phát hiện gói Bundle \((.*)\), đang giải nén các APK thành phần\.\.\.$""")
    private val REGEX_PAT_17 = Regex("""^\[Merger\] Phát hiện (\d+) Split APKs, đang hợp nhất (?:cấu trúc nhị phân \(DEX \+ resources\.arsc \+ Native Libs \+ Assets\)|thành APK đơn)\.\.\.$""")
    private val REGEX_PAT_17B = Regex("""^\[Merger\] \+ Nạp APK nền \(base\): (.*)$""")
    private val REGEX_PAT_17C = Regex("""^\[Merger\] \+ Nạp Split APK: (.*)$""")
    private val REGEX_PAT_18 = Regex("""^\[Merger\] Đang gộp thành phần từ (.*)\.\.\.$""")
    private val REGEX_PAT_19 = Regex("""^\[Merger\] Đã hợp nhất (?:hoàn chỉnh (\d+) APK thành 1 module duy nhất \((\d+) DEX\)|thành công Split APKs \((.*) KB\))\.$""")
    private val REGEX_PAT_19B = Regex("""^\[Merger\] \+ Đã hợp nhất (\d+) thành phần Feature Split vào AndroidManifest\.xml\.$""")
    private val REGEX_PAT_19C = Regex("""^\[Merger\] \+ Đã dọn thư mục ABI không đầy đủ 'lib/(.*)' \((\d+)/(\d+) thư viện \.so\) để tránh lỗi UnsatisfiedLinkError\.$""")
    private val REGEX_PAT_20 = Regex("""^\[Merger\] \+ (?:Đã tích hợp tài nguyên OBB: (.*) -> (.*)|Gộp dữ liệu OBB: (.*))$""")
    private val REGEX_PAT_21 = Regex("""^\[Patcher\] \+ Đã kích hoạt chế độ Parked App \(appCategory=game, CAR_LAUNCHER & distractionOptimized\) cho (\d+) Launcher Activity\.$""")
    private val REGEX_PAT_21B = Regex("""^\[Patcher\] \+ Đã chuẩn hóa (\d+) bảng tài nguyên nén \(Sparse/Compact TypeBlock\) sang định dạng chuẩn để bảo toàn ID tài nguyên\.$""")
    private val REGEX_PAT_22 = Regex("""^\[Patcher\] \+ Đã tối ưu hóa phần cứng: bật hỗ trợ Gamepad=(true|false), gỡ bỏ bắt buộc cho (\d+) uses-feature\.$""")
    private val REGEX_PAT_23 = Regex("""^\[Patcher\] Đang đổi tên gói: (.*) -> (.*) \(cập nhật (?:Application, Component, Permission & Authorities|Manifest & Authorities)\)\.\.\.$""")
    private val REGEX_PAT_24 = Regex("""^\[Patcher\] Đang cập nhật tên hiển thị ứng dụng thành: (.*)$""")
    private val REGEX_PAT_25 = Regex("""^\[Patcher\] \+ Đã (?:gỡ bỏ|dọn sạch an toàn) (\d+) (?:thuộc tính/meta-data bắt buộc Split APK \(chống lỗi INSTALL_FAILED_MISSING_SPLIT\)|thuộc tính/meta-data Split APK \(bảo toàn tài nguyên dùng chung\))\.$""")
    private val REGEX_PAT_25B = Regex("""^\[Patcher\] \+ Đã vô hiệu hóa (\d+) thành phần kiểm tra giấy phép Play Licensing\.$""")
    private val REGEX_PAT_26 = Regex("""^\[Patcher\] \+ Thêm quyền: (.*)$""")
    private val REGEX_PAT_27 = Regex("""^\[Patcher\] APK đã có sẵn khai báo '(.*)' \(@?([0-9a-fA-FxX]+)\)\.$""")
    private val REGEX_PAT_28 = Regex("""^\[Patcher\] Đang tạo Binary XML '(.*)' và đăng ký vào bảng resources\.arsc\.\.\.$""")
    private val REGEX_PAT_29 = Regex("""^\[Patcher\] \+ Đã cấp phát Resource ID @?([0-9a-fA-FxX]+) -> (.*)$""")
    private val REGEX_PAT_30 = Regex("""^\[Patcher\] \+ Đã gắn (.*)$""")
    private val REGEX_PAT_31 = Regex("""^\[Patcher\] Đang tiêm (.*) vào AndroidManifest\.xml\.\.\.$""")
    private val REGEX_PAT_32 = Regex("""^\[Patcher\] \+ Đã tiêm cờ mở khóa khi xe di chuyển \(distractionOptimized\) cho toàn bộ (\d+) Activity\.$""")
    private val REGEX_PAT_33 = Regex("""^\[Patcher\] \+ Đã tiêm (?:Universal AA Runtime DEX thành|Bootstrap Service DEX cô lập thành) '(.*)' \((.*)(?: KB| bytes — không xung đột AndroidX/Kotlin/Guava)\)\.$""")
    private val REGEX_PAT_33B = Regex("""^\[Patcher\] \+ Đã đóng gói (\d+) phân đoạn Isolated Car Runtime vào assets/aa_car_res\.dat\.$""")
    private val REGEX_PAT_34 = Regex("""^\[Patcher\] Cảnh báo khi tiêm DEX: (.*)$""")
    private val REGEX_PAT_SIGNER_PREPARE = Regex("""^\[Signer\] Đang chuẩn bị khóa RSA-2048 & chứng chỉ X\.509 để ký APK \(Scheme V1 \+ V2 \+ V3(?:, minSdk=(\d+))?\)\.\.\.$""")
    private val REGEX_PAT_SIGNATURE_WARNING = Regex("""^\[Patcher\] ! Cảnh báo: Phát hiện cơ chế kiểm tra chữ ký gốc tầng Native/C\+\+ \((.*)\) — (.*)$""")
    private val REGEX_PAT_35 = Regex("""^\[Signer\] Đã ký APK hoàn tất \((.*) KB\)\.$""")
    private val REGEX_PAT_36 = Regex("""^\[Root\] Đang sao chép APK vào (.*)\.\.\.$""")
    private val REGEX_PAT_37 = Regex("""^Không thể chép APK vào /data/local/tmp: (.*)$""")
    private val REGEX_PAT_38 = Regex("""^\[Root\] Kết quả pm install: (.*)$""")
    private val REGEX_PAT_39 = Regex("""^Lỗi pm install qua Root: (.*)$""")
    private val REGEX_PAT_40 = Regex("""^\[Root\] Đang cấp quyền SYSTEM_ALERT_WINDOW, PROJECT_MEDIA & bật Touch Bridge cho (.*)\.\.\.$""")
    private val REGEX_PAT_41 = Regex("""^\[Root\] Ngoại lệ: (.*)$""")
    private val REGEX_PAT_42 = Regex("""^Lỗi cài đặt qua Root: (.*)$""")
    private val REGEX_PAT_43 = Regex("""^\[Shizuku\] Thực thi: (.*)$""")
    private val REGEX_PAT_44 = Regex("""^\[Shizuku\] Kết quả: (.*)$""")
    private val REGEX_PAT_45 = Regex("""^Cài đặt qua Shizuku thất bại: (.*)$""")
    private val REGEX_PAT_46 = Regex("""^\[Shizuku\] Ngoại lệ: (.*)$""")
    private val REGEX_PAT_47 = Regex("""^Lỗi thực thi Shizuku: (.*)$""")
    private val REGEX_PAT_48 = Regex("""^\[Non-Root Session\] Đã tạo Session #(\d+), đang ghi dữ liệu APK \((.*) KB\)\.\.\.$""")
    private val REGEX_PAT_49 = Regex("""^\[Non-Root Session\] Cài đặt hoàn tất: (.*)$""")
    private val REGEX_PAT_50 = Regex("""^\[Non-Root Session\] Lỗi: (.*)$""")
    private val REGEX_PAT_51 = Regex("""^Lỗi cài đặt qua Session API: (.*)$""")
    private val REGEX_PAT_52 = Regex("""^Cài đặt thành công gói (.*)$""")
    private val REGEX_PAT_53 = Regex("""^Cài đặt thất bại \(Mã (-?\d+)\): (.*)$""")
    private val REGEX_PAT_54 = Regex("""^([A-Za-z0-9_.$]+): Lỗi không xác định khi patch APK$""")
    private val REGEX_PAT_55 = Regex("""^(\[LỖI NGHIÊM TRỌNG\] )?([A-Za-z0-9_.$]+): (.*)$""")

    fun forLanguage(lang: AppLanguage): AppStrings = when (lang) {
        AppLanguage.EN -> EN_STRINGS
        AppLanguage.VI -> VI_STRINGS
        AppLanguage.ZH -> ZH_STRINGS
        AppLanguage.ES -> ES_STRINGS
        AppLanguage.HI -> HI_STRINGS
        AppLanguage.AR -> AR_STRINGS
        AppLanguage.PT -> PT_STRINGS
        AppLanguage.RU -> RU_STRINGS
        AppLanguage.JA -> JA_STRINGS
        AppLanguage.DE -> DE_STRINGS
        AppLanguage.FR -> FR_STRINGS
    }

    private val EN_STRINGS = AppStrings(
        language = AppLanguage.EN,
        appSubtitle = "Android Auto Patcher & Installer",
        refreshTooltip = "Refresh",
        languageDialogTitle = "Language",
        closeButton = "Close",
        navSelectApp = "Apps",
        navPatchInstall = "Patch & Install",
        navEnvironment = "Environment",
        bannerExternalTitle = "Import APK / APKS / XAPK",
        bannerExternalSubtitle = "Select an APK file to patch for AA or install directly with Google Play source",
        chooseFileButton = "Patch File",
        chooseFileDirectInstallButton = "Install Original (Play Source)",
        searchPlaceholder = "Search app or package...",
        clearSearch = "Clear",
        filterExistingAAChip = "AA Ready",
        filterSystemAppsChip = "System Apps",
        badgeExistingAA = "AA Ready",
        badgeNeedInjectAA = "Standard App",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "Source: ",
        badgeSourceSideload = "Sideload",
        splitsBadgeFormat = { count -> "$count Splits" },
        emptySelectionTitle = "No app or APK selected",
        backToAppListButton = "Select App / APK",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. Patch Mode",
        strategyDirectNoPatchTitle = "Direct Install (No Patch)",
        strategyDirectNoPatchDesc = "Keep original APK & signature. Install as Google Play source.",
        strategySpoofTitle = "Unlock Existing AA App",
        strategySpoofDesc = "Fix AA config & clone package to bypass blocks.",
        strategyUniversalTitle = "Universal AA Wrapper",
        strategyUniversalDesc = "Convert any regular app to run on car screen with VirtualDisplay & touch.",
        outputPackageLabel = "Package Name (Change to bypass block)",
        randomizeTooltip = "Randomize",
        carDisplayNameLabel = "Car Screen Name",
        automotiveDescConfigLabel = "AA Capabilities:",
        projectionFullScreenLabel = "Full Screen (Projection)",
        carTemplatesLabel = "Car Templates",
        carLauncherCategoryLabel = "Launcher Category:",
        categoryNavigation = "Navigation",
        categoryIot = "IoT / Dashboard",
        categoryMedia = "Media / Audio",
        categoryPoi = "Points of Interest (POI)",
        touchModeRootLabel = "Root Shell Touch (su)",
        touchModeRootDesc = "Default OFF. Enable only if touch requires root.",
        section2InstallModeTitle = "2. Install Method (Play Store Spoof)",
        modeShizukuTitle = "Shizuku (Recommended)",
        modeShizukuSubtitle = "Install via ADB (UID 2000) as Google Play source (Android 11+)",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "Direct install as Google Play & auto-whitelist",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "Standard install, bypass AA checks via Xposed module",
        modeStandardTitle = "Standard Non-Root",
        modeStandardSubtitle = "Session installer spoof (Best for Android 10–13)",
        processingButton = "Processing...",
        directInstallNoPatchButton = "Install Original APK",
        patchAndInstallButton = "Patch & Install",
        patchAndSaveButton = "Patch & Save APK",
        savedInStorageLabel = "📁 Saved at:",
        saveToDownloadButton = "Save to Download",
        chooseSaveFolderButton = "Save As...",
        reinstallLastPatchedFormat = { name -> "Reinstall ($name)" },
        consoleTitle = "Console Log",
        clearLogsButton = "Clear",
        readyToExecute = "Ready...",
        envHeaderTitle = "Environment Status",
        diagAndroidOsTitle = "Android OS",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "Installed (v$ver)" },
        diagGearheadNotInstalled = "Not installed",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "Authorized ✓",
        diagShizukuRunningNotGranted = "Running (Permission needed)",
        diagShizukuNotRunning = "Not running",
        diagGrantShizukuButton = "Grant Permission",
        diagRootTitle = "Root Access",
        diagRootAvailable = "Granted ✓",
        diagRootNotDetected = "No Root",
        diagXposedTitle = "LSPosed / Xposed Module",
        diagXposedActive = "Active ✓",
        diagXposedInactive = "Inactive",
        unknownSourcesGuideTitle = "Enable Unknown Sources in AA",
        unknownSourcesGuideSteps = "1. Open Android Auto Settings.\n" +
            "2. Tap 'Version' 10 times to enable Developer Mode.\n" +
            "3. Menu (⋮) -> 'Developer settings'.\n" +
            "4. Enable 'Unknown sources' & set 'Application mode' to 'Developer'.",
        openAASettingsButton = "Open AA Settings",
        recheckButton = "Refresh",
        statusPatchingApk = "Patching APK...",
        statusDirectInstallingApk = "Installing original APK...",
        statusPatchFailedFormat = { err -> "❌ Patch failed: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ Saved to: $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ Patched (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 Saved: $path" },
        statusSaveDownloadFailed = "❌ Failed to save to Download",
        statusExportedSuccess = "💾 Saved APK!",
        statusExportFailed = "❌ Failed to save APK."
    )

    private val VI_STRINGS = AppStrings(
        language = AppLanguage.VI,
        appSubtitle = "Patch & Cài đặt APK Android Auto",
        refreshTooltip = "Làm mới",
        languageDialogTitle = "Ngôn ngữ",
        closeButton = "Đóng",
        navSelectApp = "Ứng dụng",
        navPatchInstall = "Patch & Cài",
        navEnvironment = "Môi trường",
        bannerExternalTitle = "Nhập file APK / APKS / XAPK",
        bannerExternalSubtitle = "Chọn file từ máy để patch AA hoặc cài gốc với nguồn Google Play",
        chooseFileButton = "Chọn file để Patch",
        chooseFileDirectInstallButton = "Cài APK gốc (Nguồn Play)",
        searchPlaceholder = "Tìm tên app hoặc package...",
        clearSearch = "Xóa",
        filterExistingAAChip = "Có sẵn AA",
        filterSystemAppsChip = "App hệ thống",
        badgeExistingAA = "Có sẵn AA",
        badgeNeedInjectAA = "App thường",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "Nguồn: ",
        badgeSourceSideload = "Ngoài luồng",
        splitsBadgeFormat = { count -> "$count Split" },
        emptySelectionTitle = "Chưa chọn ứng dụng hoặc APK",
        backToAppListButton = "Chọn Ứng dụng / APK",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. Chế độ xử lý",
        strategyDirectNoPatchTitle = "Cài nguyên gốc (Không Patch)",
        strategyDirectNoPatchDesc = "Giữ nguyên APK & chữ ký gốc. Chỉ gán nguồn cài đặt Google Play.",
        strategySpoofTitle = "Mở khóa App AA sẵn có",
        strategySpoofDesc = "Sửa cấu hình AA & đổi tên gói để vượt chặn.",
        strategyUniversalTitle = "Chuyển App thường sang AA",
        strategyUniversalDesc = "Đưa mọi ứng dụng (Video, Bản đồ, Web...) lên màn hình xe, hỗ trợ cảm ứng.",
        outputPackageLabel = "Tên gói (Đổi để tránh bị chặn)",
        randomizeTooltip = "Ngẫu nhiên",
        carDisplayNameLabel = "Tên hiển thị trên xe",
        automotiveDescConfigLabel = "Quyền hiển thị AA:",
        projectionFullScreenLabel = "Toàn màn hình (Projection)",
        carTemplatesLabel = "Giao diện xe (Templates)",
        carLauncherCategoryLabel = "Danh mục trên xe:",
        categoryNavigation = "Dẫn đường (Navigation)",
        categoryIot = "Điều khiển (IoT / Dashboard)",
        categoryMedia = "Đa phương tiện (Media)",
        categoryPoi = "Điểm đến (POI)",
        touchModeRootLabel = "Cảm ứng qua Root (su)",
        touchModeRootDesc = "Mặc định TẮT. Chỉ bật khi app không nhận cảm ứng thường.",
        section2InstallModeTitle = "2. Phương thức cài đặt (Nguồn Google Play)",
        modeShizukuTitle = "Shizuku (Khuyên dùng)",
        modeShizukuSubtitle = "Cài qua ADB (UID 2000), gán nguồn Play chuẩn cho Android 11+",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "Cài trực tiếp với nguồn Google Play & tự cấp quyền",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "Cài thường, mở khóa trực tiếp trong AA qua module Xposed",
        modeStandardTitle = "Không Root (Cơ bản)",
        modeStandardSubtitle = "Giả lập nguồn Play qua Session (Tốt nhất cho Android 10–13)",
        processingButton = "Đang xử lý...",
        directInstallNoPatchButton = "Cài APK gốc",
        patchAndInstallButton = "Patch & Cài đặt",
        patchAndSaveButton = "Patch & Lưu APK",
        savedInStorageLabel = "📁 Đã lưu tại:",
        saveToDownloadButton = "Lưu vào Download",
        chooseSaveFolderButton = "Lưu thành...",
        reinstallLastPatchedFormat = { name -> "Cài lại ($name)" },
        consoleTitle = "Nhật ký xử lý",
        clearLogsButton = "Xóa",
        readyToExecute = "Sẵn sàng...",
        envHeaderTitle = "Trạng thái hệ thống",
        diagAndroidOsTitle = "Hệ điều hành",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "Đã cài (v$ver)" },
        diagGearheadNotInstalled = "Chưa cài đặt",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "Đã cấp quyền ✓",
        diagShizukuRunningNotGranted = "Đang chạy (Chưa cấp quyền)",
        diagShizukuNotRunning = "Chưa chạy",
        diagGrantShizukuButton = "Cấp quyền",
        diagRootTitle = "Quyền Root",
        diagRootAvailable = "Đã cấp quyền ✓",
        diagRootNotDetected = "Không có Root",
        diagXposedTitle = "Module LSPosed / Xposed",
        diagXposedActive = "Đang bật ✓",
        diagXposedInactive = "Chưa bật",
        unknownSourcesGuideTitle = "Cách bật Nguồn không xác định trong AA",
        unknownSourcesGuideSteps = "1. Mở cài đặt Android Auto.\n" +
            "2. Nhấn 10 lần vào 'Phiên bản' để bật Chế độ nhà phát triển.\n" +
            "3. Chọn menu 3 chấm (⋮) -> 'Cài đặt cho nhà phát triển'.\n" +
            "4. Bật 'Nguồn không xác định' & chọn Chế độ ứng dụng 'Nhà phát triển'.",
        openAASettingsButton = "Mở cài đặt Android Auto",
        recheckButton = "Kiểm tra lại",
        statusPatchingApk = "Đang patch APK...",
        statusDirectInstallingApk = "Đang cài APK gốc...",
        statusPatchFailedFormat = { err -> "❌ Lỗi: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ Đã lưu: $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ Hoàn tất (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 Đã lưu: $path" },
        statusSaveDownloadFailed = "❌ Lỗi lưu vào Download",
        statusExportedSuccess = "💾 Đã lưu APK!",
        statusExportFailed = "❌ Lỗi khi lưu APK."
    )

    private val ZH_STRINGS = AppStrings(
        language = AppLanguage.ZH,
        appSubtitle = "Android Auto 修补与安装工具",
        refreshTooltip = "刷新",
        languageDialogTitle = "选择语言",
        closeButton = "关闭",
        navSelectApp = "应用",
        navPatchInstall = "修补安装",
        navEnvironment = "环境状态",
        bannerExternalTitle = "导入 APK / APKS / XAPK",
        bannerExternalSubtitle = "选择本地安装包以注入 AA 功能，或伪装 Play 商店直接安装",
        chooseFileButton = "选择文件修补",
        chooseFileDirectInstallButton = "安装原版 (伪装 Play)",
        searchPlaceholder = "搜索应用名或包名...",
        clearSearch = "清除",
        filterExistingAAChip = "已支持 AA",
        filterSystemAppsChip = "系统应用",
        badgeExistingAA = "已支持 AA",
        badgeNeedInjectAA = "普通应用",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "来源: ",
        badgeSourceSideload = "第三方",
        splitsBadgeFormat = { count -> "$count 分包" },
        emptySelectionTitle = "未选择应用或 APK",
        backToAppListButton = "选择应用 / APK",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. 处理模式",
        strategyDirectNoPatchTitle = "原版直接安装 (不修补)",
        strategyDirectNoPatchDesc = "保留原始 APK 与签名，仅伪装为 Google Play 来源安装。",
        strategySpoofTitle = "解锁现有 AA 应用",
        strategySpoofDesc = "修复 AA 配置并改包名以绕过黑名单。",
        strategyUniversalTitle = "普通应用转 AA 车载应用",
        strategyUniversalDesc = "将任意普通应用 (视频、地图、浏览器等) 投射到车机并支持触控。",
        outputPackageLabel = "输出包名 (修改以绕过黑名单)",
        randomizeTooltip = "随机后缀",
        carDisplayNameLabel = "车机显示名称",
        automotiveDescConfigLabel = "AA 权限配置:",
        projectionFullScreenLabel = "全屏投射 (Projection)",
        carTemplatesLabel = "车载模板 (Templates)",
        carLauncherCategoryLabel = "车机桌面分类:",
        categoryNavigation = "导航 (Navigation)",
        categoryIot = "控制面板 (IoT)",
        categoryMedia = "媒体音频 (Media)",
        categoryPoi = "兴趣点 (POI)",
        touchModeRootLabel = "Root 触控注入 (su)",
        touchModeRootDesc = "默认关闭。仅在常规触控无效时开启。",
        section2InstallModeTitle = "2. 安装方式 (伪装 Google Play)",
        modeShizukuTitle = "Shizuku (推荐)",
        modeShizukuSubtitle = "通过 ADB (UID 2000) 安装，支持 Android 11+",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "直接以 Play 来源安装并自动授权",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "常规安装，通过内置 Xposed 模块绕过 AA 检测",
        modeStandardTitle = "免 Root 标准安装",
        modeStandardSubtitle = "Session 伪装安装 (适用于 Android 10–13)",
        processingButton = "处理中...",
        directInstallNoPatchButton = "安装原版 APK",
        patchAndInstallButton = "修补并安装",
        patchAndSaveButton = "修补并保存",
        savedInStorageLabel = "📁 已保存至:",
        saveToDownloadButton = "存至 Download",
        chooseSaveFolderButton = "另存为...",
        reinstallLastPatchedFormat = { name -> "重新安装 ($name)" },
        consoleTitle = "处理日志",
        clearLogsButton = "清空",
        readyToExecute = "就绪...",
        envHeaderTitle = "系统环境状态",
        diagAndroidOsTitle = "Android 系统",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "已安装 (v$ver)" },
        diagGearheadNotInstalled = "未安装",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "已授权 ✓",
        diagShizukuRunningNotGranted = "运行中 (未授权)",
        diagShizukuNotRunning = "未运行",
        diagGrantShizukuButton = "授予权限",
        diagRootTitle = "Root 权限",
        diagRootAvailable = "已授权 ✓",
        diagRootNotDetected = "无 Root",
        diagXposedTitle = "LSPosed / Xposed 模块",
        diagXposedActive = "已启用 ✓",
        diagXposedInactive = "未启用",
        unknownSourcesGuideTitle = "开启 AA“未知来源”步骤",
        unknownSourcesGuideSteps = "1. 打开 Android Auto 设置。\n" +
            "2. 连续点击底部“版本”10 次开启开发者模式。\n" +
            "3. 点击右上角菜单 (⋮) -> “开发者设置”。\n" +
            "4. 勾选“未知来源”，将“应用模式”设为“开发者”。",
        openAASettingsButton = "打开 AA 设置",
        recheckButton = "重新检测",
        statusPatchingApk = "正在修补...",
        statusDirectInstallingApk = "正在安装原版 APK...",
        statusPatchFailedFormat = { err -> "❌ 失败: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ 已保存: $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ 修补完成 (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 已保存: $path" },
        statusSaveDownloadFailed = "❌ 保存到 Download 失败",
        statusExportedSuccess = "💾 APK 已保存！",
        statusExportFailed = "❌ 保存 APK 失败。"
    )

    private val ES_STRINGS = AppStrings(
        language = AppLanguage.ES,
        appSubtitle = "Parcheador e Instalador para Android Auto",
        refreshTooltip = "Actualizar",
        languageDialogTitle = "Idioma",
        closeButton = "Cerrar",
        navSelectApp = "Apps",
        navPatchInstall = "Parchear",
        navEnvironment = "Entorno",
        bannerExternalTitle = "Importar APK / APKS / XAPK",
        bannerExternalSubtitle = "Elige un archivo APK para parchear para AA o instalar como Google Play",
        chooseFileButton = "Elegir para Parchear",
        chooseFileDirectInstallButton = "Instalar Original (Google Play)",
        searchPlaceholder = "Buscar app o paquete...",
        clearSearch = "Borrar",
        filterExistingAAChip = "Con AA",
        filterSystemAppsChip = "Apps del Sistema",
        badgeExistingAA = "Con AA",
        badgeNeedInjectAA = "App Normal",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "Origen: ",
        badgeSourceSideload = "Externo",
        splitsBadgeFormat = { count -> "$count Splits" },
        emptySelectionTitle = "Ninguna app o APK seleccionada",
        backToAppListButton = "Elegir App / APK",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. Modo de Parcheo",
        strategyDirectNoPatchTitle = "Instalación Directa (Sin Parche)",
        strategyDirectNoPatchDesc = "Mantiene APK y firma original. Instala con origen Google Play.",
        strategySpoofTitle = "Desbloquear App AA Existente",
        strategySpoofDesc = "Repara config AA y clona paquete para evitar bloqueo.",
        strategyUniversalTitle = "Convertir App Normal a AA",
        strategyUniversalDesc = "Proyecta cualquier app (Video, Mapas, Web...) en la pantalla del coche con control táctil.",
        outputPackageLabel = "Nombre de Paquete (Cambiar para evitar bloqueo)",
        randomizeTooltip = "Aleatorio",
        carDisplayNameLabel = "Nombre en el Coche",
        automotiveDescConfigLabel = "Permisos AA:",
        projectionFullScreenLabel = "Pantalla Completa (Projection)",
        carTemplatesLabel = "Plantillas (Templates)",
        carLauncherCategoryLabel = "Categoría en el Coche:",
        categoryNavigation = "Navegación",
        categoryIot = "Panel / Utilidades (IoT)",
        categoryMedia = "Multimedia (Media)",
        categoryPoi = "Puntos de Interés (POI)",
        touchModeRootLabel = "Toque por Root (su)",
        touchModeRootDesc = "Desactivado por defecto. Activar solo si el toque normal falla.",
        section2InstallModeTitle = "2. Método de Instalación (Origen Play Store)",
        modeShizukuTitle = "Shizuku (Recomendado)",
        modeShizukuSubtitle = "Instala vía ADB (UID 2000) como Google Play (Android 11+)",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "Instalación directa como Google Play y lista blanca",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "Instalación normal, omite bloqueos en AA vía Xposed",
        modeStandardTitle = "Sin Root Estándar",
        modeStandardSubtitle = "Simula origen Play vía Session (Android 10–13)",
        processingButton = "Procesando...",
        directInstallNoPatchButton = "Instalar APK Original",
        patchAndInstallButton = "Parchear e Instalar",
        patchAndSaveButton = "Parchear y Guardar",
        savedInStorageLabel = "📁 Guardado en:",
        saveToDownloadButton = "Guardar en Descargas",
        chooseSaveFolderButton = "Guardar como...",
        reinstallLastPatchedFormat = { name -> "Reinstalar ($name)" },
        consoleTitle = "Registro",
        clearLogsButton = "Borrar",
        readyToExecute = "Listo...",
        envHeaderTitle = "Estado del Entorno",
        diagAndroidOsTitle = "Sistema Android",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "Instalado (v$ver)" },
        diagGearheadNotInstalled = "No instalado",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "Autorizado ✓",
        diagShizukuRunningNotGranted = "Activo (Falta permiso)",
        diagShizukuNotRunning = "Inactivo",
        diagGrantShizukuButton = "Dar Permiso",
        diagRootTitle = "Acceso Root",
        diagRootAvailable = "Concedido ✓",
        diagRootNotDetected = "Sin Root",
        diagXposedTitle = "Módulo LSPosed / Xposed",
        diagXposedActive = "Activo ✓",
        diagXposedInactive = "Inactivo",
        unknownSourcesGuideTitle = "Activar Orígenes Desconocidos en AA",
        unknownSourcesGuideSteps = "1. Abre los ajustes de Android Auto.\n" +
            "2. Toca 'Versión' 10 veces para activar el Modo Desarrollador.\n" +
            "3. Menú (⋮) -> 'Ajustes de desarrollador'.\n" +
            "4. Activa 'Orígenes desconocidos' y pon Modo en 'Desarrollador'.",
        openAASettingsButton = "Ajustes de Android Auto",
        recheckButton = "Actualizar",
        statusPatchingApk = "Parcheando APK...",
        statusDirectInstallingApk = "Instalando APK original...",
        statusPatchFailedFormat = { err -> "❌ Error: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ Guardado: $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ Parcheado (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 Guardado: $path" },
        statusSaveDownloadFailed = "❌ Error al guardar en Descargas",
        statusExportedSuccess = "💾 ¡APK guardado!",
        statusExportFailed = "❌ Error al guardar APK."
    )

    private val HI_STRINGS = AppStrings(
        language = AppLanguage.HI,
        appSubtitle = "Android Auto पैचर और इंस्टॉलर",
        refreshTooltip = "रीफ़्रेश",
        languageDialogTitle = "भाषा (Language)",
        closeButton = "बंद करें",
        navSelectApp = "ऐप्स",
        navPatchInstall = "पैच और इंस्टॉल",
        navEnvironment = "वातावरण",
        bannerExternalTitle = "APK / APKS / XAPK आयात करें",
        bannerExternalSubtitle = "AA के लिए पैच करने या Google Play स्रोत से इंस्टॉल करने के लिए फ़ाइल चुनें",
        chooseFileButton = "पैच के लिए चुनें",
        chooseFileDirectInstallButton = "मूल APK इंस्टॉल (Play स्रोत)",
        searchPlaceholder = "ऐप या पैकेज खोजें...",
        clearSearch = "साफ़ करें",
        filterExistingAAChip = "AA तैयार",
        filterSystemAppsChip = "सिस्टम ऐप्स",
        badgeExistingAA = "AA तैयार",
        badgeNeedInjectAA = "सामान्य ऐप",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "स्रोत: ",
        badgeSourceSideload = "बाहरी",
        splitsBadgeFormat = { count -> "$count स्प्लिट" },
        emptySelectionTitle = "कोई ऐप या APK नहीं चुना गया",
        backToAppListButton = "ऐप / APK चुनें",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. पैच मोड",
        strategyDirectNoPatchTitle = "सीधा इंस्टॉल (बिना पैच)",
        strategyDirectNoPatchDesc = "मूल APK और हस्ताक्षर सुरक्षित रखता है। केवल Google Play स्रोत सेट करता है।",
        strategySpoofTitle = "मौजूदा AA ऐप अनलॉक करें",
        strategySpoofDesc = "AA कॉन्फ़िग सुधारता है और ब्लॉक से बचने हेतु पैकेज बदलता है।",
        strategyUniversalTitle = "सामान्य ऐप को AA में बदलें",
        strategyUniversalDesc = "किसी भी ऐप (वीडियो, मैप्स, ब्राउज़र) को टच कंट्रोल के साथ कार स्क्रीन पर चलाएं।",
        outputPackageLabel = "पैकेज नाम (ब्लॉक से बचने के लिए बदलें)",
        randomizeTooltip = "रैंडम",
        carDisplayNameLabel = "कार स्क्रीन पर नाम",
        automotiveDescConfigLabel = "AA अनुमतियां:",
        projectionFullScreenLabel = "फ़ुल स्क्रीन (Projection)",
        carTemplatesLabel = "कार टेम्पलेट",
        carLauncherCategoryLabel = "कार लॉन्चर श्रेणी:",
        categoryNavigation = "नेविगेशन (Navigation)",
        categoryIot = "डैशबोर्ड (IoT)",
        categoryMedia = "मीडिया (Media)",
        categoryPoi = "स्थान (POI)",
        touchModeRootLabel = "Root टच मोड (su)",
        touchModeRootDesc = "डिफ़ॉल्ट बंद। केवल तभी खोलें जब सामान्य टच काम न करे।",
        section2InstallModeTitle = "2. इंस्टॉल तरीका (Play Store स्रोत)",
        modeShizukuTitle = "Shizuku (अनुशंसित)",
        modeShizukuSubtitle = "ADB (UID 2000) द्वारा Google Play स्रोत से इंस्टॉल (Android 11+)",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "Google Play स्रोत से सीधा इंस्टॉल और अनुमति",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "सामान्य इंस्टॉल, Xposed मॉड्यूल से AA जांच बायपास",
        modeStandardTitle = "सामान्य नॉन-रूट",
        modeStandardSubtitle = "सेशन इंस्टॉलर ट्रिक (Android 10–13 के लिए)",
        processingButton = "प्रोसेस हो रहा है...",
        directInstallNoPatchButton = "मूल APK इंस्टॉल करें",
        patchAndInstallButton = "पैच और इंस्टॉल करें",
        patchAndSaveButton = "पैच और सहेजें",
        savedInStorageLabel = "📁 यहाँ सहेजा गया:",
        saveToDownloadButton = "Download में सहेजें",
        chooseSaveFolderButton = "फ़ोल्डर चुनें...",
        reinstallLastPatchedFormat = { name -> "पुनः इंस्टॉल ($name)" },
        consoleTitle = "लॉग कंसोल",
        clearLogsButton = "साफ़ करें",
        readyToExecute = "तैयार...",
        envHeaderTitle = "सिस्टम स्थिति",
        diagAndroidOsTitle = "Android सिस्टम",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "इंस्टॉल है (v$ver)" },
        diagGearheadNotInstalled = "इंस्टॉल नहीं है",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "अनुमति प्राप्त ✓",
        diagShizukuRunningNotGranted = "चालू है (अनुमति दें)",
        diagShizukuNotRunning = "बंद है",
        diagGrantShizukuButton = "अनुमति दें",
        diagRootTitle = "Root एक्सेस",
        diagRootAvailable = "उपलब्ध ✓",
        diagRootNotDetected = "Root नहीं है",
        diagXposedTitle = "LSPosed / Xposed मॉड्यूल",
        diagXposedActive = "सक्रिय ✓",
        diagXposedInactive = "निष्क्रिय",
        unknownSourcesGuideTitle = "AA में Unknown Sources चालू करें",
        unknownSourcesGuideSteps = "1. Android Auto सेटिंग्स खोलें।\n" +
            "2. डेवलपर मोड के लिए 'Version' पर 10 बार टैप करें।\n" +
            "3. मेनू (⋮) -> 'Developer settings' चुनें।\n" +
            "4. 'Unknown sources' चालू करें और मोड को 'Developer' रखें।",
        openAASettingsButton = "AA सेटिंग्स खोलें",
        recheckButton = "पुनः जांचें",
        statusPatchingApk = "पैच हो रहा है...",
        statusDirectInstallingApk = "मूल APK इंस्टॉल हो रहा है...",
        statusPatchFailedFormat = { err -> "❌ विफल: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ सहेजा गया: $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ पैच पूर्ण (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 सहेजा गया: $path" },
        statusSaveDownloadFailed = "❌ Download में सहेजना विफल",
        statusExportedSuccess = "💾 APK सहेजा गया!",
        statusExportFailed = "❌ APK सहेजने में त्रुटि।"
    )

    private val AR_STRINGS = AppStrings(
        language = AppLanguage.AR,
        appSubtitle = "أداة تعديل وتثبيت تطبيقات Android Auto",
        refreshTooltip = "تحديث",
        languageDialogTitle = "اللغة",
        closeButton = "إغلاق",
        navSelectApp = "التطبيقات",
        navPatchInstall = "تعديل وتثبيت",
        navEnvironment = "البيئة",
        bannerExternalTitle = "استيراد APK / APKS / XAPK",
        bannerExternalSubtitle = "اختر ملف APK لتعديله لنظام AA أو تثبيته بمصدر Google Play",
        chooseFileButton = "اختيار للتعديل",
        chooseFileDirectInstallButton = "تثبيت الأصلي (مصدر Play)",
        searchPlaceholder = "بحث عن تطبيق أو حزمة...",
        clearSearch = "مسح",
        filterExistingAAChip = "يدعم AA",
        filterSystemAppsChip = "تطبيقات النظام",
        badgeExistingAA = "يدعم AA",
        badgeNeedInjectAA = "تطبيق عادي",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "المصدر: ",
        badgeSourceSideload = "خارجي",
        splitsBadgeFormat = { count -> "$count أجزاء" },
        emptySelectionTitle = "لم يتم اختيار تطبيق أو APK",
        backToAppListButton = "اختر تطبيق / APK",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. وضع التعديل",
        strategyDirectNoPatchTitle = "تثبيت مباشر (بدون تعديل)",
        strategyDirectNoPatchDesc = "يحفظ APK والتوقيع الأصلي. يثبت بمصدر Google Play فقط.",
        strategySpoofTitle = "تفعيل تطبيق AA حالي",
        strategySpoofDesc = "يصلح إعدادات AA ويغير اسم الحزمة لتجاوز الحظر.",
        strategyUniversalTitle = "تحويل تطبيق عادي إلى AA",
        strategyUniversalDesc = "عرض أي تطبيق عادي (فيديو، خرائط، متصفح) على شاشة السيارة مع دعم اللمس.",
        outputPackageLabel = "اسم الحزمة (غيّره لتجاوز الحظر)",
        randomizeTooltip = "عشوائي",
        carDisplayNameLabel = "الاسم على شاشة السيارة",
        automotiveDescConfigLabel = "صلاحيات AA:",
        projectionFullScreenLabel = "شاشة كاملة (Projection)",
        carTemplatesLabel = "قوالب السيارة",
        carLauncherCategoryLabel = "الفئة في السيارة:",
        categoryNavigation = "الملاحة (Navigation)",
        categoryIot = "لوحة التحكم (IoT)",
        categoryMedia = "الوسائط (Media)",
        categoryPoi = "نقاط الاهتمام (POI)",
        touchModeRootLabel = "اللمس عبر الروت (su)",
        touchModeRootDesc = "معطل افتراضياً. فعّله فقط إذا لم يعمل اللمس العادي.",
        section2InstallModeTitle = "2. طريقة التثبيت (مصدر Play Store)",
        modeShizukuTitle = "Shizuku (موصى به)",
        modeShizukuSubtitle = "تثبيت عبر ADB (UID 2000) بمصدر Google Play (أندرويد 11+)",
        modeRootTitle = "روت (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "تثبيت مباشر بمصدر Google Play ومنح الصلاحيات",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "تثبيت عادي وتجاوز الفحص عبر وحدة Xposed",
        modeStandardTitle = "بدون روت (قياسي)",
        modeStandardSubtitle = "محاكاة المصدر عبر الجلسة (لأندرويد 10–13)",
        processingButton = "جاري المعالجة...",
        directInstallNoPatchButton = "تثبيت APK الأصلي",
        patchAndInstallButton = "تعديل وتثبيت",
        patchAndSaveButton = "تعديل وحفظ",
        savedInStorageLabel = "📁 تم الحفظ في:",
        saveToDownloadButton = "حفظ في التنزيلات",
        chooseSaveFolderButton = "حفظ باسم...",
        reinstallLastPatchedFormat = { name -> "إعادة تثبيت ($name)" },
        consoleTitle = "السجل",
        clearLogsButton = "مسح",
        readyToExecute = "جاهز...",
        envHeaderTitle = "حالة البيئة",
        diagAndroidOsTitle = "نظام أندرويد",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "مثبت (v$ver)" },
        diagGearheadNotInstalled = "غير مثبت",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "مصرح ✓",
        diagShizukuRunningNotGranted = "يعمل (يحتاج صلاحية)",
        diagShizukuNotRunning = "متوقف",
        diagGrantShizukuButton = "منح الصلاحية",
        diagRootTitle = "صلاحية الروت",
        diagRootAvailable = "متاح ✓",
        diagRootNotDetected = "لا يوجد روت",
        diagXposedTitle = "وحدة LSPosed / Xposed",
        diagXposedActive = "مفعلة ✓",
        diagXposedInactive = "غير مفعلة",
        unknownSourcesGuideTitle = "تفعيل المصادر غير المعروفة في AA",
        unknownSourcesGuideSteps = "1. افتح إعدادات Android Auto.\n" +
            "2. اضغط 10 مرات على 'الإصدار' لتفعيل وضع المطور.\n" +
            "3. القائمة (⋮) -> 'إعدادات المطور'.\n" +
            "4. فعّل 'مصادر غير معروفة' واجعل وضع التطبيق 'مطور'.",
        openAASettingsButton = "إعدادات Android Auto",
        recheckButton = "تحديث",
        statusPatchingApk = "جاري التعديل...",
        statusDirectInstallingApk = "جاري تثبيت APK الأصلي...",
        statusPatchFailedFormat = { err -> "❌ فشل: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ تم الحفظ: $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ تم التعديل (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 تم الحفظ: $path" },
        statusSaveDownloadFailed = "❌ تعذر الحفظ في التنزيلات",
        statusExportedSuccess = "💾 تم حفظ APK!",
        statusExportFailed = "❌ فشل حفظ APK."
    )

    private val PT_STRINGS = AppStrings(
        language = AppLanguage.PT,
        appSubtitle = "Patcher e Instalador para Android Auto",
        refreshTooltip = "Atualizar",
        languageDialogTitle = "Idioma",
        closeButton = "Fechar",
        navSelectApp = "Apps",
        navPatchInstall = "Patch e Instalar",
        navEnvironment = "Ambiente",
        bannerExternalTitle = "Importar APK / APKS / XAPK",
        bannerExternalSubtitle = "Escolha um APK para aplicar patch AA ou instalar como Google Play",
        chooseFileButton = "Escolher para Patch",
        chooseFileDirectInstallButton = "Instalar Original (Google Play)",
        searchPlaceholder = "Buscar app ou pacote...",
        clearSearch = "Limpar",
        filterExistingAAChip = "Com AA",
        filterSystemAppsChip = "Apps do Sistema",
        badgeExistingAA = "Com AA",
        badgeNeedInjectAA = "App Comum",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "Origem: ",
        badgeSourceSideload = "Externo",
        splitsBadgeFormat = { count -> "$count Splits" },
        emptySelectionTitle = "Nenhum app ou APK selecionado",
        backToAppListButton = "Escolher App / APK",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. Modo de Patch",
        strategyDirectNoPatchTitle = "Instalação Direta (Sem Patch)",
        strategyDirectNoPatchDesc = "Mantém APK e assinatura original. Instala como Google Play.",
        strategySpoofTitle = "Desbloquear App AA Existente",
        strategySpoofDesc = "Corrige config AA e clona pacote para evitar bloqueio.",
        strategyUniversalTitle = "Converter App Comum para AA",
        strategyUniversalDesc = "Projeta qualquer app (Vídeo, Mapas, Web...) na tela do carro com toque.",
        outputPackageLabel = "Nome do Pacote (Mude para evitar bloqueio)",
        randomizeTooltip = "Aleatório",
        carDisplayNameLabel = "Nome na Tela do Carro",
        automotiveDescConfigLabel = "Permissões AA:",
        projectionFullScreenLabel = "Tela Cheia (Projection)",
        carTemplatesLabel = "Templates do Carro",
        carLauncherCategoryLabel = "Categoria no Carro:",
        categoryNavigation = "Navegação (Navigation)",
        categoryIot = "Painel / Utilitários (IoT)",
        categoryMedia = "Mídia / Áudio (Media)",
        categoryPoi = "Pontos de Interesse (POI)",
        touchModeRootLabel = "Toque via Root (su)",
        touchModeRootDesc = "Padrão DESATIVADO. Ative apenas se o toque normal falhar.",
        section2InstallModeTitle = "2. Método de Instalação (Origem Play Store)",
        modeShizukuTitle = "Shizuku (Recomendado)",
        modeShizukuSubtitle = "Instala via ADB (UID 2000) como Google Play (Android 11+)",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "Instalação direta como Google Play e liberação automática",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "Instalação normal, libera no AA via módulo Xposed",
        modeStandardTitle = "Sem Root Padrão",
        modeStandardSubtitle = "Simula origem Play via Session (Android 10–13)",
        processingButton = "Processando...",
        directInstallNoPatchButton = "Instalar APK Original",
        patchAndInstallButton = "Patch e Instalar",
        patchAndSaveButton = "Patch e Salvar",
        savedInStorageLabel = "📁 Salvo em:",
        saveToDownloadButton = "Salvar em Download",
        chooseSaveFolderButton = "Salvar como...",
        reinstallLastPatchedFormat = { name -> "Reinstalar ($name)" },
        consoleTitle = "Log de Execução",
        clearLogsButton = "Limpar",
        readyToExecute = "Pronto...",
        envHeaderTitle = "Status do Ambiente",
        diagAndroidOsTitle = "Sistema Android",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "Instalado (v$ver)" },
        diagGearheadNotInstalled = "Não instalado",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "Autorizado ✓",
        diagShizukuRunningNotGranted = "Ativo (Sem permissão)",
        diagShizukuNotRunning = "Inativo",
        diagGrantShizukuButton = "Permitir",
        diagRootTitle = "Acesso Root",
        diagRootAvailable = "Concedido ✓",
        diagRootNotDetected = "Sem Root",
        diagXposedTitle = "Módulo LSPosed / Xposed",
        diagXposedActive = "Ativo ✓",
        diagXposedInactive = "Inativo",
        unknownSourcesGuideTitle = "Ativar Fontes Desconhecidas no AA",
        unknownSourcesGuideSteps = "1. Abra as configurações do Android Auto.\n" +
            "2. Toque em 'Versão' 10 vezes para ativar o Modo Desenvolvedor.\n" +
            "3. Menu (⋮) -> 'Configurações do desenvolvedor'.\n" +
            "4. Marque 'Fontes desconhecidas' e mude Modo para 'Desenvolvedor'.",
        openAASettingsButton = "Configurações do AA",
        recheckButton = "Atualizar",
        statusPatchingApk = "Aplicando patch...",
        statusDirectInstallingApk = "Instalando APK original...",
        statusPatchFailedFormat = { err -> "❌ Erro: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ Salvo: $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ Concluído (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 Salvo: $path" },
        statusSaveDownloadFailed = "❌ Falha ao salvar em Download",
        statusExportedSuccess = "💾 APK salvo!",
        statusExportFailed = "❌ Falha ao salvar APK."
    )

    private val RU_STRINGS = AppStrings(
        language = AppLanguage.RU,
        appSubtitle = "Патчер и установщик для Android Auto",
        refreshTooltip = "Обновить",
        languageDialogTitle = "Язык",
        closeButton = "Закрыть",
        navSelectApp = "Приложения",
        navPatchInstall = "Патч",
        navEnvironment = "Система",
        bannerExternalTitle = "Импорт APK / APKS / XAPK",
        bannerExternalSubtitle = "Выберите файл APK для патча под AA или установки как из Google Play",
        chooseFileButton = "Выбрать для патча",
        chooseFileDirectInstallButton = "Установить оригинал (Play)",
        searchPlaceholder = "Поиск по имени или пакету...",
        clearSearch = "Очистить",
        filterExistingAAChip = "С поддержкой AA",
        filterSystemAppsChip = "Системные",
        badgeExistingAA = "Поддержка AA",
        badgeNeedInjectAA = "Обычное прил.",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "Источник: ",
        badgeSourceSideload = "Сторонний",
        splitsBadgeFormat = { count -> "$count части" },
        emptySelectionTitle = "Приложение или APK не выбраны",
        backToAppListButton = "Выбрать приложение / APK",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. Режим обработки",
        strategyDirectNoPatchTitle = "Прямая установка (Без патча)",
        strategyDirectNoPatchDesc = "Сохраняет APK и подпись. Устанавливает с источником Google Play.",
        strategySpoofTitle = "Разблокировка готового AA APK",
        strategySpoofDesc = "Исправляет конфиг AA и меняет имя пакета для обхода блокировки.",
        strategyUniversalTitle = "Конвертация обычного приложения в AA",
        strategyUniversalDesc = "Запуск любого приложения (Видео, Карты, Браузер) на экране авто с поддержкой нажатий.",
        outputPackageLabel = "Имя пакета (Измените для обхода блокировки)",
        randomizeTooltip = "Случайно",
        carDisplayNameLabel = "Имя на экране авто",
        automotiveDescConfigLabel = "Разрешения AA:",
        projectionFullScreenLabel = "Полный экран (Projection)",
        carTemplatesLabel = "Шаблоны (Templates)",
        carLauncherCategoryLabel = "Категория в авто:",
        categoryNavigation = "Навигация (Navigation)",
        categoryIot = "Панель / Утилиты (IoT)",
        categoryMedia = "Мультимедиа (Media)",
        categoryPoi = "Точки интереса (POI)",
        touchModeRootLabel = "Нажатия через Root (su)",
        touchModeRootDesc = "По умолчанию ВЫКЛ. Включайте, если обычные нажатия не работают.",
        section2InstallModeTitle = "2. Способ установки (Источник Google Play)",
        modeShizukuTitle = "Shizuku (Рекомендуется)",
        modeShizukuSubtitle = "Установка через ADB (UID 2000) как Google Play (Android 11+)",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "Прямая установка как Google Play и выдача разрешений",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "Обычная установка, обход проверки в AA через модуль Xposed",
        modeStandardTitle = "Без Root (Стандарт)",
        modeStandardSubtitle = "Подмена источника через сессию (Android 10–13)",
        processingButton = "Обработка...",
        directInstallNoPatchButton = "Установить оригинал",
        patchAndInstallButton = "Патч и установка",
        patchAndSaveButton = "Патч и сохранение",
        savedInStorageLabel = "📁 Сохранено в:",
        saveToDownloadButton = "В Download",
        chooseSaveFolderButton = "Сохранить как...",
        reinstallLastPatchedFormat = { name -> "Переустановить ($name)" },
        consoleTitle = "Журнал",
        clearLogsButton = "Очистить",
        readyToExecute = "Готово...",
        envHeaderTitle = "Состояние системы",
        diagAndroidOsTitle = "Система Android",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "Установлено (v$ver)" },
        diagGearheadNotInstalled = "Не установлено",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "Разрешено ✓",
        diagShizukuRunningNotGranted = "Запущен (Нет прав)",
        diagShizukuNotRunning = "Не запущен",
        diagGrantShizukuButton = "Дать права",
        diagRootTitle = "Права Root",
        diagRootAvailable = "Доступен ✓",
        diagRootNotDetected = "Нет Root",
        diagXposedTitle = "Модуль LSPosed / Xposed",
        diagXposedActive = "Активен ✓",
        diagXposedInactive = "Не активен",
        unknownSourcesGuideTitle = "Неизвестные источники в AA",
        unknownSourcesGuideSteps = "1. Откройте настройки Android Auto.\n" +
            "2. Нажмите 10 раз на «Версия» для включения режима разработчика.\n" +
            "3. Меню (⋮) -> «Для разработчиков».\n" +
            "4. Включите «Неизвестные источники» и режим «Для разработчиков».",
        openAASettingsButton = "Настройки Android Auto",
        recheckButton = "Обновить",
        statusPatchingApk = "Патч APK...",
        statusDirectInstallingApk = "Установка оригинала...",
        statusPatchFailedFormat = { err -> "❌ Ошибка: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ Сохранено: $path (${ms}мс)" },
        statusPatchedSuccessFormat = { ms -> "✅ Готово (${ms}мс)" },
        statusSavedToStorageFormat = { path -> "💾 Сохранено: $path" },
        statusSaveDownloadFailed = "❌ Ошибка сохранения в Download",
        statusExportedSuccess = "💾 APK сохранен!",
        statusExportFailed = "❌ Ошибка сохранения APK."
    )

    private val JA_STRINGS = AppStrings(
        language = AppLanguage.JA,
        appSubtitle = "Android Auto パッチャー＆インストーラー",
        refreshTooltip = "更新",
        languageDialogTitle = "言語",
        closeButton = "閉じる",
        navSelectApp = "アプリ",
        navPatchInstall = "パッチ導入",
        navEnvironment = "環境診断",
        bannerExternalTitle = "APK / APKS / XAPK を読み込む",
        bannerExternalSubtitle = "APKを選択してAA対応化、またはPlayストア提供元として直接導入",
        chooseFileButton = "パッチ用に選択",
        chooseFileDirectInstallButton = "元APKを導入 (Play偽装)",
        searchPlaceholder = "アプリ名やパッケージ名を検索...",
        clearSearch = "クリア",
        filterExistingAAChip = "AA対応済",
        filterSystemAppsChip = "システムアプリ",
        badgeExistingAA = "AA対応済",
        badgeNeedInjectAA = "通常アプリ",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "提供元: ",
        badgeSourceSideload = "外部",
        splitsBadgeFormat = { count -> "$count 分割" },
        emptySelectionTitle = "アプリまたはAPKが未選択です",
        backToAppListButton = "アプリ / APK を選択",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. パッチモード",
        strategyDirectNoPatchTitle = "直接インストール (パッチなし)",
        strategyDirectNoPatchDesc = "元のAPKと署名を維持し、Google Play提供元として導入します。",
        strategySpoofTitle = "既存のAAアプリを制限解除",
        strategySpoofDesc = "AA設定の修正とパッケージ名変更でブロックを回避。",
        strategyUniversalTitle = "通常アプリをAA車載化",
        strategyUniversalDesc = "通常のアプリ (動画、地図、ブラウザ等) を車載画面に投影しタッチ操作に対応。",
        outputPackageLabel = "パッケージ名 (変更してブロック回避)",
        randomizeTooltip = "ランダム",
        carDisplayNameLabel = "車載画面での表示名",
        automotiveDescConfigLabel = "AA権限設定:",
        projectionFullScreenLabel = "全画面投影 (Projection)",
        carTemplatesLabel = "車載テンプレート",
        carLauncherCategoryLabel = "車載カテゴリ:",
        categoryNavigation = "ナビゲーション (Navigation)",
        categoryIot = "ダッシュボード (IoT)",
        categoryMedia = "メディア・音声 (Media)",
        categoryPoi = "スポット (POI)",
        touchModeRootLabel = "Rootタッチ操作 (su)",
        touchModeRootDesc = "標準はOFF。通常タッチが効かないアプリのみONにします。",
        section2InstallModeTitle = "2. インストール方式 (Playストア偽装)",
        modeShizukuTitle = "Shizuku (推奨)",
        modeShizukuSubtitle = "ADB権限 (UID 2000) でPlay提供元として導入 (Android 11+)",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "Play提供元として直接導入＆権限を自動付与",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "通常導入し、内蔵XposedモジュールでAA制限を回避",
        modeStandardTitle = "非Root 標準導入",
        modeStandardSubtitle = "Session方式で提供元を偽装 (Android 10–13向け)",
        processingButton = "処理中...",
        directInstallNoPatchButton = "元のAPKを導入",
        patchAndInstallButton = "パッチして導入",
        patchAndSaveButton = "パッチして保存",
        savedInStorageLabel = "📁 保存先:",
        saveToDownloadButton = "Downloadに保存",
        chooseSaveFolderButton = "別名で保存...",
        reinstallLastPatchedFormat = { name -> "再導入 ($name)" },
        consoleTitle = "処理ログ",
        clearLogsButton = "消去",
        readyToExecute = "準備完了...",
        envHeaderTitle = "システム環境状態",
        diagAndroidOsTitle = "Android OS",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "導入済 (v$ver)" },
        diagGearheadNotInstalled = "未導入",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "許可済 ✓",
        diagShizukuRunningNotGranted = "実行中 (未許可)",
        diagShizukuNotRunning = "停止中",
        diagGrantShizukuButton = "権限を付与",
        diagRootTitle = "Root 権限",
        diagRootAvailable = "許可済 ✓",
        diagRootNotDetected = "Rootなし",
        diagXposedTitle = "LSPosed / Xposed モジュール",
        diagXposedActive = "有効 ✓",
        diagXposedInactive = "無効",
        unknownSourcesGuideTitle = "AA「提供元不明のアプリ」有効化手順",
        unknownSourcesGuideSteps = "1. Android Autoの設定を開く。\n" +
            "2. 下部の「バージョン」を10回タップして開発者モードを有効化。\n" +
            "3. 右上メニュー(⋮) ->「デベロッパー向け設定」を選択。\n" +
            "4.「提供元不明のアプリ」をONにし、モードを「デベロッパー」に設定。",
        openAASettingsButton = "AA設定を開く",
        recheckButton = "再確認",
        statusPatchingApk = "パッチ中...",
        statusDirectInstallingApk = "元APKを導入中...",
        statusPatchFailedFormat = { err -> "❌ エラー: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ 保存完了: $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ パッチ完了 (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 保存先: $path" },
        statusSaveDownloadFailed = "❌ Downloadへの保存失敗",
        statusExportedSuccess = "💾 APKを保存しました！",
        statusExportFailed = "❌ APKの保存に失敗しました。"
    )

    private val DE_STRINGS = AppStrings(
        language = AppLanguage.DE,
        appSubtitle = "Android Auto Patcher & Installer",
        refreshTooltip = "Aktualisieren",
        languageDialogTitle = "Sprache",
        closeButton = "Schließen",
        navSelectApp = "Apps",
        navPatchInstall = "Patchen",
        navEnvironment = "System",
        bannerExternalTitle = "APK / APKS / XAPK importieren",
        bannerExternalSubtitle = "APK wählen, um sie für AA zu patchen oder als Google Play zu installieren",
        chooseFileButton = "Datei patchen",
        chooseFileDirectInstallButton = "Original installieren (Play)",
        searchPlaceholder = "App oder Paket suchen...",
        clearSearch = "Löschen",
        filterExistingAAChip = "AA-fähig",
        filterSystemAppsChip = "System-Apps",
        badgeExistingAA = "AA-fähig",
        badgeNeedInjectAA = "Standard-App",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "Quelle: ",
        badgeSourceSideload = "Sideload",
        splitsBadgeFormat = { count -> "$count Splits" },
        emptySelectionTitle = "Keine App oder APK gewählt",
        backToAppListButton = "App / APK wählen",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. Patch-Modus",
        strategyDirectNoPatchTitle = "Direkt installieren (Ohne Patch)",
        strategyDirectNoPatchDesc = "Behält Original-APK & Signatur. Installiert mit Google Play-Quelle.",
        strategySpoofTitle = "Vorhandene AA-App freischalten",
        strategySpoofDesc = "Repariert AA-Konfig & klont Paketnamen gegen Sperre.",
        strategyUniversalTitle = "Standard-App für AA umwandeln",
        strategyUniversalDesc = "Jede App (Video, Karten, Browser...) mit Touch-Steuerung auf dem Autobildschirm nutzen.",
        outputPackageLabel = "Paketname (Ändern gegen Sperre)",
        randomizeTooltip = "Zufällig",
        carDisplayNameLabel = "Anzeigename im Auto",
        automotiveDescConfigLabel = "AA-Berechtigungen:",
        projectionFullScreenLabel = "Vollbild (Projection)",
        carTemplatesLabel = "Auto-Vorlagen (Templates)",
        carLauncherCategoryLabel = "Kategorie im Auto:",
        categoryNavigation = "Navigation",
        categoryIot = "Dashboard / Werkzeuge (IoT)",
        categoryMedia = "Medien & Audio (Media)",
        categoryPoi = "Sonderziele (POI)",
        touchModeRootLabel = "Root-Touch (su)",
        touchModeRootDesc = "Standard AUS. Nur aktivieren, wenn Touch sonst nicht reagiert.",
        section2InstallModeTitle = "2. Installationsmethode (Play Store-Quelle)",
        modeShizukuTitle = "Shizuku (Empfohlen)",
        modeShizukuSubtitle = "Installiert per ADB (UID 2000) als Google Play (Android 11+)",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "Direktinstallation als Google Play & Auto-Freigabe",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "Normale Installation, umgeht AA-Prüfung per Xposed-Modul",
        modeStandardTitle = "Standard ohne Root",
        modeStandardSubtitle = "Session-Trick (Optimal für Android 10–13)",
        processingButton = "Verarbeite...",
        directInstallNoPatchButton = "Original-APK installieren",
        patchAndInstallButton = "Patchen & Installieren",
        patchAndSaveButton = "Patchen & Speichern",
        savedInStorageLabel = "📁 Gespeichert unter:",
        saveToDownloadButton = "In Download speichern",
        chooseSaveFolderButton = "Speichern unter...",
        reinstallLastPatchedFormat = { name -> "Neu installieren ($name)" },
        consoleTitle = "Protokoll",
        clearLogsButton = "Leeren",
        readyToExecute = "Bereit...",
        envHeaderTitle = "Systemstatus",
        diagAndroidOsTitle = "Android-System",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "Installiert (v$ver)" },
        diagGearheadNotInstalled = "Nicht installiert",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "Autorisiert ✓",
        diagShizukuRunningNotGranted = "Läuft (Keine Berechtigung)",
        diagShizukuNotRunning = "Nicht aktiv",
        diagGrantShizukuButton = "Erlauben",
        diagRootTitle = "Root-Zugriff",
        diagRootAvailable = "Vorhanden ✓",
        diagRootNotDetected = "Kein Root",
        diagXposedTitle = "LSPosed / Xposed-Modul",
        diagXposedActive = "Aktiv ✓",
        diagXposedInactive = "Inaktiv",
        unknownSourcesGuideTitle = "Unbekannte Quellen in AA aktivieren",
        unknownSourcesGuideSteps = "1. Android Auto-Einstellungen öffnen.\n" +
            "2. 10x auf 'Version' tippen für Entwicklermodus.\n" +
            "3. Menü (⋮) -> 'Entwicklereinstellungen'.\n" +
            "4. 'Unbekannte Quellen' aktivieren & Modus auf 'Entwickler' setzen.",
        openAASettingsButton = "AA-Einstellungen",
        recheckButton = "Prüfen",
        statusPatchingApk = "Patche APK...",
        statusDirectInstallingApk = "Installiere Original-APK...",
        statusPatchFailedFormat = { err -> "❌ Fehler: $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ Gespeichert: $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ Gepatcht (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 Gespeichert: $path" },
        statusSaveDownloadFailed = "❌ Fehler beim Speichern in Download",
        statusExportedSuccess = "💾 APK gespeichert!",
        statusExportFailed = "❌ Fehler beim Speichern."
    )

    private val FR_STRINGS = AppStrings(
        language = AppLanguage.FR,
        appSubtitle = "Patcheur et Installateur Android Auto",
        refreshTooltip = "Actualiser",
        languageDialogTitle = "Langue",
        closeButton = "Fermer",
        navSelectApp = "Apps",
        navPatchInstall = "Patcher",
        navEnvironment = "Système",
        bannerExternalTitle = "Importer APK / APKS / XAPK",
        bannerExternalSubtitle = "Choisissez un APK à patcher pour AA ou à installer comme Google Play",
        chooseFileButton = "Choisir pour Patcher",
        chooseFileDirectInstallButton = "Installer l'Original (Play)",
        searchPlaceholder = "Rechercher une app ou un package...",
        clearSearch = "Effacer",
        filterExistingAAChip = "Compatible AA",
        filterSystemAppsChip = "Apps Système",
        badgeExistingAA = "Compatible AA",
        badgeNeedInjectAA = "App Standard",
        badgeSourcePlayStore = "Google Play ✓",
        badgeSourceOtherPrefix = "Source : ",
        badgeSourceSideload = "Externe",
        splitsBadgeFormat = { count -> "$count Splits" },
        emptySelectionTitle = "Aucune app ou APK sélectionné",
        backToAppListButton = "Choisir App / APK",
        targetLabelPrefix = "",
        originalPackageFormat = { pkg, count -> "$pkg ($count APK)" },
        section1PatchStrategyTitle = "1. Mode de Patch",
        strategyDirectNoPatchTitle = "Installation Directe (Sans Patch)",
        strategyDirectNoPatchDesc = "Garde l'APK et la signature d'origine. Installe avec source Google Play.",
        strategySpoofTitle = "Débloquer une App AA Existante",
        strategySpoofDesc = "Corrige la config AA et clone le package pour contourner le blocage.",
        strategyUniversalTitle = "Convertir une App Standard pour AA",
        strategyUniversalDesc = "Affiche toute app (Vidéo, Cartes, Web...) sur l'écran auto avec contrôle tactile.",
        outputPackageLabel = "Nom du Package (Modifier contre le blocage)",
        randomizeTooltip = "Aléatoire",
        carDisplayNameLabel = "Nom sur l'Écran Auto",
        automotiveDescConfigLabel = "Autorisations AA :",
        projectionFullScreenLabel = "Plein Écran (Projection)",
        carTemplatesLabel = "Modèles Auto (Templates)",
        carLauncherCategoryLabel = "Catégorie Auto :",
        categoryNavigation = "Navigation",
        categoryIot = "Tableau de Bord (IoT)",
        categoryMedia = "Média & Audio (Media)",
        categoryPoi = "Points d'Intérêt (POI)",
        touchModeRootLabel = "Tactile via Root (su)",
        touchModeRootDesc = "DÉSACTIVÉ par défaut. Activer seulement si le tactile normal échoue.",
        section2InstallModeTitle = "2. Méthode d'Installation (Source Play Store)",
        modeShizukuTitle = "Shizuku (Recommandé)",
        modeShizukuSubtitle = "Installe via ADB (UID 2000) comme Google Play (Android 11+)",
        modeRootTitle = "Root (Magisk / KernelSU / APatch)",
        modeRootSubtitle = "Installation directe comme Google Play et autorisation auto",
        modeLsposedTitle = "LSPosed / Xposed",
        modeLsposedSubtitle = "Installation normale, contourne les vérifications AA via Xposed",
        modeStandardTitle = "Sans Root Standard",
        modeStandardSubtitle = "Simulation via Session (Idéal sur Android 10–13)",
        processingButton = "Traitement...",
        directInstallNoPatchButton = "Installer l'APK Original",
        patchAndInstallButton = "Patcher & Installer",
        patchAndSaveButton = "Patcher & Enregistrer",
        savedInStorageLabel = "📁 Enregistré dans :",
        saveToDownloadButton = "Dans Download",
        chooseSaveFolderButton = "Enregistrer sous...",
        reinstallLastPatchedFormat = { name -> "Réinstaller ($name)" },
        consoleTitle = "Journal",
        clearLogsButton = "Effacer",
        readyToExecute = "Prêt...",
        envHeaderTitle = "État du Système",
        diagAndroidOsTitle = "Système Android",
        diagGearheadTitle = "Android Auto",
        diagGearheadInstalledFormat = { ver -> "Installé (v$ver)" },
        diagGearheadNotInstalled = "Non installé",
        diagShizukuTitle = "Shizuku (ADB UID 2000)",
        diagShizukuGranted = "Autorisé ✓",
        diagShizukuRunningNotGranted = "Actif (Permission requise)",
        diagShizukuNotRunning = "Inactif",
        diagGrantShizukuButton = "Autoriser",
        diagRootTitle = "Accès Root",
        diagRootAvailable = "Accordé ✓",
        diagRootNotDetected = "Sans Root",
        diagXposedTitle = "Module LSPosed / Xposed",
        diagXposedActive = "Actif ✓",
        diagXposedInactive = "Inactif",
        unknownSourcesGuideTitle = "Activer les Sources Inconnues dans AA",
        unknownSourcesGuideSteps = "1. Ouvrez les paramètres Android Auto.\n" +
            "2. Appuyez 10 fois sur « Version » pour activer le Mode Développeur.\n" +
            "3. Menu (⋮) -> « Paramètres développeur ».\n" +
            "4. Cochez « Sources inconnues » et réglez le mode sur « Développeur ».",
        openAASettingsButton = "Paramètres Android Auto",
        recheckButton = "Actualiser",
        statusPatchingApk = "Patch en cours...",
        statusDirectInstallingApk = "Installation de l'original...",
        statusPatchFailedFormat = { err -> "❌ Échec : $err" },
        statusPatchedAndSavedFormat = { path, ms -> "✅ Enregistré : $path (${ms}ms)" },
        statusPatchedSuccessFormat = { ms -> "✅ Patché (${ms}ms)" },
        statusSavedToStorageFormat = { path -> "💾 Enregistré : $path" },
        statusSaveDownloadFailed = "❌ Échec dans Download",
        statusExportedSuccess = "💾 APK enregistré !",
        statusExportFailed = "❌ Échec de l'enregistrement."
    )

    fun translateLogOrMessage(raw: String, strings: AppStrings): String {
        val lang = strings.language

        when (raw) {
            "[Shizuku] Đã được cấp quyền Shizuku thành công!" -> return when (lang) {
                AppLanguage.EN -> "[Shizuku] Shizuku permission granted successfully!"
                AppLanguage.ZH -> "[Shizuku] 已成功授予 Shizuku 权限！"
                AppLanguage.ES -> "[Shizuku] ¡Permiso de Shizuku concedido con éxito!"
                AppLanguage.HI -> "[Shizuku] Shizuku अनुमति सफलतापूर्वक प्रदान की गई!"
                AppLanguage.AR -> "[Shizuku] تم منح صلاحية Shizuku بنجاح!"
                AppLanguage.PT -> "[Shizuku] Permissão do Shizuku concedida com sucesso!"
                AppLanguage.RU -> "[Shizuku] Разрешение Shizuku успешно получено!"
                AppLanguage.JA -> "[Shizuku] Shizuku の権限が正常に付与されました！"
                AppLanguage.DE -> "[Shizuku] Shizuku-Berechtigung erfolgreich erteilt!"
                AppLanguage.FR -> "[Shizuku] Permission Shizuku accordée avec succès !"
                else -> raw
            }
            "[Shizuku] Người dùng từ chối cấp quyền Shizuku." -> return when (lang) {
                AppLanguage.EN -> "[Shizuku] User denied Shizuku permission."
                AppLanguage.ZH -> "[Shizuku] 用户拒绝了 Shizuku 权限。"
                AppLanguage.ES -> "[Shizuku] El usuario denegó el permiso de Shizuku."
                AppLanguage.HI -> "[Shizuku] उपयोगकर्ता ने Shizuku अनुमति अस्वीकार कर दी।"
                AppLanguage.AR -> "[Shizuku] رفض المستخدم منح صلاحية Shizuku."
                AppLanguage.PT -> "[Shizuku] O usuário negou a permissão do Shizuku."
                AppLanguage.RU -> "[Shizuku] Пользователь отклонил запрос прав Shizuku."
                AppLanguage.JA -> "[Shizuku] ユーザーが Shizuku 権限を拒否しました。"
                AppLanguage.DE -> "[Shizuku] Benutzer hat die Shizuku-Berechtigung verweigert."
                AppLanguage.FR -> "[Shizuku] L'utilisateur a refusé la permission Shizuku."
                else -> raw
            }
            "[Shizuku] Dịch vụ Shizuku chưa chạy trên thiết bị." -> return when (lang) {
                AppLanguage.EN -> "[Shizuku] Shizuku service is not running on this device."
                AppLanguage.ZH -> "[Shizuku] 设备上的 Shizuku 服务未运行。"
                AppLanguage.ES -> "[Shizuku] El servicio Shizuku no se está ejecutando en el dispositivo."
                AppLanguage.HI -> "[Shizuku] डिवाइस पर Shizuku सेवा नहीं चल रही है।"
                AppLanguage.AR -> "[Shizuku] خدمة Shizuku غير قيد التشغيل على الجهاز."
                AppLanguage.PT -> "[Shizuku] O serviço Shizuku não está em execução no dispositivo."
                AppLanguage.RU -> "[Shizuku] Служба Shizuku не запущена на устройстве."
                AppLanguage.JA -> "[Shizuku] 端末で Shizuku サービスが実行されていません。"
                AppLanguage.DE -> "[Shizuku] Shizuku-Dienst läuft nicht auf dem Gerät."
                AppLanguage.FR -> "[Shizuku] Le service Shizuku ne fonctionne pas sur l'appareil."
                else -> raw
            }
            "[Shizuku] Đã có sẵn quyền Shizuku." -> return when (lang) {
                AppLanguage.EN -> "[Shizuku] Shizuku permission is already granted."
                AppLanguage.ZH -> "[Shizuku] 已拥有 Shizuku 权限。"
                AppLanguage.ES -> "[Shizuku] El permiso de Shizuku ya está concedido."
                AppLanguage.HI -> "[Shizuku] Shizuku अनुमति पहले से प्राप्त है।"
                AppLanguage.AR -> "[Shizuku] صلاحية Shizuku ممنوحة بالفعل."
                AppLanguage.PT -> "[Shizuku] A permissão do Shizuku já foi concedida."
                AppLanguage.RU -> "[Shizuku] Разрешение Shizuku уже предоставлено."
                AppLanguage.JA -> "[Shizuku] 既に Shizuku の権限が付与されています。"
                AppLanguage.DE -> "[Shizuku] Shizuku-Berechtigung ist bereits vorhanden."
                AppLanguage.FR -> "[Shizuku] La permission Shizuku est déjà accordée."
                else -> raw
            }
            "Đang nhập file APK/APKS từ bộ nhớ..." -> return when (lang) {
                AppLanguage.EN -> "Importing APK/APKS file from device storage..."
                AppLanguage.ZH -> "正在从设备存储导入 APK/APKS 文件..."
                AppLanguage.ES -> "Importando archivo APK/APKS del almacenamiento..."
                AppLanguage.HI -> "स्टोरेज से APK/APKS फ़ाइल आयात की जा रही है..."
                AppLanguage.AR -> "جاري استيراد ملف APK/APKS من الذاكرة..."
                AppLanguage.PT -> "Importando arquivo APK/APKS do armazenamento..."
                AppLanguage.RU -> "Импорт файла APK/APKS из памяти устройства..."
                AppLanguage.JA -> "ストレージから APK/APKS ファイルを読み込んでいます..."
                AppLanguage.DE -> "Importiere APK/APKS-Datei aus dem Gerätespeicher..."
                AppLanguage.FR -> "Importation du fichier APK/APKS depuis le stockage..."
                else -> raw
            }
            "Không thể đọc được file APK đã chọn." -> return when (lang) {
                AppLanguage.EN -> "Could not read the selected APK file."
                AppLanguage.ZH -> "无法读取所选的 APK 文件。"
                AppLanguage.ES -> "No se pudo leer el archivo APK seleccionado."
                AppLanguage.HI -> "चुनी गई APK फ़ाइल को पढ़ा नहीं जा सका।"
                AppLanguage.AR -> "تعذر قراءة ملف APK المحدد."
                AppLanguage.PT -> "Não foi possível ler o arquivo APK selecionado."
                AppLanguage.RU -> "Не удалось прочитать выбранный файл APK."
                AppLanguage.JA -> "選択された APK ファイルを読み込めませんでした。"
                AppLanguage.DE -> "Die ausgewählte APK-Datei konnte nicht gelesen werden."
                AppLanguage.FR -> "Impossible de lire le fichier APK sélectionné."
                else -> raw
            }
            "=== BẮT ĐẦU QUY TRÌNH PATCH ANDROID AUTO ===" -> return when (lang) {
                AppLanguage.EN -> "=== STARTING ANDROID AUTO PATCH PIPELINE ==="
                AppLanguage.ZH -> "=== 开始 ANDROID AUTO 修补流程 ==="
                AppLanguage.ES -> "=== INICIANDO PROCESO DE PARCHEO ANDROID AUTO ==="
                AppLanguage.HI -> "=== ANDROID AUTO पैच प्रक्रिया शुरू हो रही है ==="
                AppLanguage.AR -> "=== بدء عملية تعديل ANDROID AUTO ==="
                AppLanguage.PT -> "=== INICIANDO PROCESSO DE PATCH ANDROID AUTO ==="
                AppLanguage.RU -> "=== ЗАПУСК ПРОЦЕССА ПАТЧИНГА ANDROID AUTO ==="
                AppLanguage.JA -> "=== ANDROID AUTO パッチ処理を開始 ==="
                AppLanguage.DE -> "=== STARTE ANDROID AUTO PATCH-PROZESS ==="
                AppLanguage.FR -> "=== DÉMARRAGE DU PROCESSUS DE PATCH ANDROID AUTO ==="
                else -> raw
            }
            "[Patcher] Đang xử lý cấu trúc APK nhị phân bằng ARSCLib..." -> return when (lang) {
                AppLanguage.EN -> "[Patcher] Processing binary APK structure with ARSCLib..."
                AppLanguage.ZH -> "[Patcher] 正在使用 ARSCLib 处理二进制 APK 结构..."
                AppLanguage.ES -> "[Patcher] Procesando estructura binaria del APK con ARSCLib..."
                AppLanguage.HI -> "[Patcher] ARSCLib के साथ बाइनरी APK संरचना संसाधित की जा रही है..."
                AppLanguage.AR -> "[Patcher] جاري معالجة بنية APK الثنائية باستخدام ARSCLib..."
                AppLanguage.PT -> "[Patcher] Processando estrutura binária do APK com ARSCLib..."
                AppLanguage.RU -> "[Patcher] Обработка бинарной структуры APK через ARSCLib..."
                AppLanguage.JA -> "[Patcher] ARSCLib でバイナリ APK 構造を処理しています..."
                AppLanguage.DE -> "[Patcher] Verarbeite binäre APK-Struktur mit ARSCLib..."
                AppLanguage.FR -> "[Patcher] Traitement de la structure APK binaire avec ARSCLib..."
                else -> raw
            }
            "[Patcher] Đang nạp cấu trúc APK nhị phân bằng ARSCLib..." -> return when (lang) {
                AppLanguage.EN -> "[Patcher] Loading binary APK structure with ARSCLib..."
                AppLanguage.ZH -> "[Patcher] 正在使用 ARSCLib 加载二进制 APK 结构..."
                AppLanguage.ES -> "[Patcher] Cargando estructura binaria del APK con ARSCLib..."
                AppLanguage.HI -> "[Patcher] ARSCLib के साथ बाइनरी APK संरचना लोड हो रही है..."
                AppLanguage.AR -> "[Patcher] جاري تحميل بنية APK الثنائية باستخدام ARSCLib..."
                AppLanguage.PT -> "[Patcher] Carregando estrutura binária do APK com ARSCLib..."
                AppLanguage.RU -> "[Patcher] Загрузка бинарной структуры APK через ARSCLib..."
                AppLanguage.JA -> "[Patcher] ARSCLib でバイナリ APK 構造を読み込んでいます..."
                AppLanguage.DE -> "[Patcher] Lade binäre APK-Struktur mit ARSCLib..."
                AppLanguage.FR -> "[Patcher] Chargement de la structure APK binaire avec ARSCLib..."
                else -> raw
            }
            "[Patcher] Chế độ SPOOF_AND_ACTIVATE: Giữ nguyên CarAppService/MediaBrowserService gốc của APK." -> return when (lang) {
                AppLanguage.EN -> "[Patcher] SPOOF_AND_ACTIVATE mode: Preserving original CarAppService/MediaBrowserService."
                AppLanguage.ZH -> "[Patcher] SPOOF_AND_ACTIVATE 模式：保留 APK 原始 CarAppService/MediaBrowserService。"
                AppLanguage.ES -> "[Patcher] Modo SPOOF_AND_ACTIVATE: Conservando CarAppService/MediaBrowserService original."
                AppLanguage.HI -> "[Patcher] SPOOF_AND_ACTIVATE मोड: मूल CarAppService/MediaBrowserService को सुरक्षित रखा गया।"
                AppLanguage.AR -> "[Patcher] وضع SPOOF_AND_ACTIVATE: الحفاظ على خدمة CarAppService/MediaBrowserService الأصلية."
                AppLanguage.PT -> "[Patcher] Modo SPOOF_AND_ACTIVATE: Mantendo CarAppService/MediaBrowserService original do APK."
                AppLanguage.RU -> "[Patcher] Режим SPOOF_AND_ACTIVATE: сохранение исходного CarAppService/MediaBrowserService."
                AppLanguage.JA -> "[Patcher] SPOOF_AND_ACTIVATE モード: 元の CarAppService/MediaBrowserService を維持します。"
                AppLanguage.DE -> "[Patcher] SPOOF_AND_ACTIVATE-Modus: Originaler CarAppService/MediaBrowserService bleibt erhalten."
                AppLanguage.FR -> "[Patcher] Mode SPOOF_AND_ACTIVATE : Conservation du CarAppService/MediaBrowserService d'origine."
                else -> raw
            }
            "[Patcher] Chế độ PARKED_APP_MODE: Kích hoạt đầy đủ CAR_LAUNCHER & appCategory=\"game\" chuẩn Android Auto Parked App (không tiêm DEX)." -> return when (lang) {
                AppLanguage.EN -> "[Patcher] PARKED_APP_MODE: Enabled CAR_LAUNCHER & appCategory=\"game\" for Android Auto Parked App (no DEX injection)."
                AppLanguage.ZH -> "[Patcher] PARKED_APP_MODE 模式：已启用 CAR_LAUNCHER 与 appCategory=\"game\" 车载驻车应用支持（不注入 DEX）。"
                AppLanguage.ES -> "[Patcher] Modo PARKED_APP_MODE: Activado CAR_LAUNCHER y appCategory=\"game\" para Android Auto Parked App (sin inyección DEX)."
                AppLanguage.HI -> "[Patcher] PARKED_APP_MODE: Android Auto Parked App के लिए CAR_LAUNCHER और appCategory=\"game\" सक्रिय किया गया (बिना DEX इंजेक्शन)।"
                AppLanguage.AR -> "[Patcher] وضع PARKED_APP_MODE: تم تفعيل CAR_LAUNCHER و appCategory=\"game\" لتطبيق التوقف على Android Auto (بدون حقن DEX)."
                AppLanguage.PT -> "[Patcher] Modo PARKED_APP_MODE: Ativado CAR_LAUNCHER e appCategory=\"game\" padrão Android Auto Parked App (sem injeção DEX)."
                AppLanguage.RU -> "[Patcher] Режим PARKED_APP_MODE: включены CAR_LAUNCHER и appCategory=\"game\" для Android Auto Parked App (без инжекта DEX)."
                AppLanguage.JA -> "[Patcher] PARKED_APP_MODE モード: Android Auto 駐車アプリ用に CAR_LAUNCHER と appCategory=\"game\" を有効化しました (DEX 注入なし)。"
                AppLanguage.DE -> "[Patcher] PARKED_APP_MODE: CAR_LAUNCHER & appCategory=\"game\" für Android Auto Parked App aktiviert (keine DEX-Injektion)."
                AppLanguage.FR -> "[Patcher] Mode PARKED_APP_MODE : CAR_LAUNCHER et appCategory=\"game\" activés pour Android Auto Parked App (sans injection DEX)."
                else -> raw
            }
            "[Patcher] Đang đóng gói lại cấu trúc APK nhị phân (căn chỉnh 16KB Page Size cho thư viện Native .so)..." -> return when (lang) {
                AppLanguage.EN -> "[Patcher] Repackaging binary APK structure (aligning 16KB Page Size for Native .so libs)..."
                AppLanguage.ZH -> "[Patcher] 正在重新打包二进制 APK 结构 (为 Native .so 库进行 16KB 页面对齐)..."
                AppLanguage.ES -> "[Patcher] Reempaquetando estructura binaria del APK (alineando 16KB Page Size para libs .so)..."
                AppLanguage.HI -> "[Patcher] बाइनरी APK संरचना पुनः पैक की जा रही है (Native .so के लिए 16KB संरेखण)..."
                AppLanguage.AR -> "[Patcher] جاري إعادة حزم بنية APK الثنائية (محاذاة 16KB لمكتبات Native .so)..."
                AppLanguage.PT -> "[Patcher] Reempacotando estrutura binária do APK (alinhando 16KB Page Size para bibliotecas .so)..."
                AppLanguage.RU -> "[Patcher] Переупаковка бинарной структуры APK (выравнивание 16KB Page Size для библиотек .so)..."
                AppLanguage.JA -> "[Patcher] バイナリ APK 構造を再パッケージ化しています (Native .so の 16KB ページアライメント)..."
                AppLanguage.DE -> "[Patcher] Packe binäre APK-Struktur neu (16KB Page Size-Ausrichtung für Native .so-Libs)..."
                AppLanguage.FR -> "[Patcher] Reconditionnement de la structure APK binaire (alignement Page Size 16KB pour .so)..."
                else -> raw
            }
            "[Patcher] Đang đóng gói lại cấu trúc APK nhị phân..." -> return when (lang) {
                AppLanguage.EN -> "[Patcher] Repackaging binary APK structure..."
                AppLanguage.ZH -> "[Patcher] 正在重新打包二进制 APK 结构..."
                AppLanguage.ES -> "[Patcher] Reempaquetando estructura binaria del APK..."
                AppLanguage.HI -> "[Patcher] बाइनरी APK संरचना को फिर से पैक किया जा रहा है..."
                AppLanguage.AR -> "[Patcher] جاري إعادة حزم بنية APK الثنائية..."
                AppLanguage.PT -> "[Patcher] Reempacotando estrutura binária do APK..."
                AppLanguage.RU -> "[Patcher] Переупаковка бинарной структуры APK..."
                AppLanguage.JA -> "[Patcher] バイナリ APK 構造を再パッケージ化しています..."
                AppLanguage.DE -> "[Patcher] Packe binäre APK-Struktur neu..."
                AppLanguage.FR -> "[Patcher] Reconditionnement de la structure APK binaire..."
                else -> raw
            }
            "[Patcher] ! Không tìm thấy payload Universal AA Runtime trong assets, giữ nguyên DEX hiện tại." -> return when (lang) {
                AppLanguage.EN -> "[Patcher] ! Could not find Universal AA Runtime payload in assets, keeping current DEX."
                AppLanguage.ZH -> "[Patcher] ! 未在 assets 中找到通用 AA 运行时组件，保留当前 DEX。"
                AppLanguage.ES -> "[Patcher] ! No se encontró el complemento Universal AA Runtime en assets, manteniendo DEX actual."
                AppLanguage.HI -> "[Patcher] ! assets में Universal AA Runtime पेलोड नहीं मिला, मौजूदा DEX रखा जा रहा है।"
                AppLanguage.AR -> "[Patcher] ! لم يتم العثور على حمولة Universal AA Runtime في assets، سيتم الإبقاء على DEX الحالي."
                AppLanguage.PT -> "[Patcher] ! Payload Universal AA Runtime não encontrado em assets, mantendo DEX atual."
                AppLanguage.RU -> "[Patcher] ! В assets не найден пэйлоад Universal AA Runtime, сохраняется текущий DEX."
                AppLanguage.JA -> "[Patcher] ! assets 内に Universal AA Runtime ペイロードが見つかりません。現在の DEX を維持します。"
                AppLanguage.DE -> "[Patcher] ! Universal AA Runtime Payload in assets nicht gefunden, behalte aktuelle DEX."
                AppLanguage.FR -> "[Patcher] ! Payload Universal AA Runtime introuvable dans assets, conservation du DEX actuel."
                else -> raw
            }
            "[Patcher] ! Không tìm thấy assets/aa_stub_runtime.dex, giữ nguyên DEX hiện tại." -> return when (lang) {
                AppLanguage.EN -> "[Patcher] ! Could not find assets/aa_stub_runtime.dex, keeping current DEX."
                AppLanguage.ZH -> "[Patcher] ! 未找到 assets/aa_stub_runtime.dex，保留当前 DEX。"
                AppLanguage.ES -> "[Patcher] ! No se encontró assets/aa_stub_runtime.dex, manteniendo DEX actual."
                AppLanguage.HI -> "[Patcher] ! assets/aa_stub_runtime.dex नहीं मिला, मौजूदा DEX रखा जा रहा है।"
                AppLanguage.AR -> "[Patcher] ! لم يتم العثور على assets/aa_stub_runtime.dex، سيتم الإبقاء على DEX الحالي."
                AppLanguage.PT -> "[Patcher] ! assets/aa_stub_runtime.dex não encontrado, mantendo DEX atual."
                AppLanguage.RU -> "[Patcher] ! Не найден assets/aa_stub_runtime.dex, сохраняется текущий DEX."
                AppLanguage.JA -> "[Patcher] ! assets/aa_stub_runtime.dex が見つかりません。現在の DEX を維持します。"
                AppLanguage.DE -> "[Patcher] ! assets/aa_stub_runtime.dex nicht gefunden, behalte aktuelle DEX."
                AppLanguage.FR -> "[Patcher] ! assets/aa_stub_runtime.dex introuvable, conservation du DEX actuel."
                else -> raw
            }
            "[Patcher] ! Cảnh báo: Phát hiện Google Play PairIP VM (libpairipcore.so / Pairip Application) — giữ nguyên thành phần Manifest của PairIP để không kích hoạt bẫy chống sửa đổi. Nếu ứng dụng tự đóng sau khi ký lại APK, cần bật giả lập chữ ký gốc (LSPosed + CorePatch / pairipfix) hoặc dùng bản APK độc lập ngoài Play Store." -> return when (lang) {
                AppLanguage.EN -> "[Patcher] ! Warning: Detected Google Play PairIP VM (libpairipcore.so / Pairip Application) — preserving PairIP Manifest components to avoid tamper detection. If app closes on launch after resigning, enable signature spoofing (LSPosed + CorePatch / pairipfix) or use a non-Play Store APK."
                AppLanguage.ZH -> "[Patcher] ! 警告: 检测到 Google Play PairIP VM (libpairipcore.so / Pairip Application) — 已保留 PairIP Manifest 组件以防触发防篡改。若重签后应用闪退，请开启签名伪装 (LSPosed + CorePatch / pairipfix) 或使用非 Play 商店独立版 APK。"
                AppLanguage.ES -> "[Patcher] ! Advertencia: Detectado Google Play PairIP VM (libpairipcore.so / Pairip Application) — conservando Manifest de PairIP. Si la app se cierra al iniciar tras firmar, active la suplantación de firma (LSPosed + CorePatch) o use un APK fuera de Play Store."
                AppLanguage.HI -> "[Patcher] ! चेतावनी: Google Play PairIP VM (libpairipcore.so / Pairip Application) मिला — PairIP घटकों को सुरक्षित रखा गया। यदि पुनः हस्ताक्षर के बाद ऐप बंद हो जाता है, तो सिग्नेचर स्पूफिंग (LSPosed + CorePatch) सक्षम करें।"
                AppLanguage.AR -> "[Patcher] ! تحذير: تم اكتشاف Google Play PairIP VM — تم الحفاظ على عناصر PairIP لتجنب الحماية. إذا تعطل التطبيق بعد التوقيع، يُرجى تفعيل تزييف التوقيع (LSPosed + CorePatch) أو استخدام APK من خارج متجر Play."
                AppLanguage.PT -> "[Patcher] ! Aviso: Detectado Google Play PairIP VM (libpairipcore.so / Pairip Application) — mantendo componentes do PairIP. Se o app fechar sozinho após assinar, use simulação de assinatura (LSPosed + CorePatch)."
                AppLanguage.RU -> "[Patcher] ! Внимание: Обнаружена Google Play PairIP VM (libpairipcore.so / Pairip Application) — компоненты PairIP сохранены. Если приложение закрывается при запуске, включите подмену подписи (LSPosed + CorePatch) или используйте APK не из Play Store."
                AppLanguage.JA -> "[Patcher] ! 警告: Google Play PairIP VM (libpairipcore.so / Pairip Application) を検出 — 改ざん検知を防ぐため PairIP 構成要素を維持します。再署名後にアプリが落ちる場合は署名偽装 (LSPosed + CorePatch) を有効にするか別版 APK を使用してください。"
                AppLanguage.DE -> "[Patcher] ! Warnung: Google Play PairIP VM erkannt — PairIP Manifest-Komponenten beibehalten. Falls App nach Signierung schließt, Signatur-Spoofing (LSPosed + CorePatch) nutzen."
                AppLanguage.FR -> "[Patcher] ! Avertissement : Google Play PairIP VM détecté — composants PairIP conservés. Si l'application se ferme après signature, activez l'usurpation de signature (LSPosed + CorePatch)."
                else -> raw
            }
            "[Signer] Đang chuẩn bị khóa RSA-2048 & chứng chỉ X.509 để ký APK (Scheme V1 + V2 + V3)..." -> return when (lang) {
                AppLanguage.EN -> "[Signer] Preparing RSA-2048 key & X.509 certificate to sign APK (Scheme V1 + V2 + V3)..."
                AppLanguage.ZH -> "[Signer] 正在准备 RSA-2048 密钥与 X.509 证书以签名 APK (V1 + V2 + V3 方案)..."
                AppLanguage.ES -> "[Signer] Preparando clave RSA-2048 y certificado X.509 para firmar APK (Esquema V1 + V2 + V3)..."
                AppLanguage.HI -> "[Signer] APK साइन करने के लिए RSA-2048 कुंजी और X.509 प्रमाणपत्र तैयार किया जा रहा है (V1 + V2 + V3)..."
                AppLanguage.AR -> "[Signer] جاري تحضير مفتاح RSA-2048 وشهادة X.509 لتوقيع APK (V1 + V2 + V3)..."
                AppLanguage.PT -> "[Signer] Preparando chave RSA-2048 e certificado X.509 para assinar o APK (Scheme V1 + V2 + V3)..."
                AppLanguage.RU -> "[Signer] Подготовка ключа RSA-2048 и сертификата X.509 для подписи APK (V1 + V2 + V3)..."
                AppLanguage.JA -> "[Signer] APK 署名用の RSA-2048 鍵と X.509 証明書を準備中 (V1 + V2 + V3)..."
                AppLanguage.DE -> "[Signer] Bereite RSA-2048-Schlüssel & X.509-Zertifikat zum Signieren vor (V1 + V2 + V3)..."
                AppLanguage.FR -> "[Signer] Préparation de la clé RSA-2048 et du certificat X.509 pour signer l'APK (V1 + V2 + V3)..."
                else -> raw
            }
            "[Root] Đang cài đặt với nguồn com.android.vending..." -> return when (lang) {
                AppLanguage.EN -> "[Root] Installing with installer source com.android.vending..."
                AppLanguage.ZH -> "[Root] 正在使用 com.android.vending 来源进行安装..."
                AppLanguage.ES -> "[Root] Instalando con origen com.android.vending..."
                AppLanguage.HI -> "[Root] com.android.vending स्रोत के साथ इंस्टॉल किया जा रहा है..."
                AppLanguage.AR -> "[Root] جاري التثبيت بالمصدر com.android.vending..."
                AppLanguage.PT -> "[Root] Instalando com origem com.android.vending..."
                AppLanguage.RU -> "[Root] Установка с источником com.android.vending..."
                AppLanguage.JA -> "[Root] 提供元 com.android.vending としてインストール中..."
                AppLanguage.DE -> "[Root] Installiere mit Quelle com.android.vending..."
                AppLanguage.FR -> "[Root] Installation avec la source com.android.vending..."
                else -> raw
            }
            "[Root] Đã khôi phục trạng thái Play Protect ban đầu." -> return when (lang) {
                AppLanguage.EN -> "[Root] Restored original Play Protect settings."
                AppLanguage.ZH -> "[Root] 已恢复 Play 保护机制原始状态。"
                AppLanguage.ES -> "[Root] Configuración original de Play Protect restaurada."
                AppLanguage.HI -> "[Root] Play Protect की मूल स्थिति बहाल कर दी गई।"
                AppLanguage.AR -> "[Root] تمت استعادة إعدادات Play Protect الأصلية."
                AppLanguage.PT -> "[Root] Configurações originais do Play Protect restauradas."
                AppLanguage.RU -> "[Root] Исходное состояние Play Защиты восстановлено."
                AppLanguage.JA -> "[Root] Play プロテクトの元の設定を復元しました。"
                AppLanguage.DE -> "[Root] Ursprüngliche Play Protect-Einstellungen wiederhergestellt."
                AppLanguage.FR -> "[Root] Paramètres d'origine de Play Protect restaurés."
                else -> raw
            }
            "[Root] Đã khởi động lại dịch vụ Android Auto (Gearhead) để cập nhật danh sách ứng dụng ngay lập tức." -> return when (lang) {
                AppLanguage.EN -> "[Root] Restarted Android Auto (Gearhead) service to refresh app list immediately."
                AppLanguage.ZH -> "[Root] 已重启 Android Auto (Gearhead) 服务以立即刷新应用列表。"
                AppLanguage.ES -> "[Root] Servicio Android Auto (Gearhead) reiniciado para actualizar la lista de apps."
                AppLanguage.HI -> "[Root] ऐप सूची तुरंत अपडेट करने के लिए Android Auto (Gearhead) सेवा को रीस्टार्ट किया गया।"
                AppLanguage.AR -> "[Root] تمت إعادة تشغيل خدمة Android Auto (Gearhead) لتحديث قائمة التطبيقات فوراً."
                AppLanguage.PT -> "[Root] Serviço Android Auto (Gearhead) reiniciado para atualizar a lista de apps imediatamente."
                AppLanguage.RU -> "[Root] Служба Android Auto (Gearhead) перезапущена для мгновенного обновления списка приложений."
                AppLanguage.JA -> "[Root] アプリ一覧を即時反映するため Android Auto (Gearhead) を再起動しました。"
                AppLanguage.DE -> "[Root] Android Auto (Gearhead) neu gestartet, um die App-Liste sofort zu aktualisieren."
                AppLanguage.FR -> "[Root] Service Android Auto (Gearhead) redémarré pour actualiser la liste des applications."
                else -> raw
            }
            "Thiết bị chưa cấp quyền Root (Superuser) cho AAMaKer." -> return when (lang) {
                AppLanguage.EN -> "Root (Superuser) access is not granted to AAMaKer."
                AppLanguage.ZH -> "设备尚未向 AAMaKer 授予 Root (超级用户) 权限。"
                AppLanguage.ES -> "El dispositivo no ha concedido acceso Root (Superusuario) a AAMaKer."
                AppLanguage.HI -> "डिवाइस ने AAMaKer को Root (Superuser) अनुमति नहीं दी है।"
                AppLanguage.AR -> "لم يمنح الجهاز صلاحية الروت (Superuser) لتطبيق AAMaKer."
                AppLanguage.PT -> "O dispositivo não concedeu acesso Root (Superusuário) ao AAMaKer."
                AppLanguage.RU -> "Устройство не предоставило права Root (Superuser) приложению AAMaKer."
                AppLanguage.JA -> "AAMaKer に Root (スーパーユーザー) 権限が付与されていません。"
                AppLanguage.DE -> "Dem Gerät wurde kein Root-Zugriff (Superuser) für AAMaKer gewährt."
                AppLanguage.FR -> "L'accès Root (Superutilisateur) n'a pas été accordé à AAMaKer."
                else -> raw
            }
            "Đã cài đặt thành công qua Root (với nguồn Google Play Store & tự động cấp quyền AppOps)!" -> return when (lang) {
                AppLanguage.EN -> "Successfully installed via Root (with Google Play Store source & AppOps auto-granted)!"
                AppLanguage.ZH -> "已通过 Root 成功安装 (伪装 Google Play 商店来源并自动授予 AppOps 权限)！"
                AppLanguage.ES -> "¡Instalado con éxito vía Root (con origen Google Play Store y permisos AppOps)!"
                AppLanguage.HI -> "Root के माध्यम से सफलतापूर्वक इंस्टॉल किया गया (Google Play स्रोत और AppOps अनुमतियों के साथ)!"
                AppLanguage.AR -> "تم التثبيت بنجاح عبر الروت (بمصدر متجر Google Play ومنح صلاحيات AppOps تلقائياً)!"
                AppLanguage.PT -> "Instalado com sucesso via Root (com origem Google Play Store e permissões AppOps)!"
                AppLanguage.RU -> "Успешно установлено через Root (с источником Google Play Store и выдачей прав AppOps)!"
                AppLanguage.JA -> "Root 経由で正常にインストールされました (Google Play 提供元＆AppOps 権限自動付与)！"
                AppLanguage.DE -> "Erfolgreich über Root installiert (mit Google Play Store-Quelle & AppOps-Rechten)!"
                AppLanguage.FR -> "Installé avec succès via Root (avec source Google Play Store et permissions AppOps) !"
                else -> raw
            }
            "Dịch vụ Shizuku chưa chạy. Vui lòng mở app Shizuku và kích hoạt trước." -> return when (lang) {
                AppLanguage.EN -> "Shizuku service is not running. Please open the Shizuku app and start it first."
                AppLanguage.ZH -> "Shizuku 服务尚未运行。请先打开 Shizuku 应用并启动服务。"
                AppLanguage.ES -> "El servicio Shizuku no está activo. Abra la app Shizuku e inícielo primero."
                AppLanguage.HI -> "Shizuku सेवा चालू नहीं है। कृपया पहले Shizuku ऐप खोलें और इसे सक्रिय करें।"
                AppLanguage.AR -> "خدمة Shizuku غير مشغلة. يرجى فتح تطبيق Shizuku وتفعيله أولاً."
                AppLanguage.PT -> "O serviço Shizuku não está em execução. Abra o app Shizuku e ative-o primeiro."
                AppLanguage.RU -> "Служба Shizuku не запущена. Откройте приложение Shizuku и запустите его."
                AppLanguage.JA -> "Shizuku サービスが動作していません。Shizuku アプリを開いて起動してください。"
                AppLanguage.DE -> "Shizuku-Dienst läuft nicht. Bitte öffnen Sie zuerst die Shizuku-App und starten Sie sie."
                AppLanguage.FR -> "Le service Shizuku n'est pas actif. Veuillez ouvrir l'application Shizuku et la démarrer."
                else -> raw
            }
            "Chưa cấp quyền Shizuku cho AAMaKer." -> return when (lang) {
                AppLanguage.EN -> "Shizuku permission has not been granted to AAMaKer."
                AppLanguage.ZH -> "尚未向 AAMaKer 授予 Shizuku 权限。"
                AppLanguage.ES -> "No se ha concedido permiso de Shizuku a AAMaKer."
                AppLanguage.HI -> "AAMaKer को Shizuku अनुमति नहीं दी गई है।"
                AppLanguage.AR -> "لم يتم منح صلاحية Shizuku لتطبيق AAMaKer."
                AppLanguage.PT -> "Permissão do Shizuku não concedida ao AAMaKer."
                AppLanguage.RU -> "Приложению AAMaKer не предоставлено разрешение Shizuku."
                AppLanguage.JA -> "AAMaKer に Shizuku の権限が付与されていません。"
                AppLanguage.DE -> "Shizuku-Berechtigung wurde AAMaKer noch nicht erteilt."
                AppLanguage.FR -> "La permission Shizuku n'a pas été accordée à AAMaKer."
                else -> raw
            }
            "[Shizuku] Đang khởi tạo phiên cài đặt với UID 2000 (ADB Shell)..." -> return when (lang) {
                AppLanguage.EN -> "[Shizuku] Initializing install session with UID 2000 (ADB Shell)..."
                AppLanguage.ZH -> "[Shizuku] 正在使用 UID 2000 (ADB Shell) 初始化安装会话..."
                AppLanguage.ES -> "[Shizuku] Iniciando sesión de instalación con UID 2000 (ADB Shell)..."
                AppLanguage.HI -> "[Shizuku] UID 2000 (ADB Shell) के साथ इंस्टॉल सेशन शुरू हो रहा है..."
                AppLanguage.AR -> "[Shizuku] جاري تهيئة جلسة التثبيت بمعرف UID 2000 (ADB Shell)..."
                AppLanguage.PT -> "[Shizuku] Inicializando sessão de instalação com UID 2000 (ADB Shell)..."
                AppLanguage.RU -> "[Shizuku] Инициализация сессии установки с UID 2000 (ADB Shell)..."
                AppLanguage.JA -> "[Shizuku] UID 2000 (ADB Shell) でインストールセッションを初期化中..."
                AppLanguage.DE -> "[Shizuku] Initialisiere Installationssitzung mit UID 2000 (ADB Shell)..."
                AppLanguage.FR -> "[Shizuku] Initialisation de la session d'installation avec UID 2000 (ADB Shell)..."
                else -> raw
            }
            "[Shizuku] Đã khôi phục trạng thái Play Protect ban đầu." -> return when (lang) {
                AppLanguage.EN -> "[Shizuku] Restored original Play Protect settings."
                AppLanguage.ZH -> "[Shizuku] 已恢复 Play 保护机制原始状态。"
                AppLanguage.ES -> "[Shizuku] Configuración original de Play Protect restaurada."
                AppLanguage.HI -> "[Shizuku] Play Protect की मूल स्थिति बहाल कर दी गई।"
                AppLanguage.AR -> "[Shizuku] تمت استعادة إعدادات Play Protect الأصلية."
                AppLanguage.PT -> "[Shizuku] Configurações originais do Play Protect restauradas."
                AppLanguage.RU -> "[Shizuku] Исходное состояние Play Защиты восстановлено."
                AppLanguage.JA -> "[Shizuku] Play プロテクトの元の設定を復元しました。"
                AppLanguage.DE -> "[Shizuku] Ursprüngliche Play Protect-Einstellungen wiederhergestellt."
                AppLanguage.FR -> "[Shizuku] Paramètres d'origine de Play Protect restaurés."
                else -> raw
            }
            "[Shizuku] Đã cấp quyền SYSTEM_ALERT_WINDOW, kích hoạt Touch Bridge & làm mới cache ứng dụng Android Auto (Gearhead)." -> return when (lang) {
                AppLanguage.EN -> "[Shizuku] Granted SYSTEM_ALERT_WINDOW, enabled Touch Bridge & refreshed Android Auto (Gearhead) cache."
                AppLanguage.ZH -> "[Shizuku] 已授予 SYSTEM_ALERT_WINDOW，激活触控桥接并刷新 Android Auto (Gearhead) 缓存。"
                AppLanguage.ES -> "[Shizuku] Permiso SYSTEM_ALERT_WINDOW concedido, Touch Bridge activado y caché de Android Auto actualizada."
                AppLanguage.HI -> "[Shizuku] SYSTEM_ALERT_WINDOW अनुमति दी गई, Touch Bridge सक्रिय किया गया और Android Auto कैश रीफ़्रेश किया गया।"
                AppLanguage.AR -> "[Shizuku] تم منح SYSTEM_ALERT_WINDOW وتفعيل جسر اللمس وتحديث ذاكرة Android Auto."
                AppLanguage.PT -> "[Shizuku] Permissão SYSTEM_ALERT_WINDOW concedida, Touch Bridge ativado e cache do Android Auto atualizado."
                AppLanguage.RU -> "[Shizuku] Выдано право SYSTEM_ALERT_WINDOW, включен Touch Bridge и обновлен кэш Android Auto."
                AppLanguage.JA -> "[Shizuku] SYSTEM_ALERT_WINDOW 権限の付与、Touch Bridge の有効化、Android Auto キャッシュの更新を完了しました。"
                AppLanguage.DE -> "[Shizuku] SYSTEM_ALERT_WINDOW gewährt, Touch Bridge aktiviert & Android Auto-Cache aktualisiert."
                AppLanguage.FR -> "[Shizuku] Permission SYSTEM_ALERT_WINDOW accordée, Touch Bridge activé et cache Android Auto actualisé."
                else -> raw
            }
            "Cài đặt qua Shizuku thành công với nguồn Google Play Store!" -> return when (lang) {
                AppLanguage.EN -> "Successfully installed via Shizuku with Google Play Store source!"
                AppLanguage.ZH -> "已通过 Shizuku 成功安装 (伪装 Google Play 商店来源)！"
                AppLanguage.ES -> "¡Instalado con éxito vía Shizuku con origen Google Play Store!"
                AppLanguage.HI -> "Google Play Store स्रोत के साथ Shizuku के माध्यम से सफलतापूर्वक इंस्टॉल किया गया!"
                AppLanguage.AR -> "تم التثبيت عبر Shizuku بنجاح بمصدر متجر Google Play!"
                AppLanguage.PT -> "Instalado com sucesso via Shizuku com origem Google Play Store!"
                AppLanguage.RU -> "Успешно установлено через Shizuku с источником Google Play Store!"
                AppLanguage.JA -> "Google Play ストア提供元として Shizuku で正常にインストールされました！"
                AppLanguage.DE -> "Erfolgreich über Shizuku mit Google Play Store-Quelle installiert!"
                AppLanguage.FR -> "Installé avec succès via Shizuku avec la source Google Play Store !"
                else -> raw
            }
            "[Non-Root Session] Khởi tạo PackageInstaller.SessionParams..." -> return when (lang) {
                AppLanguage.EN -> "[Non-Root Session] Initializing PackageInstaller.SessionParams..."
                AppLanguage.ZH -> "[Non-Root Session] 正在初始化 PackageInstaller.SessionParams..."
                AppLanguage.ES -> "[Non-Root Session] Inicializando PackageInstaller.SessionParams..."
                AppLanguage.HI -> "[Non-Root Session] PackageInstaller.SessionParams प्रारंभ किया जा रहा है..."
                AppLanguage.AR -> "[Non-Root Session] جاري تهيئة PackageInstaller.SessionParams..."
                AppLanguage.PT -> "[Non-Root Session] Inicializando PackageInstaller.SessionParams..."
                AppLanguage.RU -> "[Non-Root Session] Инициализация PackageInstaller.SessionParams..."
                AppLanguage.JA -> "[Non-Root Session] PackageInstaller.SessionParams を初期化しています..."
                AppLanguage.DE -> "[Non-Root Session] Initialisiere PackageInstaller.SessionParams..."
                AppLanguage.FR -> "[Non-Root Session] Initialisation de PackageInstaller.SessionParams..."
                else -> raw
            }
            "[Non-Root Session] Đang gửi yêu cầu xác nhận cài đặt tới hệ thống..." -> return when (lang) {
                AppLanguage.EN -> "[Non-Root Session] Sending installation confirmation request to system..."
                AppLanguage.ZH -> "[Non-Root Session] 正在向系统发送安装确认请求..."
                AppLanguage.ES -> "[Non-Root Session] Enviando solicitud de confirmación de instalación al sistema..."
                AppLanguage.HI -> "[Non-Root Session] सिस्टम को इंस्टॉलेशन पुष्टि अनुरोध भेजा जा रहा है..."
                AppLanguage.AR -> "[Non-Root Session] جاري إرسال طلب تأكيد التثبيت إلى النظام..."
                AppLanguage.PT -> "[Non-Root Session] Enviando solicitação de confirmação de instalação ao sistema..."
                AppLanguage.RU -> "[Non-Root Session] Отправка запроса подтверждения установки в систему..."
                AppLanguage.JA -> "[Non-Root Session] システムにインストール確認リクエストを送信中..."
                AppLanguage.DE -> "[Non-Root Session] Sende Installationsbestätigungsanfrage an das System..."
                AppLanguage.FR -> "[Non-Root Session] Envoi de la demande de confirmation d'installation au système..."
                else -> raw
            }
            "Đang chờ người dùng xác nhận hộp thoại cài đặt trên màn hình..." -> return when (lang) {
                AppLanguage.EN -> "Waiting for user to confirm the installation dialog on screen..."
                AppLanguage.ZH -> "正在等待用户在屏幕上确认安装对话框..."
                AppLanguage.ES -> "Esperando a que el usuario confirme el diálogo de instalación en pantalla..."
                AppLanguage.HI -> "स्क्रीन पर इंस्टॉलेशन संवाद की पुष्टि करने के लिए उपयोगकर्ता की प्रतीक्षा की जा रही है..."
                AppLanguage.AR -> "في انتظار تأكيد المستخدم لنافذة التثبيت على الشاشة..."
                AppLanguage.PT -> "Aguardando confirmação do usuário na caixa de diálogo de instalação..."
                AppLanguage.RU -> "Ожидание подтверждения установки пользователем на экране..."
                AppLanguage.JA -> "画面上のインストール確認ダイアログの承認を待っています..."
                AppLanguage.DE -> "Warte auf Bestätigung des Installationsdialogs durch den Benutzer..."
                AppLanguage.FR -> "En attente de la confirmation de l'utilisateur sur l'écran..."
                else -> raw
            }
        }

        REGEX_PAT_0.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Shizuku] Permission request error: $err"
                AppLanguage.ZH -> "[Shizuku] 权限请求错误: $err"
                AppLanguage.ES -> "[Shizuku] Error al solicitar permiso: $err"
                AppLanguage.HI -> "[Shizuku] अनुमति अनुरोध त्रुटि: $err"
                AppLanguage.AR -> "[Shizuku] خطأ في طلب الصلاحية: $err"
                AppLanguage.PT -> "[Shizuku] Erro ao solicitar permissão: $err"
                AppLanguage.RU -> "[Shizuku] Ошибка запроса прав: $err"
                AppLanguage.JA -> "[Shizuku] 権限リクエストエラー: $err"
                AppLanguage.DE -> "[Shizuku] Fehler bei der Berechtigungsanfrage: $err"
                AppLanguage.FR -> "[Shizuku] Erreur lors de la demande de permission : $err"
                else -> raw
            }
        }

        REGEX_PAT_1.matchEntire(raw)?.let { m ->
            val info = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Loaded: $info"
                AppLanguage.ZH -> "已加载: $info"
                AppLanguage.ES -> "Cargado: $info"
                AppLanguage.HI -> "लोड किया गया: $info"
                AppLanguage.AR -> "تم تحميل: $info"
                AppLanguage.PT -> "Carregado: $info"
                AppLanguage.RU -> "Загружено: $info"
                AppLanguage.JA -> "読み込み完了: $info"
                AppLanguage.DE -> "Geladen: $info"
                AppLanguage.FR -> "Chargé : $info"
                else -> raw
            }
        }

        REGEX_PAT_2.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Export] Error saving file: $err"
                AppLanguage.VI -> "[Export] Lỗi khi lưu file: $err"
                AppLanguage.ZH -> "[导出] 保存文件出错: $err"
                AppLanguage.ES -> "[Exportar] Error al guardar archivo: $err"
                AppLanguage.HI -> "[निर्यात] फ़ाइल सहेजने में त्रुटि: $err"
                AppLanguage.AR -> "[تصدير] خطأ أثناء حفظ الملف: $err"
                AppLanguage.PT -> "[Exportar] Erro ao salvar arquivo: $err"
                AppLanguage.RU -> "[Экспорт] Ошибка сохранения файла: $err"
                AppLanguage.JA -> "[エクスポート] ファイル保存エラー: $err"
                AppLanguage.DE -> "[Export] Fehler beim Speichern der Datei: $err"
                AppLanguage.FR -> "[Export] Erreur lors de l'enregistrement du fichier : $err"
            }
        }

        REGEX_PAT_3.matchEntire(raw)?.let { m ->
            val kb = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Export] Exported patched APK successfully ($kb KB)."
                AppLanguage.VI -> "[Export] Đã xuất file APK đã patch thành công ($kb KB)."
                AppLanguage.ZH -> "[导出] 已成功导出修补后的 APK ($kb KB)。"
                AppLanguage.ES -> "[Exportar] APK parcheado exportado con éxito ($kb KB)."
                AppLanguage.HI -> "[निर्यात] पैच किया गया APK सफलतापूर्वक निर्यात किया गया ($kb KB)।"
                AppLanguage.AR -> "[تصدير] تم تصدير ملف APK المعدل بنجاح ($kb KB)."
                AppLanguage.PT -> "[Exportar] APK modificado exportado com sucesso ($kb KB)."
                AppLanguage.RU -> "[Экспорт] Пропатченный APK успешно экспортирован ($kb KB)."
                AppLanguage.JA -> "[エクスポート] パッチ済み APK を正常に出力しました ($kb KB)。"
                AppLanguage.DE -> "[Export] Gepatchte APK erfolgreich exportiert ($kb KB)."
                AppLanguage.FR -> "[Export] APK patché exporté avec succès ($kb KB)."
            }
        }

        REGEX_PAT_4.matchEntire(raw)?.let { m ->
            val path = m.groupValues[1]
            val kb = m.groupValues[2]
            return when (lang) {
                AppLanguage.EN -> "[Storage] Saved patched APK to device storage: $path ($kb KB)"
                AppLanguage.ZH -> "[存储] 已将修补后的 APK 保存到设备存储: $path ($kb KB)"
                AppLanguage.ES -> "[Almacenamiento] APK parcheado guardado en el dispositivo: $path ($kb KB)"
                AppLanguage.HI -> "[स्टोरेज] पैच किया गया APK डिवाइस स्टोरेज में सहेजा गया: $path ($kb KB)"
                AppLanguage.AR -> "[التخزين] تم حفظ ملف APK المعدل في ذاكرة الجهاز: $path ($kb KB)"
                AppLanguage.PT -> "[Armazenamento] APK modificado salvo no dispositivo: $path ($kb KB)"
                AppLanguage.RU -> "[Память] Пропатченный APK сохранен в память устройства: $path ($kb KB)"
                AppLanguage.JA -> "[ストレージ] パッチ済み APK を端末に保存しました: $path ($kb KB)"
                AppLanguage.DE -> "[Speicher] Gepatchte APK im Gerätespeicher abgelegt: $path ($kb KB)"
                AppLanguage.FR -> "[Stockage] APK patché enregistré dans le stockage : $path ($kb KB)"
                else -> raw
            }
        }

        REGEX_PAT_5.matchEntire(raw)?.let { m ->
            val path = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Storage] Saved patched APK to: $path"
                AppLanguage.ZH -> "[存储] 已将修补后的 APK 保存至: $path"
                AppLanguage.ES -> "[Almacenamiento] APK parcheado guardado en: $path"
                AppLanguage.HI -> "[स्टोरेज] पैच किया गया APK यहाँ सहेजा गया: $path"
                AppLanguage.AR -> "[التخزين] تم حفظ ملف APK المعدل في: $path"
                AppLanguage.PT -> "[Armazenamento] APK modificado salvo em: $path"
                AppLanguage.RU -> "[Память] Пропатченный APK сохранен в: $path"
                AppLanguage.JA -> "[ストレージ] パッチ済み APK の保存先: $path"
                AppLanguage.DE -> "[Speicher] Gepatchte APK gespeichert unter: $path"
                AppLanguage.FR -> "[Stockage] APK patché enregistré dans : $path"
                else -> raw
            }
        }

        REGEX_PAT_6.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Storage] Warning while saving to Download/AAMaKer: $err"
                AppLanguage.ZH -> "[存储] 保存到 Download/AAMaKer 时出现警告: $err"
                AppLanguage.ES -> "[Almacenamiento] Advertencia al guardar en Download/AAMaKer: $err"
                AppLanguage.HI -> "[स्टोरेज] Download/AAMaKer में सहेजते समय चेतावनी: $err"
                AppLanguage.AR -> "[التخزين] تحذير أثناء الحفظ في Download/AAMaKer: $err"
                AppLanguage.PT -> "[Armazenamento] Aviso ao salvar em Download/AAMaKer: $err"
                AppLanguage.RU -> "[Память] Предупреждение при сохранении в Download/AAMaKer: $err"
                AppLanguage.JA -> "[ストレージ] Download/AAMaKer への保存時の警告: $err"
                AppLanguage.DE -> "[Speicher] Warnung beim Speichern in Download/AAMaKer: $err"
                AppLanguage.FR -> "[Stockage] Avertissement lors de l'enregistrement dans Download/AAMaKer : $err"
                else -> raw
            }
        }

        REGEX_PAT_7.matchEntire(raw)?.let { m ->
            val rawModeTitle = m.groupValues[1]
            val localizedMode = InstallMode.entries.firstOrNull { it.title == rawModeTitle || it.name == rawModeTitle }
                ?.let { strings.installModeTitle(it) } ?: rawModeTitle
            return when (lang) {
                AppLanguage.EN -> "--- Starting system installation with mode: $localizedMode ---"
                AppLanguage.ZH -> "--- 开始系统安装，当前模式: $localizedMode ---"
                AppLanguage.ES -> "--- Iniciando instalación en el sistema en modo: $localizedMode ---"
                AppLanguage.HI -> "--- इस मोड के साथ सिस्टम इंस्टॉलेशन शुरू हो रहा है: $localizedMode ---"
                AppLanguage.AR -> "--- بدء التثبيت على النظام بالوضع: $localizedMode ---"
                AppLanguage.PT -> "--- Iniciando instalação no sistema com o modo: $localizedMode ---"
                AppLanguage.RU -> "--- Запуск установки в систему в режиме: $localizedMode ---"
                AppLanguage.JA -> "--- 次のモードでシステムへのインストールを開始: $localizedMode ---"
                AppLanguage.DE -> "--- Starte Systeminstallation im Modus: $localizedMode ---"
                AppLanguage.FR -> "--- Démarrage de l'installation système en mode : $localizedMode ---"
                else -> raw
            }
        }

        REGEX_PAT_8.matchEntire(raw)?.let { m ->
            val app = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Source application: $app"
                AppLanguage.ZH -> "原始应用: $app"
                AppLanguage.ES -> "Aplicación original: $app"
                AppLanguage.HI -> "मूल ऐप: $app"
                AppLanguage.AR -> "التطبيق الأصلي: $app"
                AppLanguage.PT -> "Aplicativo original: $app"
                AppLanguage.RU -> "Исходное приложение: $app"
                AppLanguage.JA -> "対象アプリ: $app"
                AppLanguage.DE -> "Ursprüngliche App: $app"
                AppLanguage.FR -> "Application d'origine : $app"
                else -> raw
            }
        }

        REGEX_PAT_9.matchEntire(raw)?.let { m ->
            val rawStrategy = m.groupValues[1]
            val localizedStrategy = PatchStrategy.entries.firstOrNull { it.title == rawStrategy || it.name == rawStrategy }
                ?.let { strings.strategyTitle(it) } ?: rawStrategy
            return when (lang) {
                AppLanguage.EN -> "Patch strategy: $localizedStrategy"
                AppLanguage.ZH -> "修补策略: $localizedStrategy"
                AppLanguage.ES -> "Estrategia de parcheo: $localizedStrategy"
                AppLanguage.HI -> "पैच मोड: $localizedStrategy"
                AppLanguage.AR -> "استراتيجية التعديل: $localizedStrategy"
                AppLanguage.PT -> "Estratégia de patch: $localizedStrategy"
                AppLanguage.RU -> "Режим патча: $localizedStrategy"
                AppLanguage.JA -> "パッチモード: $localizedStrategy"
                AppLanguage.DE -> "Patch-Strategie: $localizedStrategy"
                AppLanguage.FR -> "Stratégie de patch : $localizedStrategy"
                else -> raw
            }
        }

        REGEX_PAT_10.matchEntire(raw)?.let { m ->
            val ms = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "=== PATCH COMPLETED IN ${ms}ms ==="
                AppLanguage.ZH -> "=== 修补完成，耗时 ${ms}ms ==="
                AppLanguage.ES -> "=== PARCHEO COMPLETADO EN ${ms}ms ==="
                AppLanguage.HI -> "=== पैच ${ms}ms में पूरा हुआ ==="
                AppLanguage.AR -> "=== اكتمل التعديل خلال ${ms}ms ==="
                AppLanguage.PT -> "=== PATCH CONCLUÍDO EM ${ms}ms ==="
                AppLanguage.RU -> "=== ПАТЧ ЗАВЕРШЕН ЗА ${ms}мс ==="
                AppLanguage.JA -> "=== パッチ完了 (${ms}ms) ==="
                AppLanguage.DE -> "=== PATCH ABGESCHLOSSEN IN ${ms}ms ==="
                AppLanguage.FR -> "=== PATCH TERMINÉ EN ${ms}ms ==="
                else -> raw
            }
        }

        REGEX_PAT_11.matchEntire(raw)?.let { m ->
            val path = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Output file: $path"
                AppLanguage.ZH -> "输出文件: $path"
                AppLanguage.ES -> "Archivo de salida: $path"
                AppLanguage.HI -> "आउटपुट फ़ाइल: $path"
                AppLanguage.AR -> "ملف الإخراج: $path"
                AppLanguage.PT -> "Arquivo de saída: $path"
                AppLanguage.RU -> "Выходной файл: $path"
                AppLanguage.JA -> "出力ファイル: $path"
                AppLanguage.DE -> "Ausgabedatei: $path"
                AppLanguage.FR -> "Fichier de sortie : $path"
                else -> raw
            }
        }

        REGEX_PAT_12.matchEntire(raw)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Direct Install] Detected bundle ($name), extracting original APKs (preserving signature)..."
                AppLanguage.ZH -> "[Direct Install] 检测到安装包集合 ($name)，正在解压原版 APK (保留原始签名)..."
                AppLanguage.ES -> "[Direct Install] Paquete Bundle detectado ($name), extrayendo APKs originales (conservando firma)..."
                AppLanguage.HI -> "[Direct Install] बंडल ($name) मिला, मूल APK निकाले जा रहे हैं (हस्ताक्षर सुरक्षित)..."
                AppLanguage.AR -> "[Direct Install] تم اكتشاف حزمة ($name)، جاري استخراج ملفات APK الأصلية (مع الحفاظ على التوقيع)..."
                AppLanguage.PT -> "[Direct Install] Bundle detectado ($name), extraindo APKs originais (mantendo assinatura)..."
                AppLanguage.RU -> "[Direct Install] Обнаружен бандл ($name), распаковка оригинальных APK (с сохранением подписи)..."
                AppLanguage.JA -> "[Direct Install] バンドル ($name) を検出、元の署名を維持して APK を展開中..."
                AppLanguage.DE -> "[Direct Install] Bundle ($name) erkannt, entpacke Original-APKs (Signatur bleibt erhalten)..."
                AppLanguage.FR -> "[Direct Install] Bundle ($name) détecté, extraction des APK originaux (signature conservée)..."
                else -> raw
            }
        }

        REGEX_PAT_13.matchEntire(raw)?.let { m ->
            val count = m.groupValues[1]
            val pkg = m.groupValues[2]
            return when (lang) {
                AppLanguage.EN -> "[Direct Install] Installing $count original APK file(s) (preserving original signature) for $pkg..."
                AppLanguage.ZH -> "[Direct Install] 正在为 $pkg 安装 $count 个原版 APK 文件 (保留原始数字签名)..."
                AppLanguage.ES -> "[Direct Install] Instalando $count archivo(s) APK original(es) (conservando firma original) para $pkg..."
                AppLanguage.HI -> "[Direct Install] $pkg के लिए $count मूल APK फ़ाइल(लें) इंस्टॉल की जा रही हैं (मूल हस्ताक्षर सहित)..."
                AppLanguage.AR -> "[Direct Install] جاري تثبيت $count ملف APK أصلي (مع الحفاظ على التوقيع الأصلي) للحزمة $pkg..."
                AppLanguage.PT -> "[Direct Install] Instalando $count arquivo(s) APK original(is) (mantendo assinatura original) para $pkg..."
                AppLanguage.RU -> "[Direct Install] Установка $count оригинальных файлов APK (с сохранением исходной подписи) для $pkg..."
                AppLanguage.JA -> "[Direct Install] $pkg の元の署名を維持したまま $count 個のオリジナル APK を導入中..."
                AppLanguage.DE -> "[Direct Install] Installiere $count Original-APK-Datei(en) (mit Originalsignatur) für $pkg..."
                AppLanguage.FR -> "[Direct Install] Installation de $count fichier(s) APK original(aux) (signature d'origine conservée) pour $pkg..."
                else -> raw
            }
        }

        REGEX_PAT_14.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Direct Install] Error preparing original APK: $err"
                AppLanguage.ZH -> "[Direct Install] 准备原版 APK 出错: $err"
                AppLanguage.ES -> "[Direct Install] Error al preparar APK original: $err"
                AppLanguage.HI -> "[Direct Install] मूल APK तैयार करने में त्रुटि: $err"
                AppLanguage.AR -> "[Direct Install] خطأ في تحضير ملف APK الأصلي: $err"
                AppLanguage.PT -> "[Direct Install] Erro ao preparar APK original: $err"
                AppLanguage.RU -> "[Direct Install] Ошибка подготовки оригинального APK: $err"
                AppLanguage.JA -> "[Direct Install] 元の APK の準備中にエラー: $err"
                AppLanguage.DE -> "[Direct Install] Fehler beim Vorbereiten der Original-APK: $err"
                AppLanguage.FR -> "[Direct Install] Erreur lors de la préparation de l'APK original : $err"
                else -> raw
            }
        }

        REGEX_PAT_15.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Failed to read original APK file: $err"
                AppLanguage.ZH -> "读取原版 APK 文件失败: $err"
                AppLanguage.ES -> "Error al leer el archivo APK original: $err"
                AppLanguage.HI -> "मूल APK फ़ाइल पढ़ने में त्रुटि: $err"
                AppLanguage.AR -> "فشل قراءة ملف APK الأصلي: $err"
                AppLanguage.PT -> "Erro ao ler arquivo APK original: $err"
                AppLanguage.RU -> "Ошибка чтения оригинального файла APK: $err"
                AppLanguage.JA -> "元の APK ファイルの読み込みエラー: $err"
                AppLanguage.DE -> "Fehler beim Lesen der Original-APK-Datei: $err"
                AppLanguage.FR -> "Échec de la lecture du fichier APK original : $err"
                else -> raw
            }
        }

        REGEX_PAT_16.matchEntire(raw)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Merger] Detected bundle ($name), extracting component APKs..."
                AppLanguage.ZH -> "[Merger] 检测到安装包集合 ($name)，正在解压组件 APK..."
                AppLanguage.ES -> "[Merger] Paquete Bundle detectado ($name), extrayendo APKs..."
                AppLanguage.HI -> "[Merger] बंडल पैकेज ($name) मिला, घटक APK निकाले जा रहे हैं..."
                AppLanguage.AR -> "[Merger] تم اكتشاف حزمة ($name)، جاري استخراج ملفات APK الفرعية..."
                AppLanguage.PT -> "[Merger] Pacote Bundle detectado ($name), extraindo APKs componentes..."
                AppLanguage.RU -> "[Merger] Обнаружен бандл ($name), распаковка компонентов APK..."
                AppLanguage.JA -> "[Merger] バンドル ($name) を検出、構成 APK を展開しています..."
                AppLanguage.DE -> "[Merger] Bundle erkannt ($name), entpacke Teil-APKs..."
                AppLanguage.FR -> "[Merger] Bundle détecté ($name), extraction des APK composants..."
                else -> raw
            }
        }

        REGEX_PAT_17.matchEntire(raw)?.let { m ->
            val count = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Merger] Detected $count Split APKs, merging binary structure (DEX + resources.arsc + Native Libs + Assets)..."
                AppLanguage.ZH -> "[Merger] 检测到 $count 个分包 APK，正在合并二进制结构 (DEX + resources.arsc + Native Libs + Assets)..."
                AppLanguage.ES -> "[Merger] Detectados $count Split APKs, fusionando estructura binaria (DEX + resources.arsc + Native Libs + Assets)..."
                AppLanguage.HI -> "[Merger] $count Split APKs मिले, बाइनरी संरचना मर्ज की जा रही है (DEX + resources.arsc + Native Libs + Assets)..."
                AppLanguage.AR -> "[Merger] تم اكتشاف $count ملفات APK مقسمة، جاري دمج البنية الثنائية (DEX + resources.arsc + Native Libs + Assets)..."
                AppLanguage.PT -> "[Merger] Detectados $count Split APKs, mesclando estrutura binária (DEX + resources.arsc + Native Libs + Assets)..."
                AppLanguage.RU -> "[Merger] Обнаружено $count Split APK, объединение бинарной структуры (DEX + resources.arsc + Native Libs + Assets)..."
                AppLanguage.JA -> "[Merger] $count 個の分割 APK を検出、バイナリ構造を統合しています (DEX + resources.arsc + Native Libs + Assets)..."
                AppLanguage.DE -> "[Merger] $count Split-APKs erkannt, führe binäre Struktur zusammen (DEX + resources.arsc + Native Libs + Assets)..."
                AppLanguage.FR -> "[Merger] $count Split APKs détectés, fusion de la structure binaire (DEX + resources.arsc + Native Libs + Assets)..."
                else -> raw
            }
        }

        REGEX_PAT_17B.matchEntire(raw)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Merger] + Loaded base APK: $name"
                AppLanguage.ZH -> "[Merger] + 已加载基础 APK: $name"
                AppLanguage.ES -> "[Merger] + APK base cargado: $name"
                AppLanguage.HI -> "[Merger] + बेस APK लोड किया गया: $name"
                AppLanguage.AR -> "[Merger] + تم تحميل ملف APK الأساسي: $name"
                AppLanguage.PT -> "[Merger] + APK base carregado: $name"
                AppLanguage.RU -> "[Merger] + Загружен базовый APK: $name"
                AppLanguage.JA -> "[Merger] + ベース APK を読み込みました: $name"
                AppLanguage.DE -> "[Merger] + Basis-APK geladen: $name"
                AppLanguage.FR -> "[Merger] + APK de base chargé : $name"
                else -> raw
            }
        }

        REGEX_PAT_17C.matchEntire(raw)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Merger] + Loaded Split APK: $name"
                AppLanguage.ZH -> "[Merger] + 已加载 Split APK: $name"
                AppLanguage.ES -> "[Merger] + Split APK cargado: $name"
                AppLanguage.HI -> "[Merger] + Split APK लोड किया गया: $name"
                AppLanguage.AR -> "[Merger] + تم تحميل Split APK: $name"
                AppLanguage.PT -> "[Merger] + Split APK carregado: $name"
                AppLanguage.RU -> "[Merger] + Загружен Split APK: $name"
                AppLanguage.JA -> "[Merger] + 分割 APK を読み込みました: $name"
                AppLanguage.DE -> "[Merger] + Split-APK geladen: $name"
                AppLanguage.FR -> "[Merger] + Split APK chargé : $name"
                else -> raw
            }
        }

        REGEX_PAT_18.matchEntire(raw)?.let { m ->
            val name = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Merger] Merging components from $name..."
                AppLanguage.ZH -> "[Merger] 正在合并来自 $name 的组件..."
                AppLanguage.ES -> "[Merger] Fusionando componentes de $name..."
                AppLanguage.HI -> "[Merger] $name से घटक मर्ज किए जा रहे हैं..."
                AppLanguage.AR -> "[Merger] جاري دمج المكونات من $name..."
                AppLanguage.PT -> "[Merger] Mesclando componentes de $name..."
                AppLanguage.RU -> "[Merger] Объединение компонентов из $name..."
                AppLanguage.JA -> "[Merger] $name からコンポーネントを統合中..."
                AppLanguage.DE -> "[Merger] Führe Komponenten aus $name zusammen..."
                AppLanguage.FR -> "[Merger] Fusion des composants de $name..."
                else -> raw
            }
        }

        REGEX_PAT_19.matchEntire(raw)?.let { m ->
            val apkCount = m.groupValues[1].takeIf { it.isNotEmpty() }
            val dexCount = m.groupValues[2].takeIf { it.isNotEmpty() }
            val kb = m.groupValues[3].takeIf { it.isNotEmpty() }
            return when (lang) {
                AppLanguage.EN -> if (apkCount != null) {
                    "[Merger] Successfully merged $apkCount APKs into a single module ($dexCount DEX)."
                } else {
                    "[Merger] Successfully merged Split APKs ($kb KB)."
                }
                AppLanguage.ZH -> if (apkCount != null) {
                    "[Merger] 已将 $apkCount 个 APK 完整合并为 1 个独立模块 ($dexCount DEX)。"
                } else {
                    "[Merger] 已成功合并分包 APK ($kb KB)。"
                }
                AppLanguage.ES -> if (apkCount != null) {
                    "[Merger] $apkCount APKs fusionados completamente en 1 módulo ($dexCount DEX)."
                } else {
                    "[Merger] Split APKs fusionados con éxito ($kb KB)."
                }
                AppLanguage.HI -> if (apkCount != null) {
                    "[Merger] $apkCount APKs को 1 मॉड्यूल ($dexCount DEX) में पूरी तरह मर्ज किया गया।"
                } else {
                    "[Merger] Split APKs सफलतापूर्वक मर्ज किए गए ($kb KB)।"
                }
                AppLanguage.AR -> if (apkCount != null) {
                    "[Merger] تم دمج $apkCount ملفات APK بالكامل في وحدة واحدة ($dexCount DEX)."
                } else {
                    "[Merger] تم دمج ملفات APK المقسمة بنجاح ($kb KB)."
                }
                AppLanguage.PT -> if (apkCount != null) {
                    "[Merger] $apkCount APKs mesclados em 1 módulo único ($dexCount DEX)."
                } else {
                    "[Merger] Split APKs mesclados com sucesso ($kb KB)."
                }
                AppLanguage.RU -> if (apkCount != null) {
                    "[Merger] Полностью объединены $apkCount APK в 1 модуль ($dexCount DEX)."
                } else {
                    "[Merger] Split APK успешно объединены ($kb KB)."
                }
                AppLanguage.JA -> if (apkCount != null) {
                    "[Merger] $apkCount 個の APK を 1 つのモジュールに完全統合しました ($dexCount DEX)。"
                } else {
                    "[Merger] 分割 APK の統合が完了しました ($kb KB)。"
                }
                AppLanguage.DE -> if (apkCount != null) {
                    "[Merger] $apkCount APKs vollständig zu 1 Modul zusammengeführt ($dexCount DEX)."
                } else {
                    "[Merger] Split-APKs erfolgreich zusammengeführt ($kb KB)."
                }
                AppLanguage.FR -> if (apkCount != null) {
                    "[Merger] $apkCount APKs fusionnés en 1 module unique ($dexCount DEX)."
                } else {
                    "[Merger] Split APKs fusionnés avec succès ($kb KB)."
                }
                else -> raw
            }
        }

        REGEX_PAT_19B.matchEntire(raw)?.let { m ->
            val count = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Merger] + Merged $count Feature Split component(s) into AndroidManifest.xml."
                AppLanguage.ZH -> "[Merger] + 已将 $count 个 Feature Split 组件合并至 AndroidManifest.xml。"
                AppLanguage.ES -> "[Merger] + Fusionados $count componente(s) Feature Split en AndroidManifest.xml."
                AppLanguage.HI -> "[Merger] + AndroidManifest.xml में $count Feature Split घटक मर्ज किए गए।"
                AppLanguage.AR -> "[Merger] + تم دمج $count مكون Feature Split في AndroidManifest.xml."
                AppLanguage.PT -> "[Merger] + $count componente(s) Feature Split mesclados no AndroidManifest.xml."
                AppLanguage.RU -> "[Merger] + Объединено $count компонентов Feature Split в AndroidManifest.xml."
                AppLanguage.JA -> "[Merger] + $count 個の Feature Split コンポーネントを AndroidManifest.xml に統合しました。"
                AppLanguage.DE -> "[Merger] + $count Feature Split-Komponente(n) in AndroidManifest.xml integriert."
                AppLanguage.FR -> "[Merger] + $count composant(s) Feature Split fusionnés dans AndroidManifest.xml."
                else -> raw
            }
        }

        REGEX_PAT_19C.matchEntire(raw)?.let { m ->
            val abi = m.groupValues[1]
            val current = m.groupValues[2]
            val max = m.groupValues[3]
            return when (lang) {
                AppLanguage.EN -> "[Merger] + Cleaned incomplete ABI folder 'lib/$abi' ($current/$max .so libs) to prevent UnsatisfiedLinkError."
                AppLanguage.ZH -> "[Merger] + 已清理不完整的 ABI 目录 'lib/$abi' ($current/$max 个 .so 库) 以避免 UnsatisfiedLinkError 崩溃。"
                AppLanguage.ES -> "[Merger] + Limpiada carpeta ABI incompleta 'lib/$abi' ($current/$max librerías .so) para evitar UnsatisfiedLinkError."
                AppLanguage.HI -> "[Merger] + UnsatisfiedLinkError से बचने के लिए अधूरी ABI निर्देशिका 'lib/$abi' ($current/$max .so लाइब्रेरी) साफ़ की गई।"
                AppLanguage.AR -> "[Merger] + تم تنظيف دليل ABI غير المكتمل 'lib/$abi' ($current/$max مكتبات .so) لتجنب UnsatisfiedLinkError."
                AppLanguage.PT -> "[Merger] + Limpo diretório ABI incompleto 'lib/$abi' ($current/$max libs .so) para evitar UnsatisfiedLinkError."
                AppLanguage.RU -> "[Merger] + Очищен неполный каталог ABI 'lib/$abi' ($current/$max библиотек .so) для защиты от UnsatisfiedLinkError."
                AppLanguage.JA -> "[Merger] + UnsatisfiedLinkError を防ぐため、不完全な ABI ディレクトリ 'lib/$abi' ($current/$max 個の .so) を整理しました。"
                AppLanguage.DE -> "[Merger] + Unvollständigen ABI-Ordner 'lib/$abi' ($current/$max .so-Libs) bereinigt, um UnsatisfiedLinkError zu verhindern."
                AppLanguage.FR -> "[Merger] + Dossier ABI incomplet 'lib/$abi' nettoyé ($current/$max libs .so) pour éviter UnsatisfiedLinkError."
                else -> raw
            }
        }

        REGEX_PAT_20.matchEntire(raw)?.let { m ->
            val obb = m.groupValues[1].takeIf { it.isNotEmpty() } ?: m.groupValues[3]
            val target = m.groupValues[2].takeIf { it.isNotEmpty() }
            return when (lang) {
                AppLanguage.EN -> if (target != null) "[Merger] + Bundled OBB asset: $obb -> $target" else "[Merger] + Bundled OBB data: $obb"
                AppLanguage.ZH -> if (target != null) "[Merger] + 已整合 OBB 资源: $obb -> $target" else "[Merger] + 已合并 OBB 数据: $obb"
                AppLanguage.ES -> if (target != null) "[Merger] + Recurso OBB integrado: $obb -> $target" else "[Merger] + Datos OBB agrupados: $obb"
                AppLanguage.HI -> if (target != null) "[Merger] + OBB संसाधन एकीकृत: $obb -> $target" else "[Merger] + OBB डेटा समूहीकृत: $obb"
                AppLanguage.AR -> if (target != null) "[Merger] + تم دمج ملف OBB: $obb -> $target" else "[Merger] + تجميع بيانات OBB: $obb"
                AppLanguage.PT -> if (target != null) "[Merger] + Recurso OBB integrado: $obb -> $target" else "[Merger] + Dados OBB agrupados: $obb"
                AppLanguage.RU -> if (target != null) "[Merger] + Интегрирован ресурс OBB: $obb -> $target" else "[Merger] + Данные OBB объединены: $obb"
                AppLanguage.JA -> if (target != null) "[Merger] + OBB アセットを統合: $obb -> $target" else "[Merger] + OBB データを結合: $obb"
                AppLanguage.DE -> if (target != null) "[Merger] + OBB-Ressource integriert: $obb -> $target" else "[Merger] + OBB-Daten zusammengeführt: $obb"
                AppLanguage.FR -> if (target != null) "[Merger] + Ressource OBB intégrée : $obb -> $target" else "[Merger] + Données OBB regroupées : $obb"
                else -> raw
            }
        }

        REGEX_PAT_21.matchEntire(raw)?.let { m ->
            val count = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Enabled Parked App mode (appCategory=game, CAR_LAUNCHER & distractionOptimized) for $count Launcher Activity(s)."
                AppLanguage.ZH -> "[Patcher] + 已为 $count 个启动器 Activity 启用驻车应用模式 (appCategory=game, CAR_LAUNCHER & distractionOptimized)。"
                AppLanguage.ES -> "[Patcher] + Modo Parked App activado (appCategory=game, CAR_LAUNCHER y distractionOptimized) para $count Launcher Activity(s)."
                AppLanguage.HI -> "[Patcher] + $count लॉन्चर Activity के लिए Parked App मोड (appCategory=game, CAR_LAUNCHER & distractionOptimized) सक्रिय किया गया।"
                AppLanguage.AR -> "[Patcher] + تم تفعيل وضع Parked App (appCategory=game و CAR_LAUNCHER و distractionOptimized) لعدد $count نشاط مشغل."
                AppLanguage.PT -> "[Patcher] + Modo Parked App ativado (appCategory=game, CAR_LAUNCHER e distractionOptimized) para $count Launcher Activity(s)."
                AppLanguage.RU -> "[Patcher] + Включен режим Parked App (appCategory=game, CAR_LAUNCHER и distractionOptimized) для $count Launcher Activity."
                AppLanguage.JA -> "[Patcher] + $count 個のランチャー Activity に駐車アプリモード (appCategory=game, CAR_LAUNCHER, distractionOptimized) を有効化しました。"
                AppLanguage.DE -> "[Patcher] + Parked-App-Modus (appCategory=game, CAR_LAUNCHER & distractionOptimized) für $count Launcher-Activity(s) aktiviert."
                AppLanguage.FR -> "[Patcher] + Mode Parked App activé (appCategory=game, CAR_LAUNCHER & distractionOptimized) pour $count Launcher Activity(s)."
                else -> raw
            }
        }

        REGEX_PAT_21B.matchEntire(raw)?.let { m ->
            val count = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Normalized $count compressed resource table(s) (Sparse/Compact TypeBlock) to standard format to preserve resource IDs."
                AppLanguage.ZH -> "[Patcher] + 已将 $count 个压缩资源表 (Sparse/Compact TypeBlock) 规格化为标准格式以保留资源 ID。"
                AppLanguage.ES -> "[Patcher] + Normalizadas $count tabla(s) de recursos comprimidos (Sparse/Compact TypeBlock) a formato estándar para preservar IDs de recursos."
                AppLanguage.HI -> "[Patcher] + रिसोर्स ID सुरक्षित रखने के लिए $count संपीड़ित संसाधन तालिकाओं (Sparse/Compact TypeBlock) को मानक प्रारूप में सामान्यीकृत किया गया।"
                AppLanguage.AR -> "[Patcher] + تم توحيد $count جدول موارد مضغوط (Sparse/Compact TypeBlock) إلى التنسيق القياسي للحفاظ على معرفات الموارد."
                AppLanguage.PT -> "[Patcher] + Normalizadas $count tabela(s) de recursos compactadas (Sparse/Compact TypeBlock) para o formato padrão para preservar os IDs dos recursos."
                AppLanguage.RU -> "[Patcher] + Нормализовано $count сжатых таблиц ресурсов (Sparse/Compact TypeBlock) в стандартный формат для сохранения ID ресурсов."
                AppLanguage.JA -> "[Patcher] + リソース ID を保持するため、$count 個の圧縮リソーステーブル (Sparse/Compact TypeBlock) を標準形式に正規化しました。"
                AppLanguage.DE -> "[Patcher] + $count komprimierte Ressourcentabelle(n) (Sparse/Compact TypeBlock) in das Standardformat konvertiert, um Ressourcen-IDs zu erhalten."
                AppLanguage.FR -> "[Patcher] + Normalisation de $count table(s) de ressources compressées (Sparse/Compact TypeBlock) au format standard pour préserver les ID de ressources."
                else -> raw
            }
        }

        REGEX_PAT_22.matchEntire(raw)?.let { m ->
            val gamepad = m.groupValues[1]
            val count = m.groupValues[2]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Hardware features optimized: Gamepad support=$gamepad, relaxed $count required uses-feature(s)."
                AppLanguage.ZH -> "[Patcher] + 硬件特性已优化：手柄支持=$gamepad，已解除 $count 个强制 uses-feature 限制。"
                AppLanguage.ES -> "[Patcher] + Hardware optimizado: soporte Gamepad=$gamepad, relajados $count uses-feature obligatorios."
                AppLanguage.HI -> "[Patcher] + हार्डवेयर अनुकूलित: Gamepad समर्थन=$gamepad, $count आवश्यक uses-feature प्रतिबंध हटाए गए।"
                AppLanguage.AR -> "[Patcher] + تم تحسين متطلبات العتاد: دعم ذراع التحكم=$gamepad، وإلغاء إلزامية $count من uses-feature."
                AppLanguage.PT -> "[Patcher] + Hardware otimizado: suporte Gamepad=$gamepad, $count uses-feature obrigatórios flexibilizados."
                AppLanguage.RU -> "[Patcher] + Оптимизированы функции оборудования: геймпад=$gamepad, снято обязательное требование для $count uses-feature."
                AppLanguage.JA -> "[Patcher] + ハードウェア要件を最適化: ゲームパッド対応=$gamepad、$count 個の必須 uses-feature を緩和しました。"
                AppLanguage.DE -> "[Patcher] + Hardware-Features optimiert: Gamepad-Support=$gamepad, $count Pflicht-uses-feature gelockert."
                AppLanguage.FR -> "[Patcher] + Matériel optimisé : support Gamepad=$gamepad, $count uses-feature obligatoires assouplis."
                else -> raw
            }
        }

        REGEX_PAT_23.matchEntire(raw)?.let { m ->
            val oldPkg = m.groupValues[1]
            val newPkg = m.groupValues[2]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] Renaming package: $oldPkg -> $newPkg (updating Manifest & Authorities)..."
                AppLanguage.ZH -> "[Patcher] 正在修改包名: $oldPkg -> $newPkg (更新 Manifest 与 Authorities)..."
                AppLanguage.ES -> "[Patcher] Renombrando paquete: $oldPkg -> $newPkg (actualizando Manifest y Authorities)..."
                AppLanguage.HI -> "[Patcher] पैकेज का नाम बदला जा रहा है: $oldPkg -> $newPkg (Manifest और Authorities अपडेट)..."
                AppLanguage.AR -> "[Patcher] جاري تغيير اسم الحزمة: $oldPkg -> $newPkg (تحديث Manifest و Authorities)..."
                AppLanguage.PT -> "[Patcher] Renomeando pacote: $oldPkg -> $newPkg (atualizando Manifest e Authorities)..."
                AppLanguage.RU -> "[Patcher] Переименование пакета: $oldPkg -> $newPkg (обновление Manifest и Authorities)..."
                AppLanguage.JA -> "[Patcher] パッケージ名を変更中: $oldPkg -> $newPkg (Manifest と Authorities を更新)..."
                AppLanguage.DE -> "[Patcher] Benenne Paket um: $oldPkg -> $newPkg (aktualisiere Manifest & Authorities)..."
                AppLanguage.FR -> "[Patcher] Renommage du package : $oldPkg -> $newPkg (mise à jour Manifest & Authorities)..."
                else -> raw
            }
        }

        REGEX_PAT_24.matchEntire(raw)?.let { m ->
            val newName = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] Updating app display label to: $newName"
                AppLanguage.ZH -> "[Patcher] 正在将应用显示名称更新为: $newName"
                AppLanguage.ES -> "[Patcher] Actualizando nombre de la aplicación a: $newName"
                AppLanguage.HI -> "[Patcher] ऐप का प्रदर्शित नाम बदला जा रहा है: $newName"
                AppLanguage.AR -> "[Patcher] جاري تحديث اسم عرض التطبيق إلى: $newName"
                AppLanguage.PT -> "[Patcher] Atualizando nome de exibição do app para: $newName"
                AppLanguage.RU -> "[Patcher] Обновление отображаемого имени приложения на: $newName"
                AppLanguage.JA -> "[Patcher] アプリの表示名を更新中: $newName"
                AppLanguage.DE -> "[Patcher] Aktualisiere App-Anzeigenamen auf: $newName"
                AppLanguage.FR -> "[Patcher] Mise à jour du nom d'affichage de l'application en : $newName"
                else -> raw
            }
        }

        REGEX_PAT_25.matchEntire(raw)?.let { m ->
            val count = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Safely stripped $count Split APK attributes/meta-data (preserving shared resources)."
                AppLanguage.ZH -> "[Patcher] + 已安全移除 $count 个分包属性/元数据 (保留共享资源)。"
                AppLanguage.ES -> "[Patcher] + Eliminados de forma segura $count atributos/metadatos de Split APK (preservando recursos compartidos)."
                AppLanguage.HI -> "[Patcher] + $count Split APK गुण/मेटा-डेटा सुरक्षित रूप से हटाए गए (साझा संसाधन सुरक्षित)।"
                AppLanguage.AR -> "[Patcher] + تمت إزالة $count من سمات/بيانات Split APK بأمان (مع الحفاظ على الموارد المشتركة)."
                AppLanguage.PT -> "[Patcher] + Removidos com segurança $count atributos/metadados de Split APK (preservando recursos compartilhados)."
                AppLanguage.RU -> "[Patcher] + Безопасно очищено $count атрибутов/метаданных Split APK (с сохранением общих ресурсов)."
                AppLanguage.JA -> "[Patcher] + $count 個の分割 APK 属性/メタデータを安全に削除しました (共有リソースを保持)。"
                AppLanguage.DE -> "[Patcher] + $count Split-APK-Attribute/Metadaten sicher bereinigt (geteilte Ressourcen bleiben erhalten)."
                AppLanguage.FR -> "[Patcher] + Suppression sécurisée de $count attributs/méta-données Split APK (ressources partagées conservées)."
                else -> raw
            }
        }

        REGEX_PAT_25B.matchEntire(raw)?.let { m ->
            val count = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Disabled $count Play Licensing check component(s)."
                AppLanguage.ZH -> "[Patcher] + 已禁用 $count 个 Play 授权验证组件。"
                AppLanguage.ES -> "[Patcher] + Desactivados $count componente(s) de verificación de licencia de Google Play."
                AppLanguage.HI -> "[Patcher] + $count Play लाइसेंसिंग जाँच घटक अक्षम किए गए।"
                AppLanguage.AR -> "[Patcher] + تم تعطيل $count من مكونات فحص ترخيص Play."
                AppLanguage.PT -> "[Patcher] + Desativados $count componente(s) de verificação de licença do Google Play."
                AppLanguage.RU -> "[Patcher] + Отключено $count компонентов проверки лицензии Play Licensing."
                AppLanguage.JA -> "[Patcher] + $count 個の Play ライセンス確認コンポーネントを無効化しました。"
                AppLanguage.DE -> "[Patcher] + $count Play Licensing-Prüfkomponente(n) deaktiviert."
                AppLanguage.FR -> "[Patcher] + $count composant(s) de vérification de licence Play désactivé(s)."
                else -> raw
            }
        }

        REGEX_PAT_26.matchEntire(raw)?.let { m ->
            val perm = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Added permission: $perm"
                AppLanguage.ZH -> "[Patcher] + 已添加权限: $perm"
                AppLanguage.ES -> "[Patcher] + Permiso añadido: $perm"
                AppLanguage.HI -> "[Patcher] + अनुमति जोड़ी गई: $perm"
                AppLanguage.AR -> "[Patcher] + تمت إضافة الصلاحية: $perm"
                AppLanguage.PT -> "[Patcher] + Permissão adicionada: $perm"
                AppLanguage.RU -> "[Patcher] + Добавлено разрешение: $perm"
                AppLanguage.JA -> "[Patcher] + 権限を追加: $perm"
                AppLanguage.DE -> "[Patcher] + Berechtigung hinzugefügt: $perm"
                AppLanguage.FR -> "[Patcher] + Permission ajoutée : $perm"
                else -> raw
            }
        }

        REGEX_PAT_27.matchEntire(raw)?.let { m ->
            val meta = m.groupValues[1]
            val resId = m.groupValues[2]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] APK already declares '$meta' ($resId)."
                AppLanguage.ZH -> "[Patcher] APK 已包含 '$meta' 声明 ($resId)。"
                AppLanguage.ES -> "[Patcher] El APK ya tiene declarado '$meta' ($resId)."
                AppLanguage.HI -> "[Patcher] APK में पहले से '$meta' ($resId) घोषित है।"
                AppLanguage.AR -> "[Patcher] ملف APK يحتوي مسبقاً على التصريح '$meta' ($resId)."
                AppLanguage.PT -> "[Patcher] O APK já possui a declaração '$meta' ($resId)."
                AppLanguage.RU -> "[Patcher] APK уже содержит объявление '$meta' ($resId)."
                AppLanguage.JA -> "[Patcher] APK には既に '$meta' ($resId) が定義されています。"
                AppLanguage.DE -> "[Patcher] APK enthält bereits Deklaration '$meta' ($resId)."
                AppLanguage.FR -> "[Patcher] L'APK déclare déjà '$meta' ($resId)."
                else -> raw
            }
        }

        REGEX_PAT_28.matchEntire(raw)?.let { m ->
            val xmlPath = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] Generating binary XML '$xmlPath' and registering in resources.arsc..."
                AppLanguage.ZH -> "[Patcher] 正在生成二进制 XML '$xmlPath' 并注册到 resources.arsc..."
                AppLanguage.ES -> "[Patcher] Creando XML binario '$xmlPath' y registrando en resources.arsc..."
                AppLanguage.HI -> "[Patcher] बाइनरी XML '$xmlPath' बनाया जा रहा है और resources.arsc में पंजीकृत किया जा रहा है..."
                AppLanguage.AR -> "[Patcher] جاري إنشاء ملف XML الثنائي '$xmlPath' وتسجيله في resources.arsc..."
                AppLanguage.PT -> "[Patcher] Criando XML binário '$xmlPath' e registrando em resources.arsc..."
                AppLanguage.RU -> "[Patcher] Создание бинарного XML '$xmlPath' и регистрация в resources.arsc..."
                AppLanguage.JA -> "[Patcher] バイナリ XML '$xmlPath' を作成し resources.arsc に登録中..."
                AppLanguage.DE -> "[Patcher] Erstelle binäres XML '$xmlPath' und registriere es in resources.arsc..."
                AppLanguage.FR -> "[Patcher] Création du XML binaire '$xmlPath' et enregistrement dans resources.arsc..."
                else -> raw
            }
        }

        REGEX_PAT_29.matchEntire(raw)?.let { m ->
            val resId = m.groupValues[1]
            val xmlPath = m.groupValues[2]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Allocated Resource ID @0x$resId -> $xmlPath"
                AppLanguage.ZH -> "[Patcher] + 已分配资源 ID @0x$resId -> $xmlPath"
                AppLanguage.ES -> "[Patcher] + ID de recurso asignado @0x$resId -> $xmlPath"
                AppLanguage.HI -> "[Patcher] + आवंटित रिसोर्स ID @0x$resId -> $xmlPath"
                AppLanguage.AR -> "[Patcher] + تم تخصيص معرف المورد @0x$resId -> $xmlPath"
                AppLanguage.PT -> "[Patcher] + Resource ID alocado @0x$resId -> $xmlPath"
                AppLanguage.RU -> "[Patcher] + Выделен Resource ID @0x$resId -> $xmlPath"
                AppLanguage.JA -> "[Patcher] + リソース ID @0x$resId -> $xmlPath を割り当てました"
                AppLanguage.DE -> "[Patcher] + Resource-ID @0x$resId -> $xmlPath zugewiesen"
                AppLanguage.FR -> "[Patcher] + ID de ressource alloué @0x$resId -> $xmlPath"
                else -> raw
            }
        }

        REGEX_PAT_30.matchEntire(raw)?.let { m ->
            val tag = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Attached $tag"
                AppLanguage.ZH -> "[Patcher] + 已挂载 $tag"
                AppLanguage.ES -> "[Patcher] + Añadido $tag"
                AppLanguage.HI -> "[Patcher] + जोड़ा गया $tag"
                AppLanguage.AR -> "[Patcher] + تمت إضافة $tag"
                AppLanguage.PT -> "[Patcher] + Anexado $tag"
                AppLanguage.RU -> "[Patcher] + Добавлен $tag"
                AppLanguage.JA -> "[Patcher] + $tag を追加しました"
                AppLanguage.DE -> "[Patcher] + $tag hinzugefügt"
                AppLanguage.FR -> "[Patcher] + Ajouté $tag"
                else -> raw
            }
        }

        REGEX_PAT_31.matchEntire(raw)?.let { m ->
            val tag = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] Injecting $tag into AndroidManifest.xml..."
                AppLanguage.ZH -> "[Patcher] 正在向 AndroidManifest.xml 注入 $tag..."
                AppLanguage.ES -> "[Patcher] Inyectando $tag en AndroidManifest.xml..."
                AppLanguage.HI -> "[Patcher] AndroidManifest.xml में $tag इंजेक्ट किया जा रहा है..."
                AppLanguage.AR -> "[Patcher] جاري حقن $tag في AndroidManifest.xml..."
                AppLanguage.PT -> "[Patcher] Injetando $tag no AndroidManifest.xml..."
                AppLanguage.RU -> "[Patcher] Внедрение $tag в AndroidManifest.xml..."
                AppLanguage.JA -> "[Patcher] AndroidManifest.xml に $tag を注入中..."
                AppLanguage.DE -> "[Patcher] Injiziere $tag in AndroidManifest.xml..."
                AppLanguage.FR -> "[Patcher] Injection de $tag dans AndroidManifest.xml..."
                else -> raw
            }
        }

        REGEX_PAT_32.matchEntire(raw)?.let { m ->
            val count = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Injected driving bypass flags (distractionOptimized) into all $count Activities."
                AppLanguage.ZH -> "[Patcher] + 已为全部 $count 个 Activity 注入行车解锁标记 (distractionOptimized)。"
                AppLanguage.ES -> "[Patcher] + Inyectadas etiquetas de uso en movimiento (distractionOptimized) en las $count Activities."
                AppLanguage.HI -> "[Patcher] + सभी $count Activities में ड्राइविंग अनलॉक फ़्लैग (distractionOptimized) इंजेक्ट किए गए।"
                AppLanguage.AR -> "[Patcher] + تم حقن علامات تجاوز قفل القيادة (distractionOptimized) لجميع الـ $count أنشطة."
                AppLanguage.PT -> "[Patcher] + Injetadas flags de desbloqueio em movimento (distractionOptimized) em todas as $count Activities."
                AppLanguage.RU -> "[Patcher] + Внедрены флаги работы в движении (distractionOptimized) для всех $count Activity."
                AppLanguage.JA -> "[Patcher] + 全 $count 個の Activity に走行中制限解除フラグ (distractionOptimized) を注入しました。"
                AppLanguage.DE -> "[Patcher] + Fahrsperre-Bypass-Flags (distractionOptimized) in alle $count Activities injiziert."
                AppLanguage.FR -> "[Patcher] + Indicateurs de déblocage en roulant (distractionOptimized) injectés dans les $count Activities."
                else -> raw
            }
        }

        REGEX_PAT_33.matchEntire(raw)?.let { m ->
            val dexName = m.groupValues[1]
            val sizeInfo = m.groupValues[2]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Injected DEX as '$dexName' ($sizeInfo)."
                AppLanguage.ZH -> "[Patcher] + 已注入 DEX 为 '$dexName' ($sizeInfo)。"
                AppLanguage.ES -> "[Patcher] + DEX inyectado como '$dexName' ($sizeInfo)."
                AppLanguage.HI -> "[Patcher] + DEX को '$dexName' ($sizeInfo) के रूप में इंजेक्ट किया गया।"
                AppLanguage.AR -> "[Patcher] + تم حقن DEX باسم '$dexName' ($sizeInfo)."
                AppLanguage.PT -> "[Patcher] + DEX injetado como '$dexName' ($sizeInfo)."
                AppLanguage.RU -> "[Patcher] + Внедрен DEX как '$dexName' ($sizeInfo)."
                AppLanguage.JA -> "[Patcher] + DEX を '$dexName' ($sizeInfo) として注入しました。"
                AppLanguage.DE -> "[Patcher] + DEX als '$dexName' ($sizeInfo) injiziert."
                AppLanguage.FR -> "[Patcher] + DEX injecté sous '$dexName' ($sizeInfo)."
                else -> raw
            }
        }

        REGEX_PAT_33B.matchEntire(raw)?.let { m ->
            val count = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] + Packaged $count Isolated Car Runtime chunk(s) into assets/aa_car_res.dat."
                AppLanguage.ZH -> "[Patcher] + 已将 $count 个隔离的车载运行时分段打包至 assets/aa_car_res.dat。"
                AppLanguage.ES -> "[Patcher] + Empaquetados $count fragmento(s) de Isolated Car Runtime en assets/aa_car_res.dat."
                AppLanguage.HI -> "[Patcher] + $count पृथक कार रनटाइम सेगमेंट assets/aa_car_res.dat में पैक किए गए।"
                AppLanguage.AR -> "[Patcher] + تم حزم $count جزء من بيئة تشغيل السيارة المعزولة في assets/aa_car_res.dat."
                AppLanguage.PT -> "[Patcher] + Empacotados $count fragmento(s) do Isolated Car Runtime em assets/aa_car_res.dat."
                AppLanguage.RU -> "[Patcher] + Упаковано $count фрагментов изолированной среды авто в assets/aa_car_res.dat."
                AppLanguage.JA -> "[Patcher] + $count 個の独立した車載ランタイムチャンクを assets/aa_car_res.dat にパッケージ化しました。"
                AppLanguage.DE -> "[Patcher] + $count isolierte Car-Runtime-Chunk(s) in assets/aa_car_res.dat gepackt."
                AppLanguage.FR -> "[Patcher] + $count segment(s) Isolated Car Runtime compressé(s) dans assets/aa_car_res.dat."
                else -> raw
            }
        }

        REGEX_PAT_34.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] Warning while injecting DEX: $err"
                AppLanguage.ZH -> "[Patcher] 注入 DEX 时出现警告: $err"
                AppLanguage.ES -> "[Patcher] Advertencia al inyectar DEX: $err"
                AppLanguage.HI -> "[Patcher] DEX इंजेक्ट करते समय चेतावनी: $err"
                AppLanguage.AR -> "[Patcher] تحذير أثناء حقن DEX: $err"
                AppLanguage.PT -> "[Patcher] Aviso ao injetar DEX: $err"
                AppLanguage.RU -> "[Patcher] Предупреждение при внедрении DEX: $err"
                AppLanguage.JA -> "[Patcher] DEX 注入時の警告: $err"
                AppLanguage.DE -> "[Patcher] Warnung beim Injizieren von DEX: $err"
                AppLanguage.FR -> "[Patcher] Avertissement lors de l'injection DEX : $err"
                else -> raw
            }
        }

        REGEX_PAT_SIGNER_PREPARE.matchEntire(raw)?.let { m ->
            val minSdk = m.groupValues[1].takeIf { it.isNotEmpty() }
            val suffix = if (minSdk != null) ", minSdk=$minSdk" else ""
            return when (lang) {
                AppLanguage.EN -> "[Signer] Preparing RSA-2048 key & X.509 certificate to sign APK (Scheme V1 + V2 + V3$suffix)..."
                AppLanguage.ZH -> "[Signer] 正在准备 RSA-2048 密钥与 X.509 证书以签名 APK (V1 + V2 + V3 方案$suffix)..."
                AppLanguage.ES -> "[Signer] Preparando clave RSA-2048 y certificado X.509 para firmar APK (Esquema V1 + V2 + V3$suffix)..."
                AppLanguage.HI -> "[Signer] APK साइन करने के लिए RSA-2048 कुंजी और X.509 प्रमाणपत्र तैयार किया जा रहा है (V1 + V2 + V3$suffix)..."
                AppLanguage.AR -> "[Signer] جاري تحضير مفتاح RSA-2048 وشهادة X.509 لتوقيع APK (V1 + V2 + V3$suffix)..."
                AppLanguage.PT -> "[Signer] Preparando chave RSA-2048 e certificado X.509 para assinar o APK (Scheme V1 + V2 + V3$suffix)..."
                AppLanguage.RU -> "[Signer] Подготовка ключа RSA-2048 и сертификата X.509 для подписи APK (V1 + V2 + V3$suffix)..."
                AppLanguage.JA -> "[Signer] APK 署名用の RSA-2048 鍵と X.509 証明書を準備中 (V1 + V2 + V3$suffix)..."
                AppLanguage.DE -> "[Signer] Bereite RSA-2048-Schlüssel & X.509-Zertifikat zum Signieren vor (V1 + V2 + V3$suffix)..."
                AppLanguage.FR -> "[Signer] Préparation de la clé RSA-2048 et du certificat X.509 pour signer l'APK (V1 + V2 + V3$suffix)..."
                else -> raw
            }
        }

        REGEX_PAT_SIGNATURE_WARNING.matchEntire(raw)?.let { m ->
            val marker = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Patcher] ! Warning: Detected Native/C++ signature check mechanism ($marker) — if app closes upon launch after APK resigning, enable LSPosed (CorePatch / Signature Spoofing) or use an untouched DRM-free APK."
                AppLanguage.ZH -> "[Patcher] ! 警告: 检测到 Native/C++ 层原始签名验证机制 ($marker) — 若重签后应用启动闪退，需要开启 LSPosed (CorePatch / 签名伪装) 或使用未经过 DRM 保护的原生安装包。"
                AppLanguage.ES -> "[Patcher] ! Advertencia: Detectada verificación de firma a nivel Native/C++ ($marker) — si la app se cierra tras la firma, use LSPosed (CorePatch / Signature Spoofing) o un APK sin DRM."
                AppLanguage.HI -> "[Patcher] ! चेतावनी: Native/C++ सिग्नेचर जाँच तंत्र ($marker) का पता चला — यदि ऐप री-साइन के बाद बंद हो जाता है, तो LSPosed (CorePatch) सक्षम करें या मूल DRM-मुक्त APK का उपयोग करें।"
                AppLanguage.AR -> "[Patcher] ! تحذير: تم اكتشاف آلية التحقق من التوقيع Native/C++ ($marker) — إذا توقف التطبيق بعد إعادة التوقيع، يُرجى تفعيل LSPosed (CorePatch) أو استخدام APK أصلي بدون حماية DRM."
                AppLanguage.PT -> "[Patcher] ! Aviso: Detectado mecanismo de verificação de assinatura Native/C++ ($marker) — se o app fechar após a assinatura, ative o LSPosed (CorePatch) ou use um APK sem DRM."
                AppLanguage.RU -> "[Patcher] ! Предупреждение: Обнаружена проверка подписи уровня Native/C++ ($marker) — если приложение вылетает после переподписи, включите LSPosed (CorePatch) или используйте чистый APK без DRM."
                AppLanguage.JA -> "[Patcher] ! 警告: Native/C++ レベルの署名検証機構 ($marker) を検出 — 再署名後にアプリが起動直後終了する場合は LSPosed (CorePatch) を有効にするか DRM のない APK を使用してください。"
                AppLanguage.DE -> "[Patcher] ! Warnung: Native/C++-Signaturprüfungsmechanismus ($marker) erkannt — falls App nach Neusignierung schließt, LSPosed (CorePatch) nutzen oder DRM-freie APK verwenden."
                AppLanguage.FR -> "[Patcher] ! Avertissement : Mécanisme de vérification de signature Native/C++ ($marker) détecté — si l'application plante au lancement, activez LSPosed (CorePatch) ou utilisez un APK sans DRM."
                else -> raw
            }
        }

        REGEX_PAT_35.matchEntire(raw)?.let { m ->
            val kb = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Signer] APK signing completed ($kb KB)."
                AppLanguage.ZH -> "[Signer] APK 签名完成 ($kb KB)。"
                AppLanguage.ES -> "[Signer] Firma de APK completada ($kb KB)."
                AppLanguage.HI -> "[Signer] APK हस्ताक्षर पूर्ण हुआ ($kb KB)।"
                AppLanguage.AR -> "[Signer] اكتمل توقيع ملف APK ($kb KB)."
                AppLanguage.PT -> "[Signer] Assinatura do APK concluída ($kb KB)."
                AppLanguage.RU -> "[Signer] Подпись APK завершена ($kb KB)."
                AppLanguage.JA -> "[Signer] APK の署名が完了しました ($kb KB)。"
                AppLanguage.DE -> "[Signer] APK-Signierung abgeschlossen ($kb KB)."
                AppLanguage.FR -> "[Signer] Signature de l'APK terminée ($kb KB)."
                else -> raw
            }
        }

        REGEX_PAT_36.matchEntire(raw)?.let { m ->
            val path = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Root] Copying APK to $path..."
                AppLanguage.ZH -> "[Root] 正在将 APK 复制到 $path..."
                AppLanguage.ES -> "[Root] Copiando APK a $path..."
                AppLanguage.HI -> "[Root] APK को $path पर कॉपी किया जा रहा है..."
                AppLanguage.AR -> "[Root] جاري نسخ ملف APK إلى $path..."
                AppLanguage.PT -> "[Root] Copiando APK para $path..."
                AppLanguage.RU -> "[Root] Копирование APK в $path..."
                AppLanguage.JA -> "[Root] APK を $path にコピー中..."
                AppLanguage.DE -> "[Root] Kopiere APK nach $path..."
                AppLanguage.FR -> "[Root] Copie de l'APK vers $path..."
                else -> raw
            }
        }

        REGEX_PAT_37.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Failed to copy APK to /data/local/tmp: $err"
                AppLanguage.ZH -> "无法将 APK 复制到 /data/local/tmp: $err"
                AppLanguage.ES -> "No se pudo copiar el APK a /data/local/tmp: $err"
                AppLanguage.HI -> "/data/local/tmp में APK कॉपी नहीं किया जा सका: $err"
                AppLanguage.AR -> "تعذر نسخ ملف APK إلى /data/local/tmp: $err"
                AppLanguage.PT -> "Falha ao copiar APK para /data/local/tmp: $err"
                AppLanguage.RU -> "Не удалось скопировать APK в /data/local/tmp: $err"
                AppLanguage.JA -> "/data/local/tmp に APK をコピーできませんでした: $err"
                AppLanguage.DE -> "APK konnte nicht nach /data/local/tmp kopiert werden: $err"
                AppLanguage.FR -> "Impossible de copier l'APK vers /data/local/tmp : $err"
                else -> raw
            }
        }

        REGEX_PAT_38.matchEntire(raw)?.let { m ->
            val out = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Root] pm install result: $out"
                AppLanguage.ZH -> "[Root] pm install 结果: $out"
                AppLanguage.ES -> "[Root] Resultado de pm install: $out"
                AppLanguage.HI -> "[Root] pm install परिणाम: $out"
                AppLanguage.AR -> "[Root] نتيجة pm install: $out"
                AppLanguage.PT -> "[Root] Resultado do pm install: $out"
                AppLanguage.RU -> "[Root] Результат pm install: $out"
                AppLanguage.JA -> "[Root] pm install 結果: $out"
                AppLanguage.DE -> "[Root] pm install Ergebnis: $out"
                AppLanguage.FR -> "[Root] Résultat pm install : $out"
                else -> raw
            }
        }

        REGEX_PAT_39.matchEntire(raw)?.let { m ->
            val out = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Root pm install failed: $out"
                AppLanguage.ZH -> "Root pm install 失败: $out"
                AppLanguage.ES -> "Error en pm install vía Root: $out"
                AppLanguage.HI -> "Root के माध्यम से pm install त्रुटि: $out"
                AppLanguage.AR -> "فشل أمر pm install عبر الروت: $out"
                AppLanguage.PT -> "Erro no pm install via Root: $out"
                AppLanguage.RU -> "Ошибка pm install через Root: $out"
                AppLanguage.JA -> "Root での pm install エラー: $out"
                AppLanguage.DE -> "Fehler bei pm install über Root: $out"
                AppLanguage.FR -> "Erreur pm install via Root : $out"
                else -> raw
            }
        }

        REGEX_PAT_40.matchEntire(raw)?.let { m ->
            val pkg = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Root] Granting SYSTEM_ALERT_WINDOW, PROJECT_MEDIA & enabling Touch Bridge for $pkg..."
                AppLanguage.ZH -> "[Root] 正在为 $pkg 授予 SYSTEM_ALERT_WINDOW、PROJECT_MEDIA 并开启触控桥接..."
                AppLanguage.ES -> "[Root] Concediendo SYSTEM_ALERT_WINDOW, PROJECT_MEDIA y activando Touch Bridge para $pkg..."
                AppLanguage.HI -> "[Root] $pkg के लिए SYSTEM_ALERT_WINDOW, PROJECT_MEDIA और Touch Bridge सक्षम किया जा रहा है..."
                AppLanguage.AR -> "[Root] جاري منح SYSTEM_ALERT_WINDOW و PROJECT_MEDIA وتفعيل جسر اللمس لـ $pkg..."
                AppLanguage.PT -> "[Root] Concedendo SYSTEM_ALERT_WINDOW, PROJECT_MEDIA e ativando Touch Bridge para $pkg..."
                AppLanguage.RU -> "[Root] Выдача прав SYSTEM_ALERT_WINDOW, PROJECT_MEDIA и включение Touch Bridge для $pkg..."
                AppLanguage.JA -> "[Root] $pkg に SYSTEM_ALERT_WINDOW、PROJECT_MEDIA 権限を付与し Touch Bridge を有効化中..."
                AppLanguage.DE -> "[Root] Gewähre SYSTEM_ALERT_WINDOW, PROJECT_MEDIA & aktiviere Touch Bridge für $pkg..."
                AppLanguage.FR -> "[Root] Attribution de SYSTEM_ALERT_WINDOW, PROJECT_MEDIA et activation de Touch Bridge pour $pkg..."
                else -> raw
            }
        }

        REGEX_PAT_41.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Root] Exception: $err"
                AppLanguage.ZH -> "[Root] 异常: $err"
                AppLanguage.ES -> "[Root] Excepción: $err"
                AppLanguage.HI -> "[Root] अपवाद: $err"
                AppLanguage.AR -> "[Root] استثناء: $err"
                AppLanguage.PT -> "[Root] Exceção: $err"
                AppLanguage.RU -> "[Root] Исключение: $err"
                AppLanguage.JA -> "[Root] 例外エラー: $err"
                AppLanguage.DE -> "[Root] Ausnahme: $err"
                AppLanguage.FR -> "[Root] Exception : $err"
                else -> raw
            }
        }

        REGEX_PAT_42.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Root installation error: $err"
                AppLanguage.ZH -> "Root 安装错误: $err"
                AppLanguage.ES -> "Error de instalación vía Root: $err"
                AppLanguage.HI -> "Root इंस्टॉलेशन त्रुटि: $err"
                AppLanguage.AR -> "خطأ في التثبيت عبر الروت: $err"
                AppLanguage.PT -> "Erro de instalação via Root: $err"
                AppLanguage.RU -> "Ошибка установки через Root: $err"
                AppLanguage.JA -> "Root インストールエラー: $err"
                AppLanguage.DE -> "Root-Installationsfehler: $err"
                AppLanguage.FR -> "Erreur d'installation via Root : $err"
                else -> raw
            }
        }

        REGEX_PAT_43.matchEntire(raw)?.let { m ->
            val cmd = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Shizuku] Executing: $cmd"
                AppLanguage.ZH -> "[Shizuku] 正在执行: $cmd"
                AppLanguage.ES -> "[Shizuku] Ejecutando: $cmd"
                AppLanguage.HI -> "[Shizuku] निष्पादित किया जा रहा है: $cmd"
                AppLanguage.AR -> "[Shizuku] جاري تنفيذ: $cmd"
                AppLanguage.PT -> "[Shizuku] Executando: $cmd"
                AppLanguage.RU -> "[Shizuku] Выполнение: $cmd"
                AppLanguage.JA -> "[Shizuku] 実行中: $cmd"
                AppLanguage.DE -> "[Shizuku] Führe aus: $cmd"
                AppLanguage.FR -> "[Shizuku] Exécution : $cmd"
                else -> raw
            }
        }

        REGEX_PAT_44.matchEntire(raw)?.let { m ->
            val res = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Shizuku] Result: $res"
                AppLanguage.ZH -> "[Shizuku] 结果: $res"
                AppLanguage.ES -> "[Shizuku] Resultado: $res"
                AppLanguage.HI -> "[Shizuku] परिणाम: $res"
                AppLanguage.AR -> "[Shizuku] النتيجة: $res"
                AppLanguage.PT -> "[Shizuku] Resultado: $res"
                AppLanguage.RU -> "[Shizuku] Результат: $res"
                AppLanguage.JA -> "[Shizuku] 結果: $res"
                AppLanguage.DE -> "[Shizuku] Ergebnis: $res"
                AppLanguage.FR -> "[Shizuku] Résultat : $res"
                else -> raw
            }
        }

        REGEX_PAT_45.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Shizuku installation failed: $err"
                AppLanguage.ZH -> "通过 Shizuku 安装失败: $err"
                AppLanguage.ES -> "Falló la instalación por Shizuku: $err"
                AppLanguage.HI -> "Shizuku के माध्यम से इंस्टॉलेशन विफल: $err"
                AppLanguage.AR -> "فشل التثبيت عبر Shizuku: $err"
                AppLanguage.PT -> "Falha na instalação via Shizuku: $err"
                AppLanguage.RU -> "Ошибка установки через Shizuku: $err"
                AppLanguage.JA -> "Shizuku でのインストールに失敗しました: $err"
                AppLanguage.DE -> "Installation über Shizuku fehlgeschlagen: $err"
                AppLanguage.FR -> "Échec de l'installation via Shizuku : $err"
                else -> raw
            }
        }

        REGEX_PAT_46.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Shizuku] Exception: $err"
                AppLanguage.ZH -> "[Shizuku] 异常: $err"
                AppLanguage.ES -> "[Shizuku] Excepción: $err"
                AppLanguage.HI -> "[Shizuku] अपवाद: $err"
                AppLanguage.AR -> "[Shizuku] استثناء: $err"
                AppLanguage.PT -> "[Shizuku] Exceção: $err"
                AppLanguage.RU -> "[Shizuku] Исключение: $err"
                AppLanguage.JA -> "[Shizuku] 例外エラー: $err"
                AppLanguage.DE -> "[Shizuku] Ausnahme: $err"
                AppLanguage.FR -> "[Shizuku] Exception : $err"
                else -> raw
            }
        }

        REGEX_PAT_47.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Shizuku execution error: $err"
                AppLanguage.ZH -> "Shizuku 执行错误: $err"
                AppLanguage.ES -> "Error de ejecución de Shizuku: $err"
                AppLanguage.HI -> "Shizuku निष्पादन त्रुटि: $err"
                AppLanguage.AR -> "خطأ في تنفيذ Shizuku: $err"
                AppLanguage.PT -> "Erro de execução do Shizuku: $err"
                AppLanguage.RU -> "Ошибка выполнения Shizuku: $err"
                AppLanguage.JA -> "Shizuku 実行エラー: $err"
                AppLanguage.DE -> "Shizuku-Ausführungsfehler: $err"
                AppLanguage.FR -> "Erreur d'exécution Shizuku : $err"
                else -> raw
            }
        }

        REGEX_PAT_48.matchEntire(raw)?.let { m ->
            val id = m.groupValues[1]
            val kb = m.groupValues[2]
            return when (lang) {
                AppLanguage.EN -> "[Non-Root Session] Created Session #$id, writing APK data ($kb KB)..."
                AppLanguage.ZH -> "[Non-Root Session] 已创建会话 #$id，正在写入 APK 数据 ($kb KB)..."
                AppLanguage.ES -> "[Non-Root Session] Sesión #$id creada, escribiendo datos del APK ($kb KB)..."
                AppLanguage.HI -> "[Non-Root Session] सेशन #$id बनाया गया, APK डेटा लिखा जा रहा है ($kb KB)..."
                AppLanguage.AR -> "[Non-Root Session] تم إنشاء الجلسة #$id، جاري كتابة بيانات APK ($kb KB)..."
                AppLanguage.PT -> "[Non-Root Session] Sessão #$id criada, gravando dados do APK ($kb KB)..."
                AppLanguage.RU -> "[Non-Root Session] Создана сессия #$id, запись данных APK ($kb KB)..."
                AppLanguage.JA -> "[Non-Root Session] セッション #$id を作成、APK データを書き込み中 ($kb KB)..."
                AppLanguage.DE -> "[Non-Root Session] Sitzung #$id erstellt, schreibe APK-Daten ($kb KB)..."
                AppLanguage.FR -> "[Non-Root Session] Session #$id créée, écriture des données APK ($kb KB)..."
                else -> raw
            }
        }

        REGEX_PAT_49.matchEntire(raw)?.let { m ->
            val pkg = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Non-Root Session] Installation completed: $pkg"
                AppLanguage.ZH -> "[Non-Root Session] 安装完成: $pkg"
                AppLanguage.ES -> "[Non-Root Session] Instalación completada: $pkg"
                AppLanguage.HI -> "[Non-Root Session] इंस्टॉलेशन पूर्ण: $pkg"
                AppLanguage.AR -> "[Non-Root Session] اكتمل التثبيت: $pkg"
                AppLanguage.PT -> "[Non-Root Session] Instalação concluída: $pkg"
                AppLanguage.RU -> "[Non-Root Session] Установка завершена: $pkg"
                AppLanguage.JA -> "[Non-Root Session] インストール完了: $pkg"
                AppLanguage.DE -> "[Non-Root Session] Installation abgeschlossen: $pkg"
                AppLanguage.FR -> "[Non-Root Session] Installation terminée : $pkg"
                else -> raw
            }
        }

        REGEX_PAT_50.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "[Non-Root Session] Error: $err"
                AppLanguage.ZH -> "[Non-Root Session] 错误: $err"
                AppLanguage.ES -> "[Non-Root Session] Error: $err"
                AppLanguage.HI -> "[Non-Root Session] त्रुटि: $err"
                AppLanguage.AR -> "[Non-Root Session] خطأ: $err"
                AppLanguage.PT -> "[Non-Root Session] Erro: $err"
                AppLanguage.RU -> "[Non-Root Session] Ошибка: $err"
                AppLanguage.JA -> "[Non-Root Session] エラー: $err"
                AppLanguage.DE -> "[Non-Root Session] Fehler: $err"
                AppLanguage.FR -> "[Non-Root Session] Erreur : $err"
                else -> raw
            }
        }

        REGEX_PAT_51.matchEntire(raw)?.let { m ->
            val err = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Session API installation error: $err"
                AppLanguage.ZH -> "Session API 安装错误: $err"
                AppLanguage.ES -> "Error de instalación por Session API: $err"
                AppLanguage.HI -> "Session API इंस्टॉलेशन त्रुटि: $err"
                AppLanguage.AR -> "خطأ في التثبيت عبر Session API: $err"
                AppLanguage.PT -> "Erro na instalação via Session API: $err"
                AppLanguage.RU -> "Ошибка установки через Session API: $err"
                AppLanguage.JA -> "Session API インストールエラー: $err"
                AppLanguage.DE -> "Fehler bei der Session-API-Installation: $err"
                AppLanguage.FR -> "Erreur d'installation via Session API : $err"
                else -> raw
            }
        }

        REGEX_PAT_52.matchEntire(raw)?.let { m ->
            val pkg = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "Successfully installed package $pkg"
                AppLanguage.ZH -> "已成功安装包 $pkg"
                AppLanguage.ES -> "Paquete $pkg instalado con éxito"
                AppLanguage.HI -> "पैकेज $pkg सफलतापूर्वक इंस्टॉल हो गया"
                AppLanguage.AR -> "تم تثبيت الحزمة $pkg بنجاح"
                AppLanguage.PT -> "Pacote $pkg instalado com sucesso"
                AppLanguage.RU -> "Пакет $pkg успешно установлен"
                AppLanguage.JA -> "パッケージ $pkg のインストールに成功しました"
                AppLanguage.DE -> "Paket $pkg erfolgreich installiert"
                AppLanguage.FR -> "Package $pkg installé avec succès"
                else -> raw
            }
        }

        REGEX_PAT_53.matchEntire(raw)?.let { m ->
            val code = m.groupValues[1]
            val msg = m.groupValues[2]
            return when (lang) {
                AppLanguage.EN -> "Installation failed (Code $code): $msg"
                AppLanguage.ZH -> "安装失败 (代码 $code): $msg"
                AppLanguage.ES -> "Instalación fallida (Código $code): $msg"
                AppLanguage.HI -> "इंस्टॉलेशन विफल (कोड $code): $msg"
                AppLanguage.AR -> "فشل التثبيت (الرمز $code): $msg"
                AppLanguage.PT -> "Falha na instalação (Código $code): $msg"
                AppLanguage.RU -> "Ошибка установки (Код $code): $msg"
                AppLanguage.JA -> "インストール失敗 (コード $code): $msg"
                AppLanguage.DE -> "Installation fehlgeschlagen (Code $code): $msg"
                AppLanguage.FR -> "Échec de l'installation (Code $code) : $msg"
                else -> raw
            }
        }

        REGEX_PAT_54.matchEntire(raw)?.let { m ->
            val cls = m.groupValues[1]
            return when (lang) {
                AppLanguage.EN -> "$cls: Unknown error while patching APK"
                AppLanguage.ZH -> "$cls: 修补 APK 时发生未知错误"
                AppLanguage.ES -> "$cls: Error desconocido al parchear APK"
                AppLanguage.HI -> "$cls: APK पैच करते समय अज्ञात त्रुटि"
                AppLanguage.AR -> "$cls: خطأ غير معروف أثناء تعديل APK"
                AppLanguage.PT -> "$cls: Erro desconhecido ao aplicar patch no APK"
                AppLanguage.RU -> "$cls: Неизвестная ошибка при патче APK"
                AppLanguage.JA -> "$cls: APK パッチ中に不明なエラーが発生しました"
                AppLanguage.DE -> "$cls: Unbekannter Fehler beim Patchen der APK"
                AppLanguage.FR -> "$cls : Erreur inconnue lors du patch de l'APK"
                else -> raw
            }
        }

        REGEX_PAT_55.matchEntire(raw)?.let { m ->
            val hasPrefix = m.groupValues[1].isNotEmpty()
            val cls = m.groupValues[2]
            val innerMsg = m.groupValues[3]
            val translatedInner = when {
                innerMsg == "APK không chứa AndroidManifest.xml hợp lệ!" -> when (lang) {
                    AppLanguage.EN -> "APK does not contain a valid AndroidManifest.xml!"
                    AppLanguage.ZH -> "APK 不包含有效的 AndroidManifest.xml！"
                    AppLanguage.ES -> "¡El APK no contiene un AndroidManifest.xml válido!"
                    AppLanguage.HI -> "APK में मान्य AndroidManifest.xml नहीं है!"
                    AppLanguage.AR -> "لا يحتوي ملف APK على AndroidManifest.xml صالح!"
                    AppLanguage.PT -> "O APK não contém um AndroidManifest.xml válido!"
                    AppLanguage.RU -> "APK не содержит корректного AndroidManifest.xml!"
                    AppLanguage.JA -> "APK に有効な AndroidManifest.xml が含まれていません！"
                    AppLanguage.DE -> "APK enthält keine gültige AndroidManifest.xml!"
                    AppLanguage.FR -> "L'APK ne contient pas de fichier AndroidManifest.xml valide !"
                    else -> innerMsg
                }
                innerMsg == "AndroidManifest.xml thiếu thẻ gốc <manifest>!" -> when (lang) {
                    AppLanguage.EN -> "AndroidManifest.xml is missing the root <manifest> tag!"
                    AppLanguage.ZH -> "AndroidManifest.xml 缺少根标签 <manifest>！"
                    AppLanguage.ES -> "¡Falta la etiqueta raíz <manifest> en AndroidManifest.xml!"
                    AppLanguage.HI -> "AndroidManifest.xml में मूल <manifest> टैग गायब है!"
                    AppLanguage.AR -> "ملف AndroidManifest.xml يفتقد الوسم الجذري <manifest>!"
                    AppLanguage.PT -> "AndroidManifest.xml não possui a tag raiz <manifest>!"
                    AppLanguage.RU -> "В AndroidManifest.xml отсутствует корневой тег <manifest>!"
                    AppLanguage.JA -> "AndroidManifest.xml にルート <manifest> タグがありません！"
                    AppLanguage.DE -> "In AndroidManifest.xml fehlt das Wurzel-Tag <manifest>!"
                    AppLanguage.FR -> "La balise racine <manifest> est manquante dans AndroidManifest.xml !"
                    else -> innerMsg
                }
                innerMsg == "Không tìm thấy PackageBlock trong resources.arsc!" -> when (lang) {
                    AppLanguage.EN -> "PackageBlock not found in resources.arsc!"
                    AppLanguage.ZH -> "在 resources.arsc 中未找到 PackageBlock！"
                    AppLanguage.ES -> "¡No se encontró PackageBlock en resources.arsc!"
                    AppLanguage.HI -> "resources.arsc में PackageBlock नहीं मिला!"
                    AppLanguage.AR -> "لم يتم العثور على PackageBlock في resources.arsc!"
                    AppLanguage.PT -> "PackageBlock não encontrado em resources.arsc!"
                    AppLanguage.RU -> "Не найден PackageBlock в resources.arsc!"
                    AppLanguage.JA -> "resources.arsc 内に PackageBlock が見つかりません！"
                    AppLanguage.DE -> "PackageBlock in resources.arsc nicht gefunden!"
                    AppLanguage.FR -> "PackageBlock introuvable dans resources.arsc !"
                    else -> innerMsg
                }
                innerMsg == "Danh sách file APK đầu vào trống!" -> when (lang) {
                    AppLanguage.EN -> "Input APK file list is empty!"
                    AppLanguage.ZH -> "输入 APK 文件列表为空！"
                    AppLanguage.ES -> "¡La lista de archivos APK de entrada está vacía!"
                    AppLanguage.HI -> "इनपुट APK फ़ाइल सूची खाली है!"
                    AppLanguage.AR -> "قائمة ملفات APK المدخلة فارغة!"
                    AppLanguage.PT -> "A lista de arquivos APK de entrada está vazia!"
                    AppLanguage.RU -> "Список входных файлов APK пуст!"
                    AppLanguage.JA -> "入力 APK ファイルリストが空です！"
                    AppLanguage.DE -> "Eingabe-APK-Dateiliste ist leer!"
                    AppLanguage.FR -> "La liste des fichiers APK d'entrée est vide !"
                    else -> innerMsg
                }
                innerMsg.startsWith("Không tìm thấy file .apk nào bên trong ") -> {
                    val bundleName = innerMsg.removePrefix("Không tìm thấy file .apk nào bên trong ")
                    when (lang) {
                        AppLanguage.EN -> "No .apk files found inside $bundleName"
                        AppLanguage.ZH -> "在 $bundleName 内未找到任何 .apk 文件"
                        AppLanguage.ES -> "No se encontraron archivos .apk dentro de $bundleName"
                        AppLanguage.HI -> "$bundleName के अंदर कोई .apk फ़ाइल नहीं मिली"
                        AppLanguage.AR -> "لم يتم العثور على أي ملف .apk داخل $bundleName"
                        AppLanguage.PT -> "Nenhum arquivo .apk encontrado dentro de $bundleName"
                        AppLanguage.RU -> "Внутри $bundleName не найдено файлов .apk"
                        AppLanguage.JA -> "$bundleName 内に .apk ファイルが見つかりません"
                        AppLanguage.DE -> "Keine .apk-Dateien in $bundleName gefunden"
                        AppLanguage.FR -> "Aucun fichier .apk trouvé dans $bundleName"
                        else -> innerMsg
                    }
                }
                else -> innerMsg
            }
            if (hasPrefix) {
                return when (lang) {
                    AppLanguage.EN -> "[CRITICAL ERROR] $cls: $translatedInner"
                    AppLanguage.VI -> "[LỖI NGHIÊM TRỌNG] $cls: $translatedInner"
                    AppLanguage.ZH -> "[严重错误] $cls: $translatedInner"
                    AppLanguage.ES -> "[ERROR CRÍTICO] $cls: $translatedInner"
                    AppLanguage.HI -> "[गंभीर त्रुटि] $cls: $translatedInner"
                    AppLanguage.AR -> "[خطأ فادح] $cls: $translatedInner"
                    AppLanguage.PT -> "[ERRO CRÍTICO] $cls: $translatedInner"
                    AppLanguage.RU -> "[КРИТИЧЕСКАЯ ОШИБКА] $cls: $translatedInner"
                    AppLanguage.JA -> "[重大なエラー] $cls: $translatedInner"
                    AppLanguage.DE -> "[KRITISCHER FEHLER] $cls: $translatedInner"
                    AppLanguage.FR -> "[ERREUR CRITIQUE] $cls : $translatedInner"
                }
            } else if (translatedInner != innerMsg) {
                return "$cls: $translatedInner"
            }
        }

        return raw
    }
}

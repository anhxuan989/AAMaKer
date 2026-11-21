package com.aamaker.app.i18n

data class AboutStrings(
    val navOther: String,
    val generalSectionTitle: String,
    val aboutTooltip: String,
    val dialogTitle: String,
    val shortDescription: String,
    val versionLabel: String,
    val developerSectionTitle: String,
    val developerLabel: String,
    val githubProjectLabel: String,
    val openInBrowserTooltip: String,
    val updateSectionTitle: String,
    val checkUpdateButton: String,
    val checkingUpdate: String,
    val upToDateFormat: String,
    val updateAvailableFormat: String,
    val releaseNotesLabel: String,
    val downloadApkButton: String,
    val openReleaseButton: String,
    val retryButton: String,
    val noReleasesFound: String,
    val updateCheckFailedFormat: String,
    val openSourceSectionTitle: String,
    val closeButton: String
)

object AboutLocalization {
    fun of(language: AppLanguage): AboutStrings = when (language) {
        AppLanguage.EN -> en
        AppLanguage.VI -> vi
        AppLanguage.ZH -> zh
        AppLanguage.ES -> es
        AppLanguage.HI -> hi
        AppLanguage.AR -> ar
        AppLanguage.PT -> pt
        AppLanguage.RU -> ru
        AppLanguage.JA -> ja
        AppLanguage.DE -> de
        AppLanguage.FR -> fr
    }

    private val en = AboutStrings(
        navOther = "Other",
        generalSectionTitle = "General",
        aboutTooltip = "About",
        dialogTitle = "About AAMaKer",
        shortDescription = "All-in-one Android Auto APK Patcher, Car Stage split-screen launcher, and Google Play source installer.",
        versionLabel = "Version",
        developerSectionTitle = "Developer & Project",
        developerLabel = "Developer",
        githubProjectLabel = "GitHub Repository",
        openInBrowserTooltip = "Open in browser",
        updateSectionTitle = "Updates",
        checkUpdateButton = "Check for updates",
        checkingUpdate = "Checking latest GitHub release…",
        upToDateFormat = "You are up to date (%s)",
        updateAvailableFormat = "New version available: %s",
        releaseNotesLabel = "Release notes",
        downloadApkButton = "Download APK",
        openReleaseButton = "View Release",
        retryButton = "Retry",
        noReleasesFound = "No releases published on GitHub yet.",
        updateCheckFailedFormat = "Update check failed: %s",
        openSourceSectionTitle = "Open Source Libraries",
        closeButton = "Close"
    )

    private val vi = AboutStrings(
        navOther = "Khác",
        generalSectionTitle = "Chung",
        aboutTooltip = "Giới thiệu",
        dialogTitle = "Giới thiệu AAMaKer",
        shortDescription = "Công cụ tất cả trong một để vá APK Android Auto, trình khởi chạy chia đôi màn hình Car Stage và cài đặt nguồn Google Play.",
        versionLabel = "Phiên bản",
        developerSectionTitle = "Nhà phát triển & Dự án",
        developerLabel = "Nhà phát triển",
        githubProjectLabel = "Kho mã nguồn GitHub",
        openInBrowserTooltip = "Mở trong trình duyệt",
        updateSectionTitle = "Cập nhật",
        checkUpdateButton = "Kiểm tra cập nhật",
        checkingUpdate = "Đang kiểm tra bản phát hành mới nhất trên GitHub…",
        upToDateFormat = "Bạn đang dùng phiên bản mới nhất (%s)",
        updateAvailableFormat = "Có phiên bản mới: %s",
        releaseNotesLabel = "Ghi chú phát hành",
        downloadApkButton = "Tải APK",
        openReleaseButton = "Xem bản phát hành",
        retryButton = "Thử lại",
        noReleasesFound = "Chưa có bản phát hành nào trên GitHub.",
        updateCheckFailedFormat = "Kiểm tra cập nhật thất bại: %s",
        openSourceSectionTitle = "Thư viện mã nguồn mở",
        closeButton = "Đóng"
    )

    private val zh = AboutStrings(
        navOther = "其他",
        generalSectionTitle = "常规",
        aboutTooltip = "关于",
        dialogTitle = "关于 AAMaKer",
        shortDescription = "集 Android Auto APK 修补、Car Stage 分屏启动器与 Google Play 来源安装于一体的全能工具。",
        versionLabel = "版本",
        developerSectionTitle = "开发者与项目",
        developerLabel = "开发者",
        githubProjectLabel = "GitHub 仓库",
        openInBrowserTooltip = "在浏览器中打开",
        updateSectionTitle = "更新",
        checkUpdateButton = "检查更新",
        checkingUpdate = "正在检查 GitHub 最新发布版本…",
        upToDateFormat = "已是最新版本 (%s)",
        updateAvailableFormat = "发现新版本：%s",
        releaseNotesLabel = "更新日志",
        downloadApkButton = "下载 APK",
        openReleaseButton = "查看发布页",
        retryButton = "重试",
        noReleasesFound = "GitHub 上尚未发布任何版本。",
        updateCheckFailedFormat = "检查更新失败：%s",
        openSourceSectionTitle = "开源组件",
        closeButton = "关闭"
    )

    private val es = AboutStrings(
        navOther = "Otros",
        generalSectionTitle = "General",
        aboutTooltip = "Acerca de",
        dialogTitle = "Acerca de AAMaKer",
        shortDescription = "Herramienta todo en uno para parchear APK de Android Auto, lanzador de pantalla dividida Car Stage e instalador de origen Google Play.",
        versionLabel = "Versión",
        developerSectionTitle = "Desarrollador y Proyecto",
        developerLabel = "Desarrollador",
        githubProjectLabel = "Repositorio en GitHub",
        openInBrowserTooltip = "Abrir en el navegador",
        updateSectionTitle = "Actualizaciones",
        checkUpdateButton = "Buscar actualizaciones",
        checkingUpdate = "Comprobando la última versión en GitHub…",
        upToDateFormat = "Tienes la última versión (%s)",
        updateAvailableFormat = "Nueva versión disponible: %s",
        releaseNotesLabel = "Notas de la versión",
        downloadApkButton = "Descargar APK",
        openReleaseButton = "Ver lanzamiento",
        retryButton = "Reintentar",
        noReleasesFound = "Aún no hay versiones publicadas en GitHub.",
        updateCheckFailedFormat = "Error al buscar actualizaciones: %s",
        openSourceSectionTitle = "Bibliotecas de código abierto",
        closeButton = "Cerrar"
    )

    private val hi = AboutStrings(
        navOther = "अन्य",
        generalSectionTitle = "सामान्य",
        aboutTooltip = "परिचय",
        dialogTitle = "AAMaKer के बारे में",
        shortDescription = "Android Auto APK पैचर, Car Stage स्प्लिट-स्क्रीन लॉन्चर और Google Play स्रोत इंस्टॉलर के लिए ऑल-इन-वन टूल।",
        versionLabel = "संस्करण",
        developerSectionTitle = "डेवलपर और प्रोजेक्ट",
        developerLabel = "डेवलपर",
        githubProjectLabel = "GitHub रिपॉजिटरी",
        openInBrowserTooltip = "ब्राउज़र में खोलें",
        updateSectionTitle = "अपडेट",
        checkUpdateButton = "अपडेट जांचें",
        checkingUpdate = "नवीनतम GitHub रिलीज़ की जांच हो रही है…",
        upToDateFormat = "आप नवीनतम संस्करण पर हैं (%s)",
        updateAvailableFormat = "नया संस्करण उपलब्ध है: %s",
        releaseNotesLabel = "रिलीज़ नोट्स",
        downloadApkButton = "APK डाउनलोड करें",
        openReleaseButton = "रिलीज़ देखें",
        retryButton = "पुनः प्रयास करें",
        noReleasesFound = "GitHub पर अभी तक कोई रिलीज़ प्रकाशित नहीं हुई है।",
        updateCheckFailedFormat = "अपडेट जांच विफल: %s",
        openSourceSectionTitle = "ओपन सोर्स लाइब्रेरी",
        closeButton = "बंद करें"
    )

    private val ar = AboutStrings(
        navOther = "أخرى",
        generalSectionTitle = "عام",
        aboutTooltip = "حول",
        dialogTitle = "حول AAMaKer",
        shortDescription = "أداة شاملة لتعديل تطبيقات Android Auto ومشغل تقسيم الشاشة Car Stage والتثبيت بمصدر Google Play.",
        versionLabel = "الإصدار",
        developerSectionTitle = "المطور والمشروع",
        developerLabel = "المطور",
        githubProjectLabel = "مستودع GitHub",
        openInBrowserTooltip = "فتح في المتصفح",
        updateSectionTitle = "التحديثات",
        checkUpdateButton = "التحقق من التحديثات",
        checkingUpdate = "جارٍ التحقق من أحدث إصدار على GitHub…",
        upToDateFormat = "لديك أحدث إصدار (%s)",
        updateAvailableFormat = "يتوفر إصدار جديد: %s",
        releaseNotesLabel = "ملاحظات الإصدار",
        downloadApkButton = "تنزيل APK",
        openReleaseButton = "عرض الإصدار",
        retryButton = "إعادة المحاولة",
        noReleasesFound = "لا توجد إصدارات منشورة على GitHub بعد.",
        updateCheckFailedFormat = "فشل التحقق من التحديثات: %s",
        openSourceSectionTitle = "مكتبات مفتوحة المصدر",
        closeButton = "إغلاق"
    )

    private val pt = AboutStrings(
        navOther = "Outros",
        generalSectionTitle = "Geral",
        aboutTooltip = "Sobre",
        dialogTitle = "Sobre o AAMaKer",
        shortDescription = "Ferramenta completa para aplicar patches em APKs do Android Auto, lançador em tela dividida Car Stage e instalador com origem Google Play.",
        versionLabel = "Versão",
        developerSectionTitle = "Desenvolvedor e Projeto",
        developerLabel = "Desenvolvedor",
        githubProjectLabel = "Repositório no GitHub",
        openInBrowserTooltip = "Abrir no navegador",
        updateSectionTitle = "Atualizações",
        checkUpdateButton = "Verificar atualizações",
        checkingUpdate = "Verificando a versão mais recente no GitHub…",
        upToDateFormat = "Você está na versão mais recente (%s)",
        updateAvailableFormat = "Nova versão disponível: %s",
        releaseNotesLabel = "Notas da versão",
        downloadApkButton = "Baixar APK",
        openReleaseButton = "Ver lançamento",
        retryButton = "Tentar novamente",
        noReleasesFound = "Nenhuma versão publicada no GitHub ainda.",
        updateCheckFailedFormat = "Falha ao verificar atualizações: %s",
        openSourceSectionTitle = "Bibliotecas de código aberto",
        closeButton = "Fechar"
    )

    private val ru = AboutStrings(
        navOther = "Другое",
        generalSectionTitle = "Общие",
        aboutTooltip = "О приложении",
        dialogTitle = "О AAMaKer",
        shortDescription = "Универсальный инструмент для патчинга APK Android Auto, лаунчера с разделением экрана Car Stage и установки с источником Google Play.",
        versionLabel = "Версия",
        developerSectionTitle = "Разработчик и проект",
        developerLabel = "Разработчик",
        githubProjectLabel = "Репозиторий GitHub",
        openInBrowserTooltip = "Открыть в браузере",
        updateSectionTitle = "Обновления",
        checkUpdateButton = "Проверить обновления",
        checkingUpdate = "Проверка последнего релиза на GitHub…",
        upToDateFormat = "У вас установлена последняя версия (%s)",
        updateAvailableFormat = "Доступна новая версия: %s",
        releaseNotesLabel = "Что нового",
        downloadApkButton = "Скачать APK",
        openReleaseButton = "Открыть релиз",
        retryButton = "Повторить",
        noReleasesFound = "На GitHub пока нет опубликованных релизов.",
        updateCheckFailedFormat = "Ошибка проверки обновлений: %s",
        openSourceSectionTitle = "Библиотеки с открытым исходным кодом",
        closeButton = "Закрыть"
    )

    private val ja = AboutStrings(
        navOther = "その他",
        generalSectionTitle = "一般",
        aboutTooltip = "アプリについて",
        dialogTitle = "AAMaKer について",
        shortDescription = "Android Auto APK パッチャー、Car Stage 分割画面ランチャー、Google Play ソースインストーラーを備えたオールインワンツール。",
        versionLabel = "バージョン",
        developerSectionTitle = "開発者とプロジェクト",
        developerLabel = "開発者",
        githubProjectLabel = "GitHub リポジトリ",
        openInBrowserTooltip = "ブラウザで開く",
        updateSectionTitle = "アップデート",
        checkUpdateButton = "アップデートを確認",
        checkingUpdate = "GitHub の最新リリースを確認中…",
        upToDateFormat = "最新バージョンです (%s)",
        updateAvailableFormat = "新しいバージョンがあります: %s",
        releaseNotesLabel = "リリースノート",
        downloadApkButton = "APK をダウンロード",
        openReleaseButton = "リリースを見る",
        retryButton = "再試行",
        noReleasesFound = "GitHub にまだリリースが公開されていません。",
        updateCheckFailedFormat = "アップデート確認に失敗しました: %s",
        openSourceSectionTitle = "オープンソースライブラリ",
        closeButton = "閉じる"
    )

    private val de = AboutStrings(
        navOther = "Sonstiges",
        generalSectionTitle = "Allgemein",
        aboutTooltip = "Über",
        dialogTitle = "Über AAMaKer",
        shortDescription = "All-in-One-Tool zum Patchen von Android Auto APKs, Car Stage Split-Screen-Launcher und Google Play-Quellinstaller.",
        versionLabel = "Version",
        developerSectionTitle = "Entwickler & Projekt",
        developerLabel = "Entwickler",
        githubProjectLabel = "GitHub-Repository",
        openInBrowserTooltip = "Im Browser öffnen",
        updateSectionTitle = "Updates",
        checkUpdateButton = "Nach Updates suchen",
        checkingUpdate = "Prüfe neuestes GitHub-Release…",
        upToDateFormat = "Du bist auf dem neuesten Stand (%s)",
        updateAvailableFormat = "Neue Version verfügbar: %s",
        releaseNotesLabel = "Versionshinweise",
        downloadApkButton = "APK herunterladen",
        openReleaseButton = "Release ansehen",
        retryButton = "Erneut versuchen",
        noReleasesFound = "Auf GitHub wurden noch keine Releases veröffentlicht.",
        updateCheckFailedFormat = "Update-Prüfung fehlgeschlagen: %s",
        openSourceSectionTitle = "Open-Source-Bibliotheken",
        closeButton = "Schließen"
    )

    private val fr = AboutStrings(
        navOther = "Autres",
        generalSectionTitle = "Général",
        aboutTooltip = "À propos",
        dialogTitle = "À propos d'AAMaKer",
        shortDescription = "Outil tout-en-un pour patcher les APK Android Auto, lanceur écran partagé Car Stage et installateur source Google Play.",
        versionLabel = "Version",
        developerSectionTitle = "Développeur et Projet",
        developerLabel = "Développeur",
        githubProjectLabel = "Dépôt GitHub",
        openInBrowserTooltip = "Ouvrir dans le navigateur",
        updateSectionTitle = "Mises à jour",
        checkUpdateButton = "Vérifier les mises à jour",
        checkingUpdate = "Vérification de la dernière version sur GitHub…",
        upToDateFormat = "Vous utilisez la dernière version (%s)",
        updateAvailableFormat = "Nouvelle version disponible : %s",
        releaseNotesLabel = "Notes de version",
        downloadApkButton = "Télécharger l'APK",
        openReleaseButton = "Voir la version",
        retryButton = "Réessayer",
        noReleasesFound = "Aucune version publiée sur GitHub pour le moment.",
        updateCheckFailedFormat = "Échec de la vérification : %s",
        openSourceSectionTitle = "Bibliothèques open source",
        closeButton = "Fermer"
    )
}

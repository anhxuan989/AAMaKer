package com.aamaker.core.patcher.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.aamaker.core.patcher.model.PatchConfig
import com.aamaker.core.patcher.security.PackageNameValidator
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.writer.ZipAligner
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern

class AnyAutoCompanionEngine(
    private val context: Context,
    private val assetReader: ((String) -> ByteArray?)? = null
) {

    companion object {
        private const val TEMPLATE_ASSET_NAME = "anyauto_template.apk"
        private const val ATTR_PACKAGE = 0x01010004
        private const val ATTR_NAME = 0x01010003
        private const val ATTR_VALUE = 0x01010024
        private const val ATTR_LABEL = 0x01010001
        private const val ATTR_AUTHORITIES = 0x01010018

        private val NATIVE_SO_ALIGNMENT_PATTERN: Pattern = Pattern.compile("^lib/[^/]+/[^/]+\\.so$")
    }

    fun generateCompanionApk(
        config: PatchConfig,
        outputUnsignedApk: File,
        workDir: File,
        onLog: (String) -> Unit
    ): BinaryPatchOutcome {
        onLog("[AnyAutoEngine] Bắt đầu khởi tạo Dedicated Car Launcher qua AnyAuto Engine...")

        val templateBytes = readTemplateBytes()
            ?: throw IllegalStateException("Không tìm thấy file mẫu AnyAuto runtime template ($TEMPLATE_ASSET_NAME) trong assets!")

        val tempTemplateFile = File(workDir, "template_base.apk")
        tempTemplateFile.outputStream().use { it.write(templateBytes) }

        val apkModule = ApkModule.loadApkFile(tempTemplateFile).apply {
            setLoadDefaultFramework(false)
        }

        try {
            val manifestBlock = apkModule.androidManifest
                ?: throw IllegalStateException("AnyAuto template APK không chứa AndroidManifest.xml hợp lệ!")
            val rootManifest = manifestBlock.documentElement
                ?: throw IllegalStateException("AndroidManifest.xml thiếu thẻ gốc <manifest>!")

            val originalPkg = config.originalPackageName
            val targetPkg = config.customPackageName?.takeIf { it.isNotBlank() } ?: "${originalPkg}.car"
            require(PackageNameValidator.isValidPackageName(targetPkg)) {
                "Package Name đích không hợp lệ: '$targetPkg'"
            }

            val finalAppName = config.customAppName?.takeIf { it.isNotBlank() } ?: "${config.appName} (Car)"
            onLog("[AnyAutoEngine] Tên gói Car Launcher: $targetPkg")
            onLog("[AnyAutoEngine] Tên hiển thị trên xe: $finalAppName")

            // 1. Cập nhật Package Name
            manifestBlock.packageName = targetPkg
            setAndroidStringAttr(rootManifest, "package", ATTR_PACKAGE, targetPkg)

            val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
                ?: throw IllegalStateException("Thiếu thẻ <application> trong manifest template!")

            setAndroidStringAttr(appElement, "label", ATTR_LABEL, finalAppName)

            // 2. Tìm MainActivity của ứng dụng mục tiêu
            val targetActivityName = resolveTargetMainActivity(config)
            onLog("[AnyAutoEngine] Ứng dụng mục tiêu: $originalPkg -> Activity: ${targetActivityName ?: "(Auto Launch)"}")

            // 3. Cập nhật Meta-Data đích vào Application
            var pkgMetaFound = false
            var actMetaFound = false
            appElement.getElements { it.name == "meta-data" }.forEach { meta ->
                val metaName = meta.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                if (metaName == "com.aamaker.anyauto.TARGET_PACKAGE") {
                    setAndroidStringAttr(meta, "value", ATTR_VALUE, originalPkg)
                    pkgMetaFound = true
                } else if (metaName == "com.aamaker.anyauto.TARGET_ACTIVITY") {
                    if (!targetActivityName.isNullOrBlank()) {
                        setAndroidStringAttr(meta, "value", ATTR_VALUE, targetActivityName)
                    }
                    actMetaFound = true
                }
            }

            if (!pkgMetaFound) {
                val metaElem = appElement.createChildElement("meta-data")
                setAndroidStringAttr(metaElem, "name", ATTR_NAME, "com.aamaker.anyauto.TARGET_PACKAGE")
                setAndroidStringAttr(metaElem, "value", ATTR_VALUE, originalPkg)
            }
            if (!actMetaFound && !targetActivityName.isNullOrBlank()) {
                val metaElem = appElement.createChildElement("meta-data")
                setAndroidStringAttr(metaElem, "name", ATTR_NAME, "com.aamaker.anyauto.TARGET_ACTIVITY")
                setAndroidStringAttr(metaElem, "value", ATTR_VALUE, targetActivityName)
            }

            // 3.1 Cấu hình chuyên biệt: assets/anyauto_target.json
            val targetConfigJson = org.json.JSONObject().apply {
                put("targetPackage", originalPkg)
                put("targetActivity", targetActivityName ?: "")
                put("appName", finalAppName)
            }.toString(2).toByteArray(Charsets.UTF_8)
            apkModule.add(ByteInputSource(targetConfigJson, "assets/anyauto_target.json"))
            onLog("[AnyAutoEngine] + Đã tạo file cấu hình dedicated app: assets/anyauto_target.json")

            // 4. Cập nhật TẤT CẢ các Provider authorities để đảm bảo tính duy nhất (tránh INSTALL_FAILED_CONFLICTING_PROVIDER)
            appElement.getElements { it.name == "provider" }.forEach { prov ->
                val provName = prov.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                val authAttr = prov.searchAttributeByResourceId(ATTR_AUTHORITIES)
                    ?: prov.searchAttributeByName("authorities")
                val oldAuth = authAttr?.valueAsString

                val newAuth = when {
                    provName == "rikka.shizuku.ShizukuProvider" -> "$targetPkg.shizuku"
                    provName == "androidx.core.content.FileProvider" -> "$targetPkg.files"
                    provName == "androidx.startup.InitializationProvider" || oldAuth?.contains("androidx-startup") == true -> "$targetPkg.androidx-startup"
                    !oldAuth.isNullOrBlank() && oldAuth.contains("com.mag.anyauto") -> {
                        oldAuth.replace("com.mag.anyauto", targetPkg)
                    }
                    !oldAuth.isNullOrBlank() -> "$targetPkg.${oldAuth.substringAfterLast('.')}"
                    else -> "$targetPkg.provider"
                }
                setAndroidStringAttr(prov, "authorities", ATTR_AUTHORITIES, newAuth)
                onLog("[AnyAutoEngine] + Cập nhật Provider ($provName): authorities = $newAuth")
            }

            // 4.1 Cập nhật tên các permission & uses-permission nội bộ (tránh INSTALL_FAILED_DUPLICATE_PERMISSION)
            rootManifest.getElements { it.name in listOf("permission", "uses-permission") }.forEach { perm ->
                val nameAttr = perm.searchAttributeByResourceId(ATTR_NAME)
                    ?: perm.searchAttributeByName("name")
                val oldName = nameAttr?.valueAsString
                if (!oldName.isNullOrBlank() && oldName.contains("com.mag.anyauto")) {
                    val newName = oldName.replace("com.mag.anyauto", targetPkg)
                    setAndroidStringAttr(perm, "name", ATTR_NAME, newName)
                }
            }

            // 5. Cập nhật nhãn Activity & Service
            appElement.getElements { it.name in listOf("activity", "service") }.forEach { comp ->
                setAndroidStringAttr(comp, "label", ATTR_LABEL, finalAppName)
            }

            // 6. Xóa bỏ mọi file XML adaptive icon cũ của template để ép hệ thống tải bitmap PNG
            cleanupOldAdaptiveIcons(apkModule, onLog)

            // 7. Trích xuất và thay thế Icon của ứng dụng mục tiêu
            val targetIconBytes = extractTargetAppIconBytes(config, onLog)
            if (targetIconBytes != null && targetIconBytes.isNotEmpty()) {
                injectAppIcon(apkModule, targetIconBytes, onLog)
            }

            stripSignatureEntries(apkModule)
            apkModule.refreshTable()
            apkModule.refreshManifest()
            apkModule.setApkSignatureBlock(null)

            onLog("[AnyAutoEngine] Đang đóng gói binary APK...")
            val writer = apkModule.createApkFileWriter(outputUnsignedApk)
            writer.zipAligner = ZipAligner.apkAligner().apply {
                setFileAlignment(NATIVE_SO_ALIGNMENT_PATTERN, 16384)
            }
            writer.write()

            return BinaryPatchOutcome(
                dexInjected = false,
                minSdkVersion = 29
            )
        } finally {
            runCatching { apkModule.close() }
            runCatching { tempTemplateFile.delete() }
        }
    }

    private fun resolveTargetMainActivity(config: PatchConfig): String? {
        // 1. Thử lấy từ PackageManager nếu app đã cài
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(config.originalPackageName)
            val comp = launchIntent?.component
            if (comp != null) {
                return comp.className
            }
        } catch (_: Throwable) {
        }

        // 2. Thử đọc từ manifest của source APK nếu có
        for (file in config.sourceApkFiles) {
            if (!file.exists() || !file.name.endsWith(".apk", ignoreCase = true)) continue
            try {
                ApkModule.loadApkFile(file).use { srcMod ->
                    srcMod.setLoadDefaultFramework(false)
                    val root = srcMod.androidManifest?.documentElement ?: return@use
                    val app = root.getElements { it.name == "application" }.asSequence().firstOrNull() ?: return@use
                    for (act in app.getElements { it.name == "activity" || it.name == "activity-alias" }) {
                        val hasLauncher = act.getElements { it.name == "intent-filter" }.asSequence().any { filter ->
                            val hasMain = filter.getElements { it.name == "action" }.asSequence().any {
                                it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == Intent.ACTION_MAIN
                            }
                            val hasLaunchCat = filter.getElements { it.name == "category" }.asSequence().any {
                                it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == Intent.CATEGORY_LAUNCHER
                            }
                            hasMain && hasLaunchCat
                        }
                        if (hasLauncher) {
                            val actName = act.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                            if (!actName.isNullOrBlank()) {
                                return if (actName.startsWith(".")) config.originalPackageName + actName else actName
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun extractTargetAppIconBytes(config: PatchConfig, onLog: (String) -> Unit): ByteArray? {
        // 1. Thử lấy từ PackageManager nếu ứng dụng đã cài đặt trên thiết bị
        try {
            val appInfo = runCatching { context.packageManager.getApplicationInfo(config.originalPackageName, 0) }.getOrNull()
            if (appInfo != null) {
                val drawable = runCatching { context.packageManager.getApplicationIcon(appInfo) }.getOrNull()
                if (drawable != null) {
                    val bitmap = drawableToBitmap(drawable)
                    if (bitmap != null) {
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        onLog("[AnyAutoEngine] + Đã trích xuất icon từ ứng dụng đã cài đặt (${stream.size()} bytes).")
                        return stream.toByteArray()
                    }
                }
            }
        } catch (e: Throwable) {
            onLog("[AnyAutoEngine] Không thể trích xuất icon từ PackageManager: ${e.message}")
        }

        // 2. Thử lấy từ source APK files
        for (file in config.sourceApkFiles) {
            if (!file.exists() || !file.name.endsWith(".apk", ignoreCase = true)) continue
            try {
                val pkgInfo = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                val appInfo = pkgInfo?.applicationInfo
                if (appInfo != null) {
                    appInfo.sourceDir = file.absolutePath
                    appInfo.publicSourceDir = file.absolutePath
                    val drawable = appInfo.loadIcon(context.packageManager)
                    val bitmap = drawableToBitmap(drawable)
                    if (bitmap != null) {
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        onLog("[AnyAutoEngine] + Đã trích xuất icon từ file APK nguồn qua ArchiveInfo (${stream.size()} bytes).")
                        return stream.toByteArray()
                    }
                }
            } catch (_: Throwable) {
            }

            // 3. Thử trích xuất trực tiếp ảnh launcher từ APK nguồn bằng ApkModule
            try {
                ApkModule.loadApkFile(file).use { srcMod ->
                    srcMod.setLoadDefaultFramework(false)
                    val candidates = srcMod.listInputSources().filter { s ->
                        val a = s.alias
                        (a.contains("mipmap") || a.contains("drawable")) &&
                                (a.contains("ic_launcher") || a.contains("icon")) &&
                                (a.endsWith(".png", ignoreCase = true) || a.endsWith(".webp", ignoreCase = true))
                    }
                    val best = candidates.firstOrNull { it.alias.contains("xxxhdpi") }
                        ?: candidates.firstOrNull { it.alias.contains("xxhdpi") }
                        ?: candidates.firstOrNull { it.alias.contains("xhdpi") }
                        ?: candidates.firstOrNull()

                    if (best != null) {
                        val bytes = best.openStream().use { it.readBytes() }
                        if (bytes.isNotEmpty()) {
                            onLog("[AnyAutoEngine] + Đã trích xuất icon trực tiếp từ file APK (${best.alias}, ${bytes.size} bytes).")
                            return bytes
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return null
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width >= 192 && bmp.height >= 192) {
                return bmp
            }
        }
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun cleanupOldAdaptiveIcons(apkModule: ApkModule, onLog: (String) -> Unit) {
        val zipMap = apkModule.zipEntryMap
        val toRemove = mutableListOf<String>()
        for (source in apkModule.listInputSources()) {
            val alias = source.alias
            if (alias.contains("mipmap-anydpi") ||
                (alias.contains("drawable") && alias.contains("ic_launcher") && alias.endsWith(".xml", ignoreCase = true))
            ) {
                toRemove.add(alias)
            }
        }
        for (alias in toRemove) {
            zipMap.remove(alias)
        }
        if (toRemove.isNotEmpty()) {
            onLog("[AnyAutoEngine] + Đã dọn dẹp ${toRemove.size} file XML icon cũ của template.")
        }
    }

    private fun injectAppIcon(apkModule: ApkModule, iconBytes: ByteArray, onLog: (String) -> Unit) {
        try {
            // 1. Quét và ghi đè TẤT CẢ các file icon launcher hiện có trong apkModule (kể cả có đuôi -v4)
            val existingLauncherAliases = apkModule.listInputSources()
                .map { it.alias }
                .filter { alias ->
                    (alias.contains("mipmap") || alias.contains("drawable")) &&
                            (alias.contains("launcher") || alias.contains("icon")) &&
                            alias.endsWith(".png", ignoreCase = true)
                }

            for (alias in existingLauncherAliases) {
                apkModule.add(ByteInputSource(iconBytes, alias))
            }

            // 2. Bổ sung đầy đủ các đường dẫn chuẩn theo mọi mật độ màn hình (cả có và không có -v4)
            val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
            for (d in densities) {
                apkModule.add(ByteInputSource(iconBytes, "res/mipmap-$d/ic_launcher.png"))
                apkModule.add(ByteInputSource(iconBytes, "res/mipmap-$d-v4/ic_launcher.png"))
                apkModule.add(ByteInputSource(iconBytes, "res/mipmap-$d/ic_launcher_round.png"))
                apkModule.add(ByteInputSource(iconBytes, "res/mipmap-$d-v4/ic_launcher_round.png"))
                apkModule.add(ByteInputSource(iconBytes, "res/mipmap-$d/ic_launcher_foreground.png"))
                apkModule.add(ByteInputSource(iconBytes, "res/mipmap-$d-v4/ic_launcher_foreground.png"))
                apkModule.add(ByteInputSource(iconBytes, "res/mipmap-$d/ic_launcher_background.png"))
                apkModule.add(ByteInputSource(iconBytes, "res/mipmap-$d-v4/ic_launcher_background.png"))
            }

            apkModule.add(ByteInputSource(iconBytes, "res/drawable/ic_launcher.png"))
            apkModule.add(ByteInputSource(iconBytes, "res/drawable-v4/ic_launcher.png"))
            apkModule.add(ByteInputSource(iconBytes, "res/drawable/ic_launcher_fg.png"))

            onLog("[AnyAutoEngine] + Đã nạp icon ứng dụng đích vào Car Launcher.")
        } catch (e: Throwable) {
            onLog("[AnyAutoEngine] Cảnh báo gắn icon: ${e.message}")
        }
    }

    private fun stripSignatureEntries(apkModule: ApkModule) {
        val zipMap = apkModule.zipEntryMap
        val toRemove = mutableListOf<String>()
        for (source in apkModule.listInputSources()) {
            val alias = source.alias
            if (SplitApkMerger.isSignatureFile(alias) || alias == "stamp-cert-sha256") {
                toRemove.add(alias)
            }
        }
        for (alias in toRemove) {
            zipMap.remove(alias)
        }
    }

    private fun readTemplateBytes(): ByteArray? {
        assetReader?.let { return it(TEMPLATE_ASSET_NAME) }
        return try {
            context.assets.open(TEMPLATE_ASSET_NAME).use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun setAndroidStringAttr(
        element: ResXmlElement,
        name: String,
        resourceId: Int,
        value: String
    ) {
        val attr: ResXmlAttribute = element.getOrCreateAndroidAttribute(name, resourceId)
        attr.setValueAsString(value)
    }
}

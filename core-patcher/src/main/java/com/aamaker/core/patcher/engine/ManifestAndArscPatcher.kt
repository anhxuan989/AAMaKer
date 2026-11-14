package com.aamaker.core.patcher.engine

import android.content.Context
import com.aamaker.core.patcher.model.PatchConfig
import com.aamaker.core.patcher.model.PatchStrategy
import com.aamaker.core.patcher.security.BridgeAuthManager
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.writer.ZipAligner
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.util.regex.Pattern

data class BinaryPatchOutcome(
    val dexInjected: Boolean,
    val minSdkVersion: Int
)

class ManifestAndArscPatcher(
    private val context: Context? = null,
    private val assetReader: ((String) -> ByteArray?)? = null
) {

    fun patchApkBinary(
        inputApk: File,
        outputUnsignedApk: File,
        config: PatchConfig,
        onLog: (String) -> Unit
    ): Boolean {
        val apkModule = ApkModule.loadApkFile(inputApk).apply {
            setLoadDefaultFramework(false)
        }
        return try {
            patchApkModule(apkModule, outputUnsignedApk, config, onLog).dexInjected
        } finally {
            runCatching { apkModule.close() }
        }
    }

    fun patchApkModule(
        apkModule: ApkModule,
        outputUnsignedApk: File,
        config: PatchConfig,
        onLog: (String) -> Unit
    ): BinaryPatchOutcome {
        onLog("[Patcher] Đang xử lý cấu trúc APK nhị phân bằng ARSCLib...")
        apkModule.setLoadDefaultFramework(false)
        val normalizedTypes = SplitApkMerger.normalizeSparseAndCompactTypeBlocks(apkModule)
        if (normalizedTypes > 0) {
            onLog("[Patcher] + Đã chuẩn hóa $normalizedTypes bảng tài nguyên nén (Sparse/Compact TypeBlock) sang định dạng chuẩn để bảo toàn ID tài nguyên.")
        }
        val manifestBlock = apkModule.androidManifest
            ?: throw IllegalStateException("APK không chứa AndroidManifest.xml hợp lệ!")

        val rootManifest = manifestBlock.documentElement
            ?: throw IllegalStateException("AndroidManifest.xml thiếu thẻ gốc <manifest>!")

        val resolvedMinSdk = (manifestBlock.minSdkVersion ?: 24).coerceIn(21, 35)

        stripSplitAndTamperComponents(apkModule, rootManifest, config, onLog)

        val originalPkg = apkModule.packageName ?: config.originalPackageName
        val targetPkg = config.customPackageName?.takeIf { it.isNotBlank() } ?: originalPkg

        if (targetPkg != originalPkg) {
            onLog("[Patcher] Đang đổi tên gói: $originalPkg -> $targetPkg (cập nhật Application, Component, Permission & Authorities)...")
            renamePackageAndAuthorities(apkModule, manifestBlock, originalPkg, targetPkg)
        }

        config.customAppName?.takeIf { it.isNotBlank() }?.let { newName ->
            onLog("[Patcher] Đang cập nhật tên hiển thị ứng dụng thành: $newName")
            val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
            if (appElement != null) {
                setAndroidStringAttr(appElement, "label", ATTR_LABEL, newName)
                appElement.getElements { it.name == "activity" || it.name == "activity-alias" }.forEach { actElem ->
                    val isMainLauncher = actElem.getElements { it.name == "intent-filter" }.asSequence().any { filter ->
                        val hasMain = filter.getElements { it.name == "action" }.asSequence().any {
                            it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == "android.intent.action.MAIN"
                        }
                        val hasLauncher = filter.getElements { it.name == "category" }.asSequence().any {
                            val cat = it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                            cat == "android.intent.category.LAUNCHER" || cat == "android.intent.category.LEANBACK_LAUNCHER"
                        }
                        hasMain && hasLauncher
                    }
                    if (isMainLauncher && actElem.searchAttributeByResourceId(ATTR_LABEL) != null) {
                        setAndroidStringAttr(actElem, "label", ATTR_LABEL, newName)
                    }
                }
            }
        }

        if (config.strategy != PatchStrategy.PARKED_APP_MODE) {
            ensureUsesPermissions(
                rootManifest = rootManifest,
                permissions = listOf(
                    "androidx.car.app.NAVIGATION_TEMPLATES",
                    "androidx.car.app.MAP_TEMPLATES",
                    "androidx.car.app.ACCESS_SURFACE",
                    "android.permission.FOREGROUND_SERVICE",
                    "android.permission.WAKE_LOCK",
                    BridgeAuthManager.PERMISSION_COMMAND_BRIDGE
                ),
                onLog = onLog
            )

            ensureAutomotiveAppDescriptor(apkModule, rootManifest, config, onLog)
            ensureCarAppQueries(rootManifest)
        } else {
            val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
            val hasCarService = appElement?.getElements { it.name == "service" }?.asSequence()?.any { srv ->
                srv.getElements { it.name == "intent-filter" }.asSequence().any { filter ->
                    filter.getElements { it.name == "action" }.asSequence().any { act ->
                        val actName = act.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                        actName == "androidx.car.app.CarAppService" || actName == "android.media.browse.MediaBrowserService"
                    }
                }
            } == true
            if (!hasCarService && appElement != null) {
                appElement.getElements { it.name == "meta-data" }.asSequence().toList().forEach { meta ->
                    val metaName = meta.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                    if (metaName == CAR_APP_META_NAME || metaName == "com.android.automotive" || metaName == "androidx.car.app.minCarApiLevel") {
                        meta.removeSelf()
                    }
                }
            }
        }

        val shouldApplyParkedManifest = config.strategy == PatchStrategy.PARKED_APP_MODE || config.injectParkedGameManifest
        if (shouldApplyParkedManifest) {
            injectParkedAppCompatibility(rootManifest, config, onLog)
        } else if (config.bypassDrivingRestriction) {
            injectDrivingBypassIntoAllActivities(rootManifest, onLog)
        }
        ensureHardwareFeaturesCompatibility(rootManifest, config, onLog)

        var dexInjected = false
        when (config.strategy) {
            PatchStrategy.UNIVERSAL_INJECTION -> {
                injectCarAppServiceIntoManifest(rootManifest, config, onLog)
                dexInjected = injectRuntimeStubDex(apkModule, onLog)
            }
            PatchStrategy.PARKED_APP_MODE -> {
                rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()?.let { appElem ->
                    enableEmbeddedActivitiesForCarDisplay(appElem, config, isUniversalProjection = false)
                }
                onLog("[Patcher] Chế độ PARKED_APP_MODE: Kích hoạt đầy đủ CAR_LAUNCHER & appCategory=\"game\" chuẩn Android Auto Parked App (không tiêm DEX).")
            }
            else -> {
                rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()?.let { appElem ->
                    enableEmbeddedActivitiesForCarDisplay(appElem, config, isUniversalProjection = false)
                }
                onLog("[Patcher] Chế độ SPOOF_AND_ACTIVATE: Giữ nguyên CarAppService/MediaBrowserService gốc của APK.")
            }
        }

        stripSignatureEntries(apkModule)

        apkModule.refreshTable()
        apkModule.refreshManifest()
        apkModule.setApkSignatureBlock(null)

        val uncompressed = apkModule.uncompressedFiles
        uncompressed.addPath("resources.arsc")
        for (source in apkModule.listInputSources()) {
            val alias = source.alias
            if (alias.startsWith("lib/") && alias.endsWith(".so")) {
                uncompressed.addPath(alias)
            }
        }
        apkModule.zipEntryMap.autoSortApkFiles()

        onLog("[Patcher] Đang đóng gói lại cấu trúc APK nhị phân (căn chỉnh 16KB Page Size cho thư viện Native .so)...")
        val writer = apkModule.createApkFileWriter(outputUnsignedApk)
        writer.zipAligner = ZipAligner.apkAligner().apply {
            setFileAlignment(NATIVE_SO_ALIGNMENT_PATTERN, 16384)
        }
        writer.write()

        return BinaryPatchOutcome(
            dexInjected = dexInjected,
            minSdkVersion = resolvedMinSdk
        )
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

    private fun stripSplitAndTamperComponents(
        apkModule: ApkModule,
        rootManifest: ResXmlElement,
        config: PatchConfig,
        onLog: (String) -> Unit
    ) {
        val removedCount = SplitApkMerger.cleanSplitArtifactsSafely(apkModule)

        val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
        if (appElement != null) {
            setAndroidBooleanAttr(appElement, "extractNativeLibs", ATTR_EXTRACT_NATIVE_LIBS, true)

            val appClsName = appElement.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
            val inputAliases = apkModule.listInputSources().map { it.alias }
            val hasPairipVm = appClsName == "com.pairip.application.Application" ||
                inputAliases.any { it.endsWith("/libpairipcore.so") || it.startsWith("assets/pairip") }
            val nativeProtectorLib = inputAliases.firstOrNull { alias ->
                NATIVE_PROTECTOR_SO_SUFFIXES.any { alias.endsWith(it) }
            }

            if (hasPairipVm) {
                onLog(
                    "[Patcher] ! Cảnh báo: Phát hiện Google Play PairIP VM (libpairipcore.so / Pairip Application) — " +
                        "giữ nguyên thành phần Manifest của PairIP để không kích hoạt bẫy chống sửa đổi. " +
                        "Nếu ứng dụng tự đóng sau khi ký lại APK, cần bật giả lập chữ ký gốc (LSPosed + CorePatch / pairipfix) " +
                        "hoặc dùng bản APK độc lập ngoài Play Store."
                )
            } else if (nativeProtectorLib != null || config.originalPackageName in KNOWN_NATIVE_SIGNATURE_CHECK_PACKAGES) {
                val marker = nativeProtectorLib ?: config.originalPackageName
                onLog(
                    "[Patcher] ! Cảnh báo: Phát hiện cơ chế kiểm tra chữ ký gốc tầng Native/C++ ($marker) — " +
                        "nếu ứng dụng tự đóng khi khởi chạy sau khi đổi chữ ký APK, cần bật LSPosed (CorePatch / Signature Spoofing) " +
                        "hoặc dùng gói cài đặt gốc chưa bọc DRM."
                )
            }

            if (config.removeSignatureVerification && !hasPairipVm) {
                var strippedChecks = 0
                appElement.getElements { it.name in COMPONENT_TAGS }.asSequence().toList().forEach { comp ->
                    val compName = comp.searchAttributeByResourceId(ATTR_NAME)?.valueAsString ?: return@forEach
                    if (compName.startsWith("com.pairip.licensecheck.")) {
                        comp.removeSelf()
                        strippedChecks++
                    }
                }
                if (strippedChecks > 0) {
                    onLog("[Patcher] + Đã vô hiệu hóa $strippedChecks thành phần kiểm tra giấy phép Play Licensing.")
                }
            }
        }

        if (removedCount > 0) {
            onLog("[Patcher] + Đã dọn sạch an toàn $removedCount thuộc tính/meta-data Split APK (bảo toàn tài nguyên dùng chung).")
        }
    }

    private fun renamePackageAndAuthorities(
        apkModule: ApkModule,
        manifest: ResXmlDocument,
        oldPkg: String,
        newPkg: String
    ) {
        val root = manifest.documentElement ?: return
        val appElement = root.getElements { it.name == "application" }.asSequence().firstOrNull()

        if (appElement != null) {
            expandRelativeClassAttr(appElement, ATTR_NAME, oldPkg)
            expandRelativeClassAttr(appElement, ATTR_MANAGE_SPACE_ACTIVITY, oldPkg)
            expandRelativeClassAttr(appElement, ATTR_BACKUP_AGENT, oldPkg)
            expandRelativeClassAttr(appElement, ATTR_APP_COMPONENT_FACTORY, oldPkg)

            rewritePackagePrefixedAttr(appElement, ATTR_PERMISSION, oldPkg, newPkg)
            rewritePackagePrefixedAttr(appElement, ATTR_TASK_AFFINITY, oldPkg, newPkg, allowExactMatch = true)
            rewritePackagePrefixedAttr(appElement, ATTR_PROCESS, oldPkg, newPkg, allowExactMatch = true)

            appElement.getElements { it.name in COMPONENT_TAGS }.forEach { comp ->
                expandRelativeClassAttr(comp, ATTR_NAME, oldPkg)

                if (comp.name == "activity") {
                    expandRelativeClassAttr(comp, ATTR_PARENT_ACTIVITY_NAME, oldPkg)
                }

                if (comp.name == "activity-alias") {
                    expandRelativeClassAttr(comp, ATTR_TARGET_ACTIVITY, oldPkg)
                    val fallbackTargetAttr = comp.searchAttributeByName("targetActivity")
                    if (fallbackTargetAttr != null && fallbackTargetAttr.valueType == ValueType.STRING) {
                        val currentTarget = fallbackTargetAttr.valueAsString
                        if (!currentTarget.isNullOrEmpty()) {
                            when {
                                currentTarget.startsWith(".") -> fallbackTargetAttr.setValueAsString(oldPkg + currentTarget)
                                !currentTarget.contains(".") -> fallbackTargetAttr.setValueAsString("$oldPkg.$currentTarget")
                            }
                        }
                    }
                }

                rewritePackagePrefixedAttr(comp, ATTR_PERMISSION, oldPkg, newPkg)
                rewritePackagePrefixedAttr(comp, ATTR_READ_PERMISSION, oldPkg, newPkg)
                rewritePackagePrefixedAttr(comp, ATTR_WRITE_PERMISSION, oldPkg, newPkg)
                rewritePackagePrefixedAttr(comp, ATTR_TASK_AFFINITY, oldPkg, newPkg, allowExactMatch = true)
                rewritePackagePrefixedAttr(comp, ATTR_PROCESS, oldPkg, newPkg, allowExactMatch = true)

                if (comp.name == "provider") {
                    val authAttr = comp.searchAttributeByResourceId(ATTR_AUTHORITIES)
                        ?: comp.searchAttributeByName("authorities")
                    if (authAttr != null) {
                        when (authAttr.valueType) {
                            ValueType.STRING -> {
                                val currentAuth = authAttr.valueAsString
                                if (!currentAuth.isNullOrEmpty()) {
                                    authAttr.setValueAsString(rewriteAuthorityString(currentAuth, oldPkg, newPkg))
                                }
                            }
                            ValueType.REFERENCE -> {
                                if (authAttr.data != 0 && apkModule.hasTableBlock()) {
                                    val entries = apkModule.tableBlock?.resolveReference(authAttr.data).orEmpty()
                                    for (entry in entries) {
                                        if (entry == null || entry.isNull) continue
                                        val resStr = entry.resValue?.valueAsString
                                        if (!resStr.isNullOrEmpty()) {
                                            entry.setValueAsString(rewriteAuthorityString(resStr, oldPkg, newPkg))
                                        }
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        val permTags = setOf(
            "permission",
            "permission-tree",
            "permission-group",
            "uses-permission",
            "uses-permission-sdk-23"
        )
        root.getElements { it.name in permTags }.forEach { permElem ->
            rewritePackagePrefixedAttr(permElem, ATTR_NAME, oldPkg, newPkg)
            rewritePackagePrefixedAttr(permElem, ATTR_PERMISSION_GROUP, oldPkg, newPkg)
        }

        apkModule.packageName = newPkg
    }

    private fun rewriteAuthorityString(rawAuth: String, oldPkg: String, newPkg: String): String {
        return rawAuth.split(";")
            .map { part ->
                val trimmed = part.trim()
                when {
                    trimmed.isEmpty() -> trimmed
                    trimmed.contains(oldPkg) -> trimmed.replace(oldPkg, newPkg)
                    trimmed.startsWith("$newPkg.") || trimmed == newPkg -> trimmed
                    else -> "$newPkg.$trimmed"
                }
            }
            .joinToString(";")
    }

    private fun expandRelativeClassAttr(element: ResXmlElement, attrId: Int, oldPkg: String) {
        val attr = element.searchAttributeByResourceId(attrId) ?: return
        if (attr.valueType != ValueType.STRING) return
        val currentVal = attr.valueAsString ?: return
        if (currentVal.startsWith(".")) {
            attr.setValueAsString(oldPkg + currentVal)
        } else if (!currentVal.contains(".") && currentVal.isNotEmpty()) {
            attr.setValueAsString("$oldPkg.$currentVal")
        }
    }

    private fun rewritePackagePrefixedAttr(
        element: ResXmlElement,
        attrId: Int,
        oldPkg: String,
        newPkg: String,
        allowExactMatch: Boolean = false
    ) {
        val attr = element.searchAttributeByResourceId(attrId) ?: return
        if (attr.valueType != ValueType.STRING) return
        val currentVal = attr.valueAsString ?: return
        when {
            allowExactMatch && currentVal == oldPkg -> attr.setValueAsString(newPkg)
            currentVal.startsWith("$oldPkg.") -> attr.setValueAsString(newPkg + currentVal.substring(oldPkg.length))
        }
    }

    private fun ensureUsesPermissions(
        rootManifest: ResXmlElement,
        permissions: List<String>,
        onLog: (String) -> Unit
    ) {
        val existingPerms = rootManifest.getElements { it.name == "uses-permission" }
            .asSequence()
            .mapNotNull { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString }
            .toSet()

        for (perm in permissions) {
            if (perm !in existingPerms) {
                val permElem = rootManifest.createChildElement("uses-permission")
                setAndroidStringAttr(permElem, "name", ATTR_NAME, perm)
                onLog("[Patcher] + Thêm quyền: $perm")
            }
        }
    }

    private fun ensureAutomotiveAppDescriptor(
        apkModule: ApkModule,
        rootManifest: ResXmlElement,
        config: PatchConfig,
        onLog: (String) -> Unit
    ) {
        val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
            ?: return

        val existingCarMeta = appElement.getElements { it.name == "meta-data" }
            .asSequence()
            .firstOrNull { elem ->
                val metaName = elem.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                metaName == CAR_APP_META_NAME || metaName == "com.android.automotive"
            }

        val existingResAttr = existingCarMeta?.searchAttributeByResourceId(ATTR_RESOURCE)
        if (existingCarMeta != null && existingResAttr != null && existingResAttr.valueType == ValueType.REFERENCE && existingResAttr.data != 0) {
            onLog("[Patcher] APK đã có sẵn khai báo '$CAR_APP_META_NAME' (@0x${Integer.toHexString(existingResAttr.data)}).")
            ensureMinCarApiLevelMeta(appElement)
            return
        }

        existingCarMeta?.removeSelf()

        val xmlPath = "res/xml/automotive_app_desc.xml"
        onLog("[Patcher] Đang tạo Binary XML '$xmlPath' và đăng ký vào bảng resources.arsc...")
        val descXmlDoc = createAutomotiveAppDescDocument(config)
        apkModule.removeResFile(xmlPath, false)
        apkModule.add(ByteInputSource(descXmlDoc.bytes, xmlPath))

        apkModule.ensureTableBlock()
        val tableBlock = apkModule.tableBlock
        val packageBlock = tableBlock?.currentPackage ?: tableBlock?.packages?.asSequence()?.firstOrNull()
            ?: throw IllegalStateException("Không tìm thấy PackageBlock trong resources.arsc!")

        val entry = packageBlock.getOrCreate("", "xml", "automotive_app_desc")
        entry.setValueAsString(xmlPath)
        val automotiveDescResId = entry.resourceId
        onLog("[Patcher] + Đã cấp phát Resource ID @0x${Integer.toHexString(automotiveDescResId)} -> $xmlPath")

        ensureMinCarApiLevelMeta(appElement)

        val metaElem = appElement.createChildElement("meta-data")
        setAndroidStringAttr(metaElem, "name", ATTR_NAME, CAR_APP_META_NAME)
        val resAttr = metaElem.getOrCreateAndroidAttribute("resource", ATTR_RESOURCE)
        resAttr.valueType = ValueType.REFERENCE
        resAttr.data = automotiveDescResId
        onLog("[Patcher] + Đã gắn <meta-data android:name=\"$CAR_APP_META_NAME\" android:resource=\"@0x${Integer.toHexString(automotiveDescResId)}\" />")
    }

    private fun ensureCarAppQueries(rootManifest: ResXmlElement) {
        val queriesElem = rootManifest.getElements { it.name == "queries" }.asSequence().firstOrNull()
            ?: rootManifest.createChildElement("queries")

        val hasCarConnectionProvider = queriesElem.getElements { it.name == "provider" }
            .asSequence()
            .any { it.searchAttributeByResourceId(ATTR_AUTHORITIES)?.valueAsString == "androidx.car.app.connection" }

        if (!hasCarConnectionProvider) {
            val providerElem = queriesElem.createChildElement("provider")
            setAndroidStringAttr(providerElem, "name", ATTR_NAME, "androidx.car.app.connection.provider")
            setAndroidStringAttr(providerElem, "authorities", ATTR_AUTHORITIES, "androidx.car.app.connection")
        }

        val hasAAMakerPkgQuery = queriesElem.getElements { it.name == "package" }
            .asSequence()
            .any { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == "com.aamaker.app" }

        if (!hasAAMakerPkgQuery) {
            val pkgElem = queriesElem.createChildElement("package")
            setAndroidStringAttr(pkgElem, "name", ATTR_NAME, "com.aamaker.app")
        }
    }

    private fun ensureMinCarApiLevelMeta(appElement: ResXmlElement) {
        val hasMinApi = appElement.getElements { it.name == "meta-data" }
            .asSequence()
            .any { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == "androidx.car.app.minCarApiLevel" }

        if (!hasMinApi) {
            val apiMeta = appElement.createChildElement("meta-data")
            setAndroidStringAttr(apiMeta, "name", ATTR_NAME, "androidx.car.app.minCarApiLevel")
            val valAttr = apiMeta.getOrCreateAndroidAttribute("value", ATTR_VALUE)
            valAttr.valueType = ValueType.DEC
            valAttr.data = 1
        }
    }

    private fun createAutomotiveAppDescDocument(config: PatchConfig): ResXmlDocument {
        val doc = ResXmlDocument()
        val root = doc.getOrCreateElement("automotiveApp")

        if (config.strategy == PatchStrategy.UNIVERSAL_INJECTION) {
            val usesTemplate = root.createChildElement("uses")
            usesTemplate.getOrCreateAttribute("name", 0).setValueAsString("template")
            if (config.injectMediaUses) {
                val usesMedia = root.createChildElement("uses")
                usesMedia.getOrCreateAttribute("name", 0).setValueAsString("media")
            }
        } else {
            if (config.injectTemplateUses) {
                val usesTemplate = root.createChildElement("uses")
                usesTemplate.getOrCreateAttribute("name", 0).setValueAsString("template")
            }
            if (config.injectProjectionUses && !config.injectTemplateUses) {
                val usesProj = root.createChildElement("uses")
                usesProj.getOrCreateAttribute("name", 0).setValueAsString("projection")
            }
            if (config.injectMediaUses) {
                val usesMedia = root.createChildElement("uses")
                usesMedia.getOrCreateAttribute("name", 0).setValueAsString("media")
            }
        }
        doc.refresh()
        return doc
    }

    private fun injectCarAppServiceIntoManifest(
        rootManifest: ResXmlElement,
        config: PatchConfig,
        onLog: (String) -> Unit
    ) {
        val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
            ?: return

        val appLabelAttr = appElement.searchAttributeByResourceId(ATTR_LABEL)
        val appIconAttr = appElement.searchAttributeByResourceId(ATTR_ICON)

        val serviceClassName = "com.aamaker.stub.runtime.AAMakerCarAppService"
        appElement.getElements { it.name == "service" }
            .asSequence()
            .filter { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == serviceClassName }
            .toList()
            .forEach { it.removeSelf() }

        onLog("[Patcher] Đang tiêm <service android:name=\"$serviceClassName\"> vào AndroidManifest.xml...")
        val serviceElem = appElement.createChildElement("service")
        setAndroidStringAttr(serviceElem, "name", ATTR_NAME, serviceClassName)
        setAndroidBooleanAttr(serviceElem, "exported", ATTR_EXPORTED, true)

        if (appLabelAttr != null) {
            val srvLabel = serviceElem.getOrCreateAndroidAttribute("label", ATTR_LABEL)
            srvLabel.valueType = appLabelAttr.valueType
            srvLabel.data = appLabelAttr.data
            if (appLabelAttr.valueType == ValueType.STRING) {
                srvLabel.setValueAsString(appLabelAttr.valueAsString)
            }
        }
        if (appIconAttr != null) {
            val srvIcon = serviceElem.getOrCreateAndroidAttribute("icon", ATTR_ICON)
            srvIcon.valueType = appIconAttr.valueType
            srvIcon.data = appIconAttr.data
        }

        val intentFilter = serviceElem.createChildElement("intent-filter")
        val actionElem = intentFilter.createChildElement("action")
        setAndroidStringAttr(actionElem, "name", ATTR_NAME, "androidx.car.app.CarAppService")

        val catPrimary = intentFilter.createChildElement("category")
        setAndroidStringAttr(catPrimary, "name", ATTR_NAME, config.category.xmlValue)

        if (config.category.xmlValue != "androidx.car.app.category.NAVIGATION") {
            val catNav = intentFilter.createChildElement("category")
            setAndroidStringAttr(catNav, "name", ATTR_NAME, "androidx.car.app.category.NAVIGATION")
        }

        enableEmbeddedActivitiesForCarDisplay(appElement, config, isUniversalProjection = true)

        val rootShellMetaName = "com.aamaker.USE_ROOT_SHELL"
        appElement.getElements { it.name == "meta-data" }
            .asSequence()
            .filter { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == rootShellMetaName }
            .toList()
            .forEach { it.removeSelf() }

        val rootMetaElem = appElement.createChildElement("meta-data")
        setAndroidStringAttr(rootMetaElem, "name", ATTR_NAME, rootShellMetaName)
        setAndroidBooleanAttr(rootMetaElem, "value", ATTR_VALUE, config.useRootShellForTouch)

        val bridgeTokenMetaName = BridgeAuthManager.META_BRIDGE_TOKEN
        appElement.getElements { it.name == "meta-data" }
            .asSequence()
            .filter { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == bridgeTokenMetaName }
            .toList()
            .forEach { it.removeSelf() }

        val bridgeToken = context?.let { BridgeAuthManager.getOrCreateBridgeToken(it) }
        if (!bridgeToken.isNullOrBlank()) {
            val tokenMetaElem = appElement.createChildElement("meta-data")
            setAndroidStringAttr(tokenMetaElem, "name", ATTR_NAME, bridgeTokenMetaName)
            setAndroidStringAttr(tokenMetaElem, "value", ATTR_VALUE, bridgeToken)
        }
    }

    private fun injectParkedAppCompatibility(
        rootManifest: ResXmlElement,
        config: PatchConfig,
        onLog: (String) -> Unit
    ) {
        val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
            ?: return

        val appCatAttr = appElement.getOrCreateAndroidAttribute("appCategory", ATTR_APP_CATEGORY)
        appCatAttr.valueType = ValueType.DEC
        appCatAttr.data = 0
        setAndroidBooleanAttr(appElement, "isGame", ATTR_IS_GAME, true)

        ensureWindowProperty(appElement, "android.window.PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE", true)
        ensureWindowProperty(appElement, "android.window.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES", true)
        ensureWindowProperty(appElement, "android.window.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE", true)

        var patchedLauncherCount = 0
        var drivingBypassActivityCount = 0
        if (config.bypassDrivingRestriction) {
            ensureDistractionOptimizedMeta(appElement)
        }
        appElement.getElements { it.name == "activity" || it.name == "activity-alias" }.forEach { actElem ->
            if (config.bypassDrivingRestriction) {
                ensureDistractionOptimizedMeta(actElem)
                drivingBypassActivityCount++
            }
            val intentFilters = actElem.getElements { it.name == "intent-filter" }.asSequence().toList()
            for (filter in intentFilters) {
                val hasMainAction = filter.getElements { it.name == "action" }.asSequence().any {
                    it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == "android.intent.action.MAIN"
                }
                val categories = filter.getElements { it.name == "category" }.asSequence()
                    .mapNotNull { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString }
                    .toSet()

                val isLauncher = "android.intent.category.LAUNCHER" in categories ||
                    "android.intent.category.LEANBACK_LAUNCHER" in categories ||
                    "android.intent.category.INFO" in categories

                if (hasMainAction && isLauncher) {
                    setAndroidBooleanAttr(actElem, "exported", ATTR_EXPORTED, true)
                    if ("android.intent.category.CAR_LAUNCHER" !in categories) {
                        val carLauncherCat = filter.createChildElement("category")
                        setAndroidStringAttr(carLauncherCat, "name", ATTR_NAME, "android.intent.category.CAR_LAUNCHER")
                    }
                    if ("android.intent.category.CAR_MODE" !in categories) {
                        val carModeCat = filter.createChildElement("category")
                        setAndroidStringAttr(carModeCat, "name", ATTR_NAME, "android.intent.category.CAR_MODE")
                    }
                    ensureDistractionOptimizedMeta(actElem)
                    patchedLauncherCount++
                }
            }
        }

        if (patchedLauncherCount > 0) {
            onLog("[Patcher] + Đã kích hoạt chế độ Parked App (appCategory=game, CAR_LAUNCHER & distractionOptimized) cho $patchedLauncherCount Launcher Activity.")
        }
        if (config.bypassDrivingRestriction && drivingBypassActivityCount > 0) {
            onLog("[Patcher] + Đã tiêm cờ mở khóa khi xe di chuyển (distractionOptimized) cho toàn bộ $drivingBypassActivityCount Activity.")
        }
    }

    private fun injectDrivingBypassIntoAllActivities(
        rootManifest: ResXmlElement,
        onLog: (String) -> Unit
    ) {
        val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
            ?: return
        ensureDistractionOptimizedMeta(appElement)
        var count = 0
        appElement.getElements { it.name == "activity" || it.name == "activity-alias" }.forEach { actElem ->
            ensureDistractionOptimizedMeta(actElem)
            count++
        }
        if (count > 0) {
            onLog("[Patcher] + Đã tiêm cờ mở khóa khi xe di chuyển (distractionOptimized) cho toàn bộ $count Activity.")
        }
    }

    private fun ensureHardwareFeaturesCompatibility(
        rootManifest: ResXmlElement,
        config: PatchConfig,
        onLog: (String) -> Unit
    ) {
        var relaxedCount = 0
        val existingFeatures = mutableMapOf<String, ResXmlElement>()

        rootManifest.getElements { it.name == "uses-feature" }.forEach { featElem ->
            val featName = featElem.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
            if (!featName.isNullOrEmpty()) {
                existingFeatures[featName] = featElem
                val reqAttr = featElem.searchAttributeByResourceId(ATTR_REQUIRED)
                if (reqAttr != null && reqAttr.valueAsBoolean) {
                    reqAttr.setValueAsBoolean(false)
                    relaxedCount++
                }
            }
        }

        val featuresToEnsure = mutableListOf(
            "android.hardware.touchscreen",
            "android.hardware.faketouch",
            "android.hardware.screen.landscape",
            "android.hardware.screen.portrait"
        )
        if (config.enableGamepadSupport) {
            featuresToEnsure.add("android.hardware.gamepad")
        }

        for (featName in featuresToEnsure) {
            val existing = existingFeatures[featName]
            if (existing != null) {
                setAndroidBooleanAttr(existing, "required", ATTR_REQUIRED, false)
            } else {
                val featElem = rootManifest.createChildElement("uses-feature")
                setAndroidStringAttr(featElem, "name", ATTR_NAME, featName)
                setAndroidBooleanAttr(featElem, "required", ATTR_REQUIRED, false)
            }
        }

        if (config.enableGamepadSupport || relaxedCount > 0) {
            onLog("[Patcher] + Đã tối ưu hóa phần cứng: bật hỗ trợ Gamepad=${config.enableGamepadSupport}, gỡ bỏ bắt buộc cho $relaxedCount uses-feature.")
        }
    }

    private fun ensureDistractionOptimizedMeta(element: ResXmlElement) {
        val metaKeys = listOf(
            "distractionOptimized",
            "com.google.android.gms.car.distractionOptimized",
            "com.android.automotive.distractionOptimized"
        )
        val existingMetaNames = element.getElements { it.name == "meta-data" }.asSequence()
            .mapNotNull { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString }
            .toSet()

        for (metaKey in metaKeys) {
            if (metaKey !in existingMetaNames) {
                val meta = element.createChildElement("meta-data")
                setAndroidStringAttr(meta, "name", ATTR_NAME, metaKey)
                setAndroidBooleanAttr(meta, "value", ATTR_VALUE, true)
            } else {
                element.getElements { it.name == "meta-data" }.asSequence()
                    .filter { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == metaKey }
                    .forEach { setAndroidBooleanAttr(it, "value", ATTR_VALUE, true) }
            }
        }
    }

    private fun ensureWindowProperty(appElement: ResXmlElement, propName: String, value: Boolean) {
        val hasProp = appElement.getElements { it.name == "property" }.asSequence().any {
            it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == propName
        }
        if (!hasProp) {
            val prop = appElement.createChildElement("property")
            setAndroidStringAttr(prop, "name", ATTR_NAME, propName)
            setAndroidBooleanAttr(prop, "value", ATTR_VALUE, value)
        }
    }

    private fun enableEmbeddedActivitiesForCarDisplay(
        appElement: ResXmlElement,
        config: PatchConfig? = null,
        isUniversalProjection: Boolean = false
    ) {
        val unlockOrientation = config?.unlockScreenOrientation ?: true
        setAndroidBooleanAttr(appElement, "resizeableActivity", ATTR_RESIZEABLE_ACTIVITY, true)

        appElement.getElements { it.name == "activity" }.forEach { actElem ->
            setAndroidBooleanAttr(actElem, "resizeableActivity", ATTR_RESIZEABLE_ACTIVITY, true)
            setAndroidBooleanAttr(actElem, "allowEmbedded", ATTR_ALLOW_EMBEDDED, true)

            if (isUniversalProjection) {
                setAndroidBooleanAttr(actElem, "showOnLockScreen", ATTR_SHOW_ON_LOCK_SCREEN, true)
                setAndroidBooleanAttr(actElem, "showWhenLocked", ATTR_SHOW_WHEN_LOCKED, true)
            }

            if (unlockOrientation) {
                val orientAttr = actElem.searchAttributeByResourceId(ATTR_SCREEN_ORIENTATION)
                val isLandscapeLocked = orientAttr != null &&
                    (orientAttr.valueType == ValueType.DEC || orientAttr.valueType == ValueType.HEX) &&
                    orientAttr.data in LANDSCAPE_ORIENTATION_VALUES

                // Preserve explicit landscape orientations (landscape/sensorLandscape/reverseLandscape/userLandscape)
                // so landscape-only apps (PS Remote Play, PXPlay, games without portrait layouts) never crash on portrait phones.
                if (!isLandscapeLocked) {
                    actElem.removeAttributesWithId(ATTR_SCREEN_ORIENTATION)
                }

                if (isUniversalProjection && !isLandscapeLocked) {
                    val existingCfg = actElem.searchAttributeByResourceId(ATTR_CONFIG_CHANGES)
                    val safeSizeOrientationMask = 0x0D80
                    when {
                        existingCfg == null -> {
                            val cfgAttr = actElem.getOrCreateAndroidAttribute("configChanges", ATTR_CONFIG_CHANGES)
                            cfgAttr.valueType = ValueType.HEX
                            cfgAttr.data = safeSizeOrientationMask
                        }
                        existingCfg.valueType == ValueType.HEX || existingCfg.valueType == ValueType.DEC -> {
                            existingCfg.data = existingCfg.data or safeSizeOrientationMask
                        }
                    }
                }
            }
        }
    }

    private fun injectRuntimeStubDex(
        apkModule: ApkModule,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            val bootstrapDexBytes = readBootstrapDexFromAssets()
            val runtimePayloads = readRuntimePayloadChunksFromAssets()

            if (bootstrapDexBytes == null || runtimePayloads.isEmpty()) {
                onLog("[Patcher] ! Không tìm thấy payload Universal AA Runtime trong assets, giữ nguyên DEX hiện tại.")
                return false
            }

            val existingDexList = apkModule.listDexFiles()
            val nextDexIndex = if (existingDexList.isEmpty()) {
                1
            } else {
                val maxNum = existingDexList.maxOf { dex -> dex.dexNumber.let { if (it == 0) 1 else it } }
                maxNum + 1
            }
            val nextDexName = if (nextDexIndex == 1) "classes.dex" else "classes$nextDexIndex.dex"

            apkModule.add(ByteInputSource(bootstrapDexBytes, nextDexName))
            onLog("[Patcher] + Đã tiêm Bootstrap Service DEX cô lập thành '$nextDexName' (${bootstrapDexBytes.size} bytes — không xung đột AndroidX/Kotlin/Guava).")

            for ((i, payloadBytes) in runtimePayloads.withIndex()) {
                val assetName = if (i == 0) "assets/aa_car_res.dat" else "assets/aa_car_res_${i + 1}.dat"
                apkModule.removeInputSource(assetName)
                apkModule.add(ByteInputSource(payloadBytes, assetName))
            }
            onLog("[Patcher] + Đã đóng gói ${runtimePayloads.size} phân đoạn Isolated Car Runtime vào assets/aa_car_res.dat.")

            if (apkModule.getInputSource("car-app-api.level") == null) {
                apkModule.add(
                    ByteInputSource(
                        "6\n".toByteArray(Charsets.UTF_8),
                        "car-app-api.level"
                    )
                )
            }
            true
        } catch (e: Throwable) {
            onLog("[Patcher] Cảnh báo khi tiêm DEX: ${e.message}")
            false
        }
    }

    private fun readAssetBytes(name: String): ByteArray? {
        assetReader?.let { return it(name) }
        return try {
            context?.assets?.open(name)?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBootstrapDexFromAssets(): ByteArray? {
        return readAssetBytes("aa_bootstrap_stub.dex")
    }

    private fun readRuntimePayloadChunksFromAssets(): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        val first = readAssetBytes("aa_car_res.dat") ?: return emptyList()
        list.add(first)
        var idx = 2
        while (true) {
            val extra = readAssetBytes("aa_car_res_$idx.dat") ?: break
            list.add(extra)
            idx++
        }
        return list
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

    private fun setAndroidBooleanAttr(
        element: ResXmlElement,
        name: String,
        resourceId: Int,
        value: Boolean
    ) {
        val attr: ResXmlAttribute = element.getOrCreateAndroidAttribute(name, resourceId)
        attr.setValueAsBoolean(value)
    }

    companion object {
        private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")
        private val NATIVE_SO_ALIGNMENT_PATTERN: Pattern = Pattern.compile("^lib/.+\\.so$")
        private val LANDSCAPE_ORIENTATION_VALUES = setOf(0, 6, 8, 11)
        private val NATIVE_PROTECTOR_SO_SUFFIXES = listOf(
            "/libDexHelper.so",
            "/libDexHelper-x86.so",
            "/libshield.so",
            "/libjiagu.so",
            "/libjiagu_a64.so",
            "/libsecneo.so",
            "/libnesec.so"
        )
        private val KNOWN_NATIVE_SIGNATURE_CHECK_PACKAGES = setOf(
            "com.playstation.remoteplay",
            "psplay.grill2010.com",
            "com.grill.psplay"
        )

        private const val ATTR_LABEL = 0x01010001
        private const val ATTR_ICON = 0x01010002
        private const val ATTR_NAME = 0x01010003
        private const val ATTR_MANAGE_SPACE_ACTIVITY = 0x01010004
        private const val ATTR_PERMISSION = 0x01010006
        private const val ATTR_READ_PERMISSION = 0x01010007
        private const val ATTR_WRITE_PERMISSION = 0x01010008
        private const val ATTR_PERMISSION_GROUP = 0x0101000a
        private const val ATTR_EXPORTED = 0x01010010
        private const val ATTR_PROCESS = 0x01010011
        private const val ATTR_TASK_AFFINITY = 0x01010012
        private const val ATTR_AUTHORITIES = 0x01010018
        private const val ATTR_SCREEN_ORIENTATION = 0x0101001e
        private const val ATTR_CONFIG_CHANGES = 0x0101001f
        private const val ATTR_VALUE = 0x01010024
        private const val ATTR_RESOURCE = 0x01010025
        private const val ATTR_TARGET_ACTIVITY = 0x01010202
        private const val ATTR_BACKUP_AGENT = 0x0101027f
        private const val ATTR_REQUIRED = 0x0101028e
        private const val ATTR_PARENT_ACTIVITY_NAME = 0x010103a7
        private const val ATTR_SHOW_ON_LOCK_SCREEN = 0x010103c9
        private const val ATTR_IS_GAME = 0x010103f4
        private const val ATTR_ALLOW_EMBEDDED = 0x010103f5
        private const val ATTR_EXTRACT_NATIVE_LIBS = 0x010104ea
        private const val ATTR_RESIZEABLE_ACTIVITY = 0x010104f6
        private const val ATTR_IS_FEATURE_SPLIT = 0x0101053b
        private const val ATTR_APP_CATEGORY = 0x01010545
        private const val ATTR_ISOLATED_SPLITS = 0x0101054b
        private const val ATTR_SHOW_WHEN_LOCKED = 0x01010569
        private const val ATTR_APP_COMPONENT_FACTORY = 0x0101057a
        private const val ATTR_IS_SPLIT_REQUIRED = 0x01010591
        private const val ATTR_REQUIRED_SPLIT_TYPES = 0x0101064e
        private const val ATTR_SPLIT_TYPES = 0x0101064f
        private const val CAR_APP_META_NAME = "com.google.android.gms.car.application"
    }
}

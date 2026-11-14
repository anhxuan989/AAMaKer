package com.aamaker.core.patcher.engine

import android.content.Context
import com.aamaker.core.patcher.model.PatchConfig
import com.aamaker.core.patcher.model.PatchStrategy
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import java.io.File

class ManifestAndArscPatcher(private val context: Context) {

    fun patchApkBinary(
        inputApk: File,
        outputUnsignedApk: File,
        config: PatchConfig,
        onLog: (String) -> Unit
    ): Boolean {
        onLog("[Patcher] Đang nạp cấu trúc APK nhị phân bằng ARSCLib...")
        val apkModule = ApkModule.loadApkFile(inputApk)
        apkModule.setLoadDefaultFramework(false)
        val manifest: ResXmlDocument = apkModule.androidManifest
            ?: throw IllegalStateException("APK không chứa AndroidManifest.xml hợp lệ!")

        val rootManifest = manifest.documentElement
            ?: throw IllegalStateException("AndroidManifest.xml thiếu thẻ gốc <manifest>!")

        stripSplitApkRequirements(rootManifest, onLog)

        val originalPkg = apkModule.packageName ?: config.originalPackageName
        val targetPkg = config.customPackageName?.takeIf { it.isNotBlank() } ?: originalPkg

        if (targetPkg != originalPkg) {
            onLog("[Patcher] Đang đổi tên gói: $originalPkg -> $targetPkg (cập nhật Manifest & Authorities)...")
            renamePackageAndAuthorities(apkModule, manifest, originalPkg, targetPkg)
        }

        config.customAppName?.takeIf { it.isNotBlank() }?.let { newName ->
            onLog("[Patcher] Đang cập nhật tên hiển thị ứng dụng thành: $newName")
            val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
            val labelAttr = appElement?.searchAttributeByResourceId(ATTR_LABEL)
            if (labelAttr != null) {
                labelAttr.setValueAsString(newName)
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
                    "android.permission.WAKE_LOCK"
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
                    enableEmbeddedActivitiesForCarDisplay(appElem, config)
                }
                onLog("[Patcher] Chế độ PARKED_APP_MODE: Kích hoạt đầy đủ CAR_LAUNCHER & appCategory=\"game\" chuẩn Android Auto Parked App (không tiêm DEX).")
            }
            else -> {
                rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()?.let { appElem ->
                    enableEmbeddedActivitiesForCarDisplay(appElem, config)
                }
                onLog("[Patcher] Chế độ SPOOF_AND_ACTIVATE: Giữ nguyên CarAppService/MediaBrowserService gốc của APK.")
            }
        }

        apkModule.refreshTable()
        apkModule.refreshManifest()
        apkModule.setApkSignatureBlock(null)
        onLog("[Patcher] Đang đóng gói lại cấu trúc APK nhị phân...")
        apkModule.writeApk(outputUnsignedApk)
        apkModule.close()
        return dexInjected
    }

    private fun stripSplitApkRequirements(
        rootManifest: ResXmlElement,
        onLog: (String) -> Unit
    ) {
        val manifestSplitAttrIds = setOf(
            ATTR_IS_SPLIT_REQUIRED,
            ATTR_REQUIRED_SPLIT_TYPES,
            ATTR_SPLIT_TYPES
        )
        val manifestSplitAttrNames = setOf(
            "isSplitRequired",
            "requiredSplitTypes",
            "splitTypes",
            "isolatedSplits"
        )

        var removedCount = 0
        rootManifest.attributes.asSequence().toList().forEach { attr ->
            if (attr.nameId in manifestSplitAttrIds || attr.name in manifestSplitAttrNames) {
                rootManifest.removeAttribute(attr)
                removedCount++
            }
        }

        val appElement = rootManifest.getElements { it.name == "application" }.asSequence().firstOrNull()
        if (appElement != null) {
            appElement.attributes.asSequence().toList().forEach { attr ->
                if (attr.nameId in manifestSplitAttrIds || attr.name in manifestSplitAttrNames) {
                    appElement.removeAttribute(attr)
                    removedCount++
                }
            }

            setAndroidBooleanAttr(appElement, "extractNativeLibs", ATTR_EXTRACT_NATIVE_LIBS, true)

            val splitMetaNames = setOf(
                "com.android.vending.splits",
                "com.android.vending.splits.required",
                "com.android.stamp.source",
                "com.android.stamp.type",
                "com.android.dynamic.apk.fused.modules"
            )
            appElement.getElements { it.name == "meta-data" }.asSequence().toList().forEach { meta ->
                val metaName = meta.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                if (metaName != null && (metaName in splitMetaNames || metaName.startsWith("com.android.vending.splits"))) {
                    meta.removeSelf()
                    removedCount++
                }
            }
        }

        if (removedCount > 0) {
            onLog("[Patcher] + Đã gỡ bỏ $removedCount thuộc tính/meta-data bắt buộc Split APK (chống lỗi INSTALL_FAILED_MISSING_SPLIT).")
        }
    }

    private fun renamePackageAndAuthorities(
        apkModule: ApkModule,
        manifest: ResXmlDocument,
        oldPkg: String,
        newPkg: String
    ) {
        apkModule.packageName = newPkg
        val root = manifest.documentElement ?: return
        val appElement = root.getElements { it.name == "application" }.asSequence().firstOrNull() ?: return

        val componentTags = COMPONENT_TAGS
        appElement.getElements { it.name in componentTags }.forEach { comp ->
            val nameAttr = comp.searchAttributeByResourceId(ATTR_NAME)
            val currentName = nameAttr?.valueAsString
            if (!currentName.isNullOrEmpty() && currentName.startsWith(".")) {
                nameAttr.setValueAsString(oldPkg + currentName)
            }

            if (comp.name == "activity-alias") {
                val targetActAttr = comp.searchAttributeByResourceId(ATTR_TARGET_ACTIVITY)
                    ?: comp.searchAttributeByName("targetActivity")
                val currentTarget = targetActAttr?.valueAsString
                if (!currentTarget.isNullOrEmpty() && currentTarget.startsWith(".")) {
                    targetActAttr.setValueAsString(oldPkg + currentTarget)
                }
            }

            if (comp.name == "provider") {
                val authAttr = comp.searchAttributeByResourceId(ATTR_AUTHORITIES)
                val currentAuth = authAttr?.valueAsString
                if (!currentAuth.isNullOrEmpty()) {
                    val updatedAuth = currentAuth.split(";")
                        .map { part ->
                            val trimmed = part.trim()
                            when {
                                trimmed.isEmpty() -> trimmed
                                trimmed.contains(oldPkg) -> trimmed.replace(oldPkg, newPkg)
                                else -> "$newPkg.$trimmed"
                            }
                        }
                        .joinToString(";")
                    authAttr.setValueAsString(updatedAuth)
                }
            }
        }

        val permTags = setOf("permission", "uses-permission", "uses-permission-sdk-23")
        root.getElements { it.name in permTags }.forEach { permElem ->
            val nameAttr = permElem.searchAttributeByResourceId(ATTR_NAME)
            val permName = nameAttr?.valueAsString
            if (!permName.isNullOrEmpty() && permName.startsWith("$oldPkg.")) {
                nameAttr.setValueAsString(permName.replaceFirst(oldPkg, newPkg))
            }
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
        apkModule.add(com.reandroid.archive.ByteInputSource(descXmlDoc.bytes, xmlPath))

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

        ensureCarAppHelperComponents(appElement)

        enableEmbeddedActivitiesForCarDisplay(appElement, config)

        val rootShellMetaName = "com.aamaker.USE_ROOT_SHELL"
        appElement.getElements { it.name == "meta-data" }
            .asSequence()
            .filter { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == rootShellMetaName }
            .toList()
            .forEach { it.removeSelf() }

        val rootMetaElem = appElement.createChildElement("meta-data")
        setAndroidStringAttr(rootMetaElem, "name", ATTR_NAME, rootShellMetaName)
        setAndroidBooleanAttr(rootMetaElem, "value", ATTR_VALUE, config.useRootShellForTouch)
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
        config: PatchConfig? = null
    ) {
        val unlockOrientation = config?.unlockScreenOrientation ?: true
        setAndroidBooleanAttr(appElement, "resizeableActivity", ATTR_RESIZEABLE_ACTIVITY, true)
        appElement.getElements { it.name == "activity" || it.name == "activity-alias" }.forEach { actElem ->
            setAndroidBooleanAttr(actElem, "resizeableActivity", ATTR_RESIZEABLE_ACTIVITY, true)
            setAndroidBooleanAttr(actElem, "allowEmbedded", ATTR_ALLOW_EMBEDDED, true)

            setAndroidBooleanAttr(actElem, "showOnLockScreen", ATTR_SHOW_ON_LOCK_SCREEN, true)
            setAndroidBooleanAttr(actElem, "showWhenLocked", ATTR_SHOW_WHEN_LOCKED, true)
            setAndroidBooleanAttr(actElem, "showForAllUsers", ATTR_SHOW_FOR_ALL_USERS, true)
            setAndroidBooleanAttr(actElem, "inheritShowWhenLocked", ATTR_INHERIT_SHOW_WHEN_LOCKED, true)

            if (unlockOrientation && actElem.name == "activity") {
                actElem.searchAttributeByResourceId(ATTR_SCREEN_ORIENTATION)?.let { orientAttr ->

                    orientAttr.valueType = ValueType.DEC
                    orientAttr.data = -1
                }
                val cfgAttr = actElem.getOrCreateAndroidAttribute("configChanges", ATTR_CONFIG_CHANGES)

                val requiredMask = 0x1F80
                if (cfgAttr.valueType == ValueType.HEX || cfgAttr.valueType == ValueType.DEC) {
                    cfgAttr.data = cfgAttr.data or requiredMask
                } else {
                    cfgAttr.valueType = ValueType.HEX
                    cfgAttr.data = requiredMask
                }
            }
        }
    }

    private fun ensureCarAppHelperComponents(appElement: ResXmlElement) {
        val permActivityName = "androidx.car.app.CarAppPermissionActivity"
        val hasPermActivity = appElement.getElements { it.name == "activity" }
            .asSequence()
            .any { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == permActivityName }

        if (!hasPermActivity) {
            val actElem = appElement.createChildElement("activity")
            setAndroidStringAttr(actElem, "name", ATTR_NAME, permActivityName)
            setAndroidBooleanAttr(actElem, "exported", ATTR_EXPORTED, false)
            val themeAttr = actElem.getOrCreateAndroidAttribute("theme", 0x01010000)
            themeAttr.valueType = ValueType.REFERENCE
            themeAttr.data = 0x01030010
        }

        val receiverName = "androidx.car.app.notification.CarAppNotificationBroadcastReceiver"
        val hasReceiver = appElement.getElements { it.name == "receiver" }
            .asSequence()
            .any { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString == receiverName }

        if (!hasReceiver) {
            val rcvElem = appElement.createChildElement("receiver")
            setAndroidStringAttr(rcvElem, "name", ATTR_NAME, receiverName)
            setAndroidBooleanAttr(rcvElem, "exported", ATTR_EXPORTED, false)
        }
    }

    private fun injectRuntimeStubDex(
        apkModule: ApkModule,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            var nextDexIndex = apkModule.listDexFiles().size + 1
            val stubDexList = extractAllStubDexFromAssets()
            if (stubDexList.isNotEmpty()) {
                for ((i, stubDexBytes) in stubDexList.withIndex()) {
                    val nextDexName = if (nextDexIndex == 1) "classes.dex" else "classes$nextDexIndex.dex"
                    val byteSource = com.reandroid.archive.ByteInputSource(stubDexBytes, nextDexName)
                    apkModule.add(byteSource)
                    onLog("[Patcher] + Đã tiêm Universal AA Runtime DEX thành '$nextDexName' (${stubDexBytes.size / 1024} KB).")
                    nextDexIndex++

                    val assetName = if (i == 0) "assets/aa_car_res.dat" else "assets/aa_car_res_${i + 1}.dat"
                    val gzippedBytes = java.io.ByteArrayOutputStream().use { bos ->
                        java.util.zip.GZIPOutputStream(bos).use { gzip ->
                            gzip.write(stubDexBytes)
                        }
                        bos.toByteArray()
                    }
                    apkModule.removeInputSource(assetName)
                    apkModule.add(com.reandroid.archive.ByteInputSource(gzippedBytes, assetName))
                }

                if (apkModule.getInputSource("car-app-api.level") == null) {
                    val levelSource = com.reandroid.archive.ByteInputSource(
                        "6\n".toByteArray(Charsets.UTF_8),
                        "car-app-api.level"
                    )
                    apkModule.add(levelSource)
                }
                true
            } else {
                onLog("[Patcher] ! Không tìm thấy assets/aa_stub_runtime.dex, giữ nguyên DEX hiện tại.")
                false
            }
        } catch (e: Throwable) {
            onLog("[Patcher] Cảnh báo khi tiêm DEX: ${e.message}")
            false
        }
    }

    private fun decodeRuntimePayload(rawBytes: ByteArray): ByteArray {
        if (rawBytes.size >= 2 && (rawBytes[0].toInt() and 0xFF) == 0x1F && (rawBytes[1].toInt() and 0xFF) == 0x8B) {
            return java.util.zip.GZIPInputStream(rawBytes.inputStream()).use { it.readBytes() }
        }
        return rawBytes
    }

    private fun extractAllStubDexFromAssets(): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val stream = try {
                context.assets.open("aa_car_res.dat")
            } catch (_: Throwable) {
                try {
                    context.assets.open("aa_car_runtime.bin")
                } catch (_: Throwable) {
                    context.assets.open("aa_stub_runtime.dex")
                }
            }
            stream.use { list.add(decodeRuntimePayload(it.readBytes())) }
        } catch (_: Throwable) {
            return emptyList()
        }
        var idx = 2
        while (true) {
            try {
                val extraStream = try {
                    context.assets.open("aa_car_res_$idx.dat")
                } catch (_: Throwable) {
                    try {
                        context.assets.open("aa_car_runtime_$idx.bin")
                    } catch (_: Throwable) {
                        context.assets.open("aa_stub_runtime_$idx.dex")
                    }
                }
                extraStream.use { list.add(decodeRuntimePayload(it.readBytes())) }
                idx++
            } catch (_: Throwable) {
                break
            }
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
        private const val ATTR_LABEL = 0x01010001
        private const val ATTR_ICON = 0x01010002
        private const val ATTR_NAME = 0x01010003
        private const val ATTR_EXPORTED = 0x01010010
        private const val ATTR_AUTHORITIES = 0x01010018
        private const val ATTR_SCREEN_ORIENTATION = 0x0101001e
        private const val ATTR_CONFIG_CHANGES = 0x0101001f
        private const val ATTR_VALUE = 0x01010024
        private const val ATTR_RESOURCE = 0x01010025
        private const val ATTR_TARGET_ACTIVITY = 0x01010202
        private const val ATTR_REQUIRED = 0x0101028e
        private const val ATTR_SHOW_ON_LOCK_SCREEN = 0x010103c9
        private const val ATTR_IS_GAME = 0x010103f4
        private const val ATTR_ALLOW_EMBEDDED = 0x010103f5
        private const val ATTR_SHOW_FOR_ALL_USERS = 0x0101043d
        private const val ATTR_EXTRACT_NATIVE_LIBS = 0x010104ea
        private const val ATTR_RESIZEABLE_ACTIVITY = 0x010104f6
        private const val ATTR_APP_CATEGORY = 0x01010545
        private const val ATTR_SHOW_WHEN_LOCKED = 0x01010569
        private const val ATTR_IS_SPLIT_REQUIRED = 0x01010591
        private const val ATTR_INHERIT_SHOW_WHEN_LOCKED = 0x0101060b
        private const val ATTR_REQUIRED_SPLIT_TYPES = 0x0101064e
        private const val ATTR_SPLIT_TYPES = 0x0101064f
        private const val CAR_APP_META_NAME = "com.google.android.gms.car.application"
    }
}

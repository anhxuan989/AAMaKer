package com.aamaker.core.patcher.engine

import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import com.reandroid.archive.FileInputSource
import com.reandroid.arsc.chunk.TypeBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class LoadedApkSession(
    val apkModule: ApkModule,
    private val closeables: List<Closeable>
) : Closeable {
    override fun close() {
        runCatching { apkModule.close() }
        for (c in closeables) {
            runCatching { c.close() }
        }
    }
}

class SplitApkMerger {

    fun prepareApkModule(
        inputFiles: List<File>,
        workDir: File,
        onLog: (String) -> Unit
    ): LoadedApkSession {
        require(inputFiles.isNotEmpty()) { "Danh sách file APK đầu vào trống!" }

        val firstFile = inputFiles.first()
        val ext = firstFile.extension.lowercase()

        if (inputFiles.size == 1 && ext in BUNDLE_EXTENSIONS) {
            onLog("[Merger] Phát hiện gói Bundle (${firstFile.name}), đang giải nén các thành phần...")
            val extractedDir = File(workDir, "extracted_bundle_${System.currentTimeMillis()}").apply { mkdirs() }
            val (extractedApks, extractedObbs) = extractBundleContents(firstFile, extractedDir)
            if (extractedApks.isEmpty()) {
                throw IllegalArgumentException("Không tìm thấy file .apk nào bên trong ${firstFile.name}")
            }
            if (extractedApks.size == 1 && extractedObbs.isEmpty()) {
                val singleMod = ApkModule.loadApkFile(extractedApks.first()).apply {
                    setLoadDefaultFramework(false)
                }
                normalizeSparseAndCompactTypeBlocks(singleMod)
                sanitizeMergedModule(singleMod, wasSplitBundle = false, onLog = onLog)
                return LoadedApkSession(singleMod, listOf(singleMod))
            }
            return mergeApksIntoModule(extractedApks, extractedObbs, onLog)
        }

        if (inputFiles.size == 1) {
            val singleMod = ApkModule.loadApkFile(firstFile).apply {
                setLoadDefaultFramework(false)
            }
            normalizeSparseAndCompactTypeBlocks(singleMod)
            sanitizeMergedModule(singleMod, wasSplitBundle = false, onLog = onLog)
            return LoadedApkSession(singleMod, listOf(singleMod))
        }

        onLog("[Merger] Phát hiện ${inputFiles.size} Split APKs, đang hợp nhất cấu trúc nhị phân (DEX + resources.arsc + Native Libs + Assets)...")
        return mergeApksIntoModule(inputFiles, emptyList(), onLog)
    }

    private fun extractBundleContents(
        bundleFile: File,
        targetDir: File
    ): Pair<List<File>, List<File>> {
        val apks = mutableListOf<File>()
        val obbs = mutableListOf<File>()
        var idx = 0
        ZipInputStream(BufferedInputStream(FileInputStream(bundleFile))).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val rawName = File(entry.name).name
                    if (rawName.endsWith(".apk", ignoreCase = true)) {
                        val safeName = if (rawName.equals("base.apk", ignoreCase = true) &&
                            apks.none { it.name.equals("base.apk", ignoreCase = true) }
                        ) {
                            "base.apk"
                        } else {
                            "split_${idx++}_${rawName.replace(UNSAFE_FILE_CHARS, "_")}"
                        }
                        val outFile = File(targetDir, safeName)
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        apks.add(outFile)
                    } else if (rawName.endsWith(".obb", ignoreCase = true)) {
                        val outFile = File(targetDir, rawName.replace(UNSAFE_FILE_CHARS, "_"))
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                        obbs.add(outFile)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        val sortedApks = apks.sortedBy {
            when {
                it.name.equals("base.apk", ignoreCase = true) -> 0
                it.name.contains("base", ignoreCase = true) -> 1
                else -> 2
            }
        }
        return sortedApks to obbs
    }

    private fun mergeApksIntoModule(
        apkFiles: List<File>,
        obbFiles: List<File>,
        onLog: (String) -> Unit
    ): LoadedApkSession {
        val apkBundle = ApkBundle()
        val splitModules = mutableListOf<ApkModule>()
        var hasAssignedBase = false

        try {
            apkFiles.forEachIndexed { index, file ->
                val tempName = "mod_${index}_${file.nameWithoutExtension.replace(UNSAFE_FILE_CHARS, "_")}"
                val mod = ApkModule.loadApkFile(file, tempName).apply {
                    setLoadDefaultFramework(false)
                }
                normalizeSparseAndCompactTypeBlocks(mod)
                val isBaseCandidate = !hasAssignedBase && (
                    file.name.equals("base.apk", ignoreCase = true) ||
                        (mod.hasAndroidManifest() && mod.split.isNullOrEmpty())
                    )
                if (isBaseCandidate) {
                    mod.moduleName = "base"
                    hasAssignedBase = true
                    onLog("[Merger] + Nạp APK nền (base): ${file.name}")
                } else {
                    mod.moduleName = "split_${index}_${file.nameWithoutExtension.replace(UNSAFE_FILE_CHARS, "_")}"
                    splitModules.add(mod)
                    onLog("[Merger] + Nạp Split APK: ${file.name}")
                }
                apkBundle.addModule(mod)
            }

            val mergedModule = apkBundle.mergeModules(false)
            mergedModule.setLoadDefaultFramework(false)
            normalizeSparseAndCompactTypeBlocks(mergedModule)

            mergeFeatureSplitManifestElements(mergedModule, splitModules, onLog)

            for (obb in obbFiles) {
                val assetPath = "assets/obb/${obb.name}"
                if (!mergedModule.containsFile(assetPath)) {
                    onLog("[Merger] + Gộp dữ liệu OBB: ${obb.name}")
                    val source = FileInputSource(obb, assetPath)
                    mergedModule.add(source)
                    mergedModule.uncompressedFiles.addPath(assetPath)
                }
            }

            sanitizeMergedModule(mergedModule, wasSplitBundle = true, onLog = onLog)
            onLog("[Merger] Đã hợp nhất hoàn chỉnh ${apkFiles.size} APK thành 1 module duy nhất (${mergedModule.listDexFiles().size} DEX).")
            return LoadedApkSession(mergedModule, listOf(apkBundle))
        } catch (t: Throwable) {
            runCatching { apkBundle.close() }
            throw t
        }
    }

    private fun mergeFeatureSplitManifestElements(
        mergedModule: ApkModule,
        splitModules: List<ApkModule>,
        onLog: (String) -> Unit
    ) {
        val baseManifestRoot = mergedModule.androidManifest?.documentElement ?: return
        val baseAppElem = baseManifestRoot.getElements { it.name == "application" }.asSequence().firstOrNull() ?: return
        val basePkg = mergedModule.packageName

        val existingRootPerms = baseManifestRoot.getElements { it.name in ROOT_MERGEABLE_TAGS }
            .asSequence()
            .mapNotNull { elem ->
                val name = elem.searchAttributeByResourceId(ATTR_NAME)?.valueAsString ?: return@mapNotNull null
                "${elem.name}:$name"
            }
            .toMutableSet()

        val existingAppComponents = baseAppElem.getElements { it.name in APP_MERGEABLE_TAGS }
            .asSequence()
            .mapNotNull { elem ->
                val name = elem.searchAttributeByResourceId(ATTR_NAME)?.valueAsString ?: return@mapNotNull null
                val normName = expandClassNameValue(name, basePkg)
                "${elem.name}:$normName"
            }
            .toMutableSet()

        var mergedCompCount = 0

        for (splitMod in splitModules) {
            if (!splitMod.hasAndroidManifest()) continue
            val splitRoot = runCatching { splitMod.androidManifest?.documentElement }.getOrNull() ?: continue
            val splitPkg = runCatching { splitMod.packageName }.getOrNull() ?: basePkg

            splitRoot.getElements { it.name in ROOT_MERGEABLE_TAGS }.forEach { elem ->
                val name = elem.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                if (!name.isNullOrEmpty() && existingRootPerms.add("${elem.name}:$name")) {
                    cloneXmlElementInto(elem, baseManifestRoot, splitPkg)
                }
            }

            splitRoot.getElements { it.name == "queries" }.forEach { splitQueries ->
                val baseQueries = baseManifestRoot.getElements { it.name == "queries" }.asSequence().firstOrNull()
                    ?: baseManifestRoot.createChildElement("queries")
                val existingQueryPkgs = baseQueries.getElements { it.name == "package" }.asSequence()
                    .mapNotNull { it.searchAttributeByResourceId(ATTR_NAME)?.valueAsString }
                    .toMutableSet()
                splitQueries.getElements { true }.forEach { qChild ->
                    if (qChild.name == "package") {
                        val qPkg = qChild.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                        if (!qPkg.isNullOrEmpty() && existingQueryPkgs.add(qPkg)) {
                            cloneXmlElementInto(qChild, baseQueries, null)
                        }
                    } else {
                        cloneXmlElementInto(qChild, baseQueries, null)
                    }
                }
            }

            val splitApp = splitRoot.getElements { it.name == "application" }.asSequence().firstOrNull() ?: continue
            splitApp.getElements { it.name in APP_MERGEABLE_TAGS }.forEach { comp ->
                val rawName = comp.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                if (!rawName.isNullOrEmpty()) {
                    val expandedName = if (comp.name != "meta-data") {
                        expandClassNameValue(rawName, splitPkg)
                    } else {
                        rawName
                    }
                    if (!shouldSkipSplitComponent(comp.name, expandedName) &&
                        existingAppComponents.add("${comp.name}:$expandedName")
                    ) {
                        cloneXmlElementInto(comp, baseAppElem, splitPkg)
                        mergedCompCount++
                    }
                }
            }
        }

        if (mergedCompCount > 0) {
            onLog("[Merger] + Đã hợp nhất $mergedCompCount thành phần Feature Split vào AndroidManifest.xml.")
        }
    }

    private fun expandClassNameValue(rawName: String, pkg: String?): String {
        if (pkg.isNullOrEmpty() || rawName.isEmpty()) return rawName
        return when {
            rawName.startsWith(".") -> pkg + rawName
            !rawName.contains(".") -> "$pkg.$rawName"
            else -> rawName
        }
    }

    private fun shouldSkipSplitComponent(tagName: String, androidName: String): Boolean {
        if (androidName.startsWith("com.google.android.play.core.missingsplits.")) {
            return true
        }
        if (tagName == "meta-data") {
            return androidName.startsWith("com.android.vending.") ||
                androidName.startsWith("com.android.stamp.") ||
                androidName.startsWith("com.android.dynamic.apk.") ||
                androidName.startsWith("shadow.bundletool.com.android.dynamic.apk.")
        }
        return false
    }

    private fun cloneXmlElementInto(src: ResXmlElement, dstParent: ResXmlElement, classOwnerPkg: String?) {
        val dst = dstParent.createChildElement(src.name)
        val srcPrefix = src.prefix
        val srcUri = src.uri
        if (!srcPrefix.isNullOrEmpty() && !srcUri.isNullOrEmpty()) {
            dst.setNamespace(srcUri, srcPrefix)
        }

        val attrIter = src.attributes
        while (attrIter.hasNext()) {
            val srcAttr: ResXmlAttribute = attrIter.next()
            val attrName = srcAttr.name ?: ""
            val nameId = srcAttr.nameId
            if (nameId in SPLIT_ATTR_IDS || attrName in SPLIT_ATTR_NAMES) {
                continue
            }
            val uri = srcAttr.uri
            val prefix = srcAttr.prefix

            val dstAttr = when {
                nameId != 0 && (uri == ANDROID_NS_URI || prefix == "android" || uri.isNullOrEmpty()) ->
                    dst.getOrCreateAndroidAttribute(attrName, nameId)
                !uri.isNullOrEmpty() && !prefix.isNullOrEmpty() ->
                    dst.getOrCreateAttribute(uri, prefix, attrName, nameId)
                else ->
                    dst.getOrCreateAttribute(attrName, nameId)
            }

            if (srcAttr.valueType == ValueType.STRING) {
                val rawStr = srcAttr.valueAsString ?: ""
                val resolvedStr = if (!classOwnerPkg.isNullOrEmpty() &&
                    src.name in CLASS_BEARING_TAGS &&
                    (nameId == ATTR_NAME || nameId == ATTR_TARGET_ACTIVITY || nameId == ATTR_PARENT_ACTIVITY_NAME)
                ) {
                    expandClassNameValue(rawStr, classOwnerPkg)
                } else {
                    rawStr
                }
                dstAttr.setValueAsString(resolvedStr)
            } else {
                dstAttr.valueType = srcAttr.valueType
                dstAttr.data = srcAttr.data
            }
        }

        src.getElements { true }.forEach { child ->
            cloneXmlElementInto(child, dst, null)
        }
    }

    private fun sanitizeMergedModule(
        apkModule: ApkModule,
        wasSplitBundle: Boolean,
        onLog: (String) -> Unit
    ) {
        if (wasSplitBundle && apkModule.hasAndroidManifest()) {
            cleanSplitArtifactsSafely(apkModule)
        }

        val zipMap = apkModule.zipEntryMap
        val toRemove = mutableListOf<String>()
        for (source in apkModule.listInputSources()) {
            val alias = source.alias
            if (isSignatureFile(alias) || alias == "stamp-cert-sha256") {
                toRemove.add(alias)
            }
        }
        for (path in toRemove) {
            zipMap.remove(path)
        }

        pruneIncompleteStubAbiDirs(apkModule, onLog)

        val uncompressed = apkModule.uncompressedFiles
        uncompressed.addPath("resources.arsc")
        for (source in apkModule.listInputSources()) {
            val alias = source.alias
            if (alias.startsWith("lib/") && alias.endsWith(".so")) {
                uncompressed.addPath(alias)
            }
        }
    }

    private fun pruneIncompleteStubAbiDirs(
        apkModule: ApkModule,
        onLog: (String) -> Unit
    ) {
        val abiLibs = mutableMapOf<String, MutableList<String>>()
        for (source in apkModule.listInputSources()) {
            val alias = source.alias
            if (alias.startsWith("lib/") && alias.endsWith(".so")) {
                val parts = alias.split("/")
                if (parts.size == 3) {
                    val abi = parts[1]
                    val soName = parts[2]
                    abiLibs.getOrPut(abi) { mutableListOf() }.add(soName)
                }
            }
        }
        if (abiLibs.size <= 1) return

        val maxCount = abiLibs.values.maxOf { it.size }
        if (maxCount < 4) return

        val primaryAbis = abiLibs.filterValues { it.size == maxCount }
        val coreLibSet = primaryAbis.values.first().toSet()

        val zipMap = apkModule.zipEntryMap
        for ((abi, libs) in abiLibs) {
            if (libs.size <= 2 && libs.size * 3 < maxCount) {
                val missingCoreCount = coreLibSet.count { it !in libs }
                if (missingCoreCount >= maxCount - libs.size) {
                    zipMap.removeDir("lib/$abi")
                    onLog("[Merger] + Đã dọn thư mục ABI không đầy đủ 'lib/$abi' (${libs.size}/$maxCount thư viện .so) để tránh lỗi UnsatisfiedLinkError.")
                }
            }
        }
    }

    companion object {
        private const val ATTR_NAME = 0x01010003
        private const val ATTR_VALUE = 0x01010024
        private const val ATTR_RESOURCE = 0x01010025
        private const val ATTR_TARGET_ACTIVITY = 0x01010202
        private const val ATTR_PARENT_ACTIVITY_NAME = 0x010103a7
        private const val ATTR_IS_FEATURE_SPLIT = 0x0101053b
        private const val ATTR_SPLIT_NAME = 0x01010549
        private const val ATTR_ISOLATED_SPLITS = 0x0101054b
        private const val ATTR_IS_SPLIT_REQUIRED = 0x01010591
        private const val ATTR_REQUIRED_SPLIT_TYPES = 0x0101064e
        private const val ATTR_SPLIT_TYPES = 0x0101064f

        private const val ANDROID_NS_URI = "http://schemas.android.com/apk/res/android"
        private val UNSAFE_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")
        private val BUNDLE_EXTENSIONS = setOf("apks", "xapk", "apkm", "zip")
        private val CLASS_BEARING_TAGS = setOf(
            "application",
            "activity",
            "activity-alias",
            "service",
            "receiver",
            "provider"
        )
        private val SPLIT_ATTR_IDS = setOf(
            ATTR_IS_FEATURE_SPLIT,
            ATTR_SPLIT_NAME,
            ATTR_ISOLATED_SPLITS,
            ATTR_IS_SPLIT_REQUIRED,
            ATTR_REQUIRED_SPLIT_TYPES,
            ATTR_SPLIT_TYPES
        )
        private val SPLIT_ATTR_NAMES = setOf(
            "split",
            "configForSplit",
            "splitName",
            "isFeatureSplit",
            "isolatedSplits",
            "isSplitRequired",
            "requiredSplitTypes",
            "splitTypes"
        )
        private val SPLIT_META_PREFIXES = listOf(
            "com.android.vending.splits",
            "com.android.vending.derived.apk.id",
            "com.android.stamp.",
            "com.android.dynamic.apk.",
            "shadow.bundletool.com.android.dynamic.apk."
        )
        private val ROOT_MERGEABLE_TAGS = setOf(
            "uses-permission",
            "uses-permission-sdk-23",
            "permission",
            "uses-feature"
        )
        private val APP_MERGEABLE_TAGS = setOf(
            "activity",
            "activity-alias",
            "service",
            "receiver",
            "provider",
            "meta-data"
        )

        /**
         * Safely strips Split APK / App Bundle manifest constraints without triggering ARSCLib's
         * destructive ApkSplitInfoCleaner bug (which deletes shared @style/@string/@mipmap/@bool
         * resources referenced by PlayCoreMissingSplitsActivity or AssetPackExtractionService)
         * and also cleans recursive android:splitName (0x01010549) & <uses-split> so Android's
         * LoadedApk.getSplitClassLoader() never crashes with NameNotFoundException on launch.
         */
        fun cleanSplitArtifactsSafely(apkModule: ApkModule): Int {
            if (!apkModule.hasAndroidManifest()) return 0
            val root = runCatching { apkModule.androidManifest?.documentElement }.getOrNull() ?: return 0
            var removedCount = 0

            fun stripSplitAttrsRecursive(elem: ResXmlElement) {
                elem.attributes.asSequence().toList().forEach { attr ->
                    if (attr.nameId in SPLIT_ATTR_IDS || attr.name in SPLIT_ATTR_NAMES) {
                        elem.removeAttribute(attr)
                        removedCount++
                    }
                }
                elem.getElements { true }.asSequence().toList().forEach { child ->
                    if (child.name == "uses-split") {
                        child.removeSelf()
                        removedCount++
                    } else {
                        stripSplitAttrsRecursive(child)
                    }
                }
            }

            stripSplitAttrsRecursive(root)

            val appElem = root.getElements { it.name == "application" }.asSequence().firstOrNull()
            if (appElem != null) {
                appElem.getElements { it.name == "meta-data" }.asSequence().toList().forEach { meta ->
                    val metaName = meta.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                    if (metaName != null && SPLIT_META_PREFIXES.any { metaName == it || metaName.startsWith(it) }) {
                        if (metaName == "com.android.vending.splits" && apkModule.hasTableBlock()) {
                            val refAttr = meta.searchAttributeByResourceId(ATTR_RESOURCE)
                                ?: meta.searchAttributeByResourceId(ATTR_VALUE)
                            if (refAttr != null && refAttr.valueType == ValueType.REFERENCE && refAttr.data != 0) {
                                removeDedicatedSplitsXmlResourceOnly(apkModule, refAttr.data)
                            }
                        }
                        meta.removeSelf()
                        removedCount++
                    }
                }

                appElem.getElements { it.name == "activity" }.asSequence().toList().forEach { act ->
                    val actName = act.searchAttributeByResourceId(ATTR_NAME)?.valueAsString
                    if (actName != null && actName.startsWith("com.google.android.play.core.missingsplits.")) {
                        act.removeSelf()
                        removedCount++
                    }
                }
            }

            return removedCount
        }

        private fun removeDedicatedSplitsXmlResourceOnly(apkModule: ApkModule, resourceId: Int) {
            runCatching {
                val table = apkModule.tableBlock ?: return
                val zipMap = apkModule.zipEntryMap
                val entries = table.resolveReference(resourceId).orEmpty()
                for (entry in entries) {
                    if (entry == null || entry.isNull) continue
                    val typeName = entry.typeName
                    val entryName = entry.name
                    // Strictly remove ONLY @xml/splits* generated by bundletool — never touch shared styles/strings/drawables!
                    if (typeName == "xml" && entryName != null && entryName.startsWith("splits")) {
                        val filePath = entry.resValue?.valueAsString
                        if (!filePath.isNullOrEmpty() && filePath.startsWith("res/xml/splits")) {
                            zipMap.remove(filePath)
                        }
                        entry.isNull = true
                    }
                }
            }
        }

        fun isSignatureFile(entryName: String): Boolean {
            if (!entryName.startsWith("META-INF/")) return false
            val upper = entryName.uppercase()
            return upper.endsWith(".SF") ||
                upper.endsWith(".RSA") ||
                upper.endsWith(".DSA") ||
                upper.endsWith(".EC") ||
                upper.endsWith("MANIFEST.MF") ||
                upper.startsWith("META-INF/SIG-")
        }

        fun isClassesDex(name: String): Boolean {
            if (!name.startsWith("classes") || !name.endsWith(".dex")) return false
            val middle = name.substring(7, name.length - 4)
            return middle.isEmpty() || middle.all { it.isDigit() }
        }

        /**
         * ARSCLib 1.3.5 has a critical bug in SparseOffsetsArray / EntryArray when mutating or
         * merging FLAG_SPARSE (0x01) or FLAG_OFFSET16 (0x02) TypeBlocks produced by modern AAPT2 /
         * Play Store App Bundles: EntryArray.getOrCreate() calls ensureSize(highestEntryCount) on
         * the underlying BlockArray, inserting unindexed Entry slots with sparse index 0x0000 and
         * wiping out existing sparse entries (e.g. drawable-anydpi-v21 ExoPlayer icons -> crash
         * Resources$NotFoundException at runtime).
         *
         * Converting all sparse/offset16 TypeBlocks to standard dense 32-bit TypeBlocks before
         * merging or modifying resources.arsc preserves 100% of resource IDs, names, and values.
         */
        fun normalizeSparseAndCompactTypeBlocks(apkModule: ApkModule): Int {
            if (!apkModule.hasTableBlock()) return 0
            val tableBlock = apkModule.tableBlock ?: return 0
            var convertedCount = 0

            for (packageBlock in tableBlock.listPackages()) {
                for (specTypePair in packageBlock.listSpecTypePairs()) {
                    val typeBlockArray = specTypePair.typeBlockArray
                    val blocksToConvert = mutableListOf<TypeBlock>()
                    for (typeBlock in typeBlockArray.listItems()) {
                        if (typeBlock != null && (typeBlock.isSparse || typeBlock.isOffset16)) {
                            blocksToConvert.add(typeBlock)
                        }
                    }
                    if (blocksToConvert.isEmpty()) continue

                    for (srcType in blocksToConvert) {
                        val configCopy = ResConfig().apply {
                            copyFrom(srcType.resConfig)
                        }
                        val srcEntryArray = srcType.entryArray
                        val isSrcSparse = srcEntryArray.isSparse
                        val savedEntries = LinkedHashMap<Int, Entry>()
                        var maxEntryId = -1

                        for (i in 0 until srcEntryArray.size()) {
                            val entry = srcEntryArray.get(i)
                            if (entry == null || entry.isNull) continue
                            val entryId = if (isSrcSparse) srcEntryArray.getEntryId(i) else i
                            if (entryId < 0) continue
                            savedEntries[entryId] = entry
                            if (entryId > maxEntryId) {
                                maxEntryId = entryId
                            }
                        }

                        var denseType: TypeBlock? = null
                        for (candidate in typeBlockArray.listItems()) {
                            if (candidate != null &&
                                candidate !== srcType &&
                                !candidate.isSparse &&
                                !candidate.isOffset16 &&
                                candidate.resConfig == configCopy
                            ) {
                                denseType = candidate
                                break
                            }
                        }
                        if (denseType == null) {
                            denseType = typeBlockArray.createNext(false, false).apply {
                                resConfig.copyFrom(configCopy)
                            }
                        }

                        if (maxEntryId >= 0) {
                            denseType.ensureEntriesCount(maxEntryId + 1)
                        }
                        for ((entryId, srcEntry) in savedEntries) {
                            val dstEntry = denseType.getOrCreateEntry(entryId.toShort())
                            dstEntry.merge(srcEntry)
                        }

                        typeBlockArray.remove(srcType)
                        srcType.destroy()
                        convertedCount++
                    }

                    val highestCount = typeBlockArray.highestEntryCount
                    if (highestCount > 0) {
                        specTypePair.specBlock.entryCount = highestCount
                    }
                }
            }

            if (convertedCount > 0) {
                tableBlock.refresh()
            }
            return convertedCount
        }
    }
}

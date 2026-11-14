package com.aamaker.core.patcher.engine

import com.aamaker.core.patcher.model.PatchConfig
import com.aamaker.core.patcher.model.PatchStrategy
import com.android.apksig.ApkVerifier
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class SplitMergeAndPatchPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `split merge preserves arsc uncompressed assets 16kb so alignment and package rename`() {
        val workDir = tempFolder.newFolder("work")
        val generatedAssetsDir = File("build/generated/stub_dex_assets/main")
        assertTrue(
            "Generated stub DEX assets must exist at ${generatedAssetsDir.absolutePath}",
            File(generatedAssetsDir, "aa_bootstrap_stub.dex").exists() &&
                File(generatedAssetsDir, "aa_car_res.dat").exists()
        )

        val baseApk = File(workDir, "base.apk")
        val splitResApk = File(workDir, "split_config.xxhdpi.apk")
        val splitAbiApk = File(workDir, "split_config.arm64_v8a.apk")

        createBaseSplitApk(baseApk)
        createResourceSplitApk(splitResApk)
        createAbiSplitApk(splitAbiApk)

        val merger = SplitApkMerger()
        val patcher = ManifestAndArscPatcher(
            context = null,
            assetReader = { name ->
                val f = File(generatedAssetsDir, name)
                if (f.exists()) f.readBytes() else null
            }
        )
        val signer = ApkSignerEngine(context = null, customKeyDir = workDir)

        val unsignedApk = File(workDir, "unsigned.apk")
        val signedApk = File(workDir, "signed.apk")

        val config = PatchConfig(
            sourceApkFiles = listOf(baseApk, splitResApk, splitAbiApk),
            originalPackageName = "com.stremio.one",
            appName = "Stremio",
            strategy = PatchStrategy.UNIVERSAL_INJECTION,
            customPackageName = "com.stremio.one.aamod",
            customAppName = "Stremio AA",
            removeSignatureVerification = true
        )

        val outcome = merger.prepareApkModule(config.sourceApkFiles, workDir) {}.use { session ->
            patcher.patchApkModule(session.apkModule, unsignedApk, config) {}
        }

        assertTrue("Expected bootstrap DEX injection", outcome.dexInjected)
        assertEquals(24, outcome.minSdkVersion)

        signer.signApk(
            unsignedApk = unsignedApk,
            signedOutputApk = signedApk,
            onLog = {},
            minSdkVersion = outcome.minSdkVersion
        )

        // 1. Verify V1 + V2 + V3 signatures via Android's official ApkVerifier
        val verifierResult = ApkVerifier.Builder(signedApk)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        assertTrue(
            "Signed APK failed verification: ${verifierResult.errors}",
            verifierResult.isVerified
        )

        // 2. Verify ZIP storage rules & 16KB page alignment on native .so and 4-byte alignment on resources.arsc
        ZipFile(signedApk).use { zip ->
            val arscEntry = zip.getEntry("resources.arsc")
            assertNotNull(arscEntry)
            assertEquals("resources.arsc must be STORED", ZipEntry.STORED, arscEntry.method)

            val bundleEntry = zip.getEntry("assets/index.android.bundle")
            assertNotNull(bundleEntry)
            assertEquals("Uncompressed React Native asset must stay STORED", ZipEntry.STORED, bundleEntry.method)

            val soEntry = zip.getEntry("lib/arm64-v8a/libstremio_core.so")
            assertNotNull("Primary ABI .so must exist", soEntry)
            assertEquals("Native .so must be STORED", ZipEntry.STORED, soEntry.method)

            assertEquals("Bootstrap DEX should be classes2.dex", true, zip.getEntry("classes2.dex") != null)
            assertNotNull("Isolated runtime payload must exist in assets", zip.getEntry("assets/aa_car_res.dat"))

            // Stray 1-lib x86 stub ABI should be pruned when arm64-v8a has 4 core libs
            assertEquals("Incomplete stub ABI dir should be pruned", null, zip.getEntry("lib/x86/libstub_only.so"))
        }

        val arscOffset = findZipEntryDataOffset(signedApk, "resources.arsc")
        assertEquals("resources.arsc data offset ($arscOffset) must be 4-byte aligned", 0L, arscOffset % 4L)

        val soOffset = findZipEntryDataOffset(signedApk, "lib/arm64-v8a/libstremio_core.so")
        assertEquals("Native .so data offset ($soOffset) must be 16KB (16384) aligned", 0L, soOffset % 16384L)

        // 3. Reload patched APK via ARSCLib and verify Manifest & split resources
        ApkModule.loadApkFile(signedApk).use { patchedMod ->
            patchedMod.setLoadDefaultFramework(false)
            assertEquals("com.stremio.one.aamod", patchedMod.packageName)

            val root = patchedMod.androidManifest.documentElement
            assertEquals(null, root.searchAttributeByName("isSplitRequired"))
            assertEquals(null, root.searchAttributeByName("requiredSplitTypes"))

            val appElem = root.getElements { it.name == "application" }.asSequence().first()
            // Relative .MainApplication must be expanded to com.stremio.one.MainApplication!
            assertEquals(
                "com.stremio.one.MainApplication",
                appElem.searchAttributeByResourceId(0x01010003)?.valueAsString
            )

            // Pairip / MissingSplits components must be stripped
            val allCompNames = appElem.getElements { true }.asSequence()
                .mapNotNull { it.searchAttributeByResourceId(0x01010003)?.valueAsString }
                .toSet()
            assertFalse(allCompNames.contains("com.pairip.licensecheck.LicenseActivity"))
            assertFalse(allCompNames.contains("com.google.android.play.core.missingsplits.PlayCoreMissingSplitsActivity"))
            assertTrue(allCompNames.contains("com.aamaker.stub.runtime.AAMakerCarAppService"))
            assertTrue(allCompNames.contains("com.stremio.one.MainActivity"))

            // Provider authorities & custom permission must use new package name
            val providerElem = appElem.getElements { it.name == "provider" }.asSequence().first()
            assertEquals(
                "com.stremio.one.aamod.provider",
                providerElem.searchAttributeByResourceId(0x01010018)?.valueAsString
            )

            // Activity-alias must NOT have resizeableActivity or screenOrientation/configChanges injected onto it
            val aliasElem = appElem.getElements { it.name == "activity-alias" }.asSequence().first()
            assertEquals(null, aliasElem.searchAttributeByResourceId(0x010104f6))
            assertEquals(
                "com.stremio.one.MainActivity",
                aliasElem.searchAttributeByResourceId(0x01010202)?.valueAsString
            )

            // Split resource from xxhdpi split AND sparse anydpi resources from both base and split must survive intact!
            assertTrue("Split drawable file must be present", patchedMod.containsFile("res/drawable-xxhdpi-v4/ic_logo.png"))

            val pkg = patchedMod.tableBlock.listPackages().first()
            val drawablePair = pkg.getSpecTypePair("drawable")
            assertNotNull("Drawable SpecTypePair must exist", drawablePair)

            // Every TypeBlock must be normalized to dense (non-sparse, non-offset16)
            for (tb in drawablePair.typeBlockArray.listItems()) {
                assertFalse("TypeBlock ${tb.qualifiers} must not be sparse after normalization", tb.isSparse)
                assertFalse("TypeBlock ${tb.qualifiers} must not be offset16 after normalization", tb.isOffset16)
            }

            // Base sparse anydpi entry at 0x7f0200d8 (exo_icon_vr) and split sparse anydpi entry at 0x7f020125 (imdb) must both resolve cleanly!
            val anydpiType = drawablePair.typeBlockArray.listItems().firstOrNull { it.qualifiers.contains("anydpi") }
            assertNotNull("Merged -anydpi TypeBlock must exist", anydpiType)

            val exoVrEntry = anydpiType!!.getEntry(0x00d8.toShort())
            assertNotNull("0x00d8 exo_icon_vr must exist in -anydpi", exoVrEntry)
            assertFalse("0x00d8 exo_icon_vr must not be null", exoVrEntry.isNull)
            assertEquals("exo_icon_vr", exoVrEntry.name)
            assertEquals("res/drawable-anydpi-v21/exo_icon_vr.xml", exoVrEntry.resValue?.valueAsString)

            val imdbEntry = anydpiType.getEntry(0x0125.toShort())
            assertNotNull("0x0125 imdb must exist in -anydpi", imdbEntry)
            assertFalse("0x0125 imdb must not be null", imdbEntry.isNull)
            assertEquals("imdb", imdbEntry.name)
            assertEquals("res/drawable-anydpi-v24/imdb.xml", imdbEntry.resValue?.valueAsString)

            // Shared @string/app_name referenced on PlayCoreMissingSplitsActivity must NOT be destroyed!
            val stringPair = pkg.getSpecTypePair("string")
            assertNotNull(stringPair)
            val appNameEntry = stringPair.typeBlockArray.listItems()
                .flatMap { it.listEntries(true) }
                .firstOrNull { it.name == "app_name" }
            assertNotNull("@string/app_name must survive split cleaning", appNameEntry)
            assertFalse("@string/app_name must not be marked null", appNameEntry!!.isNull)
            assertEquals("Stremio", appNameEntry.resValue?.valueAsString)
        }
    }

    @Test
    fun `ps remote style feature split preserves landscape orientation rewrites resource provider authority strips splitName and respects pairip vm`() {
        val workDir = tempFolder.newFolder("work_psremote")
        val generatedAssetsDir = File("build/generated/stub_dex_assets/main")

        val baseApk = File(workDir, "base.apk")
        val featureSplitApk = File(workDir, "split_feature_stream.apk")

        createPsRemoteStyleBaseApk(baseApk)
        createPsRemoteFeatureSplitApk(featureSplitApk)

        val merger = SplitApkMerger()
        val patcher = ManifestAndArscPatcher(
            context = null,
            assetReader = { name ->
                val f = File(generatedAssetsDir, name)
                if (f.exists()) f.readBytes() else null
            }
        )

        val unsignedApk = File(workDir, "psremote_unsigned.apk")
        val logs = mutableListOf<String>()
        val config = PatchConfig(
            sourceApkFiles = listOf(baseApk, featureSplitApk),
            originalPackageName = "com.playstation.remoteplay",
            appName = "PS Remote Play",
            strategy = PatchStrategy.UNIVERSAL_INJECTION,
            customPackageName = "com.playstation.remoteplay.aa",
            unlockScreenOrientation = true,
            removeSignatureVerification = true
        )

        merger.prepareApkModule(config.sourceApkFiles, workDir) { logs.add(it) }.use { session ->
            patcher.patchApkModule(session.apkModule, unsignedApk, config) { logs.add(it) }
        }

        assertTrue(
            "Expected PairIP VM warning log, got: $logs",
            logs.any { it.contains("PairIP VM") }
        )

        ApkModule.loadApkFile(unsignedApk).use { patchedMod ->
            patchedMod.setLoadDefaultFramework(false)
            assertEquals("com.playstation.remoteplay.aa", patchedMod.packageName)

            val root = patchedMod.androidManifest.documentElement
            assertFalse(
                "<uses-split> tags must be removed",
                root.getElements { it.name == "uses-split" }.hasNext()
            )

            val appElem = root.getElements { it.name == "application" }.asSequence().first()
            val activities = appElem.getElements { it.name == "activity" }.asSequence().toList()
            val actByName = activities.associateBy {
                it.searchAttributeByResourceId(0x01010003)?.valueAsString.orEmpty()
            }

            // 1. PlayCoreMissingSplitsActivity removed, but PairIP LicenseActivity KEPT because libpairipcore.so is present
            assertFalse(actByName.containsKey("com.google.android.play.core.missingsplits.PlayCoreMissingSplitsActivity"))
            assertTrue(
                "PairIP LicenseActivity must be preserved when PairIP VM is active to avoid manifest tamper trap",
                actByName.containsKey("com.pairip.licensecheck.LicenseActivity")
            )

            // 2. Feature split activity merged, relative name expanded, splitName stripped, and sensorLandscape (6) preserved!
            val streamAct = actByName["com.playstation.remoteplay.StreamActivity"]
            assertNotNull("Feature split StreamActivity must be merged with expanded class name", streamAct)
            assertEquals(
                "android:splitName (0x01010549) must be stripped",
                null,
                streamAct!!.searchAttributeByResourceId(0x01010549)
            )
            val streamOrient = streamAct.searchAttributeByResourceId(0x0101001e)
            assertNotNull("sensorLandscape screenOrientation must be preserved on StreamActivity", streamOrient)
            assertEquals(6, streamOrient!!.data)

            // 3. Portrait-locked BootActivity (screenOrientation = 1) IS unlocked for car display
            val bootAct = actByName["com.playstation.remoteplay.BootActivity"]
            assertNotNull(bootAct)
            assertEquals(
                "Portrait screenOrientation (1) should be unlocked",
                null,
                bootAct!!.searchAttributeByResourceId(0x0101001e)
            )

            // 4. Provider with @string/file_provider_authority (ValueType.REFERENCE) rewrites the string in resources.arsc!
            val pkg = patchedMod.tableBlock.listPackages().first()
            val stringPair = pkg.getSpecTypePair("string")
            val authResEntry = stringPair.typeBlockArray.listItems()
                .flatMap { it.listEntries(true) }
                .firstOrNull { it.name == "file_provider_authority" }
            assertNotNull(authResEntry)
            assertEquals(
                "com.playstation.remoteplay.aa.fileprovider",
                authResEntry!!.resValue?.valueAsString
            )

            // 5. Shared @style/AppTheme referenced by PlayCoreMissingSplitsActivity must survive intact!
            val stylePair = pkg.getSpecTypePair("style")
            val themeEntry = stylePair.typeBlockArray.listItems()
                .flatMap { it.listEntries(true) }
                .firstOrNull { it.name == "AppTheme" }
            assertNotNull("@style/AppTheme must not be deleted by split cleaner", themeEntry)
            assertFalse("@style/AppTheme must not be null", themeEntry!!.isNull)
        }
    }

    private fun createBaseSplitApk(outFile: File) {
        val mod = ApkModule("base", com.reandroid.archive.ZipEntryMap())
        mod.setLoadDefaultFramework(false)

        val manifest = AndroidManifestBlock()
        val root = manifest.getOrCreateElement("manifest")
        manifest.packageName = "com.stremio.one"
        manifest.minSdkVersion = 24
        manifest.targetSdkVersion = 34

        root.getOrCreateAndroidAttribute("isSplitRequired", 0x01010591).setValueAsBoolean(true)
        root.getOrCreateAndroidAttribute("requiredSplitTypes", 0x0101064e).setValueAsString("base__abi,base__density")

        val perm = root.createChildElement("permission")
        perm.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("com.stremio.one.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

        val app = root.createChildElement("application")
        // Relative Application class name!
        app.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".MainApplication")

        val mainAct = app.createChildElement("activity")
        mainAct.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".MainActivity")
        mainAct.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true)
        val filter = mainAct.createChildElement("intent-filter")
        filter.createChildElement("action").getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("android.intent.action.MAIN")
        filter.createChildElement("category").getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("android.intent.category.LAUNCHER")

        val alias = app.createChildElement("activity-alias")
        alias.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".LauncherAlias")
        alias.getOrCreateAndroidAttribute("targetActivity", 0x01010202).setValueAsString(".MainActivity")

        val provider = app.createChildElement("provider")
        provider.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("androidx.core.content.FileProvider")
        provider.getOrCreateAndroidAttribute("authorities", 0x01010018).setValueAsString("com.stremio.one.provider")

        val pairipAct = app.createChildElement("activity")
        pairipAct.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("com.pairip.licensecheck.LicenseActivity")

        val missingSplitsAct = app.createChildElement("activity")
        missingSplitsAct.getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("com.google.android.play.core.missingsplits.PlayCoreMissingSplitsActivity")

        val splitMeta = app.createChildElement("meta-data")
        splitMeta.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("com.android.vending.splits.required")
        splitMeta.getOrCreateAndroidAttribute("value", 0x01010024).setValueAsBoolean(true)

        mod.setManifest(manifest)

        val table = TableBlock()
        val pkg = table.newPackage(0x7f, "com.stremio.one")
        val strEntry = pkg.getOrCreate("", "string", "app_name")
        strEntry.setValueAsString("Stremio")

        // Reference @string/app_name on PlayCoreMissingSplitsActivity to ensure split cleaning doesn't wipe shared resources
        val missingLabelAttr = missingSplitsAct.getOrCreateAndroidAttribute("label", 0x01010001)
        missingLabelAttr.setTypeAndData(ValueType.REFERENCE, strEntry.resourceEntry.resourceId)

        // Create a FLAG_SPARSE drawable TypeBlock in base.apk with high entry ID 0x00d8 (mimicking Stremio base.apk exo_icon_vr)
        val drawPair = pkg.getOrCreateSpecTypePair(2, "drawable")
        val anydpiCfg = ResConfig().apply { parseQualifiers("-anydpi-v21") }
        val baseSparseAnydpi = drawPair.typeBlockArray.createNext(true, false).apply {
            resConfig.copyFrom(anydpiCfg)
        }
        val vrSpec = pkg.specStringPool.getOrCreate("exo_icon_vr")
        val vrEntry = baseSparseAnydpi.getOrCreateEntry(0x00d8.toShort())
        vrEntry.setSpecReference(vrSpec)
        vrEntry.setValueAsString("res/drawable-anydpi-v21/exo_icon_vr.xml")
        drawPair.specBlock.entryCount = 0x0130
        mod.setTableBlock(table)
        mod.add(ByteInputSource("<vector/>".toByteArray(), "res/drawable-anydpi-v21/exo_icon_vr.xml"))

        // Reuse bootstrap dex bytes as a valid DEX header for classes.dex
        val validDexBytes = File("build/generated/stub_dex_assets/main/aa_bootstrap_stub.dex").readBytes()
        mod.add(ByteInputSource(validDexBytes, "classes.dex"))

        val bundleBytes = "console.log('react-native-bundle');".repeat(32).toByteArray()
        mod.add(ByteInputSource(bundleBytes, "assets/index.android.bundle"))
        mod.uncompressedFiles.addPath("assets/index.android.bundle")

        // Add 1 stray x86 stub .so in base
        mod.add(ByteInputSource(ByteArray(128) { 0x7f }, "lib/x86/libstub_only.so"))
        mod.uncompressedFiles.addPath("lib/x86/libstub_only.so")

        mod.refreshTable()
        mod.refreshManifest()
        mod.writeApk(outFile)
        mod.close()
    }

    private fun createResourceSplitApk(outFile: File) {
        val mod = ApkModule("split_xxhdpi", com.reandroid.archive.ZipEntryMap())
        mod.setLoadDefaultFramework(false)

        val manifest = AndroidManifestBlock()
        val root = manifest.getOrCreateElement("manifest")
        manifest.packageName = "com.stremio.one"
        root.getOrCreateAttribute("split", 0).setValueAsString("config.xxhdpi")
        mod.setManifest(manifest)

        val table = TableBlock()
        val pkg = table.newPackage(0x7f, "com.stremio.one")
        val drawPair = pkg.getOrCreateSpecTypePair(2, "drawable")
        val xxhdpiEntry = pkg.getOrCreate("xxhdpi", "drawable", "ic_logo")
        xxhdpiEntry.setValueAsString("res/drawable-xxhdpi-v4/ic_logo.png")

        // Also add a FLAG_SPARSE -anydpi TypeBlock in the split with entry ID 0x0125 (mimicking Stremio split_config.xxxhdpi imdb.xml)
        val anydpiCfg = ResConfig().apply { parseQualifiers("-anydpi-v21") }
        val splitSparseAnydpi = drawPair.typeBlockArray.createNext(true, false).apply {
            resConfig.copyFrom(anydpiCfg)
        }
        val imdbSpec = pkg.specStringPool.getOrCreate("imdb")
        val imdbEntry = splitSparseAnydpi.getOrCreateEntry(0x0125.toShort())
        imdbEntry.setSpecReference(imdbSpec)
        imdbEntry.setValueAsString("res/drawable-anydpi-v24/imdb.xml")
        drawPair.specBlock.entryCount = 0x0130
        mod.setTableBlock(table)

        mod.add(ByteInputSource(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "res/drawable-xxhdpi-v4/ic_logo.png"))
        mod.add(ByteInputSource("<vector/>".toByteArray(), "res/drawable-anydpi-v24/imdb.xml"))

        mod.refreshTable()
        mod.refreshManifest()
        mod.writeApk(outFile)
        mod.close()
    }

    private fun createAbiSplitApk(outFile: File) {
        val mod = ApkModule("split_arm64", com.reandroid.archive.ZipEntryMap())
        mod.setLoadDefaultFramework(false)

        val manifest = AndroidManifestBlock()
        val root = manifest.getOrCreateElement("manifest")
        manifest.packageName = "com.stremio.one"
        root.getOrCreateAttribute("split", 0).setValueAsString("config.arm64_v8a")
        mod.setManifest(manifest)

        val soNames = listOf("libstremio_core.so", "libreactnativejni.so", "libhermes.so", "libvlc.so")
        for (so in soNames) {
            val path = "lib/arm64-v8a/$so"
            mod.add(ByteInputSource(ByteArray(512) { 0x42 }, path))
            mod.uncompressedFiles.addPath(path)
        }

        mod.refreshManifest()
        mod.writeApk(outFile)
        mod.close()
    }

    private fun createPsRemoteStyleBaseApk(outFile: File) {
        val mod = ApkModule("base_psremote", com.reandroid.archive.ZipEntryMap())
        mod.setLoadDefaultFramework(false)

        val table = TableBlock()
        val pkg = table.newPackage(0x7f, "com.playstation.remoteplay")
        val appNameEntry = pkg.getOrCreate("", "string", "app_name").apply {
            setValueAsString("PS Remote Play")
        }
        val authEntry = pkg.getOrCreate("", "string", "file_provider_authority").apply {
            setValueAsString("com.playstation.remoteplay.fileprovider")
        }
        val themeEntry = pkg.getOrCreate("", "style", "AppTheme").apply {
            setValueAsBoolean(true)
        }
        mod.setTableBlock(table)

        val manifest = AndroidManifestBlock()
        val root = manifest.getOrCreateElement("manifest")
        manifest.packageName = "com.playstation.remoteplay"
        manifest.minSdkVersion = 26
        manifest.targetSdkVersion = 34

        root.getOrCreateAndroidAttribute("isolatedSplits", 0x0101054b).setValueAsBoolean(true)
        val usesSplit = root.createChildElement("uses-split")
        usesSplit.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString("feature_stream")

        val app = root.createChildElement("application")
        app.getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("com.pairip.application.Application")

        val bootAct = app.createChildElement("activity")
        bootAct.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".BootActivity")
        bootAct.getOrCreateAndroidAttribute("exported", 0x01010010).setValueAsBoolean(true)
        bootAct.getOrCreateAndroidAttribute("screenOrientation", 0x0101001e).apply {
            setTypeAndData(ValueType.DEC, 1) // portrait
        }
        val filter = bootAct.createChildElement("intent-filter")
        filter.createChildElement("action").getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("android.intent.action.MAIN")
        filter.createChildElement("category").getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("android.intent.category.LAUNCHER")

        val provider = app.createChildElement("provider")
        provider.getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("androidx.core.content.FileProvider")
        provider.getOrCreateAndroidAttribute("authorities", 0x01010018).apply {
            setTypeAndData(ValueType.REFERENCE, authEntry.resourceEntry.resourceId)
        }

        val pairipAct = app.createChildElement("activity")
        pairipAct.getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("com.pairip.licensecheck.LicenseActivity")

        val missingSplitsAct = app.createChildElement("activity")
        missingSplitsAct.getOrCreateAndroidAttribute("name", 0x01010003)
            .setValueAsString("com.google.android.play.core.missingsplits.PlayCoreMissingSplitsActivity")
        missingSplitsAct.getOrCreateAndroidAttribute("label", 0x01010001).apply {
            setTypeAndData(ValueType.REFERENCE, appNameEntry.resourceEntry.resourceId)
        }
        missingSplitsAct.getOrCreateAndroidAttribute("theme", 0x01010000).apply {
            setTypeAndData(ValueType.REFERENCE, themeEntry.resourceEntry.resourceId)
        }

        mod.setManifest(manifest)

        val validDexBytes = File("build/generated/stub_dex_assets/main/aa_bootstrap_stub.dex").readBytes()
        mod.add(ByteInputSource(validDexBytes, "classes.dex"))
        mod.add(ByteInputSource(ByteArray(512) { 0x55 }, "lib/arm64-v8a/libpairipcore.so"))
        mod.add(ByteInputSource(ByteArray(512) { 0x66 }, "lib/arm64-v8a/libRemotePlayCore.so"))
        mod.uncompressedFiles.addPath("lib/arm64-v8a/libpairipcore.so")
        mod.uncompressedFiles.addPath("lib/arm64-v8a/libRemotePlayCore.so")

        mod.refreshTable()
        mod.refreshManifest()
        mod.writeApk(outFile)
        mod.close()
    }

    private fun createPsRemoteFeatureSplitApk(outFile: File) {
        val mod = ApkModule("split_feature_stream", com.reandroid.archive.ZipEntryMap())
        mod.setLoadDefaultFramework(false)

        val manifest = AndroidManifestBlock()
        val root = manifest.getOrCreateElement("manifest")
        manifest.packageName = "com.playstation.remoteplay"
        root.getOrCreateAttribute("split", 0).setValueAsString("feature_stream")
        root.getOrCreateAndroidAttribute("isFeatureSplit", 0x0101053b).setValueAsBoolean(true)

        val app = root.createChildElement("application")
        val streamAct = app.createChildElement("activity")
        streamAct.getOrCreateAndroidAttribute("name", 0x01010003).setValueAsString(".StreamActivity")
        streamAct.getOrCreateAndroidAttribute("splitName", 0x01010549).setValueAsString("feature_stream")
        streamAct.getOrCreateAndroidAttribute("screenOrientation", 0x0101001e).apply {
            setTypeAndData(ValueType.DEC, 6) // sensorLandscape
        }

        mod.setManifest(manifest)
        mod.refreshManifest()
        mod.writeApk(outFile)
        mod.close()
    }

    private fun findZipEntryDataOffset(zipFile: File, targetEntryName: String): Long {
        RandomAccessFile(zipFile, "r").use { raf ->
            val len = raf.length()
            val searchSize = minOf(len, 65557L).toInt()
            val tail = ByteArray(searchSize)
            raf.seek(len - searchSize)
            raf.readFully(tail)

            var eocdPos = -1
            for (i in searchSize - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() &&
                    tail[i + 1] == 0x4b.toByte() &&
                    tail[i + 2] == 0x05.toByte() &&
                    tail[i + 3] == 0x06.toByte()
                ) {
                    eocdPos = i
                    break
                }
            }
            require(eocdPos >= 0) { "EOCD not found" }

            val bb = ByteBuffer.wrap(tail, eocdPos, 22).order(ByteOrder.LITTLE_ENDIAN)
            val totalEntries = bb.getShort(eocdPos + 10).toInt() and 0xFFFF
            val cdOffset = bb.getInt(eocdPos + 16).toLong() and 0xFFFFFFFFL

            raf.seek(cdOffset)
            val headerBuf = ByteArray(46)
            val lfhBuf = ByteArray(30)
            repeat(totalEntries) {
                raf.readFully(headerBuf)
                val cd = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
                require(cd.getInt(0) == 0x02014b50) { "Invalid CD signature" }
                val nameLen = cd.getShort(28).toInt() and 0xFFFF
                val extraLen = cd.getShort(30).toInt() and 0xFFFF
                val commentLen = cd.getShort(32).toInt() and 0xFFFF
                val lfhOffset = cd.getInt(42).toLong() and 0xFFFFFFFFL

                val nameBytes = ByteArray(nameLen)
                raf.readFully(nameBytes)
                val entryName = String(nameBytes, Charsets.UTF_8)
                raf.seek(raf.filePointer + extraLen + commentLen)

                if (entryName == targetEntryName) {
                    raf.seek(lfhOffset)
                    raf.readFully(lfhBuf)
                    val lfh = ByteBuffer.wrap(lfhBuf).order(ByteOrder.LITTLE_ENDIAN)
                    require(lfh.getInt(0) == 0x04034b50) { "Invalid LFH signature" }
                    val lfhNameLen = lfh.getShort(26).toInt() and 0xFFFF
                    val lfhExtraLen = lfh.getShort(28).toInt() and 0xFFFF
                    return lfhOffset + 30L + lfhNameLen + lfhExtraLen
                }
            }
        }
        throw IllegalArgumentException("Entry not found: $targetEntryName")
    }
}

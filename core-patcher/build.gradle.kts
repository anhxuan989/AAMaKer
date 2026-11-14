import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.aamaker.core.patcher"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

val stubDexCompile by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

val stubDexAssetsDir = layout.buildDirectory.dir("generated/stub_dex_assets/main")

val generateStubDex by tasks.registering {
    dependsOn(":aa-stub-runtime:bundleLibRuntimeToJarDebug")
    val inputStubJar = project(":aa-stub-runtime").layout.buildDirectory.file(
        "intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"
    )
    val outputAssetsDir = stubDexAssetsDir

    inputs.file(inputStubJar)
    inputs.files(stubDexCompile)
    outputs.dir(outputAssetsDir)

    doLast {
        val sdkDir = android.sdkDirectory
        val buildToolsDir = File(sdkDir, "build-tools")
            .listFiles()
            ?.sortedByDescending { it.name }
            ?.firstOrNull()
            ?: throw GradleException("Không tìm thấy Android SDK build-tools")

        val d8Executable = File(
            buildToolsDir,
            if (org.gradle.internal.os.OperatingSystem.current().isWindows) "d8.bat" else "d8"
        )
        val outDir = outputAssetsDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()

        val tempJarsDir = File(layout.buildDirectory.get().asFile, "tmp/stub_dex_jars").apply {
            deleteRecursively()
            mkdirs()
        }

        val bootstrapJar = File(tempJarsDir, "bootstrap_only.jar")
        val runtimeImplJar = File(tempJarsDir, "runtime_impl.jar")
        JarFile(inputStubJar.get().asFile).use { srcJar ->
            JarOutputStream(bootstrapJar.outputStream().buffered()).use { bootOut ->
                JarOutputStream(runtimeImplJar.outputStream().buffered()).use { implOut ->
                    val entries = srcJar.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val name = entry.name
                        val isBootstrapClass =
                            name == "com/aamaker/stub/runtime/AAMakerCarAppService.class" ||
                            (name.startsWith("com/aamaker/stub/runtime/AAMakerCarAppService$") && name.endsWith(".class"))
                        val targetOut = if (isBootstrapClass) bootOut else implOut
                        targetOut.putNextEntry(JarEntry(name))
                        srcJar.getInputStream(entry).use { it.copyTo(targetOut) }
                        targetOut.closeEntry()
                    }
                }
            }
        }

        val androidJar = File(sdkDir, "platforms/android-35/android.jar")
        val bootstrapOutDir = File(tempJarsDir, "bootstrap_dex_out").apply { mkdirs() }
        val bootCmd = mutableListOf(
            d8Executable.absolutePath,
            "--min-api", "26",
            "--output", bootstrapOutDir.absolutePath
        )
        if (androidJar.exists()) {
            bootCmd.add("--lib")
            bootCmd.add(androidJar.absolutePath)
        }
        bootCmd.add(bootstrapJar.absolutePath)

        val bootProc = ProcessBuilder(bootCmd).redirectErrorStream(true).start()
        val bootLog = bootProc.inputStream.bufferedReader().readText()
        if (bootProc.waitFor() != 0) {
            throw GradleException("Lỗi khi chạy d8 tạo aa_bootstrap_stub.dex: $bootLog")
        }
        val bootClassesDex = File(bootstrapOutDir, "classes.dex")
        if (!bootClassesDex.exists()) {
            throw GradleException("Không tìm thấy classes.dex cho aa_bootstrap_stub.dex")
        }
        bootClassesDex.copyTo(File(outDir, "aa_bootstrap_stub.dex"), overwrite = true)

        val jarInputs = mutableListOf<String>()
        jarInputs.add(runtimeImplJar.absolutePath)
        jarInputs.add(bootstrapJar.absolutePath)

        val seenModules = mutableSetOf<String>()
        stubDexCompile.resolvedConfiguration.resolvedArtifacts.forEachIndexed { idx, artifact ->
            val file = artifact.file
            val id = artifact.moduleVersion.id
            val moduleKey = "${id.group}:${id.name}:${file.name}"
            if (!seenModules.add(moduleKey)) return@forEachIndexed

            if (file.name.endsWith(".aar")) {
                val extractDir = File(tempJarsDir, "${id.name}-${id.version}-$idx").apply { mkdirs() }
                project.copy {
                    from(project.zipTree(file)) {
                        include("classes.jar")
                        include("libs/*.jar")
                    }
                    into(extractDir)
                }
                val extractedJar = File(extractDir, "classes.jar")
                if (extractedJar.exists() && extractedJar.length() > 0) {
                    jarInputs.add(extractedJar.absolutePath)
                }
                File(extractDir, "libs").listFiles()?.filter { it.name.endsWith(".jar") && it.length() > 0 }?.forEach {
                    jarInputs.add(it.absolutePath)
                }
            } else if (file.name.endsWith(".jar") && file.length() > 0) {
                val excludedJars = setOf(
                    "guava",
                    "kotlin-stdlib-jdk7",
                    "kotlin-stdlib-jdk8"
                )
                if (id.name !in excludedJars) {
                    jarInputs.add(file.absolutePath)
                }
            }
        }

        val runtimeOutDir = File(tempJarsDir, "runtime_dex_out").apply { mkdirs() }
        val cmd = mutableListOf(
            d8Executable.absolutePath,
            "--min-api", "26",
            "--output", runtimeOutDir.absolutePath
        )
        if (androidJar.exists()) {
            cmd.add("--lib")
            cmd.add(androidJar.absolutePath)
        }
        cmd.addAll(jarInputs)

        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val outputLog = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("Lỗi khi chạy d8 tạo aa_car_res.dat: $outputLog")
        }

        val classesDex = File(runtimeOutDir, "classes.dex")
        val targetDat = File(outDir, "aa_car_res.dat")
        if (classesDex.exists()) {
            if (targetDat.exists()) targetDat.delete()
            GZIPOutputStream(targetDat.outputStream().buffered()).use { gzipOut ->
                classesDex.inputStream().buffered().use { it.copyTo(gzipOut) }
            }
            classesDex.delete()
        }
        var extraIdx = 2
        while (true) {
            val extraDex = File(runtimeOutDir, "classes$extraIdx.dex")
            if (!extraDex.exists()) break
            val targetExtra = File(outDir, "aa_car_res_$extraIdx.dat")
            if (targetExtra.exists()) targetExtra.delete()
            GZIPOutputStream(targetExtra.outputStream().buffered()).use { gzipOut ->
                extraDex.inputStream().buffered().use { it.copyTo(gzipOut) }
            }
            extraDex.delete()
            extraIdx++
        }
    }
}

android.sourceSets.getByName("main").assets.srcDir(
    generateStubDex.map { stubDexAssetsDir.get().asFile }
)

tasks.matching {
    (it.name.startsWith("generate") && it.name.endsWith("Assets")) ||
    it.name.contains("Lint", ignoreCase = true)
}.configureEach {
    dependsOn(generateStubDex)
}

dependencies {
    stubDexCompile("org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.get()}")
    stubDexCompile(libs.androidx.core.ktx)
    stubDexCompile(libs.androidx.car.app)
    stubDexCompile(libs.androidx.car.app.projected)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.android.apksig)
    implementation(libs.reandroid.arsclib)
    testImplementation("junit:junit:4.13.2")
}

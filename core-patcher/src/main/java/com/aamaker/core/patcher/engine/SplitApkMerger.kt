package com.aamaker.core.patcher.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SplitApkMerger {

    fun prepareSingleApk(
        inputFiles: List<File>,
        workDir: File,
        onLog: (String) -> Unit
    ): File {
        require(inputFiles.isNotEmpty()) { "Danh sách file APK đầu vào trống!" }

        val firstFile = inputFiles.first()
        val ext = firstFile.extension.lowercase()

        if (inputFiles.size == 1 && (ext == "apks" || ext == "xapk" || ext == "zip")) {
            onLog("[Merger] Phát hiện gói Bundle (${firstFile.name}), đang giải nén các APK thành phần...")
            val extractedDir = File(workDir, "extracted_bundle_${System.currentTimeMillis()}").apply { mkdirs() }
            val extractedApks = extractApksFromBundle(firstFile, extractedDir)
            if (extractedApks.isEmpty()) {
                throw IllegalArgumentException("Không tìm thấy file .apk nào bên trong ${firstFile.name}")
            }
            if (extractedApks.size == 1 && !hasObbEntries(firstFile)) {
                return extractedApks.first()
            }
            return mergeMultipleApks(extractedApks, workDir, onLog, sourceBundleFile = firstFile)
        }

        if (inputFiles.size == 1) {
            return firstFile
        }

        onLog("[Merger] Phát hiện ${inputFiles.size} Split APKs, đang hợp nhất thành APK đơn...")
        return mergeMultipleApks(inputFiles, workDir, onLog)
    }

    private fun hasObbEntries(bundleFile: File): Boolean {
        return try {
            ZipFile(bundleFile).use { zip ->
                zip.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".obb", ignoreCase = true) }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun extractApksFromBundle(bundleFile: File, targetDir: File): List<File> {
        val result = mutableListOf<File>()
        ZipInputStream(BufferedInputStream(FileInputStream(bundleFile))).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                    val safeName = File(entry.name).name
                    val outFile = File(targetDir, safeName)
                    FileOutputStream(outFile).use { fos ->
                        zis.copyTo(fos)
                    }
                    result.add(outFile)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return result.sortedBy { if (it.name.contains("base", ignoreCase = true)) 0 else 1 }
    }

    private fun mergeMultipleApks(
        apkFiles: List<File>,
        workDir: File,
        onLog: (String) -> Unit,
        sourceBundleFile: File? = null
    ): File {
        val baseApk = apkFiles.firstOrNull { it.name == "base.apk" } ?: apkFiles.first()
        val splitApks = apkFiles.filter { it != baseApk }

        val mergedFile = File(workDir, "merged_base_${System.currentTimeMillis()}.apk")
        val addedEntries = HashSet<String>()
        var nextDexIndex = 1

        ZipOutputStream(BufferedOutputStream(FileOutputStream(mergedFile))).use { zos ->
            ZipFile(baseApk).use { baseZip ->
                val entries = baseZip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (isSignatureFile(entry.name)) continue

                    if (isClassesDex(entry.name)) {
                        nextDexIndex++
                    }
                    copyZipEntry(baseZip, entry, entry.name, zos)
                    addedEntries.add(entry.name)
                }
            }

            for (splitFile in splitApks) {
                onLog("[Merger] Đang gộp thành phần từ ${splitFile.name}...")
                ZipFile(splitFile).use { splitZip ->
                    val entries = splitZip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val name = entry.name
                        if (entry.isDirectory || isSignatureFile(name)) continue
                        if (name == "AndroidManifest.xml" || name == "resources.arsc" || name == "stamp-cert-sha256") continue

                        val targetName = if (isClassesDex(name)) {
                            val dexName = if (nextDexIndex == 1) "classes.dex" else "classes$nextDexIndex.dex"
                            nextDexIndex++
                            dexName
                        } else {
                            name
                        }

                        if (addedEntries.add(targetName)) {
                            copyZipEntry(splitZip, entry, targetName, zos)
                        }
                    }
                }
            }

            if (sourceBundleFile != null) {
                try {
                    ZipFile(sourceBundleFile).use { bundleZip ->
                        val entries = bundleZip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (!entry.isDirectory && entry.name.endsWith(".obb", ignoreCase = true)) {
                                val obbFileName = File(entry.name).name
                                val targetObbAsset = "assets/obb/$obbFileName"
                                if (addedEntries.add(targetObbAsset)) {
                                    onLog("[Merger] Đang gộp thành phần từ $obbFileName...")
                                    copyZipEntry(bundleZip, entry, targetObbAsset, zos)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }

        onLog("[Merger] Đã hợp nhất thành công Split APKs (${mergedFile.length() / 1024} KB).")
        return mergedFile
    }

    private fun copyZipEntry(
        sourceZip: ZipFile,
        entry: ZipEntry,
        targetName: String,
        zos: ZipOutputStream
    ) {
        val shouldStoreUncompressed = targetName == "resources.arsc" || targetName.endsWith(".so")
        val buffer = ByteArray(64 * 1024)

        val newEntry = ZipEntry(targetName)
        if (shouldStoreUncompressed) {
            val crc = CRC32()
            var totalSize = 0L
            sourceZip.getInputStream(entry).use { input ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    crc.update(buffer, 0, read)
                    totalSize += read
                }
            }
            newEntry.method = ZipEntry.STORED
            newEntry.size = totalSize
            newEntry.compressedSize = totalSize
            newEntry.crc = crc.value
        } else {
            newEntry.method = ZipEntry.DEFLATED
        }

        zos.putNextEntry(newEntry)
        sourceZip.getInputStream(entry).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                zos.write(buffer, 0, read)
            }
        }
        zos.closeEntry()
    }

    companion object {
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
    }
}

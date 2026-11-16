package com.aamaker.core.installer.util

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ApkBundleExtractor {

    private val BUNDLE_EXTENSIONS = setOf("apks", "xapk", "apkm", "zip")
    private const val MAX_ZIP_ENTRIES = 2048
    private const val MAX_TOTAL_EXTRACTED_BYTES = 4L * 1024L * 1024L * 1024L

    data class ExtractedInstallPayload(
        val apkFiles: List<File>,
        val tempDirectoryToCleanup: File?
    ) {
        fun cleanup() {
            tempDirectoryToCleanup?.deleteRecursively()
        }
    }

    fun prepareApksForDirectInstall(
        inputFiles: List<File>,
        cacheRoot: File,
        onLog: (String) -> Unit
    ): ExtractedInstallPayload {
        require(inputFiles.isNotEmpty()) { "Danh sách file APK đầu vào trống!" }

        val firstFile = inputFiles.first()
        val ext = firstFile.extension.lowercase()

        if (inputFiles.size == 1 && ext in BUNDLE_EXTENSIONS) {
            onLog("[Direct Install] Phát hiện gói Bundle (${firstFile.name}), đang trích xuất các APK gốc (giữ nguyên chữ ký)...")
            val tempDir = File(cacheRoot, "direct_bundle_${System.currentTimeMillis()}").apply { mkdirs() }
            val canonicalRoot = tempDir.canonicalFile
            val extracted = mutableListOf<File>()
            var entryCount = 0
            var apkIdx = 0
            var totalBytes = 0L

            try {
                ZipInputStream(BufferedInputStream(FileInputStream(firstFile))).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        entryCount++
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            throw IllegalStateException("Gói Bundle chứa quá nhiều mục ($entryCount)!")
                        }
                        if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                            val rawBase = File(entry.name).name
                            val safeName = if (rawBase.equals("base.apk", ignoreCase = true) &&
                                extracted.none { it.name.equals("base.apk", ignoreCase = true) }
                            ) {
                                "base.apk"
                            } else {
                                InstallerSecurityValidator.sanitizeEntryFileName(rawBase, ++apkIdx)
                            }
                            val outFile = File(tempDir, safeName)
                            InstallerSecurityValidator.ensureInsideDirectory(outFile, canonicalRoot)

                            val buffer = ByteArray(64 * 1024)
                            FileOutputStream(outFile).use { fos ->
                                while (true) {
                                    val read = zis.read(buffer)
                                    if (read < 0) break
                                    totalBytes += read
                                    if (totalBytes > MAX_TOTAL_EXTRACTED_BYTES) {
                                        throw IllegalStateException("Dung lượng giải nén vượt quá giới hạn an toàn (4 GB)!")
                                    }
                                    fos.write(buffer, 0, read)
                                }
                            }
                            extracted.add(outFile)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (t: Throwable) {
                tempDir.deleteRecursively()
                throw t
            }

            if (extracted.isEmpty()) {
                tempDir.deleteRecursively()
                throw IllegalArgumentException("Không tìm thấy file .apk nào bên trong ${firstFile.name}")
            }

            val sorted = extracted.sortedBy {
                when {
                    it.name.equals("base.apk", ignoreCase = true) -> 0
                    it.name.contains("base", ignoreCase = true) -> 1
                    else -> 2
                }
            }
            return ExtractedInstallPayload(apkFiles = sorted, tempDirectoryToCleanup = tempDir)
        }

        return ExtractedInstallPayload(apkFiles = inputFiles, tempDirectoryToCleanup = null)
    }
}

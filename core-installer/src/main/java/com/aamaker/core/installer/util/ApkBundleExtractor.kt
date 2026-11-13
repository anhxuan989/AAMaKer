package com.aamaker.core.installer.util

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ApkBundleExtractor {

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

        if (inputFiles.size == 1 && (ext == "apks" || ext == "xapk" || ext == "zip")) {
            onLog("[Direct Install] Phát hiện gói Bundle (${firstFile.name}), đang trích xuất các APK gốc (giữ nguyên chữ ký)...")
            val tempDir = File(cacheRoot, "direct_bundle_${System.currentTimeMillis()}").apply { mkdirs() }
            val extracted = mutableListOf<File>()

            ZipInputStream(BufferedInputStream(FileInputStream(firstFile))).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)) {
                        val safeName = File(entry.name).name
                        val outFile = File(tempDir, safeName)
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos, bufferSize = 64 * 1024)
                        }
                        extracted.add(outFile)
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (extracted.isEmpty()) {
                tempDir.deleteRecursively()
                throw IllegalArgumentException("Không tìm thấy file .apk nào bên trong ${firstFile.name}")
            }

            val sorted = extracted.sortedBy { if (it.name.contains("base", ignoreCase = true)) 0 else 1 }
            return ExtractedInstallPayload(apkFiles = sorted, tempDirectoryToCleanup = tempDir)
        }

        return ExtractedInstallPayload(apkFiles = inputFiles, tempDirectoryToCleanup = null)
    }
}

package com.aamaker.core.installer.util

import java.io.File

internal object InstallerSecurityValidator {
    private val ANDROID_PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val UNSAFE_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")

    fun isValidPackageName(pkg: String?): Boolean {
        if (pkg.isNullOrBlank() || pkg.length > 255) return false
        return ANDROID_PACKAGE_REGEX.matches(pkg)
    }

    fun shellSingleQuote(raw: String): String {
        return "'" + raw.replace("'", "'\\''") + "'"
    }

    fun sanitizeEntryFileName(rawName: String, index: Int): String {
        val base = File(rawName).name.replace(UNSAFE_FILE_CHARS, "_").trim('.', '_').take(140)
        return if (base.equals("base.apk", ignoreCase = true) && index == 0) {
            "base.apk"
        } else {
            "apk_${index}_${base.ifBlank { "split.apk" }}"
        }
    }

    fun ensureInsideDirectory(file: File, canonicalRoot: File) {
        val canonicalTarget = file.canonicalFile
        val rootPath = canonicalRoot.path
        val targetPath = canonicalTarget.path
        if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
            throw SecurityException("Phát hiện đường dẫn giải nén không hợp lệ: ${file.name}")
        }
    }
}

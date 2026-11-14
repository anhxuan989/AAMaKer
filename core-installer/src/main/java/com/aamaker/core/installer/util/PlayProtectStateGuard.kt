package com.aamaker.core.installer.util

internal data class PlayProtectSnapshot(
    val entries: Map<String, String?>
) {
    fun buildRestoreShellCommand(): String {
        return entries.entries.joinToString("; ") { (key, rawValue) ->
            if (isUnset(rawValue)) {
                "settings delete global $key"
            } else {
                val safeVal = rawValue!!.replace("'", "'\\''")
                "settings put global $key '$safeVal'"
            }
        }
    }

    companion object {
        val KEYS = listOf(
            "verifier_verify_adb_installs",
            "package_verifier_enable",
            "package_verifier_user_consent"
        )

        fun buildBackupAndDisableShellCommand(): String {
            val readPart = KEYS.joinToString("; ") { key ->
                "echo \"$key=\$(settings get global $key)\""
            }
            val disablePart = listOf(
                "settings put global verifier_verify_adb_installs 0",
                "settings put global package_verifier_enable 0",
                "settings put global package_verifier_user_consent -1"
            ).joinToString("; ")
            return "$readPart; $disablePart"
        }

        fun parseSnapshot(lines: List<String>): PlayProtectSnapshot {
            val map = LinkedHashMap<String, String?>()
            for (line in lines) {
                val idx = line.indexOf('=')
                if (idx <= 0) continue
                val key = line.substring(0, idx).trim()
                if (key in KEYS) {
                    val value = line.substring(idx + 1).trim()
                    map[key] = if (isUnset(value)) null else value
                }
            }
            // Fallback defaults if reading failed or returned unexpected output:
            // restore Play Protect enabled (1) rather than leaving it disabled.
            if (map.isEmpty()) {
                map["verifier_verify_adb_installs"] = "1"
                map["package_verifier_enable"] = "1"
                map["package_verifier_user_consent"] = "1"
            }
            return PlayProtectSnapshot(map)
        }

        private fun isUnset(value: String?): Boolean {
            return value.isNullOrEmpty() || value.equals("null", ignoreCase = true)
        }
    }
}

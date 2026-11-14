package com.aamaker.core.patcher.security

import android.content.Context
import java.io.File
import java.security.SecureRandom

object BridgeAuthManager {
    const val PREFS_NAME = "aamaker_bridge_auth"
    const val KEY_BRIDGE_TOKEN = "bridge_secret_token_v1"
    const val META_BRIDGE_TOKEN = "com.aamaker.BRIDGE_TOKEN"
    const val EXTRA_BRIDGE_TOKEN = "bridge_token"
    const val PERMISSION_COMMAND_BRIDGE = "com.aamaker.permission.COMMAND_BRIDGE"

    @Synchronized
    fun getOrCreateBridgeToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_BRIDGE_TOKEN, null)
        if (!existing.isNullOrBlank() && existing.length >= 32) {
            return existing
        }
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_BRIDGE_TOKEN, generated).commit()
        return generated
    }

    fun isValidToken(context: Context, candidateToken: String?): Boolean {
        if (candidateToken.isNullOrBlank()) return false
        val expected = getOrCreateBridgeToken(context)
        return constantTimeEquals(expected, candidateToken)
    }

    fun hasLocalPatchingSigningKeyCertMatch(context: Context, senderPackageName: String): Boolean {
        if (senderPackageName == context.packageName) return true
        val certFile = File(File(context.filesDir, "signing_keys"), "aamaker_cert_v3.der")
        if (!certFile.exists()) return false
        return try {
            val expectedCertBytes = certFile.readBytes()
            val pm = context.packageManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(
                    senderPackageName,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signers = pkgInfo.signingInfo?.apkContentsSigners ?: return false
                signers.any { it.toByteArray().contentEquals(expectedCertBytes) }
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(
                    senderPackageName,
                    android.content.pm.PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                val signers = pkgInfo.signatures ?: return false
                signers.any { it.toByteArray().contentEquals(expectedCertBytes) }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}

package com.aamaker.core.patcher.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object PackageNameValidator {
    private val ANDROID_PACKAGE_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    private val ANDROID_CLASS_REGEX = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)+$")

    fun isValidPackageName(pkg: String?): Boolean {
        if (pkg.isNullOrBlank() || pkg.length > 255) return false
        return ANDROID_PACKAGE_REGEX.matches(pkg)
    }

    fun isValidClassName(cls: String?): Boolean {
        if (cls.isNullOrBlank() || cls.length > 512) return false
        return ANDROID_CLASS_REGEX.matches(cls)
    }

    fun sanitizeSafeFileName(rawName: String?, fallback: String): String {
        val base = rawName?.let { File(it).name }.orEmpty()
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('.', '_')
        return if (base.isBlank()) fallback else base.take(180)
    }
}

object LocalKeyEnvelopeCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "aamaker_master_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128
    private val ENCRYPTED_MAGIC = byteArrayOf('A'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), 1)

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = ks.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey
            }
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    fun encryptIfAvailable(plainBytes: ByteArray): ByteArray {
        val secretKey = getOrCreateSecretKey() ?: return plainBytes
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            if (iv == null || iv.size != GCM_IV_LENGTH) return plainBytes
            val cipherText = cipher.doFinal(plainBytes)
            ByteArray(ENCRYPTED_MAGIC.size + iv.size + cipherText.size).also { out ->
                System.arraycopy(ENCRYPTED_MAGIC, 0, out, 0, ENCRYPTED_MAGIC.size)
                System.arraycopy(iv, 0, out, ENCRYPTED_MAGIC.size, iv.size)
                System.arraycopy(cipherText, 0, out, ENCRYPTED_MAGIC.size + iv.size, cipherText.size)
            }
        } catch (_: Throwable) {
            plainBytes
        }
    }

    fun decryptOrReadRaw(storedBytes: ByteArray): ByteArray? {
        if (!hasEncryptedMagic(storedBytes)) {
            return storedBytes
        }
        if (storedBytes.size <= ENCRYPTED_MAGIC.size + GCM_IV_LENGTH) return null
        val secretKey = getOrCreateSecretKey() ?: return null
        return try {
            val iv = storedBytes.copyOfRange(ENCRYPTED_MAGIC.size, ENCRYPTED_MAGIC.size + GCM_IV_LENGTH)
            val cipherText = storedBytes.copyOfRange(ENCRYPTED_MAGIC.size + GCM_IV_LENGTH, storedBytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(cipherText)
        } catch (_: Throwable) {
            null
        }
    }

    fun hasEncryptedMagic(bytes: ByteArray): Boolean {
        if (bytes.size < ENCRYPTED_MAGIC.size) return false
        for (i in ENCRYPTED_MAGIC.indices) {
            if (bytes[i] != ENCRYPTED_MAGIC[i]) return false
        }
        return true
    }
}

object BridgeAuthManager {
    const val PREFS_NAME = "aamaker_bridge_auth"
    const val KEY_BRIDGE_TOKEN = "bridge_secret_token_v1"
    const val KEY_BRIDGE_TOKEN_ENC = "bridge_secret_token_enc_v2"
    const val META_BRIDGE_TOKEN = "com.aamaker.BRIDGE_TOKEN"
    const val EXTRA_BRIDGE_TOKEN = "bridge_token"
    const val PERMISSION_COMMAND_BRIDGE = "com.aamaker.permission.COMMAND_BRIDGE"

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedSigningCertBytes: ByteArray? = null

    @Volatile
    private var cachedSigningCertLastModified: Long = -1L

    @Synchronized
    fun getOrCreateBridgeToken(context: Context): String {
        cachedToken?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encBase64 = prefs.getString(KEY_BRIDGE_TOKEN_ENC, null)
        if (!encBase64.isNullOrBlank()) {
            val decoded = runCatching { Base64.decode(encBase64, Base64.NO_WRAP) }.getOrNull()
            val plain = decoded?.let { LocalKeyEnvelopeCrypto.decryptOrReadRaw(it) }
                ?.toString(Charsets.UTF_8)
            if (!plain.isNullOrBlank() && plain.length >= 32) {
                cachedToken = plain
                return plain
            }
        }

        // Migrate legacy plaintext token if present so already-patched apps remain compatible
        val legacyPlain = prefs.getString(KEY_BRIDGE_TOKEN, null)
        val tokenToPersist = if (!legacyPlain.isNullOrBlank() && legacyPlain.length >= 32) {
            legacyPlain
        } else {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        }

        val encryptedBytes = LocalKeyEnvelopeCrypto.encryptIfAvailable(tokenToPersist.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        prefs.edit()
            .putString(KEY_BRIDGE_TOKEN_ENC, encoded)
            .remove(KEY_BRIDGE_TOKEN)
            .apply()

        cachedToken = tokenToPersist
        return tokenToPersist
    }

    fun isValidToken(context: Context, candidateToken: String?): Boolean {
        if (candidateToken.isNullOrBlank()) return false
        val expected = getOrCreateBridgeToken(context)
        return constantTimeEquals(expected, candidateToken)
    }

    fun invalidateSigningCertCache() {
        cachedSigningCertBytes = null
        cachedSigningCertLastModified = -1L
    }

    private fun readExpectedSigningCertBytes(context: Context): ByteArray? {
        val certFile = File(File(context.filesDir, "signing_keys"), "aamaker_cert_v3.der")
        if (!certFile.exists()) return null
        val modTime = certFile.lastModified()
        val currentCached = cachedSigningCertBytes
        if (currentCached != null && modTime == cachedSigningCertLastModified) {
            return currentCached
        }
        return runCatching {
            certFile.readBytes().also {
                cachedSigningCertBytes = it
                cachedSigningCertLastModified = modTime
            }
        }.getOrNull()
    }

    fun hasLocalPatchingSigningKeyCertMatch(context: Context, senderPackageName: String): Boolean {
        if (!PackageNameValidator.isValidPackageName(senderPackageName)) return false
        if (senderPackageName == context.packageName) return true
        val expectedCertBytes = readExpectedSigningCertBytes(context) ?: return false
        return try {
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

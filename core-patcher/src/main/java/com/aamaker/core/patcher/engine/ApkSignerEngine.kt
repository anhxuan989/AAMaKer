package com.aamaker.core.patcher.engine

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

class ApkSignerEngine(private val context: Context) {

    fun signApk(
        unsignedApk: File,
        signedOutputApk: File,
        onLog: (String) -> Unit
    ) {
        onLog("[Signer] Đang chuẩn bị khóa RSA-2048 & chứng chỉ X.509 để ký APK (Scheme V1 + V2 + V3)...")
        val (privateKey, certificate) = getOrCreateSigningCredentials()

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "AAMAKER_KEY",
            privateKey,
            listOf(certificate)
        ).build()

        val apkSigner = ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedOutputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()

        apkSigner.sign()
        onLog("[Signer] Đã ký APK hoàn tất (${signedOutputApk.length() / 1024} KB).")
    }

    private fun getOrCreateSigningCredentials(): Pair<PrivateKey, X509Certificate> {
        val keyDir = File(context.filesDir, "signing_keys").apply { mkdirs() }
        val pkFile = File(keyDir, "aamaker_rsa_v3.pk8")
        val certFile = File(keyDir, "aamaker_cert_v3.der")

        if (pkFile.exists() && certFile.exists()) {
            try {
                val keySpec = PKCS8EncodedKeySpec(pkFile.readBytes())
                val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpec)
                val cert = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
                return privateKey to cert
            } catch (_: Exception) {
                pkFile.delete()
                certFile.delete()
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        val certBytes = SelfSignedX509Generator.generateDerCertificate(keyPair)

        pkFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(certBytes)

        val cert = certBytes.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        return keyPair.private to cert
    }
}

internal object SelfSignedX509Generator {

    fun generateDerCertificate(keyPair: java.security.KeyPair): ByteArray {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86400_000L)
        val notAfter = Date(now + 25L * 365 * 86400_000L)

        val sigAlgId = derSequence(
            derOid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)),
            derNull()
        )

        val cRdn = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x06)), derPrintableString("US")))
        val oRdn = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x0A)), derUtf8String("Automotive App Studio")))
        val ouRdn = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x0B)), derUtf8String("Mobile Release")))
        val cnRdn = derSet(derSequence(derOid(byteArrayOf(0x55, 0x04, 0x03)), derUtf8String("App Release Key")))
        val name = derSequence(cRdn, oRdn, ouRdn, cnRdn)

        val validity = derSequence(
            derUtcTime(notBefore),
            derUtcTime(notAfter)
        )

        val versionV3 = derExplicitTag(0, derInteger(BigInteger.valueOf(2)))
        val serialNumber = derInteger(BigInteger.valueOf((now xor 0x5A3C9E12L) and 0x7FFFFFFFL))
        val subjectPublicKeyInfo = keyPair.public.encoded

        val tbsCertificate = derSequence(
            versionV3,
            serialNumber,
            sigAlgId,
            name,
            validity,
            name,
            subjectPublicKeyInfo
        )

        val signature = java.security.Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(tbsCertificate)
        }.sign()

        return derSequence(
            tbsCertificate,
            sigAlgId,
            derBitString(signature)
        )
    }

    private fun derSequence(vararg items: ByteArray): ByteArray = derTag(0x30, concat(*items))
    private fun derSet(vararg items: ByteArray): ByteArray = derTag(0x31, concat(*items))
    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)
    private fun derOid(content: ByteArray): ByteArray = derTag(0x06, content)
    private fun derUtf8String(str: String): ByteArray = derTag(0x0C, str.toByteArray(Charsets.UTF_8))
    private fun derPrintableString(str: String): ByteArray = derTag(0x13, str.toByteArray(Charsets.US_ASCII))
    private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())
    private fun derExplicitTag(tagNo: Int, content: ByteArray): ByteArray = derTag(0xA0 or tagNo, content)

    private fun derBitString(bytes: ByteArray): ByteArray {
        val padded = ByteArray(bytes.size + 1)
        padded[0] = 0x00
        System.arraycopy(bytes, 0, padded, 1, bytes.size)
        return derTag(0x03, padded)
    }

    private fun derUtcTime(date: Date): ByteArray {
        val sdf = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return derTag(0x17, sdf.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val lenBytes = encodeLength(content.size)
        val out = ByteArray(1 + lenBytes.size + content.size)
        out[0] = tag.toByte()
        System.arraycopy(lenBytes, 0, out, 1, lenBytes.size)
        System.arraycopy(content, 0, out, 1 + lenBytes.size, content.size)
        return out
    }

    private fun encodeLength(length: Int): ByteArray {
        return when {
            length < 128 -> byteArrayOf(length.toByte())
            length <= 0xFF -> byteArrayOf(0x81.toByte(), length.toByte())
            length <= 0xFFFF -> byteArrayOf(
                0x82.toByte(),
                (length shr 8).toByte(),
                (length and 0xFF).toByte()
            )
            else -> byteArrayOf(
                0x83.toByte(),
                (length shr 16).toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte()
            )
        }
    }

    private fun concat(vararg arrays: ByteArray): ByteArray {
        val total = arrays.sumOf { it.size }
        val result = ByteArray(total)
        var pos = 0
        for (arr in arrays) {
            System.arraycopy(arr, 0, result, pos, arr.size)
            pos += arr.size
        }
        return result
    }
}

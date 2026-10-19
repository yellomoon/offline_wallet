package com.offlinewallet.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SecretBox {
    const val ITERATIONS = 200_000

    data class Blob(val salt: ByteArray, val iv: ByteArray, val ciphertext: ByteArray)

    fun encrypt(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS): Blob {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = derive(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            Blob(salt, iv, cipher.doFinal(plain))
        } finally {
            key.fill(0)
        }
    }

    fun decrypt(blob: Blob, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val key = derive(password, blob.salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.iv))
            cipher.doFinal(blob.ciphertext)
        } catch (_: Exception) {
            throw WalletException("密码不正确")
        } finally {
            key.fill(0)
        }
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

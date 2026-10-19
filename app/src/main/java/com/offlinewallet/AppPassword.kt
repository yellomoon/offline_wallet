package com.offlinewallet

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.offlinewallet.core.WalletException
import com.offlinewallet.core.base64ToBytes
import com.offlinewallet.core.toBase64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AppPassword {
    private const val ALIAS = "offline-wallet-app-password"
    private const val IV = "app_pass_iv"
    private const val CT = "app_pass_ct"

    fun exists(prefs: SharedPreferences): Boolean = !prefs.getString(CT, null).isNullOrBlank()

    fun save(prefs: SharedPreferences, password: CharArray) {
        val plain = String(password).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(plain)
            prefs.edit()
                .putString(IV, cipher.iv.toBase64())
                .putString(CT, ciphertext.toBase64())
                .commit()
        } finally {
            plain.fill(0)
        }
    }

    fun load(prefs: SharedPreferences): CharArray {
        val iv = prefs.getString(IV, null)?.base64ToBytes() ?: throw WalletException("还没有设置本机密码")
        val ciphertext = prefs.getString(CT, null)?.base64ToBytes() ?: throw WalletException("还没有设置本机密码")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(ciphertext)
        return try {
            String(plain, Charsets.UTF_8).toCharArray()
        } finally {
            plain.fill(0)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}

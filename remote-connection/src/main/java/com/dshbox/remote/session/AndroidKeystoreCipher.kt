package com.dshbox.remote.session

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [VaultCipher] 的生产实现：AES-256-GCM，密钥生成并常驻 AndroidKeyStore，
 * 不出安全硬件；密文格式为 `IV(12 字节) + 密文`，IV 每次加密随机生成。
 */
class AndroidKeystoreCipher(private val keyAlias: String = DEFAULT_KEY_ALIAS) : VaultCipher {

    private val key: SecretKey by lazy { obtainKey() }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        return iv + encrypted
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = data.copyOfRange(IV_LENGTH_BYTES, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "dshbox_remote_vault"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}

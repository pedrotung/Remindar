package com.example.reminder.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 中的 AES 密钥加密 API Key 后再存入 SharedPreferences。
 * 密钥本身保存在系统安全硬件中、无法导出；即使 prefs 文件被拷走也无法解密。
 */
object SecretStore {

    private const val ALIAS = "reminder_ai_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    /** 返回 “iv:密文”（均为 Base64） */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ct)
    }

    /** 解密失败（如换机后从备份恢复，密钥已不存在）返回 null，需要用户重新填写 */
    fun decrypt(stored: String): String? = try {
        val (iv, ct) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
        }
        String(cipher.doFinal(unb64(ct)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}

package com.liquidglass.app.music

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.math.BigInteger

/**
 * 网易云 weapi 加密实现（纯 Kotlin，无后端依赖）。
 *
 * weapi 流程：
 *  1. 随机 16 字符 secretKey
 *  2. params = AES-CBC(text, fixedKey) → base64 → 再 AES-CBC(_, secretKey) → base64
 *  3. encSecKey = RSA(reverse(secretKey), 网易云公钥) → hex
 *  4. POST { params, encSecKey }
 *
 * 网易云所有 weapi 接口统一走这套。
 */
object NetEaseCrypto {

    private const val FIXED_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val RSA_EXPONENT = "010001"
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private val random = SecureRandom()

    private fun randomSecretKey(): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(BASE62[random.nextInt(BASE62.length)]) }
        return sb.toString()
    }

    private fun aesCbcEncrypt(plaintext: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        )
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    private fun rsaEncrypt(text: String): String {
        // 网易云 RSA：明文反转后做大数模幂
        val reversed = text.reversed()
        val base = BigInteger(reversed.toByteArray(Charsets.UTF_8))
        val mod = BigInteger(RSA_MODULUS, 16)
        val exp = BigInteger(RSA_EXPONENT, 16)
        val result = base.modPow(exp, mod)
        return result.toString(16).padStart(256, '0')
    }

    /**
     * 对原始 JSON 文本做 weapi 加密，返回 (params, encSecKey) 用于 POST form。
     */
    fun encrypt(rawJson: String): Pair<String, String> {
        val secretKey = randomSecretKey()
        val params = aesCbcEncrypt(
            aesCbcEncrypt(rawJson, FIXED_KEY),
            secretKey
        )
        val encSecKey = rsaEncrypt(secretKey)
        return params to encSecKey
    }
}

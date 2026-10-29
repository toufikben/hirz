package dz.hirz.server.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM for small secrets at rest (the family code word). Encryption runs in the JVM, so the key never
 * appears in SQL text or database logs. Output is `iv(12) || ciphertext+tag`.
 */
class DataCipher(key: ByteArray) {
    init { require(key.size == 32) { "HIRZ_DATA_KEY must be 32 bytes (base64)" } }

    private val secret = SecretKeySpec(key.copyOf(), "AES")
    private val random = SecureRandom()

    fun encrypt(plain: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, iv)) }
        return iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(blob: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
            .apply { init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES)) }
        return String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

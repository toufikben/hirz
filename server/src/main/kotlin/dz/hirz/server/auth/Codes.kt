package dz.hirz.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Random tokens and human-typable codes. Only hashes are ever stored. */
object Codes {
    /** No 0/O, 1/I/L: codes are read aloud and typed by hand. */
    private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
    private val random = SecureRandom()

    fun newSourceToken(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun newCode(len: Int = 8): String = buildString { repeat(len) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun newSalt(): String = ByteArray(16).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** PIN hash shared with the companion: sha256(salt + pin). */
    fun pinHash(salt: String, pin: String): String = hash(salt + pin)

    fun matches(raw: String, hash: String): Boolean =
        MessageDigest.isEqual(hash(raw).toByteArray(), hash.toByteArray())

    /** Normalizes a typed code: upper-case, no spaces or dashes. */
    fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }
}

package com.lifetrack.app.data

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Portable, password-protected manual backups. This wraps the existing versioned database JSON;
 * it does not change Room storage, enable automatic backup, or persist the password anywhere.
 *
 * AES-GCM authenticates the ciphertext and all interpretation-critical header fields. A fresh
 * random salt and IV are used for every export. Parsing/size/KDF bounds are checked before KDF
 * work. Callers must run this off the main thread and clear their password CharArray afterwards.
 */
object BackupCrypto {
    const val MAX_PLAINTEXT_BYTES: Int = 10 * 1_024 * 1_024
    const val MAX_ENVELOPE_BYTES: Int = 15 * 1_024 * 1_024
    const val ITERATIONS: Int = 600_000
    private const val MIN_ITERATIONS = 210_000
    private const val MAX_ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val KIND = "lifetrack-encrypted"
    private const val KDF = "PBKDF2-HMAC-SHA256"
    private val random = SecureRandom()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    @Serializable
    private data class Envelope(
        val kind: String,
        val version: Int,
        val kdf: String,
        val iterations: Int,
        val salt: String,
        val iv: String,
        val ciphertext: String
    )

    /** Recognizes the explicit envelope marker; malformed non-envelope input remains plain input. */
    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > MAX_ENVELOPE_BYTES) return false
        return runCatching {
            val root = json.parseToJsonElement(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")) as? JsonObject
            val kind = root?.get("kind") as? JsonPrimitive
            kind?.isString == true && kind.content == KIND
        }.getOrDefault(false)
    }

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        require(plain.size <= MAX_PLAINTEXT_BYTES) { "Backups must be 10 MB or smaller before encryption." }
        require(password.size in 10..1_024) { "Choose a backup password of 10–1,024 characters." }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val encoder = Base64.getEncoder()
        val header = Envelope(KIND, 1, KDF, ITERATIONS, encoder.encodeToString(salt), encoder.encodeToString(iv), "")
        val encrypted = crypt(Cipher.ENCRYPT_MODE, plain, password, salt, iv, header)
        val result = json.encodeToString(header.copy(ciphertext = encoder.encodeToString(encrypted)))
            .toByteArray(Charsets.UTF_8)
        check(result.size <= MAX_ENVELOPE_BYTES) { "The encrypted backup is too large." }
        return result
    }

    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        require(envelope.size <= MAX_ENVELOPE_BYTES) { "Encrypted backups must be 15 MB or smaller." }
        require(password.isNotEmpty() && password.size <= 1_024) { "Enter the backup password (up to 1,024 characters)." }
        val parsed = try {
            json.decodeFromString<Envelope>(envelope.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        } catch (_: Exception) {
            throw IllegalArgumentException("This is not a valid encrypted LifeTrack backup.")
        }
        require(parsed.kind == KIND && parsed.version == 1 && parsed.kdf == KDF) {
            "This encrypted backup format is not supported."
        }
        require(parsed.iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "This backup has unsupported password-protection settings." }
        // Check encoded length before allocating its decoded representation or deriving a key.
        require(parsed.salt.length == encodedLength(SALT_BYTES) && parsed.iv.length == encodedLength(IV_BYTES) &&
            parsed.ciphertext.length <= encodedLength(MAX_PLAINTEXT_BYTES + TAG_BYTES)) {
            "This encrypted backup has invalid or oversized data."
        }
        val decoder = Base64.getDecoder()
        val salt: ByteArray
        val iv: ByteArray
        val ciphertext: ByteArray
        try {
            salt = decoder.decode(parsed.salt)
            iv = decoder.decode(parsed.iv)
            ciphertext = decoder.decode(parsed.ciphertext)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("This encrypted backup contains invalid encoded data.")
        }
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES && ciphertext.size in TAG_BYTES..MAX_PLAINTEXT_BYTES + TAG_BYTES) {
            "This encrypted backup has invalid or oversized data."
        }
        val plain = try {
            crypt(Cipher.DECRYPT_MODE, ciphertext, password, salt, iv, parsed)
        } catch (_: GeneralSecurityException) {
            // Do not distinguish incorrect passwords from authenticated-content tampering.
            throw IllegalArgumentException("The password is incorrect or the backup has been changed or damaged.")
        }
        require(plain.size <= MAX_PLAINTEXT_BYTES) { "The decrypted backup exceeds 10 MB." }
        return plain
    }

    private fun crypt(mode: Int, input: ByteArray, password: CharArray, salt: ByteArray, iv: ByteArray, header: Envelope): ByteArray {
        val specification = PBEKeySpec(password, salt, header.iterations, 256)
        val keyBytes = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
        }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(header))
            return cipher.doFinal(input)
        } finally {
            // Best effort: JCE providers may retain internal copies until garbage collection.
            keyBytes.fill(0)
        }
    }

    private fun aad(header: Envelope): ByteArray = listOf(
        "LifeTrack encrypted backup v1", header.kind, header.version.toString(), header.kdf,
        header.iterations.toString(), header.salt, header.iv
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun encodedLength(bytes: Int): Int = ((bytes + 2) / 3) * 4
}

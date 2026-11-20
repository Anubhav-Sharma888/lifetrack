package com.lifetrack.app.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The single Import action classifies a backup before deciding whether to ask for a password. */
enum class BackupFileKind { PLAIN, ENCRYPTED }

/**
 * Read-only classification. Never writes to Room or asks for credentials.
 * A supported plain backup is fully decoded/validated here; an encrypted envelope is identified
 * by its explicit marker and metadata, then authenticated by BackupCrypto.decrypt on restore.
 */
object BackupInspector {
    fun inspect(bytes: ByteArray): BackupFileKind {
        require(bytes.isNotEmpty()) { "The selected backup is empty." }
        require(bytes.size <= BackupCrypto.MAX_ENVELOPE_BYTES) { "Backup files must be 15 MB or smaller." }
        val source = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: Exception) {
            throw IllegalArgumentException("This backup is not valid UTF-8 JSON.")
        }
        require(BackupValidator.maxJsonDepth(source) <= BackupValidator.MAX_JSON_DEPTH) {
            "This file nests data too deeply to be a LifeTrack backup."
        }
        val root = try {
            BackupValidator.json.parseToJsonElement(source).jsonObject
        } catch (_: Exception) {
            throw IllegalArgumentException("This is not a valid LifeTrack JSON backup.")
        }
        if ("kind" in root) {
            val marker = root["kind"] as? JsonPrimitive
            require(marker?.takeIf { it.isString }?.content == "lifetrack-encrypted") {
                "This encrypted backup format is not supported."
            }
            val version = (root["version"] as? JsonPrimitive)?.intOrNull
            val kdf = (root["kdf"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(version == 1 && kdf == "PBKDF2-HMAC-SHA256") {
                "This encrypted backup format is not supported."
            }
            require(listOf("iterations", "salt", "iv", "ciphertext").all { it in root } &&
                listOf("salt", "iv", "ciphertext").all { (root[it] as? JsonPrimitive)?.isString == true }) {
                "This is not a valid encrypted LifeTrack backup."
            }
            return BackupFileKind.ENCRYPTED
        }
        require(bytes.size <= BackupCrypto.MAX_PLAINTEXT_BYTES) {
            "Plain JSON backups must be 10 MB or smaller."
        }
        try {
            BackupValidator.decode(source)
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (_: Exception) {
            throw IllegalArgumentException("This is not a valid LifeTrack JSON backup.")
        }
        return BackupFileKind.PLAIN
    }
}

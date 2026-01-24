package org.fuchss.matrix.joinlink.helper

import de.connect2x.trixnity.core.model.RoomId
import org.fuchss.matrix.joinlink.Config
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val ENCRYPTION_ALGORITHM = "AES"
private const val KEY_ALGORITHM = "PBKDF2WithHmacSHA256"
private const val TRANSFORMATION_NAME = "AES/GCM/NoPadding"
private val logger = LoggerFactory.getLogger("org.fuchss.matrix.joinlink.Crypto")

/**
 * Encrypt a [RoomId] to a string using the [Config].
 */
fun RoomId.encrypt(config: Config): String = encryptRoomId(config, this)

private val random = SecureRandom()

@OptIn(ExperimentalEncodingApi::class)
private fun encryptRoomId(
    config: Config,
    roomId: RoomId
): String {
    val salt = ByteArray(16)
    random.nextBytes(salt)

    val factory = SecretKeyFactory.getInstance(KEY_ALGORITHM)
    val keySpec: KeySpec = PBEKeySpec(config.encryptionKey.toCharArray(), salt, 1000, 256)
    val secretKey = factory.generateSecret(keySpec)
    val secretKeySpec = SecretKeySpec(secretKey.encoded, ENCRYPTION_ALGORITHM)

    val cipher = Cipher.getInstance(TRANSFORMATION_NAME)
    val iv = ByteArray(cipher.blockSize)
    random.nextBytes(iv)

    cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, GCMParameterSpec(128, iv))

    val encrypted = cipher.doFinal(roomId.full.toByteArray())
    return "${Base64.encode(salt)}|${Base64.encode(iv)}|${Base64.encode(encrypted)}"
}

/**
 * Decrypt a [RoomId] from a string using the [Config].
 */
fun String?.decrypt(config: Config): RoomId? = decryptRoomId(config, this)

@OptIn(ExperimentalEncodingApi::class)
private fun decryptRoomId(
    config: Config,
    content: String?
): RoomId? {
    if (content == null) return null

    return try {
        val (salt, iv, encryptedContent) = content.split("|").map { Base64.decode(it) }

        val factory = SecretKeyFactory.getInstance(KEY_ALGORITHM)
        val keySpec: KeySpec = PBEKeySpec(config.encryptionKey.toCharArray(), salt, 1000, 256)
        val secretKey = factory.generateSecret(keySpec)
        val secretKeySpec = SecretKeySpec(secretKey.encoded, ENCRYPTION_ALGORITHM)

        val cipher = Cipher.getInstance(TRANSFORMATION_NAME)
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, GCMParameterSpec(128, iv))

        val decrypted = cipher.doFinal(encryptedContent)
        RoomId(String(decrypted))
    } catch (e: Exception) {
        logger.error(e.message, e)
        null
    }
}

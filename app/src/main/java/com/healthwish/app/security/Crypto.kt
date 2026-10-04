package com.healthwish.app.security

import java.io.*
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Backup file ko password se AES-256-GCM + ZIP karta hai.
// Bina password ke koi open nahi kar sakta.
object Crypto {
    private const val ITER = 65000
    private const val KEYLEN = 256

    private fun key(password: String, salt: ByteArray) =
        SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITER, KEYLEN)).encoded,
            "AES"
        )

    fun encryptToFile(plainJson: String, outFile: File, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        val enc = c.doFinal(plainJson.toByteArray())
        // file format: SALT(16) + IV(12) + CIPHERTEXT, phir ZIP
        ZipOutputStream(FileOutputStream(outFile)).use { z ->
            z.putNextEntry(ZipEntry("healthwish_backup.dat"))
            z.write(salt); z.write(iv); z.write(enc)
            z.closeEntry()
        }
    }

    fun decryptFromFile(inFile: File, password: String): String {
        ZipInputStream(FileInputStream(inFile)).use { z ->
            z.nextEntry ?: error("bad backup")
            val all = z.readBytes()
            val salt = all.copyOfRange(0, 16)
            val iv = all.copyOfRange(16, 28)
            val data = all.copyOfRange(28, all.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
            return String(c.doFinal(data))
        }
    }
}

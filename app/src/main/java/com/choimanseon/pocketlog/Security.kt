package com.choimanseon.pocketlog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.choimanseon.pocketlog.data.PocketDb
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private fun derive(secret: String, salt: ByteArray): ByteArray =
    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(secret.toCharArray(), salt, 120_000, 256)).encoded

private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)
private fun String.unb64() = Base64.decode(this, Base64.NO_WRAP)

/** App lock PIN: only a salted PBKDF2 hash is stored. */
object Pin {
    const val LENGTH = 6
    val isSet get() = app.prefs.pinHash.isNotEmpty()

    fun set(pin: String) {
        val salt = random(16)
        app.prefs.pinSalt = salt.b64()
        app.prefs.pinHash = derive(pin, salt).b64()
    }

    fun check(pin: String): Boolean {
        if (!isSet) return true
        return MessageDigest.isEqual(derive(pin, app.prefs.pinSalt.unb64()), app.prefs.pinHash.unb64())
    }

    fun clear() {
        app.prefs.pinHash = ""
        app.prefs.pinSalt = ""
        app.prefs.biometric = false
    }
}

class WrongPassword : Exception("비밀번호가 맞지 않아요")

/** Encrypted backup file = "PLBK1" + salt(16) + iv(12) + AES-GCM(SQLite database file). */
object Backup {
    private val MAGIC = "PLBK1".toByteArray()

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(derive(password, salt), "AES"), GCMParameterSpec(128, iv))
    }

    fun export(context: Context, uri: Uri, password: String) {
        app.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val plain = context.getDatabasePath(PocketDb.NAME).readBytes()
        val salt = random(16)
        val iv = random(12)
        val sealed = cipher(Cipher.ENCRYPT_MODE, password, salt, iv).doFinal(plain)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(MAGIC + salt + iv + sealed) }
    }

    /** Replaces the database and restarts the app. */
    fun restore(context: Context, uri: Uri, password: String) {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        require(bytes.size > MAGIC.size + 28 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Pocketlog 백업 파일이 아니에요" }
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = bytes.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, password, salt, iv).doFinal(bytes, MAGIC.size + 28, bytes.size - MAGIC.size - 28)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassword()
        }
        require(String(plain, 0, 15) == "SQLite format 3") { "백업 파일이 손상됐어요" }
        app.db.close()
        val path = context.getDatabasePath(PocketDb.NAME)
        path.writeBytes(plain)
        File(path.path + "-wal").delete()
        File(path.path + "-shm").delete()
        restart(context)
    }

    suspend fun reset() {
        app.db.clearAllTables()
        app.dao.seedIfEmpty()
        File(app.filesDir, "scans").deleteRecursively()
        app.prefs.budgetAlert = ""
    }

    private fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}

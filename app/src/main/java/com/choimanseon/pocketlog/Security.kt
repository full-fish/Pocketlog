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

/**
 * The Drive backup password kept for 매일 자동 백업 (TODO #56), encrypted with a key that never leaves the phone's
 * keystore. Restored on another phone (Google auto backup) it can't be opened, so the next manual backup asks again.
 */
object Vault {
    private const val ALIAS = "pocketlog-backup-password"

    private fun key(): javax.crypto.SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build()
        return javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    fun seal(secret: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv.b64() + ":" + c.doFinal(secret.toByteArray()).b64()
    }

    fun open(sealed: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.substringBefore(':').unb64())) }
        String(c.doFinal(sealed.substringAfter(':').unb64()))
    }.getOrNull()
}

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

/**
 * Encrypted backup file = "PLBK1" + salt(16) + iv(12) + AES-GCM(SQLite database file).
 * A plain Pocketlog database file restores too, without a password: the 똑똑가계부 history converted on a computer (TODO #29).
 */
object Backup {
    private val MAGIC = "PLBK1".toByteArray()
    private const val SQLITE = "SQLite format 3"

    fun isPlainDb(context: Context, uri: Uri) = context.contentResolver.openInputStream(uri)!!.use {
        val head = ByteArray(SQLITE.length)
        java.io.DataInputStream(it).readFully(head) // readNBytes needs API 33
        String(head) == SQLITE
    }

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(derive(password, salt), "AES"), GCMParameterSpec(128, iv))
    }

    /** The whole database encrypted with [password]: the .plbak file, also what goes to Google Drive. */
    fun sealed(context: Context, password: String): ByteArray {
        app.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val plain = context.getDatabasePath(PocketDb.NAME).readBytes()
        val salt = random(16)
        val iv = random(12)
        return MAGIC + salt + iv + cipher(Cipher.ENCRYPT_MODE, password, salt, iv).doFinal(plain)
    }

    fun export(context: Context, uri: Uri, password: String) {
        val bytes = sealed(context, password)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    fun restore(context: Context, uri: Uri, password: String?) =
        restore(context, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }, password)

    /** Replaces the database and restarts the app. [password] is null for a plain database file. */
    fun restore(context: Context, bytes: ByteArray, password: String?) {
        if (password == null) return replaceWith(context, bytes.also { requirePocketlog(context, it) })
        require(bytes.size > MAGIC.size + 28 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Pocketlog 백업 파일이 아니에요" }
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = bytes.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, password, salt, iv).doFinal(bytes, MAGIC.size + 28, bytes.size - MAGIC.size - 28)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassword()
        }
        require(String(plain, 0, SQLITE.length) == SQLITE) { "백업 파일이 손상됐어요" }
        replaceWith(context, plain)
    }

    /** A 똑똑가계부 .db is SQLite too: only a database Room made for Pocketlog is taken. Room migrates an older one on open. */
    private fun requirePocketlog(context: Context, bytes: ByteArray) {
        val file = File(context.cacheDir, "restore-check.db").apply { writeBytes(bytes) }
        try {
            val tables = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
            }
            require(tables.containsAll(listOf("room_master_table", "Tx", "Category"))) { "Pocketlog 데이터 파일이 아니에요. 똑똑가계부 파일은 '똑똑가계부에서 가져오기'로 열어 주세요" }
        } finally {
            file.delete()
        }
    }

    private fun replaceWith(context: Context, plain: ByteArray) {
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

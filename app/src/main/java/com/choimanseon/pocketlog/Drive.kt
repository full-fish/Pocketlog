package com.choimanseon.pocketlog

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 백업 · 복구 → Google 드라이브 (TODO #34): the same password-encrypted .plbak file, kept in the user's own Drive.
 * Google Play services shows the account picker and the consent screen and hands back an access token; Drive itself is
 * plain REST. Scope drive.file: the app sees only the files it made, and the user sees them in 내 드라이브.
 * Needs an Android OAuth client (package name + signing SHA-1) in a Google Cloud project with the Drive API on (README).
 * Backups go into a "Pocketlog" folder and only the newest [KEEP] stay (TODO #56).
 */
object Drive {
    class File(val id: String, val name: String, val modified: Instant, val size: Long)

    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val PREFIX = "Pocketlog 백업"
    private const val FOLDER = "Pocketlog"
    private const val KEEP = 5

    fun client(context: Context) = Identity.getAuthorizationClient(context)

    /** Has a token when access was granted before; otherwise [AuthorizationResult.getPendingIntent] asks the user. */
    suspend fun authorize(context: Context): AuthorizationResult = suspendCancellableCoroutine { c ->
        client(context).authorize(AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build())
            .addOnSuccessListener { c.resume(it) }
            .addOnFailureListener { c.resumeWithException(it) }
    }

    /** The app's own "Pocketlog" folder (drive.file sees only folders the app made), made the first time. */
    private fun folder(token: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val q = "name = '$FOLDER' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val found = JSONObject(String(call(token, "GET", "https://www.googleapis.com/drive/v3/files?q=${enc(q)}&fields=${enc("files(id)")}"))).optJSONArray("files")
        if (found != null && found.length() > 0) return found.getJSONObject(0).getString("id")
        val meta = JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder").toString().toByteArray()
        return JSONObject(String(call(token, "POST", "https://www.googleapis.com/drive/v3/files?fields=id", "application/json; charset=UTF-8", meta))).getString("id")
    }

    /** Uploads into the Pocketlog folder, then removes all but the newest [KEEP] backups (older ones outside the folder too). */
    fun upload(token: String, bytes: ByteArray) {
        val parent = folder(token)
        val name = "$PREFIX ${DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm").withZone(ZoneId.systemDefault()).format(Instant.now())}.plbak"
        val boundary = "pocketlog${System.nanoTime()}"
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            write(JSONObject().put("name", name).put("mimeType", "application/octet-stream").put("parents", org.json.JSONArray().put(parent)).toString().toByteArray())
            write("\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        call(token, "POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", "multipart/related; boundary=$boundary", body)
        list(token).drop(KEEP).forEach { call(token, "DELETE", "https://www.googleapis.com/drive/v3/files/${it.id}") }
    }

    /**
     * 매일 자동 백업 (TODO #56): once a day, with the password kept from the last manual backup. Skipped quietly when there is
     * none yet, when Google wants the consent screen again, or offline (the next app start or 9:00 run tries again).
     */
    suspend fun autoBackup(context: Context, now: java.time.LocalDateTime = java.time.LocalDateTime.now()) {
        val p = app.prefs
        if (!p.driveAuto || p.driveSecret.isEmpty() || p.driveLast.startsWith(now.toLocalDate().toString())) return
        val password = Vault.open(p.driveSecret) ?: return
        val token = authorize(context).takeIf { !it.hasResolution() }?.accessToken ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { upload(token, Backup.sealed(context, password)) }
        p.driveLast = now.withNano(0).toString()
    }

    /** This app's backups, newest first. */
    fun list(token: String): List<File> {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=" + enc("name contains '$PREFIX' and trashed = false") +
            "&orderBy=" + enc("modifiedTime desc") + "&fields=" + enc("files(id,name,modifiedTime,size)") + "&pageSize=30"
        val files = JSONObject(String(call(token, "GET", url))).optJSONArray("files") ?: return emptyList()
        return List(files.length()) { i ->
            val f = files.getJSONObject(i)
            File(f.getString("id"), f.getString("name"), Instant.parse(f.getString("modifiedTime")), f.optString("size").toLongOrNull() ?: 0)
        }
    }

    fun download(token: String, id: String): ByteArray = call(token, "GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media")

    private fun call(token: String, method: String, url: String, contentType: String? = null, body: ByteArray? = null): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val bytes = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) throw IOException("Google 드라이브 오류 ($code)")
            return bytes
        } finally {
            conn.disconnect()
        }
    }
}

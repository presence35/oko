package ua.ukrainedrones

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// Single place to point at your own update server. Host version.json + the APK there.
const val UPDATE_BASE_URL = "https://odesaplay.com.ua/other_apps/ukrainedrones/"

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notesEn: String,
    val notesUa: String,
    val sha256: String? = null
)

sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Downloaded(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String?) : UpdateState
}

class UpdateManager(private val context: Context) {

    companion object {
        /** True when [candidate] is a semantically newer version name than [installed]; false when either is unparseable. */
        internal fun versionNameGreater(candidate: String, installed: String): Boolean {
            val a = candidate.split('.').mapNotNull { it.toIntOrNull() }
            val b = installed.split('.').mapNotNull { it.toIntOrNull() }
            val len = maxOf(a.size, b.size)
            for (i in 0 until len) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Fetches version.json and returns Available only when the server has a newer build. */
    suspend fun check(): UpdateState = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(UPDATE_BASE_URL + "version.json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateState.Failed("HTTP ${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                val notes = json.optJSONObject("notes")
                val sha256Val = json.optString("sha256").takeIf { it.isNotBlank() }
                val latest = UpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName"),
                    apkUrl = json.getString("apkUrl"),
                    notesEn = notes?.optString("en").orEmpty(),
                    notesUa = notes?.optString("ua").orEmpty(),
                    sha256 = sha256Val
                )
                if (latest.versionCode > BuildConfig.VERSION_CODE ||
                    (latest.versionCode == BuildConfig.VERSION_CODE &&
                        versionNameGreater(latest.versionName, BuildConfig.VERSION_NAME))
                ) {
                    UpdateState.Available(latest)
                } else {
                    UpdateState.UpToDate
                }
            }
        } catch (e: Exception) {
            UpdateState.Failed(e.message)
        }
    }

    /** Fetches the shelter list copy from the update server; null on failure. */
    suspend fun fetchSheltersJson(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(UPDATE_BASE_URL + "shelters.json").build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Streams the APK into cacheDir/updates/, reporting 0..1 progress, and validates the result. */
    suspend fun download(info: UpdateInfo, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(info.apkUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")
            val total = body.contentLength()
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "app-update.apk")
            var downloaded = 0L

            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) onProgress(downloaded.toFloat() / total)
                    }
                }
            }

            if (total > 0 && downloaded != total) {
                target.delete()
                throw IOException("Incomplete download")
            }

            if (!isLikelyApk(target)) {
                target.delete()
                throw IOException("Downloaded file is not a valid APK — check the apkUrl in version.json")
            }

            // SHA-256 verification when digest is provided in version.json
            if (info.sha256 != null) {
                val computedHash = calculateSha256(target)
                if (!computedHash.equals(info.sha256.trim(), ignoreCase = true)) {
                    target.delete()
                    throw IOException("APK SHA-256 mismatch (expected: ${info.sha256}, got: $computedHash)")
                }
            }

            // Verify package integrity using PackageManager
            val pm = context.packageManager
            val archiveInfo = pm.getPackageArchiveInfo(target.absolutePath, 0)
            if (archiveInfo == null || archiveInfo.packageName != context.packageName) {
                target.delete()
                throw IOException("APK package validation failed (package: ${archiveInfo?.packageName})")
            }

            target
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isLikelyApk(file: File): Boolean {
        if (file.length() < 1_000_000L) return false
        val magic = ByteArray(2)
        RandomAccessFile(file, "r").use { it.readFully(magic) }
        return magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() // "PK" — ZIP/APK signature
    }

    fun canRequestInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Builds the installer intent for the downloaded APK, or null when permission is missing. */
    fun buildInstallIntent(file: File): Intent? {
        if (!canRequestInstall()) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

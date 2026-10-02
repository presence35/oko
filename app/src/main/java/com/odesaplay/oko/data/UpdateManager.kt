package com.odesaplay.oko

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

// Single place to point at your own update server. Host version.json + the APK there.
const val UPDATE_BASE_URL = "https://odesaplay.com.ua/other_apps/oko/"

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notesEn: String,
    val notesUa: String,
    val notesRu: String = "",
    val sha256: String? = null
) {
    fun notes(lang: AppLanguage): String = lang.pick(notesUa, notesEn, notesRu.ifBlank { notesEn })
}

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
        private const val TAG = "UpdateManager"

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

        /**
         * Only the configured origin may serve the APK. A TLS-verified `version.json` can still
         * be tampered with at the origin, and without this a tampered one would also get to
         * choose *where* the next APK is fetched from.
         */
        internal fun isTrustedApkUrl(url: String, baseUrl: String = UPDATE_BASE_URL): Boolean {
            val candidate = runCatching { URI(url) }.getOrNull() ?: return false
            val base = runCatching { URI(baseUrl) }.getOrNull() ?: return false
            return candidate.scheme.equals("https", ignoreCase = true) &&
                candidate.host?.equals(base.host, ignoreCase = true) == true &&
                candidate.port == base.port
        }

        /** A supplied digest must match exactly; a mismatch is corruption or substitution. */
        internal fun sha256Matches(expected: String, computed: String): Boolean =
            expected.trim().equals(computed.trim(), ignoreCase = true)

        /**
         * The archive must carry the same signing key as the installed app. `null` means the
         * archive's signers could not be read at all, which is just as disqualifying.
         */
        internal fun signersMatch(archive: Set<String>?, installed: Set<String>?): Boolean =
            archive != null && installed != null && archive.isNotEmpty() && archive == installed
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
                val apkUrl = json.getString("apkUrl")
                if (!isTrustedApkUrl(apkUrl)) {
                    return@withContext UpdateState.Failed("version.json points at an untrusted download URL")
                }
                val latest = UpdateInfo(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName"),
                    apkUrl = apkUrl,
                    notesEn = notes?.optString("en").orEmpty(),
                    notesUa = notes?.optString("ua").orEmpty(),
                    notesRu = notes?.optString("ru").orEmpty(),
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

            // version.json is served from the same origin as the APK, so the digest is a
            // corruption/substitution check rather than a security boundary: a mismatch is
            // fatal, an absent digest (an older version.json) is tolerated because the archive
            // pre-flight below is what actually gates the install.
            val expected = info.sha256
            if (expected == null) {
                Log.w(TAG, "version.json carries no sha256 — skipping the checksum check")
            } else if (!sha256Matches(expected, calculateSha256(target))) {
                target.delete()
                throw IOException("APK checksum mismatch — the download does not match version.json")
            }

            try {
                verifyArchive(target)
            } catch (e: Exception) {
                target.delete()
                throw e
            }

            target
        }
    }

    /**
     * Pre-flight on the artifact itself, before the system installer sees it. Everything here
     * reads the APK rather than version.json, so a tampered manifest cannot talk its way past
     * it. The installer enforces signatures anyway — this only turns a scary system error into
     * a clear refusal.
     */
    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File) {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Downloaded file is not a readable APK")
        if (archive.packageName != context.packageName) {
            throw IOException("APK belongs to ${archive.packageName}, not ${context.packageName}")
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        if (archiveCode <= BuildConfig.VERSION_CODE.toLong()) {
            throw IOException("APK version $archiveCode is not newer than the installed ${BuildConfig.VERSION_CODE}")
        }
        // Signing info needs API 28; below that the installer is the only gate.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val installed = pm.getPackageInfo(context.packageName, flags)
            if (!signersMatch(signerHashes(archive), signerHashes(installed))) {
                throw IOException("APK is signed with a different key than the installed app")
            }
        }
    }

    /** SHA-256 of each APK signer certificate; `null` when the platform will not expose them. */
    private fun signerHashes(info: PackageInfo): Set<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        return signers.mapTo(mutableSetOf()) { signer ->
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                .joinToString("") { "%02x".format(it) }
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
    @Suppress("DEPRECATION")
    fun buildInstallIntent(file: File): Intent? {
        if (!canRequestInstall()) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        // The system installer's own action: unlike a bare ACTION_VIEW + mime type, it cannot be
        // claimed by an arbitrary app angling for the URI read grant.
        return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setData(uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

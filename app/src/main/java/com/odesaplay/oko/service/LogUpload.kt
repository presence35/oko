package com.odesaplay.oko

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Upload lifecycle of a log bundle. Sealed, so "is it working" is never a pile of booleans and
 * the UI cannot show two contradictory states.
 */
sealed interface UploadState {
    data object Idle : UploadState
    data object Building : UploadState
    data class Sending(val bytes: Long) : UploadState
    data class Done(val fileName: String) : UploadState
    data class Failed(val reason: String) : UploadState
}

/**
 * Ships a [LogBundle] to the beta drop box. Deliberately a separate engine from the log ring
 * buffers: nothing here is ever called unless a human taps, so there is no background cost and
 * no way for a bundle to leak without an explicit tap.
 *
 * Ships the JSON body straight to [ENDPOINT]; the server derives the filename itself, so the
 * client is never trusted with a path. No shared secret: a token embedded in the APK is
 * extractable by anyone who unpacks it, so it would guard nothing while adding a setup step.
 * See `server/upload.php`.
 */
object LogUpload {

    private const val ENDPOINT = "https://odesaplay.com.ua/other_apps/oko/upload.php"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UploadState>(UploadState.Idle)
    val state: StateFlow<UploadState> = _state.asStateFlow()

    /** No-op if a bundle is already in flight, so a double tap can't produce two uploads. */
    fun upload(context: Context) {
        if (_state.value is UploadState.Building || _state.value is UploadState.Sending) return
        val app = context.applicationContext
        scope.launch {
            try {
                _state.value = UploadState.Building
                val bundle = LogBundle.build(app, UserPrefs(app))
                val body = bundle.toString().toRequestBody(JSON_MEDIA)
                _state.value = UploadState.Sending(body.contentLength())
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .header("X-Log-Name", LogBundle.fileName(app))
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        _state.value = UploadState.Failed("HTTP ${response.code}")
                        return@use
                    }
                    // A 2xx that isn't our own contract means something answered instead of
                    // upload.php — an error page, a captive portal, a host that returns 200 for
                    // everything. Falling back to the locally-computed name there would tell
                    // the tester their logs arrived when nothing was ever written.
                    val name = runCatching { JSONObject(text) }.getOrNull()
                        ?.takeIf { it.optBoolean("ok") }
                        ?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                    if (name == null) {
                        _state.value = UploadState.Failed(
                            "unexpected reply (HTTP ${response.code} ${response.header("Content-Type") ?: "?"})"
                        )
                        return@use
                    }
                    _state.value = UploadState.Done(name)
                }
            } catch (e: Exception) {
                _state.value = UploadState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun dismiss() {
        _state.value = UploadState.Idle
    }
}

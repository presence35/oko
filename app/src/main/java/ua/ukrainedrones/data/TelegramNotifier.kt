package ua.ukrainedrones.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ua.ukrainedrones.BuildConfig
import java.util.concurrent.TimeUnit

object TelegramNotifier {

    private const val TAG = "TelegramNotifier"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun sendSdkChanged(oldHash: String, newHash: String) {
        val token = BuildConfig.TELEGRAM_BOT_TOKEN
        val chatId = BuildConfig.TELEGRAM_CHAT_ID
        if (token.isBlank() || chatId.isBlank()) {
            Log.w(TAG, "Telegram credentials not configured")
            return
        }
        val text = buildString {
            appendLine("\u26A0\uFE0F Neptun SDK changed")
            appendLine("Old: ${oldHash.take(16)}")
            appendLine("New: ${newHash.take(16)}")
            append("View: https://neptun.in.ua/sdk/build-manifest.json")
        }
        send(token, chatId, text)
    }

    private fun send(token: String, chatId: String, text: String) {
        scope.launch {
            try {
                val json = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", "HTML")
                }
                val body = json.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "Telegram send failed: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Telegram send error: ${e.message}")
            }
        }
    }
}

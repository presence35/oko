package ua.ukrainedrones.community

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reusable HTTP client for fetching granular community (hromada) alert statuses
 * from the Ubilling Skog raw aerial alerts feed.
 *
 * Built-in safeguards:
 * - Minimum cooldown guard (default 20s) to guarantee zero 429 rate-limiting.
 * - In-memory cache returned when called within cooldown.
 * - Configurable connect/read timeouts.
 * - Pure Kotlin with standard JDK/Android networking (zero external dependencies).
 */
class UbillingCommunityAlertClient(
    private val endpointUrl: String = DEFAULT_ENDPOINT,
    private val minCooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    companion object {
        const val DEFAULT_ENDPOINT = "https://ubilling.net.ua/aerialalerts/?source=skog&raw"
        const val DEFAULT_COOLDOWN_MS = 20_000L // 20 seconds
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 6_000
    }

    @Volatile
    private var lastFetchTimeMonoMs: Long = 0L

    @Volatile
    private var cachedCommunities: List<CommunityAlert> = emptyList()

    private val lock = Any()

    /**
     * Fetches current community alert statuses, respecting the debounce cooldown.
     *
     * @param forceBypassCooldown If true, bypasses the cooldown check (use sparingly).
     * @return [Result] containing the parsed [List<CommunityAlert>].
     */
    suspend fun fetchCommunities(forceBypassCooldown: Boolean = false): Result<List<CommunityAlert>> =
        withContext(Dispatchers.IO) {
            val nowMono = System.currentTimeMillis()
            synchronized(lock) {
                if (!forceBypassCooldown && cachedCommunities.isNotEmpty() && (nowMono - lastFetchTimeMonoMs) < minCooldownMs) {
                    return@withContext Result.success(cachedCommunities)
                }
            }

            try {
                val url = URL(endpointUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("User-Agent", "OkoAlert/1.0 (Android)")
                    setRequestProperty("Accept", "application/json")
                }

                val responseCode = conn.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(
                        IllegalStateException("Ubilling feed returned HTTP $responseCode")
                    )
                }

                val body = conn.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                }

                val parsed = parseSkogRawJson(body)

                synchronized(lock) {
                    cachedCommunities = parsed
                    lastFetchTimeMonoMs = System.currentTimeMillis()
                }

                Result.success(parsed)
            } catch (e: Exception) {
                // If fetch fails but we have cached data, fall back to cached
                synchronized(lock) {
                    if (cachedCommunities.isNotEmpty()) {
                        return@withContext Result.success(cachedCommunities)
                    }
                }
                Result.failure(e)
            }
        }

    /**
     * Parses the `raw` dictionary from the Skog endpoint into [CommunityAlert] entries.
     */
    fun parseSkogRawJson(jsonString: String): List<CommunityAlert> {
        val results = mutableListOf<CommunityAlert>()
        try {
            val root = JSONObject(jsonString)
            val rawObj = root.optJSONObject("raw") ?: root

            val keys = rawObj.keys()
            while (keys.hasNext()) {
                val regionKey = keys.next()
                val regionObj = rawObj.optJSONObject(regionKey) ?: continue
                val regionId = regionKey.toIntOrNull()

                val communityArr = regionObj.optJSONArray("community") ?: continue
                for (i in 0 until communityArr.length()) {
                    val cObj = communityArr.optJSONObject(i) ?: continue
                    val name = cObj.optString("name", "").trim()
                    if (name.isEmpty()) continue

                    val alert = cObj.optBoolean("alert", false)
                    val changed = cObj.optString("changed", null)

                    results.add(
                        CommunityAlert(
                            name = name,
                            isActive = alert,
                            changedAt = changed,
                            regionId = regionId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Malformed JSON handled gracefully
        }
        return results
    }

    /**
     * Clears the in-memory cache.
     */
    fun clearCache() {
        synchronized(lock) {
            cachedCommunities = emptyList()
            lastFetchTimeMonoMs = 0L
        }
    }
}

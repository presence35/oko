package com.odesaplay.oko

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * The one built log bundle — and the only place one is ever built.
 *
 * This exists so the Debug tab's promise ("what you read is what ships") is a structural fact
 * rather than a good intention: [LogUpload] does not build its own payload, it takes [ensure]'s,
 * so the bytes on screen and the bytes in the POST body are the same string. The previous shape —
 * a build inside the uploader — made a preview impossible without one of the two silently
 * diverging.
 *
 * `Ready` carries **both** the parsed bundle and its serialized body. The body is what ships and
 * what the size is measured from, so the preview cannot render one payload while the network sees
 * another; the parsed copy saves the Debug tab re-parsing ~100 KB per recomposition.
 *
 * Serialized state, not a pile of booleans, so the tab cannot show "building" and a byte count at
 * the same time. [TTL_MS] bounds the other lie: a bundle previewed, left on screen for half an
 * hour, then sent would describe a phone that has since changed (battery, memory, log rows), so
 * anything older than the TTL is rebuilt — and the tab says so rather than pretending.
 */
object LogSnapshot {

    /** Five minutes: long enough to read the tab and tap Send, short enough that the battery and
     *  memory figures are still about the phone in the tester's hand. */
    const val TTL_MS = 5 * 60 * 1000L

    sealed interface State {
        data object Empty : State
        data object Building : State

        data class Ready(
            val bundle: JSONObject,
            /** Exactly the bytes that will be POSTed. */
            val body: String,
            val fileName: String,
            val bytes: Int,
            val builtAtMillis: Long
        ) : State

        data class Failed(val reason: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One lock over the whole build-and-publish step: a refresh racing a Send must not produce two
     *  bundles, and a Send that started before a refresh must ship the one it read. */
    private val lock = Mutex()

    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The bundle to ship and to show. Reuses the memoized one while it is younger than [TTL_MS];
     * otherwise builds. Throws if the build failed (the failure is also published to [state]), so
     * the caller's own error handling owns the wording.
     */
    suspend fun ensure(context: Context, nowMillis: Long = System.currentTimeMillis()): State.Ready =
        lock.withLock { fresh(nowMillis) ?: build(context.applicationContext, nowMillis) }

    /** Rebuild regardless of age. Fire-and-forget for the Debug tab's refresh button. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { lock.withLock { runCatching { build(app, System.currentTimeMillis()) } } }
    }

    private fun fresh(nowMillis: Long): State.Ready? =
        (_state.value as? State.Ready)?.takeIf { nowMillis - it.builtAtMillis < TTL_MS }

    private suspend fun build(app: Context, nowMillis: Long): State.Ready {
        _state.value = State.Building
        val bundle = try {
            LogBundle.build(app, UserPrefs(app))
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            throw e
        }
        val body = bundle.toString()
        return State.Ready(
            bundle = bundle,
            body = body,
            // The bundle's own claim about its name, so the header the server sanitizes and the
            // `fileName` inside the JSON cannot disagree.
            fileName = bundle.optString("fileName", LogBundle.fileName(app)),
            bytes = body.toByteArray(Charsets.UTF_8).size,
            builtAtMillis = nowMillis
        ).also { _state.value = it }
    }
}

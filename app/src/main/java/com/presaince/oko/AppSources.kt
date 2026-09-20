package com.presaince.oko

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.presaince.oko.source.NeptunSource
import com.presaince.oko.source.SourceRegistry
import com.presaince.oko.source.TestSource

/** App-wide source composition root: builds and owns the [SourceRegistry] with the single
 *  production source (NEPTUN) plus the peace-time Test simulator. Consumers only ever read
 *  [registry]; the underlying transports/decoders stay private to each source. */
object AppSources {
    private var _registry: SourceRegistry? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether the app process is foregrounded — drives REST polling cadence. */
    private val _appForeground = MutableStateFlow(true)
    val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()

    val registry: SourceRegistry
        get() = _registry ?: throw IllegalStateException("AppSources.init() not called")

    @Synchronized
    fun init(context: Context) {
        if (_registry != null) return
        appContext = context.applicationContext
        val registry = SourceRegistry()
        registry.register(NeptunSource(context), scope)
        registry.register(TestSource(), scope)
        _registry = registry
    }

    @Volatile
    private var appContext: Context? = null

    /**
     * Idempotent best-effort init for entry points that must never crash when the
     * process was force-stopped (registry is null) — e.g. Activity.onStart racing
     * the foreground-service start. Real init still happens in MainViewModel /
     * AlertService; this only guarantees [registry] never throws on a cold start.
     */
    @Synchronized
    fun ensureInit(context: Context) {
        runCatching { init(context) }
    }

    fun setAppForeground(foreground: Boolean) {
        _appForeground.value = foreground
    }

    @Synchronized
    fun clear() {
        _registry?.let { reg ->
            for (source in reg.sources.value) {
                reg.unregister(source)
            }
        }
        _registry = null
    }
}
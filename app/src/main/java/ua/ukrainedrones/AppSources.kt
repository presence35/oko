package ua.ukrainedrones

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.ukrainedrones.source.NeptunSource
import ua.ukrainedrones.source.SourceRegistry
import ua.ukrainedrones.source.TestSource

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
        val registry = SourceRegistry()
        registry.register(NeptunSource(context), scope)
        registry.register(TestSource(), scope)
        _registry = registry
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
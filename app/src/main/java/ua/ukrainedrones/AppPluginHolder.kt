package ua.ukrainedrones

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ua.ukrainedrones.connection.ConnectionHolder
import ua.ukrainedrones.plugins.NeptunPlugin
import ua.ukrainedrones.plugins.PluginRegistry
import ua.ukrainedrones.plugins.UbillingPlugin

object AppPluginHolder {
    private var _registry: PluginRegistry? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether the app process is foregrounded — drives REST polling cadence. */
    private val _appForeground = MutableStateFlow(true)
    val appForeground: StateFlow<Boolean> = _appForeground.asStateFlow()

    val registry: PluginRegistry
        get() = _registry ?: throw IllegalStateException("AppPluginHolder.init() not called")

    @Synchronized
    fun init(context: Context) {
        if (_registry != null) return
        val client = ConnectionHolder.getClient(context)
        val neptun = NeptunPlugin(client)
        val registry = PluginRegistry().also { it.register(neptun, scope) }
        val ubilling = UbillingPlugin(
            primaryHealthy = registry.wsHealthy,
            appForeground = _appForeground
        )
        registry.register(ubilling, scope)
        _registry = registry
    }

    fun setAppForeground(foreground: Boolean) {
        _appForeground.value = foreground
    }

    @Synchronized
    fun clear() {
        _registry?.let { reg ->
            for (plugin in reg.plugins.value) {
                reg.unregister(plugin)
            }
        }
        _registry = null
    }
}

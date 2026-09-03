package ua.ukrainedrones

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ua.ukrainedrones.connection.ConnectionHolder
import ua.ukrainedrones.plugins.NeptunPlugin
import ua.ukrainedrones.plugins.PluginRegistry

object AppPluginHolder {
    private var _registry: PluginRegistry? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val registry: PluginRegistry
        get() = _registry ?: throw IllegalStateException("AppPluginHolder.init() not called")

    @Synchronized
    fun init(context: Context) {
        if (_registry != null) return
        val client = ConnectionHolder.getClient(context)
        val plugin = NeptunPlugin(client)
        _registry = PluginRegistry().also { it.register(plugin, scope) }
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

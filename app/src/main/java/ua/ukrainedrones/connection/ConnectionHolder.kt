package ua.ukrainedrones.connection

import android.content.Context

object ConnectionHolder {
    private var _client: NeptunConnectionClient? = null
    private var _supervisor: ConnectionSupervisor? = null

    @Synchronized
    fun getClient(context: Context): NeptunConnectionClient =
        _client ?: NeptunConnectionClient(context.applicationContext).also { _client = it }

    @Synchronized
    fun getSupervisor(context: Context): ConnectionSupervisor =
        _supervisor ?: ConnectionSupervisor(context.applicationContext, getClient(context))
            .also { _supervisor = it }

    @Synchronized
    fun clear() {
        _client?.stop()
        _supervisor?.stop()
        _client = null
        _supervisor = null
    }
}

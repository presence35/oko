package ua.ukrainedrones.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.ukrainedrones.OblastAlert
import ua.ukrainedrones.engine.NEPTUN_TYPES
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.PluginConnectionState
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.ThreatSource

private class FakePlugin(
    override val id: String,
    threatsInit: List<NormalizedThreat> = emptyList(),
    alertsInit: List<OblastAlert> = emptyList(),
    connectionInit: PluginConnectionState = PluginConnectionState.DISCONNECTED
) : ThreatSource {
    override val name = id
    override val typeCatalog: Map<String, ThreatProps> = NEPTUN_TYPES
    private val _threats = MutableStateFlow(threatsInit)
    override val threats: StateFlow<List<NormalizedThreat>> = _threats.asStateFlow()
    private val _alerts = MutableStateFlow(alertsInit)
    override val alerts: StateFlow<List<OblastAlert>> = _alerts.asStateFlow()
    private val _connectionState = MutableStateFlow(connectionInit)
    override val connectionState: StateFlow<PluginConnectionState> = _connectionState.asStateFlow()
    var started = false; private set
    var stopped = false; private set
    override fun start(scope: CoroutineScope) { started = true }
    override fun stop() { stopped = true }

    fun emitThreats(list: List<NormalizedThreat>) { _threats.value = list }
    fun emitAlerts(list: List<OblastAlert>) { _alerts.value = list }
    fun emitConnection(state: PluginConnectionState) { _connectionState.value = state }
}

class PluginRegistryTest {

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun threat(id: String, type: String = "shahed") = NormalizedThreat(
        id = id, type = type, title = "", region = null, district = null, locality = null,
        lat = 50.0, lon = 30.0, heading = null, bearingDeg = null, status = "active",
        advisory = false, areaOnly = false, confirmations = 1, reliability = "UNKNOWN",
        count = 0, explanationShort = null, speedKmh = null, uncertaintyKm = null,
        positionQuality = null, confirmedAtMillis = null, updatedAtMillis = null, trail = emptyList()
    )

    @Test
    fun `register starts plugin`() {
        val registry = PluginRegistry()
        val plugin = FakePlugin("test")
        registry.register(plugin, testScope())
        assertTrue(plugin.started)
    }

    @Test
    fun `unregister stops plugin`() {
        val registry = PluginRegistry()
        val plugin = FakePlugin("test")
        registry.register(plugin, testScope())
        registry.unregister(plugin)
        assertTrue(plugin.stopped)
    }

    @Test
    fun `single plugin threats flow through`() {
        val registry = PluginRegistry()
        val plugin = FakePlugin("a", threatsInit = listOf(threat("t1"), threat("t2")))
        registry.register(plugin, testScope())
        assertEquals(2, registry.allThreats.value.size)
    }

    @Test
    fun `multiple plugins merge threats`() {
        val registry = PluginRegistry()
        val a = FakePlugin("a", threatsInit = listOf(threat("t1")))
        val b = FakePlugin("b", threatsInit = listOf(threat("t2"), threat("t3")))
        registry.register(a, testScope())
        registry.register(b, testScope())
        assertEquals(3, registry.allThreats.value.size)
    }

    @Test
    fun `multiple plugins merge alerts`() {
        val registry = PluginRegistry()
        val a = FakePlugin("a", alertsInit = listOf(OblastAlert("k1", "n1", "Odesa", null)))
        val b = FakePlugin("b", alertsInit = listOf(OblastAlert("k2", "n2", "Kyiv", null)))
        registry.register(a, testScope())
        registry.register(b, testScope())
        assertEquals(2, registry.allAlerts.value.size)
    }

    @Test
    fun `worst connection state wins`() {
        val registry = PluginRegistry()
        val a = FakePlugin("a", connectionInit = PluginConnectionState.CONNECTED)
        val b = FakePlugin("b", connectionInit = PluginConnectionState.DEGRADED)
        registry.register(a, testScope())
        registry.register(b, testScope())
        assertEquals(PluginConnectionState.DEGRADED, registry.connectionState.value)
    }

    @Test
    fun `type catalog merges from all plugins`() {
        val registry = PluginRegistry()
        val a = FakePlugin("a")
        val b = FakePlugin("b")
        registry.register(a, testScope())
        registry.register(b, testScope())
        assertTrue(registry.typeCatalog.value.containsKey("shahed"))
        assertTrue(registry.typeCatalog.value.containsKey("ballistic"))
    }

    @Test
    fun `empty registry defaults`() {
        val registry = PluginRegistry()
        assertEquals(0, registry.allThreats.value.size)
        assertEquals(0, registry.allAlerts.value.size)
        assertEquals(PluginConnectionState.DISCONNECTED, registry.connectionState.value)
        assertTrue(registry.typeCatalog.value.isEmpty())
    }
}

package ua.ukrainedrones.plugins

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.launch
import ua.ukrainedrones.connection.Monotonic
import ua.ukrainedrones.engine.OblastAlert
import ua.ukrainedrones.engine.NEPTUN_TYPES
import ua.ukrainedrones.engine.NormalizedThreat
import ua.ukrainedrones.engine.OperationalMode
import ua.ukrainedrones.engine.PluginConnectionState
import ua.ukrainedrones.engine.SourceType
import ua.ukrainedrones.engine.ThreatProps
import ua.ukrainedrones.engine.ThreatSource
import ua.ukrainedrones.threat

private class FakePlugin(
    override val id: String,
    threatsInit: List<NormalizedThreat> = emptyList(),
    alertsInit: List<OblastAlert> = emptyList(),
    connectionInit: PluginConnectionState = PluginConnectionState.DISCONNECTED,
    override val sourceType: SourceType = SourceType.WS
) : ThreatSource {
    override val name = id
    override val typeCatalog: Map<String, ThreatProps> = NEPTUN_TYPES
    private val _threats = MutableStateFlow(threatsInit)
    override val threats: StateFlow<List<NormalizedThreat>> = _threats.asStateFlow()
    private val _alerts = MutableStateFlow(alertsInit)
    override val alerts: StateFlow<List<OblastAlert>> = _alerts.asStateFlow()
    private val _connectionState = MutableStateFlow(connectionInit)
    override val connectionState: StateFlow<PluginConnectionState> = _connectionState.asStateFlow()
    private val _operationalMode = MutableStateFlow(OperationalMode.STREAMING)
    override val operationalMode: StateFlow<OperationalMode> = _operationalMode.asStateFlow()
    private val _enabled = MutableStateFlow(true)
    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    var started = false; private set
    var stopped = false; private set
    var enabledCalls = mutableListOf<Boolean>()
    override fun start(scope: CoroutineScope) { started = true }
    override fun stop() { stopped = true }
    override fun setEnabled(enabled: Boolean) { enabledCalls.add(enabled); _enabled.value = enabled }

    fun emitThreats(list: List<NormalizedThreat>) { _threats.value = list }
    fun emitAlerts(list: List<OblastAlert>) { _alerts.value = list }
    fun emitConnection(state: PluginConnectionState) { _connectionState.value = state }
    fun emitOperationalMode(mode: OperationalMode) { _operationalMode.value = mode }
}

class PluginRegistryTest {

    @Before
    fun setUp() {
        // JVM has no SystemClock.elapsedRealtime; give the registry a JVM-safe monotonic source.
        Monotonic.nowProvider = { System.nanoTime() }
    }

    @After
    fun tearDown() {
        Monotonic.nowProvider = { 0L }
    }

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

    @Test
    fun `wsHealthy true when a WS source is connected`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("a", connectionInit = PluginConnectionState.CONNECTED), testScope())
        assertTrue(registry.wsHealthy.value)
    }

    @Test
    fun `wsHealthy false when all WS sources offline`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("a", connectionInit = PluginConnectionState.OFFLINE), testScope())
        registry.register(FakePlugin("b", connectionInit = PluginConnectionState.DISCONNECTED), testScope())
        assertTrue(!registry.wsHealthy.value)
    }

    @Test
    fun `REST sources do not count toward wsHealthy`() {
        val registry = PluginRegistry()
        registry.register(
            FakePlugin("rest", sourceType = SourceType.REST, connectionInit = PluginConnectionState.CONNECTED),
            testScope()
        )
        assertTrue(!registry.wsHealthy.value)
    }

    @Test
    fun `degraded WS source is not healthy - backup engages`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("a", connectionInit = PluginConnectionState.DEGRADED), testScope())
        assertTrue(!registry.wsHealthy.value)
    }

    @Test
    fun `coveredByFallback true when WS down but REST authoritative`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("ws", connectionInit = PluginConnectionState.OFFLINE), testScope())
        val rest = FakePlugin("rest", sourceType = SourceType.REST)
        registry.register(rest, testScope())
        rest.emitOperationalMode(OperationalMode.POLLING)
        rest.emitConnection(PluginConnectionState.CONNECTED)
        assertTrue(registry.coveredByFallback.value)
    }

    @Test
    fun `coveredByFallback true when REST delivers`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("ws", connectionInit = PluginConnectionState.CONNECTED), testScope())
        val rest = FakePlugin("rest", sourceType = SourceType.REST)
        registry.register(rest, testScope())
        rest.emitOperationalMode(OperationalMode.POLLING)
        rest.emitConnection(PluginConnectionState.CONNECTED)
        assertTrue(registry.coveredByFallback.value)
    }

    @Test
    fun `coveredByFallback false when nothing covers`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("ws", connectionInit = PluginConnectionState.OFFLINE), testScope())
        val rest = FakePlugin("rest", sourceType = SourceType.REST)
        registry.register(rest, testScope())
        // REST standby (never fetched) → no real coverage.
        assertTrue(!registry.coveredByFallback.value)
    }

    @Test
    fun `degraded false when WS delivering`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("ws", connectionInit = PluginConnectionState.CONNECTED), testScope())
        assertTrue(!registry.degraded.value)
        assertTrue(registry.wsHealthy.value)
    }

    @Test
    fun `degraded true when WS offline`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("ws", connectionInit = PluginConnectionState.OFFLINE), testScope())
        assertTrue(registry.degraded.value)
    }

    @Test
    fun `degraded true when WS disabled`() {
        val registry = PluginRegistry()
        val ws = FakePlugin("ws", connectionInit = PluginConnectionState.CONNECTED)
        registry.register(ws, testScope())
        registry.setEnabled(ws, false)
        assertTrue(registry.degraded.value)
    }

    @Test
    fun `disabled source alerts and threats are not merged`() {
        val registry = PluginRegistry()
        val ws = FakePlugin(
            "ws",
            threatsInit = listOf(threat(id = "t1")),
            alertsInit = listOf(OblastAlert("k1", "n1", "Odesa oblast", null))
        )
        registry.register(ws, testScope())
        assertTrue(registry.allThreats.value.isNotEmpty())
        assertTrue(registry.allAlerts.value.isNotEmpty())
        registry.setEnabled(ws, false)
        assertTrue(registry.allThreats.value.isEmpty())
        assertTrue(registry.allAlerts.value.isEmpty())
    }

    @Test
    fun `disabling the primary does not keep the fallback covering`() {
        val registry = PluginRegistry()
        val ws = FakePlugin("neptun", connectionInit = PluginConnectionState.CONNECTED)
        registry.register(ws, testScope())
        val rest = FakePlugin("ubilling", sourceType = SourceType.REST)
        registry.register(rest, testScope())
        registry.setEnabled(ws, false)
        assertTrue(!registry.coveredByFallback.value)
    }

    @Test
    fun `degraded true when WS silent`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("ws", connectionInit = PluginConnectionState.DEGRADED), testScope())
        assertTrue(registry.degraded.value)
    }

    @Test
    fun `isOffline false within episode grace`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("ws", connectionInit = PluginConnectionState.OFFLINE), testScope())
        val since = registry.degradedSince.value
        assertTrue(!registry.isOffline((since ?: 0L) + 1_000L))
    }

    @Test
    fun `isOffline true past episode grace with no fallback`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("ws", connectionInit = PluginConnectionState.OFFLINE), testScope())
        val since = registry.degradedSince.value!!
        assertTrue(registry.isOffline(since + PluginRegistry.OFFLINE_EPISODE_MS + 1_000L))
    }

    @Test
    fun `isOffline false past grace when fallback covers`() {
        val registry = PluginRegistry()
        registry.register(FakePlugin("ws", connectionInit = PluginConnectionState.OFFLINE), testScope())
        val rest = FakePlugin("rest", sourceType = SourceType.REST)
        registry.register(rest, testScope())
        rest.emitOperationalMode(OperationalMode.POLLING)
        rest.emitConnection(PluginConnectionState.CONNECTED)
        val since = registry.degradedSince.value!!
        assertTrue(registry.coveredByFallback.value)
        assertTrue(!registry.isOffline(since + PluginRegistry.OFFLINE_EPISODE_MS + 1_000L))
    }

    @Test
    fun `threat data stale when nothing delivered yet`() {
        var tick = 1_000_000_000L
        Monotonic.nowProvider = { tick }
        val registry = PluginRegistry()
        assertTrue(registry.isThreatDataStale(tick))
    }

    @Test
    fun `threat data fresh right after a source delivers`() {
        var tick = 1_000_000_000L
        Monotonic.nowProvider = { tick }
        val registry = PluginRegistry()
        val ws = FakePlugin("ws")
        registry.register(ws, testScope())
        ws.emitThreats(listOf(threat("t1")))
        assertTrue(!registry.isThreatDataStale(tick))
    }

    @Test
    fun `threat data stale again past the window`() {
        var tick = 1_000_000_000L
        Monotonic.nowProvider = { tick }
        val registry = PluginRegistry()
        val ws = FakePlugin("ws")
        registry.register(ws, testScope())
        ws.emitThreats(listOf(threat("t1")))
        tick += PluginRegistry.THREAT_DATA_STALE_MS + 1_000L
        assertTrue(registry.isThreatDataStale(tick))
    }

    @Test
    fun `takeover merge prefers first registered plugin per key`() {
        val registry = PluginRegistry()
        // Same alert key from both plugins: the first registered owner wins.
        val a = FakePlugin("a", alertsInit = listOf(OblastAlert("odeska", "Одеська область", "Одеська область", null)))
        val b = FakePlugin("b", alertsInit = listOf(OblastAlert("odeska", "Одеська область", "Одеська область", "123")))
        registry.register(a, testScope())
        registry.register(b, testScope())
        assertEquals(1, registry.allAlerts.value.size)
        assertEquals("Одеська область", registry.allAlerts.value[0].name)
        assertEquals("a", registry.activeAlertSource.value)
    }

    @Test
    fun `merge keeps multiple regions of the same oblast`() {
        val registry = PluginRegistry()
        // A whole-oblast alert AND two raion alerts inside the SAME oblast — all distinct keys.
        val a = FakePlugin(
            "a",
            alertsInit = listOf(
                OblastAlert("odeska", "Одеська область", "Одеська область", null),
                OblastAlert("odeskyi", "Одеський район", "Одеська область", null),
                OblastAlert("bilhorod-dnistrovskyi", "Білгород-Дністровський район", "Одеська область", null)
            )
        )
        registry.register(a, testScope())
        assertEquals(3, registry.allAlerts.value.size)
        val keys = registry.allAlerts.value.map { it.key }.toSet()
        assertEquals(setOf("odeska", "odeskyi", "bilhorod-dnistrovskyi"), keys)
    }

    @Test
    fun `takeover merge reflects live emission`() {
        val registry = PluginRegistry()
        val a = FakePlugin("a")
        val b = FakePlugin("b", alertsInit = listOf(OblastAlert("k1", "n1", "Kyiv oblast", null)))
        registry.register(a, testScope())
        registry.register(b, testScope())
        assertEquals("b", registry.activeAlertSource.value)
        b.emitAlerts(emptyList())
        assertEquals(0, registry.allAlerts.value.size)
        assertTrue(registry.activeAlertSource.value == null)
    }

    @Test
    fun `active REST fallback supersedes stale WS alerts`() {
        val registry = PluginRegistry()
        // WS source registered first, but OFFLINE → holding a stale "alerting" entry.
        val neptun = FakePlugin("neptun", alertsInit = listOf(OblastAlert("k1", "n1", "Odesa oblast", "111")))
        registry.register(neptun, testScope())
        // REST fallback actively polling, reports the same oblast CLEAR (not alerting).
        val ubilling = FakePlugin(
            "ubilling",
            sourceType = SourceType.REST,
            alertsInit = emptyList(),
            connectionInit = PluginConnectionState.DISCONNECTED
        )
        registry.register(ubilling, testScope())
        // A successful poll flips the fallback to CONNECTED + POLLING → authoritative.
        ubilling.emitConnection(PluginConnectionState.CONNECTED)
        ubilling.emitOperationalMode(OperationalMode.POLLING)
        // Ubilling's snapshot (empty = all-clear) is authoritative → stale Neptun alert is superseded.
        assertEquals(0, registry.allAlerts.value.size)
        assertTrue(registry.activeAlertSource.value == null)
    }

    @Test
    fun `stale WS alerts fill when no authoritative source covers oblast`() {
        val registry = PluginRegistry()
        val neptun = FakePlugin("neptun", alertsInit = listOf(OblastAlert("k1", "n1", "Odesa oblast", "111")))
        registry.register(neptun, testScope())
        val ubilling = FakePlugin("ubilling", sourceType = SourceType.REST)
        registry.register(ubilling, testScope())
        // Neptun OFFLINE but ubilling is STANDBY (not yet covering) → Neptun's held alert fills.
        assertEquals(1, registry.allAlerts.value.size)
        assertEquals("neptun", registry.activeAlertSource.value)
    }

    @Test
    fun `REST poller that never fetched does not wipe held feed`() {
        val registry = PluginRegistry()
        val neptun = FakePlugin("neptun", alertsInit = listOf(OblastAlert("k1", "n1", "Odesa oblast", "111")))
        registry.register(neptun, testScope())
        val ubilling = FakePlugin("ubilling", sourceType = SourceType.REST)
        registry.register(ubilling, testScope())
        // POLLING but still DISCONNECTED (first fetch not returned) → not authoritative → held alert stays.
        ubilling.emitOperationalMode(OperationalMode.POLLING)
        assertEquals(1, registry.allAlerts.value.size)
    }

    @Test
    fun `setEnabled propagates to plugin`() {
        val registry = PluginRegistry()
        val a = FakePlugin("a")
        registry.register(a, testScope())
        registry.setEnabled(a, false)
        assertEquals(listOf(false), a.enabledCalls)
    }

    @Test
    fun `setEnabled emits a toggle event`() {
        val registry = PluginRegistry()
        val a = FakePlugin("a")
        registry.register(a, testScope())
        val collected = mutableListOf<SourceEvent>()
        val job = testScope().launch {
            registry.sourceEvents.collect { collected.add(it) }
        }
        registry.setEnabled(a, false)
        job.cancel()
        assertEquals(SourceEventKind.TOGGLED_OFF, collected.firstOrNull()?.kind)
    }
}

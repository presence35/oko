package com.odesaplay.oko.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ResilientConnectionSupervisorTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private var current: ResilientConnectionSupervisor? = null

    private var frames = CopyOnWriteArrayList<String>()
    private val baselines = AtomicInteger(0)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        server = MockWebServer()
        server.start()
        frames = CopyOnWriteArrayList()
        baselines.set(0)
    }

    @After
    fun tearDown() {
        current?.stop()
        server.shutdown()
    }

    private fun connectivityManager() =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** Empty capabilities: INTERNET/VALIDATED absent, so the supervisor treats us as offline. */
    private fun installNoNetwork() {
        val cm = connectivityManager()
        cm.activeNetwork?.let { Shadows.shadowOf(cm).setNetworkCapabilities(it, NetworkCapabilities()) }
    }

    private fun installValidatedNetwork() {
        val cm = connectivityManager()
        val net = cm.activeNetwork ?: error("Robolectric has no active network to validate")
        val caps = NetworkCapabilities()
        val shadowCaps = Shadows.shadowOf(caps)
        shadowCaps.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowCaps.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        Shadows.shadowOf(cm).setNetworkCapabilities(net, caps)
    }

    private fun keepOpenUpgrade() = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})

    private fun newSupervisor(): ResilientConnectionSupervisor {
        val sup = ResilientConnectionSupervisor(
            context = context,
            endpointUrl = server.url("/").toString(),
            onFrameReceived = { frames.add(it) },
            onBaselineRequired = { baselines.incrementAndGet() }
        )
        current = sup
        return sup
    }

    private fun awaitState(
        timeoutMs: Long = 5_000,
        predicate: (ConnectionState) -> Boolean
    ): ConnectionState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val cs = current!!.connectionState.value
            if (predicate(cs)) return cs
            Thread.sleep(20)
        }
        throw AssertionError("Timed out; last=${current!!.connectionState.value}")
    }

    private fun awaitTrue(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for condition")
    }

    // ── Deterministic (no network) ──

    @Test
    fun `recordEvent keeps only the newest fifty`() {
        val sup = newSupervisor()
        repeat(60) { i -> sup.recordEvent(ConnEventKind.NO_NETWORK, detail = "$i") }

        val events = sup.connEvents.value
        assertEquals(50, events.size)
        assertEquals("59", events.first().detail)
        assertEquals("10", events.last().detail)
    }

    @Test
    fun `dismissLogCard clears events and retry state`() {
        val sup = newSupervisor()
        sup.recordEvent(ConnEventKind.NO_NETWORK)

        sup.dismissLogCard()

        assertTrue(sup.connEvents.value.isEmpty())
        assertNull(sup.retryState.value)
    }

    @Test
    fun `retryNow records a manual retry even while stopped`() {
        val sup = newSupervisor()

        sup.retryNow()

        assertTrue(sup.connEvents.value.any { it.kind == ConnEventKind.RETRY_MANUAL })
        assertEquals(ConnectionState.Disconnected, sup.connectionState.value)
    }

    @Test
    fun `stop while stopped stays disconnected`() {
        val sup = newSupervisor()

        sup.stop()

        assertEquals(ConnectionState.Disconnected, sup.connectionState.value)
    }

    @Test
    fun `onForeground is a no-op while stopped`() {
        val sup = newSupervisor()

        sup.onForeground()

        assertEquals(ConnectionState.Disconnected, sup.connectionState.value)
    }

    // ── Network gating ──

    @Test
    fun `start without a validated network goes offline with a no-network event`() {
        installNoNetwork()
        val sup = newSupervisor()

        sup.start()

        assertTrue(sup.connectionState.value is ConnectionState.Offline)
        assertTrue(sup.connEvents.value.any { it.kind == ConnEventKind.NO_NETWORK })
    }

    @Test
    fun `losing the last validated network drops to offline`() {
        installValidatedNetwork()
        server.enqueue(keepOpenUpgrade())
        val sup = newSupervisor()
        sup.start()
        awaitState { it is ConnectionState.Connected }

        val cm = connectivityManager()
        val net = cm.activeNetwork!!
        Shadows.shadowOf(cm).getNetworkCallbacks().forEach { it.onLost(net) }

        val offline = awaitState { it is ConnectionState.Offline }
        assertEquals(OfflineCause.NetworkLost(null), (offline as ConnectionState.Offline).cause)
        assertTrue(sup.connEvents.value.any { it.kind == ConnEventKind.NO_NETWORK })
    }

    // ── Connect lifecycle ──

    @Test
    fun `validated network connects and delivers frames`() {
        installValidatedNetwork()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("hello")
                }
            })
        )
        val sup = newSupervisor()

        sup.start()

        awaitState { it is ConnectionState.Connected }
        awaitTrue { frames.isNotEmpty() }
        assertEquals("hello", frames.first())
        assertTrue(baselines.get() >= 1)
    }

    @Test
    fun `stop after connecting returns to disconnected`() {
        installValidatedNetwork()
        server.enqueue(keepOpenUpgrade())
        val sup = newSupervisor()
        sup.start()
        awaitState { it is ConnectionState.Connected }

        sup.stop()

        assertEquals(ConnectionState.Disconnected, sup.connectionState.value)
    }

    @Test
    fun `retryNow mints a fresh generation while connected`() {
        installValidatedNetwork()
        server.enqueue(keepOpenUpgrade())
        server.enqueue(keepOpenUpgrade())
        val sup = newSupervisor()
        sup.start()
        val first = awaitState { it is ConnectionState.Connected } as ConnectionState.Connected

        sup.retryNow()

        assertTrue(sup.connEvents.value.any { it.kind == ConnEventKind.RETRY_MANUAL })
        val second = awaitState {
            it is ConnectionState.Connected && (it as ConnectionState.Connected).generation > first.generation
        }
        assertTrue((second as ConnectionState.Connected).generation > first.generation)
    }
}
